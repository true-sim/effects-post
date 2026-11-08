package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.post.ScreenFlash.Flashes;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ScreenFlashTest {

    private static final int SIZE = 32;
    private static final Key<Image> SHOWN = Key.image("test.shown");
    private static final float AMOUNT = 0.1f;
    private static final float RISE = 0.25f;
    private static final float FADE = 0.5f;

    @Test
    void aFlashDecaysAlongItsCurve() {
        ScreenFlash flash = new ScreenFlash().flash(1, 0, 0, 0.8f, 1, ScreenFlash.Curve.LINEAR);
        assertThat(flash.flashAmount(Flashes.FULL, AMOUNT, RISE)).isEqualTo(0.8f);
        flash.advance(0.25f);
        assertThat(flash.flashAmount(Flashes.FULL, AMOUNT, RISE)).isCloseTo(0.6f, within(1e-6f));
        flash.flash(1, 0, 0, 1, 1, ScreenFlash.Curve.EASE_OUT).advance(0.5f);
        assertThat(flash.flashAmount(Flashes.FULL, AMOUNT, RISE)).as("1 - (1 - (1 - 0.5)^2)").isCloseTo(0.25f,
            within(1e-6f));
        flash.advance(0.5f);
        assertThat(flash.flashAmount(Flashes.FULL, AMOUNT, RISE)).isZero();
        assertThat(new ScreenFlash().flashAmount(Flashes.FULL, AMOUNT, RISE)).as("none yet").isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> flash.flash(1, 1, 1, 2, 1, ScreenFlash.Curve.LINEAR));
        assertThatIllegalArgumentException().isThrownBy(() -> flash.flash(1, 1, 1, 1, 0, ScreenFlash.Curve.LINEAR));
    }

    @Test
    void reducedFlashesStayBelowTheFlashThresholdAndOffShowNone() {
        ScreenFlash flash = new ScreenFlash().flash(5, 5, 5, 1, 0.1f, ScreenFlash.Curve.LINEAR);
        float most = 0;
        float previous = 0;
        float steepest = 0;
        for (int frame = 0; frame < 120; frame++) {
            float amount = flash.flashAmount(Flashes.REDUCED, AMOUNT, RISE);
            most = Math.max(most, amount);
            steepest = Math.max(steepest, Math.abs(amount - previous) * 120); // per second, rising or falling
            previous = amount;
            assertThat(flash.flashAmount(Flashes.OFF, AMOUNT, RISE)).isZero();
            flash.advance(1 / 120f);
        }
        assertThat(most).as("a tenth of the luminance at most").isLessThanOrEqualTo(AMOUNT);
        assertThat(steepest).as("rising over a quarter second").isLessThanOrEqualTo(AMOUNT / RISE + 1e-3f);
    }

    @Test
    void fadesHoldAndAreNeverQuickerThanHalfASecondWithFlashesLimited() {
        ScreenFlash fade = new ScreenFlash().fadeTo(0, 0, 0, 0, ScreenFlash.Curve.LINEAR);
        assertThat(fade.fadeAmount(Flashes.FULL, FADE)).as("a cut").isEqualTo(1);
        assertThat(fade.fadeAmount(Flashes.REDUCED, FADE)).as("a fade instead").isZero();
        fade.advance(0.25f);
        assertThat(fade.fadeAmount(Flashes.OFF, FADE)).isCloseTo(0.5f, within(1e-6f));
        fade.advance(10);
        assertThat(fade.fadeAmount(Flashes.FULL, FADE)).as("held").isEqualTo(1);
        fade.fadeFrom(0, 0, 0, 2, ScreenFlash.Curve.SMOOTH).advance(1);
        assertThat(fade.fadeAmount(Flashes.FULL, FADE)).isCloseTo(0.5f, within(1e-6f));
        fade.advance(1);
        assertThat(fade.fadeAmount(Flashes.FULL, FADE)).isZero();
        assertThat(fade.clear().fadeAmount(Flashes.FULL, FADE)).isZero();
    }

    /**
     * What the display shows at the middle of a uniform scene of {@code scene}, the overlay {@code overlay} blended as
     * {@code set} chooses: scRGB at 80 nits paper white, so 1 shows as 1; the tone curve {@code curve}.
     */
    private static float[] shown(PostFixture gpu, ScreenFlash overlay, String curve, float scene,
        Consumer<EngineSettings> set) {
        float[] out = gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            rgb[0] = scene;
            rgb[1] = scene;
            rgb[2] = scene;
        }, (graph, port) -> {
            graph.settings().set(DisplayTransform.CURVE, curve).set(DisplayTransform.PAPER_WHITE, 80f)
                .set(DisplayTransform.PEAK, 800f).set(DisplayTransform.DITHER, false);
            set.accept(graph.settings());
            port.add(overlay.display()).add(overlay.scene());
            for (Technique each : new DisplayTransform().displayEffects().transforms(Keys.SCENE_COLOR, SHOWN,
                DisplayEncoding.SCRGB_LINEAR)) {
                port.add(each);
            }
        }, SHOWN, Format.R16G16B16A16_SFLOAT, 1);
        int at = 4 * (SIZE / 2 * SIZE + SIZE / 2);
        return new float[] {out[at], out[at + 1], out[at + 2]};
    }

    @Test
    void theDisplayKindBlendsTheColorAsGivenAndTheSceneKindBeforeTheToneCurve() {
        try (PostFixture gpu = new PostFixture()) {
            ScreenFlash overlay = new ScreenFlash().flash(1, 0, 0, 0.5f, 1, ScreenFlash.Curve.LINEAR);
            float[] display = shown(gpu, overlay, "aces", 0.18f, s -> { });
            float[] plain = shown(gpu, new ScreenFlash(), "aces", 0.18f, s -> { });
            // Half way from the tone-mapped scene to red, in display light
            assertThat(display[0]).isCloseTo((plain[0] + 1) / 2, within(2e-3f));
            assertThat(display[1]).isCloseTo(plain[1] / 2, within(2e-3f));
            float[] scene = shown(gpu, overlay, "off", 0.18f, s -> ScreenFlash.SLOT.choose(s, ScreenFlash.SCENE));
            assertThat(scene[0]).as("scene light, no curve: the same blend").isCloseTo((0.18f + 1) / 2, within(2e-3f));
            float[] curved = shown(gpu, overlay, "aces", 0.18f, s -> ScreenFlash.SLOT.choose(s, ScreenFlash.SCENE));
            assertThat(curved[1]).as("scene light: tone mapped with the scene").isLessThan(plain[1] / 2 * 0.99f);
            float[] peak = shown(gpu, new ScreenFlash().flash(1, 1, 1, 1, 1, ScreenFlash.Curve.LINEAR), "aces", 0.18f,
                s -> s.set(ScreenFlash.DISPLAY.setting("hdrWhite"), ScreenFlash.HdrWhite.PEAK));
            assertThat(peak[0]).as("white at the peak: 800 / 80").isCloseTo(10, within(0.02f));
            float[] off = shown(gpu, overlay, "off", 0.18f, s -> ScreenFlash.SLOT.choose(s, null));
            assertThat(off[0]).as("off").isCloseTo(0.18f, within(1e-3f));
            float[] fade = shown(gpu, new ScreenFlash().fadeTo(0, 0, 0, 0, ScreenFlash.Curve.LINEAR), "off", 0.5f,
                s -> { });
            assertThat(fade[1]).as("faded to black").isZero();
        }
    }

    @Test
    void reducedFlashesChangeTheShownLuminanceByATenthAtMostWhateverTheKind() {
        // A white flash at full intensity over a dark scene, frame by frame at 60 a second through the whole path:
        // the shown luminance never moves more than a tenth from the scene's, nor faster than over a quarter second.
        try (PostFixture gpu = new PostFixture()) {
            for (var kind : new Object[] {ScreenFlash.DISPLAY, ScreenFlash.SCENE}) {
                float plain = shown(gpu, new ScreenFlash(), "aces", 0.02f, s -> { })[1];
                ScreenFlash overlay = new ScreenFlash().flash(4, 4, 4, 1, 0.2f, ScreenFlash.Curve.LINEAR);
                float most = 0;
                float previous = plain;
                float steepest = 0;
                for (int frame = 0; frame < 40; frame++) {
                    float now = shown(gpu, overlay, "aces", 0.02f, s -> {
                        ScreenFlash.SLOT.choose(s, kind == ScreenFlash.SCENE ? ScreenFlash.SCENE : ScreenFlash.DISPLAY);
                        s.set(ScreenFlash.FLASHES, Flashes.REDUCED);
                    })[1];
                    most = Math.max(most, Math.abs(now - plain));
                    steepest = Math.max(steepest, Math.abs(now - previous) * 60);
                    previous = now;
                    overlay.advance(1 / 60f);
                }
                assertThat(most).as(kind + ": a tenth at most").isLessThanOrEqualTo(0.1f + 2e-3f).isGreaterThan(0.05f);
                assertThat(steepest).as(kind + ": rising and falling over a quarter second").isLessThanOrEqualTo(0.1f / 0.25f
                    + 0.05f);
                float none = shown(gpu, new ScreenFlash().flash(4, 4, 4, 1, 0.2f, ScreenFlash.Curve.LINEAR), "aces",
                    0.02f, s -> s.set(ScreenFlash.FLASHES, Flashes.OFF))[1];
                assertThat(none).as(kind + ": off").isCloseTo(plain, within(1e-4f));
            }
        }
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(ScreenFlash.SLOT.chosen(settings)).isSameAs(ScreenFlash.DISPLAY);
        assertThat(settings.get(ScreenFlash.FLASHES)).isEqualTo(Flashes.FULL);
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        ScreenFlash.register(registry);
        assertThat(registry.all()).contains(ScreenFlash.SLOT.setting(), ScreenFlash.FLASHES,
            ScreenFlash.DISPLAY.setting("reducedAmount"));
        assertThat(new EngineSettings().load(java.util.Map.of("render.flash.display.reducedAmount", "0.5"))
            .get(ScreenFlash.DISPLAY.<Float>setting("reducedAmount"))).as("never above WCAG's tenth")
            .isLessThanOrEqualTo(0.1f);
    }
}
