package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.common.math.Maths;
import com.truesim.effects.post.layout.LensPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * A lens' flaws, in scene light after {@link Bloom} and before {@link ColorGrading}: radial distortion, lateral
 * chromatic aberration and vignetting, each a technique slot of its own, off by default, together in one pass that
 * provides a new {@link Keys#SCENE_COLOR} at {@link Anchor#POST} (built only while one is on: switching rebuilds).
 * Positions are taken around the render region's center, round (not stretched by the aspect), the radius 1 at its
 * corners.
 *
 * <ul>
 *   <li>{@link #DISTORTION} ({@code render.lens.distortion}): {@link #BROWN_CONRADY}'s radial term, a pixel at radius
 *       r showing the scene at r (1 + k r^2), scaled so the frame stays filled (pincushion, k over 0, by its corners;
 *       barrel by the middles of its nearer edges); k is at least -1/3, so the image never folds over.</li>
 *   <li>{@link #ABERRATION} ({@code render.lens.aberration}): lateral color, red magnified and blue reduced by
 *       {@code amount} of the radius at the corners, growing with r^2: {@link #RADIAL} (three taps) or
 *       {@link #SPECTRAL} (eight wavelengths between them, each channel's weights summing to one, for smooth fringes).
 *       The image is scaled by 1 / (1 + amount) with it, so red stays inside the frame.</li>
 *   <li>{@link #VIGNETTE} ({@code render.lens.vignette}): {@link #NATURAL}, the cos^4 law of the angle off the axis
 *       for the camera's field of view, or {@link #ARTISTIC}, a falloff over the outer {@code smoothness} of the way to
 *       the corners, round or following the frame ({@code roundness}); both by {@code intensity}.</li>
 * </ul>
 *
 * <pre>{@code
 * port.add(new LensEffects(camera));
 * LensEffects.DISTORTION.choose(graph.settings(), LensEffects.BROWN_CONRADY);
 * graph.settings().set(LensEffects.BROWN_CONRADY.setting("k"), -0.1f);          // render.lens.distortion.brown_conrady.k
 * }</pre>
 */
public final class LensEffects implements Technique {

    /** A view's lens effects. */
    public static final Id<LensEffects> ID = Id.of("truesim.lensEffects", LensEffects.class);

    /** Radial distortion, or {@code off}. */
    public static final TechniqueSlot<LensEffects, Integer> DISTORTION = TechniqueSlot.of("render.lens.distortion",
        TechniqueSlot.OFF, true, "lens distortion: off or brown_conrady (barrel or pincushion)",
        techniques -> techniques.register(LensEffects.DISTORTION, LensEffects.BROWN_CONRADY));
    /** Lateral chromatic aberration, or {@code off}. */
    public static final TechniqueSlot<LensEffects, Integer> ABERRATION = TechniqueSlot.of("render.lens.aberration",
        TechniqueSlot.OFF, true, "chromatic aberration: off, radial (three taps) or spectral (eight wavelengths)",
        techniques -> techniques.register(LensEffects.ABERRATION, LensEffects.RADIAL)
            .register(LensEffects.ABERRATION, LensEffects.SPECTRAL));
    /** Vignetting, or {@code off}. */
    public static final TechniqueSlot<LensEffects, Integer> VIGNETTE = TechniqueSlot.of("render.lens.vignette",
        TechniqueSlot.OFF, true, "vignetting: off, natural (the cos^4 law) or artistic",
        techniques -> techniques.register(LensEffects.VIGNETTE, LensEffects.NATURAL)
            .register(LensEffects.VIGNETTE, LensEffects.ARTISTIC));

    /**
     * Brown-Conrady's radial distortion, read as frames record.
     *
     * @param k negative barrel, positive pincushion, the radius 1 at the corners
     */
    public record Radial(
        @Tunable(value = "lens distortion: negative barrel, positive pincushion (radius 1 at the corners)",
            min = -1.0 / 3, max = 1) float k) implements TechniqueConfig {
    }

    /**
     * Chromatic aberration's tunables, read as frames record.
     *
     * @param amount the shift at the corners, a share of the radius
     */
    public record Fringe(
        @Tunable(value = "chromatic aberration's shift at the corners, a share of the radius", min = 0, max = 0.1)
        float amount) implements TechniqueConfig {
    }

    /**
     * Natural vignetting's tunables, read as frames record.
     *
     * @param intensity 1 the full cos^4 law
     */
    public record Natural(@Tunable(value = "natural vignetting's strength (1: the cos^4 law)", min = 0, max = 1)
        float intensity) implements TechniqueConfig {
    }

    /**
     * Artistic vignetting's tunables, read as frames record.
     *
     * @param intensity 1 black at the corners
     * @param smoothness the falloff's width: the outer share of the way to the corners
     * @param roundness 1 round, 0 following the frame's shape
     */
    public record Artistic(
        @Tunable(value = "the artistic vignette's strength (1: black at the corners)", min = 0, max = 1)
        float intensity,
        @Tunable(value = "how far in the artistic vignette reaches", min = 0.01, max = 1) float smoothness,
        @Tunable(value = "the artistic vignette's roundness: 1 round, 0 following the frame", min = 0, max = 1)
        float roundness) implements TechniqueConfig {
    }

    private static final Integer ON = 1;
    private static final Integer ABERRATION_RADIAL = 1;
    private static final Integer ABERRATION_SPECTRAL = 2;
    private static final Integer VIGNETTE_NATURAL = 1;
    private static final Integer VIGNETTE_ARTISTIC = 2;

    /** Brown-Conrady's radial term. */
    public static final TechniqueKind<Radial, LensEffects, Integer> BROWN_CONRADY = TechniqueKind.of(DISTORTION,
        "brown_conrady", new Radial(-0.05f), "Brown-Conrady's radial term: barrel below 0, pincushion above",
        (lens, config) -> ON);
    /** Three taps: red, green and blue each at its own scale. */
    public static final TechniqueKind<Fringe, LensEffects, Integer> RADIAL = TechniqueKind.of(ABERRATION, "radial",
        new Fringe(0.005f), "red, green and blue each read at its own scale: three taps", (lens, config) ->
            ABERRATION_RADIAL);
    /** Eight wavelengths between red and blue. */
    public static final TechniqueKind<Fringe, LensEffects, Integer> SPECTRAL = TechniqueKind.of(ABERRATION, "spectral",
        new Fringe(0.005f), "eight wavelengths between red and blue, for smooth fringes", (lens, config) ->
            ABERRATION_SPECTRAL);
    /** The cos^4 law. */
    public static final TechniqueKind<Natural, LensEffects, Integer> NATURAL = TechniqueKind.of(VIGNETTE, "natural",
        new Natural(1), "the cos^4 law of the angle off the axis, for the camera's field of view", (lens, config) ->
            VIGNETTE_NATURAL);
    /** An artist's falloff towards the corners. */
    public static final TechniqueKind<Artistic, LensEffects, Integer> ARTISTIC = TechniqueKind.of(VIGNETTE, "artistic",
        new Artistic(0.5f, 0.5f, 1), "a falloff over the outer part of the way to the corners", (lens, config) ->
            VIGNETTE_ARTISTIC);

    private final Camera camera;

    /** Lens effects for a view seen through {@code camera} (its field of view: the natural vignette). */
    public LensEffects(Camera camera) {
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /** Registers its settings in {@code registry} for discovery: the slots, their kinds' tunables with them, hidden. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(DISTORTION, ABERRATION, VIGNETTE);
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.POST).after(Bloom.ID);
    }

    /**
     * What positions are divided by after distortion {@code k} (the radius 1 at the corners) in a frame of
     * {@code aspect}, so it stays filled: by the corners for pincushion, the middles of the nearer edges for barrel.
     */
    static float scale(float k, float aspect) {
        float corner = aspect * aspect + 1;
        return k >= 0 ? 1 + k : 1 + k * Math.min(aspect * aspect, 1) / corner;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, LensEffects, Integer> distortion = g.technique(DISTORTION);
        TechniqueKind<?, LensEffects, Integer> aberration = g.technique(ABERRATION);
        TechniqueKind<?, LensEffects, Integer> vignette = g.technique(VIGNETTE);
        Image scene = g.modify(Keys.SCENE_COLOR);
        if (distortion == null && aberration == null && vignette == null) {
            return; // the scene color as it was: no new version
        }
        Image out = g.create("lens", ImageDesc.like(scene, Ratio.ONE));
        Pipeline lens = g.computeProgram("shaders/post/lens.spv", "lensMain", LensPush.BYTES);
        int aberrationMode = aberration == null ? 0 : aberration.create(this, null);
        int vignetteMode = vignette == null ? 0 : vignette.create(this, null);
        var settings = g.settings();
        LensPush push = new LensPush().aberration(aberrationMode).vignette(vignetteMode);
        g.pass("lens").read(scene, Access.SAMPLED_COMPUTE).write(out, Access.STORAGE_WRITE).record(ctx -> {
            int width = ctx.regionWidth(scene);
            int height = ctx.regionHeight(scene);
            float k = distortion == null ? 0 : BROWN_CONRADY.config(settings).k();
            push.source(ctx.sampled(scene)).target(ctx.storage(out)).region(width, height).distortion(k)
                .scale(scale(k, (float) width / height))
                .tanHalfFov((float) Maths.tan(camera.fovY() / 2));
            if (aberrationMode != 0) {
                push.amount((aberrationMode == 1 ? RADIAL : SPECTRAL).config(settings).amount());
            }
            if (vignetteMode == 1) {
                push.intensity(NATURAL.config(settings).intensity());
            } else if (vignetteMode == 2) {
                Artistic a = ARTISTIC.config(settings);
                push.intensity(a.intensity()).smoothness(a.smoothness()).roundness(a.roundness());
            }
            ctx.dispatch(lens, push, (width + 7) / 8, (height + 7) / 8, 1);
        });
        g.provide(Keys.SCENE_COLOR, out);
    }
}
