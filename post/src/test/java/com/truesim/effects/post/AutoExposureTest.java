package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoExposureTest {

    private static final int SIZE = 64;
    private static final Key<Image> SHOWN = Key.image("test.shown");
    private static final List<TechniqueKind<?, AutoExposure, Technique>> KINDS = List.of(AutoExposure.HISTOGRAM,
        AutoExposure.AVERAGE);
    /** The histogram's bins are 32 / 254 stops wide: a uniform scene's average within half a bin, 4.5%. */
    private static final float BIN = 0.045f;
    /** Seconds per frame of the tests' clock. */
    private static final double STEP = 0.1;

    private PostFixture gpu;
    private int frame; // frames recorded, as the pattern counts them
    private EngineSettings settings; // the graph's, for patterns changing them as frames record

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    private static <V> void set(EngineSettings s, TechniqueKind<?, AutoExposure, Technique> kind, String field,
        V value) {
        s.set(kind.<V>setting(field), value);
    }

    /**
     * Scene color times the exposure the display transform used at pixel (10, 10) of the last of {@code frames}:
     * scRGB, no curve, paper white 80 nits, so the shown value is the exposed one. The clock steps {@link #STEP} a
     * frame.
     */
    private float exposed(PostFixture.Pattern pattern, BiConsumer<FrameGraph, ViewPort> setup, int frames) {
        frame = 0;
        double[] now = {0};
        float[] out = gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            if (x == 0 && y == 0) {
                frame++;
            }
            pattern.rgb(x, y, w, h, rgb);
        }, (graph, port) -> {
            graph.settings().set(DisplayTransform.CURVE, "off").set(DisplayTransform.PAPER_WHITE, 80f)
                .set(DisplayTransform.PEAK, 10000f).set(DisplayTransform.DITHER, false);
            port.add(new AutoExposure(() -> {
                now[0] += STEP;
                return now[0];
            }));
            setup.accept(graph, port);
            port.add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SCRGB_LINEAR));
        }, SHOWN, Format.R16G16B16A16_SFLOAT, frames);
        return out[4 * (10 * SIZE + 10)];
    }

    private static PostFixture.Pattern uniform(float v) {
        return (x, y, w, h, out) -> {
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
    }

    /** Nine tenths of the rows at 0.1, the last tenth a lamp at 1000. */
    private static void lamp(int x, int y, int w, int h, float[] out) {
        float v = y >= h * 9 / 10 ? 1000 : 0.1f;
        out[0] = v;
        out[1] = v;
        out[2] = v;
    }

    @Test
    void theAverageIsBroughtToMiddleGrayAndCompensatedInStops() {
        for (TechniqueKind<?, AutoExposure, Technique> kind : KINDS) {
            float tolerance = kind == AutoExposure.AVERAGE ? 2e-3f : 0.18f * BIN;
            float shown = exposed(uniform(0.5f), (graph, port) -> AutoExposure.SLOT.choose(graph.settings(), kind), 1);
            assertThat(shown).as(kind.id()).isCloseTo(0.18f, within(tolerance));
            float dim = exposed(uniform(0.003f), (graph, port) -> AutoExposure.SLOT.choose(graph.settings(), kind), 1);
            assertThat(dim).as(kind.id() + ": a dim scene too").isCloseTo(0.18f, within(tolerance));
            float brighter = exposed(uniform(0.5f), (graph, port) -> {
                AutoExposure.SLOT.choose(graph.settings(), kind);
                set(graph.settings(), kind, "compensation", 1f);
            }, 1);
            assertThat(brighter).as(kind.id() + ", a stop up").isCloseTo(shown * 2, within(shown * 0.02f));
            float key = exposed(uniform(0.5f), (graph, port) -> {
                AutoExposure.SLOT.choose(graph.settings(), kind);
                set(graph.settings(), kind, "keyValue", 0.4f);
            }, 1);
            assertThat(key).as(kind.id() + ", key 0.4").isCloseTo(0.4f, within(0.4f * BIN));
        }
    }

    @Test
    void theHistogramLeavesTheBrightestOutAndTheAverageDoesNot() {
        float histogram = exposed(AutoExposureTest::lamp, (graph, port) -> {
            AutoExposure.SLOT.choose(graph.settings(), AutoExposure.HISTOGRAM);
            set(graph.settings(), AutoExposure.HISTOGRAM, "low", 0.5f);
            set(graph.settings(), AutoExposure.HISTOGRAM, "high", 0.85f);
        }, 1);
        assertThat(histogram).as("the room at middle gray, the lamp left out").isCloseTo(0.18f, within(0.18f * BIN));
        float average = exposed(AutoExposureTest::lamp, (graph, port) ->
            AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE), 1);
        // The pattern's rows: 57 of 64 at 0.1, 7 at 1000 (y >= 57). log2 average: their mean.
        double share = 7.0 / 64;
        double log = (1 - share) * Math.log(0.1) / Math.log(2) + share * Math.log(1000) / Math.log(2);
        assertThat((double) average).as("pulled down by the lamp").isCloseTo(0.1 * 0.18 / Math.pow(2, log),
            within(2e-4));
    }

    @Test
    void theExposureStaysWithinItsLimitsAndOffIsTheSetting() {
        float dark = exposed(uniform(1e-4f), (graph, port) -> {
            AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE);
            set(graph.settings(), AutoExposure.AVERAGE, "maxEv", 4f);
        }, 1);
        assertThat(dark).as("at most 16 times").isCloseTo(16e-4f, within(2e-5f));
        float bright = exposed(uniform(100), (graph, port) -> {
            AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE);
            set(graph.settings(), AutoExposure.AVERAGE, "minEv", -2f);
        }, 1);
        assertThat(bright).as("at least a quarter").isCloseTo(25, within(0.1f));
        float off = exposed(uniform(0.5f), (graph, port) -> {
            AutoExposure.SLOT.choose(graph.settings(), null);
            graph.settings().set(DisplayTransform.EXPOSURE, 3f);
        }, 2);
        assertThat(off).isCloseTo(1.5f, within(2e-3f));
    }

    @Test
    void theExposureAdaptsExponentiallyInStopsAtItsSpeeds() {
        // Bright (2) for the first frame, dark (0.02) after: the target rises from log2(0.09) to log2(9), and after n
        // more frames of 0.1 s at speed 2 the exposure is that many stops of the way: 1 - e^(-2 * 0.1 * n).
        PostFixture.Pattern darkening = (x, y, w, h, out) -> {
            float v = frame <= 1 ? 2 : 0.02f;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        double from = Math.log(0.09) / Math.log(2);
        double to = Math.log(9) / Math.log(2);
        for (int frames : new int[] {2, 4, 8}) {
            float shown = exposed(darkening, (graph, port) -> {
                AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE);
                set(graph.settings(), AutoExposure.AVERAGE, "speedUp", 2f);
                set(graph.settings(), AutoExposure.AVERAGE, "speedDown", 50f);
            }, frames);
            double ev = to + (from - to) * Math.exp(-2 * STEP * (frames - 1));
            assertThat((double) shown).as("rising, after " + frames + " frames").isCloseTo(0.02 * Math.pow(2, ev),
                within(0.02 * Math.pow(2, ev) * 0.01));
        }
        // And the other way, at the other speed: dark first, then bright.
        PostFixture.Pattern brightening = (x, y, w, h, out) -> {
            float v = frame <= 1 ? 0.02f : 2;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        float shown = exposed(brightening, (graph, port) -> {
            AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE);
            set(graph.settings(), AutoExposure.AVERAGE, "speedUp", 50f);
            set(graph.settings(), AutoExposure.AVERAGE, "speedDown", 0.5f);
        }, 5);
        double ev = from + (to - from) * Math.exp(-0.5 * STEP * 4);
        assertThat((double) shown).as("falling at its own speed").isCloseTo(2 * Math.pow(2, ev),
            within(2 * Math.pow(2, ev) * 0.01));
    }

    @Test
    void krawczyksKeyKeepsNightDarkAndFollowsAbsoluteLuminance() {
        // A uniform scene of 0.5 units at three scales: noon, dusk and night in cd/m². Shown: the key of each.
        for (double luminance : new double[] {5000, 5, 0.005}) {
            float scale = (float) (luminance / 0.5);
            float shown = exposed(uniform(0.5f), (graph, port) -> {
                AutoExposure.SLOT.choose(graph.settings(), AutoExposure.AVERAGE);
                set(graph.settings(), AutoExposure.AVERAGE, "key", AutoExposure.KeyValue.KRAWCZYK);
                set(graph.settings(), AutoExposure.AVERAGE, "luminanceScale", scale);
                set(graph.settings(), AutoExposure.AVERAGE, "minEv", -40f);
                set(graph.settings(), AutoExposure.AVERAGE, "maxEv", 40f);
            }, 1);
            assertThat((double) shown).as(luminance + " cd/m²").isCloseTo(AutoExposure.krawczyk(luminance),
                within(AutoExposure.krawczyk(luminance) * 0.005));
        }
        // effects-atmosphere's AtmosphereExposure.key: the same formula (its test values: noon above dusk above night)
        assertThat(AutoExposure.krawczyk(5000)).isGreaterThan(AutoExposure.krawczyk(5))
            .isGreaterThan(AutoExposure.krawczyk(0.005));
        assertThat(AutoExposure.krawczyk(0.005)).isLessThan(0.05);
    }

    @Test
    void aPreExposureChangeMidAdaptationLeavesTheImageAsItWas() {
        // Adapting slowly from a bright frame to a dark scene; at frame 4 the scene's units change (an atmosphere's
        // EV100 lowered by 3: scene color 8 times brighter) and the luminance scale with them. The image must show what
        // it would have without the change.
        PostFixture.Pattern unchanged = (x, y, w, h, out) -> {
            float v = frame <= 1 ? 2 : 0.02f;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        PostFixture.Pattern rescaled = (x, y, w, h, out) -> {
            if (x == 0 && y == 0 && frame == 4) { // read as frames record: the adaptation of this frame sees it
                settings.set(AutoExposure.HISTOGRAM.<Float>setting("luminanceScale"), 100f / 8);
            }
            float v = (frame <= 1 ? 2 : 0.02f) * (frame >= 4 ? 8 : 1);
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        float[] shown = new float[2];
        for (int i = 0; i < 2; i++) {
            boolean change = i == 1;
            shown[i] = exposed(change ? rescaled : unchanged, (graph, port) -> {
                settings = graph.settings();
                AutoExposure.SLOT.choose(graph.settings(), AutoExposure.HISTOGRAM);
                set(graph.settings(), AutoExposure.HISTOGRAM, "speedUp", 1f);
                graph.settings().set(AutoExposure.HISTOGRAM.<Float>setting("luminanceScale"), 100f);
            }, 6);
        }
        assertThat(shown[1]).as("the pre-exposure changed nothing").isCloseTo(shown[0], within(shown[0] * 0.01f));
        assertThat(shown[0]).as("still adapting").isLessThan(0.18f * 0.9f);
    }

    @Test
    void onlyTheRenderRegionIsMeasured() {
        PostFixture.Pattern walled = (x, y, w, h, out) -> {
            float v = x < w && y < h ? 0.1f : 1000;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        for (TechniqueKind<?, AutoExposure, Technique> kind : KINDS) {
            float shown = exposed(walled, (graph, port) -> {
                AutoExposure.SLOT.choose(graph.settings(), kind);
                graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
                port.renderScale(0.6f);
            }, 3);
            assertThat(shown).as(kind.id()).isCloseTo(0.18f, within(0.18f * BIN));
        }
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(AutoExposure.SLOT.chosen(settings)).isSameAs(AutoExposure.HISTOGRAM);
        assertThat(settings.techniques().variants(AutoExposure.SLOT)).containsExactly(AutoExposure.HISTOGRAM,
            AutoExposure.AVERAGE);
        assertThat(AutoExposure.HISTOGRAM.<Float>setting("speedUp").name()).isEqualTo("render.exposure.histogram.speedUp");
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        AutoExposure.register(registry);
        assertThat(registry.all()).contains(AutoExposure.SLOT.setting(), AutoExposure.AVERAGE.setting("key"));
    }
}
