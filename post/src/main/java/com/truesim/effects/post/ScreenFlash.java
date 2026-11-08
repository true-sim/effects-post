package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.post.layout.FlashPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * A full-screen overlay for games: a flash (damage, a hit, a white-out) that decays, and a fade to or from a color
 * (scene changes, death). Where it is blended is the kind chosen for {@link #SLOT} ({@code render.flash}):
 *
 * <ul>
 *   <li>{@link #DISPLAY} ({@code display}, the default): over the display color after tone mapping, in place
 *       ({@link DisplayTransform#DISPLAY_COLOR} at {@link Anchor#TONE_MAP}; the window's transform made with
 *       {@link DisplayTransform#displayEffects()}): the color shows as given, whatever the tone curve and exposure. On
 *       HDR displays a color of 1 shows at paper white as SDR white does, or at the peak ({@code hdrWhite}).</li>
 *   <li>{@link #SCENE} ({@code scene}): over the scene color in scene light ({@link Keys#SCENE_COLOR} at
 *       {@link Anchor#POST}, after grading): light in the scene, exposed and tone mapped with it (and measured by auto
 *       exposure, as any light is).</li>
 *   <li>{@code off}: none.</li>
 * </ul>
 *
 * <p>{@link #FLASHES} ({@code accessibility.flashes}) limits flashes for players sensitive to them: {@code REDUCED}
 * keeps every flash below WCAG 2.x's general flash threshold (success criterion 2.3.1: a change of at most a tenth of
 * the relative luminance, {@code reducedAmount}, rising and falling over a quarter second at least,
 * {@code reducedRise}, the color at most white) and {@code OFF} shows none; with either, fades take half a second at least
 * ({@code slowestFade}), and the overlay is blended in display light, where those limits hold whatever the tone
 * curve: the scene kind hands it to the display technique, and a view without the display stage shows no flashes
 * then. The limits' ranges stop at WCAG's.
 *
 * <p>The overlay is two techniques of a view, {@link #display()} and {@link #scene()}; add both (the one not in charge
 * adds no pass). The game calls {@link #flash}, {@link #fadeTo}, {@link #fadeFrom} and {@link #advance} between frames
 * on the thread that records them; frames read the overlay as it is when they record. Allocates nothing.
 *
 * <pre>{@code
 * ScreenFlash overlay = new ScreenFlash();
 * port.add(overlay.display()).add(overlay.scene());
 * overlay.flash(1, 0.1f, 0.05f, 0.6f, 0.4f, ScreenFlash.Curve.EASE_OUT);   // hit: red, decaying over 0.4 s
 * overlay.fadeTo(0, 0, 0, 1.5f, ScreenFlash.Curve.SMOOTH);                 // to black
 * overlay.advance(frameSeconds);                                         // each frame, in game time
 * }</pre>
 */
public final class ScreenFlash {

    /** The display technique's id. */
    public static final Id<Technique> DISPLAY_ID = Id.of("truesim.screenFlash.display", Technique.class);
    /** The scene technique's id. */
    public static final Id<Technique> SCENE_ID = Id.of("truesim.screenFlash.scene", Technique.class);

    /** How flashes are shown, for players sensitive to them (photosensitive epilepsy). */
    public enum Flashes {
        /** As the game asks. */
        FULL,
        /**
         * Below WCAG 2.x's general flash threshold (success criterion 2.3.1): a flash changes the screen's relative
         * luminance by at most a tenth, rising no faster than over a quarter of a second; fades take half a second or
         * more.
         */
        REDUCED,
        /** None: flashes are not shown; fades take half a second or more. */
        OFF
    }

    /** How the screen's flashes show. */
    public static final Setting<Flashes> FLASHES = Setting.choice("accessibility.flashes", Flashes.FULL,
        "screen flashes, for players sensitive to them: FULL, REDUCED (below WCAG's flash threshold) or OFF");

    /** How bright an overlay color of 1 shows on an HDR display. */
    public enum HdrWhite {
        /** At paper white, as SDR white and the UI: a white flash is as bright as a white page. */
        PAPER_WHITE,
        /** At the display's peak: a white-out as bright as the display goes. */
        PEAK
    }

    /** How an overlay's amount moves over its duration. */
    public enum Curve {
        LINEAR,
        /** Smoothstep: slow at both ends. */
        SMOOTH,
        /** Quadratic, slow at the start. */
        EASE_IN,
        /** Quadratic, slow at the end. */
        EASE_OUT;

        /** The curve at {@code t}, 0 to 1. */
        float at(float t) {
            float x = Math.clamp(t, 0, 1);
            return switch (this) {
                case LINEAR -> x;
                case SMOOTH -> x * x * (3 - 2 * x);
                case EASE_IN -> x * x;
                case EASE_OUT -> 1 - (1 - x) * (1 - x);
            };
        }
    }

    /** Where the overlay is blended, a kind's id, or {@code off}. */
    public static final TechniqueSlot<ScreenFlash, Boolean> SLOT = TechniqueSlot.of("render.flash", "display", true,
        "where screen flashes and fades are blended: off, display (as given) or scene (tone mapped with the scene)",
        techniques -> techniques.register(ScreenFlash.SLOT, ScreenFlash.DISPLAY)
            .register(ScreenFlash.SLOT, ScreenFlash.SCENE));

    /**
     * The display kind's tunables, read as frames record.
     *
     * @param hdrWhite how bright a color of 1 shows on an HDR display
     * @param reducedAmount with flashes reduced, the most a flash changes the screen: at most WCAG's tenth
     * @param reducedRise with flashes reduced, the quickest a flash rises, seconds: at least WCAG's quarter
     * @param slowestFade with flashes reduced or off, the quickest fade, seconds
     */
    public record Display(
        @Tunable("how bright a white screen flash shows on an HDR display: paper_white or peak") HdrWhite hdrWhite,
        @Tunable(value = "with flashes reduced, the most a flash changes the screen (WCAG: a tenth)", min = 0,
            max = 0.1) float reducedAmount,
        @Tunable(value = "with flashes reduced, the quickest a flash rises, seconds (WCAG: a quarter)", min = 0.25,
            max = 10) float reducedRise,
        @Tunable(value = "with flashes reduced or off, the quickest fade, seconds", min = 0.5, max = 10)
        float slowestFade) implements TechniqueConfig {
    }

    /**
     * The scene kind's tunables, read as frames record: the limits as for {@link Display}, used while flashes are
     * reduced or off (the overlay then in display light).
     *
     * @param reducedAmount with flashes reduced, the most a flash changes the screen
     * @param reducedRise with flashes reduced, the quickest a flash rises, seconds
     * @param slowestFade with flashes reduced or off, the quickest fade, seconds
     */
    public record Scene(
        @Tunable(value = "with flashes reduced, the most a flash changes the screen (WCAG: a tenth)", min = 0,
            max = 0.1) float reducedAmount,
        @Tunable(value = "with flashes reduced, the quickest a flash rises, seconds (WCAG: a quarter)", min = 0.25,
            max = 10) float reducedRise,
        @Tunable(value = "with flashes reduced or off, the quickest fade, seconds", min = 0.5, max = 10)
        float slowestFade) implements TechniqueConfig {
    }

    /** In display light, the default. */
    public static final TechniqueKind<Display, ScreenFlash, Boolean> DISPLAY = TechniqueKind.of(SLOT, "display",
        new Display(HdrWhite.PAPER_WHITE, 0.1f, 0.25f, 0.5f), "after tone mapping: the color shows as given",
        (flash, config) -> Boolean.TRUE);
    /** In scene light. */
    public static final TechniqueKind<Scene, ScreenFlash, Boolean> SCENE = TechniqueKind.of(SLOT, "scene",
        new Scene(0.1f, 0.25f, 0.5f), "in scene light: tone mapped with the scene", (flash, config) -> Boolean.FALSE);

    // The flash: color, peak amount, decay, the time since it started
    private float flashRed;
    private float flashGreen;
    private float flashBlue;
    private float flashPeak;
    private float flashSeconds = 1;
    private Curve flashCurve = Curve.LINEAR;
    private float flashAge = Float.POSITIVE_INFINITY;
    // The fade: color, towards it (true) or from it, its duration and age
    private float fadeRed;
    private float fadeGreen;
    private float fadeBlue;
    private boolean fadingTo;
    private float fadeSeconds;
    private Curve fadeCurve = Curve.LINEAR;
    private float fadeAge = Float.POSITIVE_INFINITY;

    private final Technique display = new Blend(true);
    private final Technique scene = new Blend(false);

    /** Registers its settings in {@code registry} for discovery: the slot with its kinds' tunables, the setting. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT).addHidden(FLASHES);
    }

    /** The technique blending the overlay in display light (the display kind, or any while flashes are limited). */
    public Technique display() {
        return display;
    }

    /** The technique blending the overlay in scene light (the scene kind, flashes in full). */
    public Technique scene() {
        return scene;
    }

    /**
     * Flashes color ({@code red}, {@code green}, {@code blue}, 0 and up) at {@code intensity} (0 to 1: the share of the
     * screen it covers at once), fading out over {@code seconds} along {@code curve}. A new flash replaces the last.
     */
    public ScreenFlash flash(float red, float green, float blue, float intensity, float seconds, Curve curve) {
        if (!(intensity >= 0 && intensity <= 1) || !(seconds > 0) || !(red >= 0 && green >= 0 && blue >= 0)) {
            throw new IllegalArgumentException("A flash of intensity 0 to 1, a color of 0 and up and a duration over 0: "
                + intensity + ", " + seconds);
        }
        flashRed = red;
        flashGreen = green;
        flashBlue = blue;
        flashPeak = intensity;
        flashSeconds = seconds;
        flashCurve = Objects.requireNonNull(curve, "curve");
        flashAge = 0;
        return this;
    }

    /** Fades the screen to color ({@code red}, {@code green}, {@code blue}) over {@code seconds}, and holds it. */
    public ScreenFlash fadeTo(float red, float green, float blue, float seconds, Curve curve) {
        return fade(true, red, green, blue, seconds, curve);
    }

    /** Fades the screen in from color ({@code red}, {@code green}, {@code blue}) over {@code seconds}. */
    public ScreenFlash fadeFrom(float red, float green, float blue, float seconds, Curve curve) {
        return fade(false, red, green, blue, seconds, curve);
    }

    private ScreenFlash fade(boolean to, float red, float green, float blue, float seconds, Curve curve) {
        if (!(seconds >= 0) || !(red >= 0 && green >= 0 && blue >= 0)) {
            throw new IllegalArgumentException("A fade to a color of 0 and up, over 0 seconds or more: " + seconds);
        }
        fadingTo = to;
        fadeRed = red;
        fadeGreen = green;
        fadeBlue = blue;
        fadeSeconds = seconds;
        fadeCurve = Objects.requireNonNull(curve, "curve");
        fadeAge = 0;
        return this;
    }

    /** Ends the flash and the fade: the screen as it is. */
    public ScreenFlash clear() {
        flashAge = Float.POSITIVE_INFINITY;
        fadeAge = Float.POSITIVE_INFINITY;
        fadingTo = false;
        return this;
    }

    /** Moves the overlay on by {@code seconds} of game time (a paused game's flash holds). */
    public ScreenFlash advance(float seconds) {
        if (!(seconds >= 0)) {
            throw new IllegalArgumentException("Seconds of 0 or more: " + seconds);
        }
        flashAge += seconds;
        fadeAge += seconds;
        return this;
    }

    /**
     * The flash's share of the screen now, as {@code flashes} allows: in full, its intensity decaying along its curve;
     * reduced, at most {@code reducedAmount}, rising over {@code reducedRise} at least and falling (linearly: no curve
     * steeper than that) over its time but no quicker than it rose; off, 0.
     */
    float flashAmount(Flashes flashes, float reducedAmount, float reducedRise) {
        if (flashes == Flashes.OFF) {
            return 0;
        }
        if (flashes == Flashes.FULL) {
            return flashAge >= flashSeconds ? 0 : flashPeak * (1 - flashCurve.at(flashAge / flashSeconds));
        }
        // Reduced: rising over reducedRise, then decaying over the flash's time but no quicker than it rose
        float decay = Math.max(flashSeconds, reducedRise);
        if (flashAge >= reducedRise + decay) {
            return 0;
        }
        float peak = Math.min(flashPeak, reducedAmount);
        return flashAge < reducedRise ? peak * flashAge / reducedRise
            : peak * (1 - Curve.LINEAR.at((flashAge - reducedRise) / decay));
    }

    /** The fade's share of the screen now; with flashes reduced or off, no fade is quicker than {@code slowest}. */
    float fadeAmount(Flashes flashes, float slowest) {
        if (fadeAge == Float.POSITIVE_INFINITY) {
            return fadingTo ? 1 : 0;
        }
        float seconds = flashes == Flashes.FULL ? fadeSeconds : Math.max(fadeSeconds, slowest);
        float progress = seconds == 0 ? 1 : fadeCurve.at(fadeAge / seconds);
        return fadingTo ? progress : 1 - progress;
    }

    /** The overlay blended by one of its techniques: in display light, or in scene light. */
    private final class Blend implements Technique {
        private final boolean inDisplay;

        Blend(boolean inDisplay) {
            this.inDisplay = inDisplay;
        }

        @Override
        public Id<? extends Technique> id() {
            return inDisplay ? DISPLAY_ID : SCENE_ID;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(inDisplay ? DisplayTransform.DISPLAY_COLOR : Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return inDisplay ? Order.at(Anchor.TONE_MAP).after(FilmGrain.ID) : Order.at(Anchor.POST)
                .after(ColorGrading.ID, Bloom.ID, LensEffects.ID, DepthOfField.ID);
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image image = g.modify(inDisplay ? DisplayTransform.DISPLAY_COLOR : Keys.SCENE_COLOR);
            TechniqueKind<?, ScreenFlash, Boolean> kind = g.technique(SLOT);
            Flashes flashes = g.setting(FLASHES);
            boolean sceneKind = kind == SCENE;
            // In charge: the kind's own light, or display light while flashes are limited
            boolean mine = kind != null && (inDisplay ? !sceneKind || flashes != Flashes.FULL
                : sceneKind && flashes == Flashes.FULL);
            if (!mine) {
                return;
            }
            EngineSettings settings = g.settings();
            Pipeline pipeline = g.computeProgram("shaders/post/flash.spv", "flashMain", FlashPush.BYTES);
            FlashPush push = new FlashPush();
            g.pass(inDisplay ? "screen flash, display" : "screen flash, scene").write(image, Access.STORAGE_WRITE)
                .record(ctx -> {
                    float amount;
                    float rise;
                    float slowest;
                    float white = 1;
                    if (sceneKind) {
                        Scene c = SCENE.config(settings);
                        amount = c.reducedAmount();
                        rise = c.reducedRise();
                        slowest = c.slowestFade();
                    } else {
                        Display c = DISPLAY.config(settings);
                        amount = c.reducedAmount();
                        rise = c.reducedRise();
                        slowest = c.slowestFade();
                        if (c.hdrWhite() == HdrWhite.PEAK) {
                            white = settings.get(DisplayTransform.PEAK) / settings.get(DisplayTransform.PAPER_WHITE);
                        }
                    }
                    Flashes now = settings.get(FLASHES);
                    float most = now == Flashes.FULL ? Float.MAX_VALUE : 1;
                    push.image(ctx.storage(image)).region(ctx.regionWidth(image), ctx.regionHeight(image))
                        .flash(Math.min(flashRed, most) * white, Math.min(flashGreen, most) * white,
                            Math.min(flashBlue, most) * white, flashAmount(now, amount, rise))
                        .fade(fadeRed, fadeGreen, fadeBlue, fadeAmount(now, slowest));
                    ctx.dispatch(pipeline, push, (ctx.regionWidth(image) + 7) / 8, (ctx.regionHeight(image) + 7) / 8,
                        1);
                });
        }
    }
}
