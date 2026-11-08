package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilmGrainTest {

    private static final int SIZE = 128;
    private static final Key<Image> SHOWN = Key.image("test.shown");

    private PostFixture gpu;

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    /** The display color of a scene of {@code left} (left half) and {@code right}, after {@code frames} frames. */
    private float[] shown(float left, float right, int frames, Consumer<EngineSettings> set) {
        return gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            float v = x < SIZE / 2 ? left : right;
            rgb[0] = v;
            rgb[1] = v;
            rgb[2] = v;
        }, (graph, port) -> {
            // scRGB at 80 nits, no curve: the display color is the scene color
            graph.settings().set(DisplayTransform.CURVE, "off").set(DisplayTransform.PAPER_WHITE, 80f)
                .set(DisplayTransform.DITHER, false);
            FilmGrain.SLOT.choose(graph.settings(), FilmGrain.FILM);
            set.accept(graph.settings());
            port.add(new FilmGrain());
            for (Technique each : new DisplayTransform().displayEffects().transforms(Keys.SCENE_COLOR, SHOWN,
                DisplayEncoding.SCRGB_LINEAR)) {
                port.add(each);
            }
        }, SHOWN, Format.R16G16B16A16_SFLOAT, frames);
    }

    /** The mean and the standard deviation of green over columns {@code from} to {@code to}. */
    private static double[] stats(float[] out, int from, int to) {
        double sum = 0;
        double squares = 0;
        int n = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = from; x < to; x++) {
                double v = out[4 * (y * SIZE + x) + 1];
                sum += v;
                squares += v * v;
                n++;
            }
        }
        double mean = sum / n;
        return new double[] {mean, Math.sqrt(Math.max(squares / n - mean * mean, 0))};
    }

    @Test
    void grainKeepsTheAverageBlackAndLessInTheHighlights() {
        float[] out = shown(0.2f, 1, 1, s -> {
            s.set(FilmGrain.FILM.<Float>setting("intensity"), 0.3f);
            s.set(FilmGrain.FILM.<Float>setting("response"), 0.8f);
        });
        double[] dark = stats(out, 0, SIZE / 2);
        double[] white = stats(out, SIZE / 2, SIZE);
        assertThat(dark[0]).as("the average kept").isCloseTo(0.2, within(0.2 * 0.02));
        assertThat(dark[1]).as("grain there").isGreaterThan(0.2 * 0.3 * 0.2);
        // The response leaves 1 - 0.8 sqrt(luma) of the grain: a fifth at white, 0.64 at 0.2
        double ratio = 0.2 / (1 - 0.8 * Math.sqrt(0.2));
        assertThat(white[1] / white[0]).as("less in the highlights").isCloseTo(dark[1] / dark[0] * ratio,
            within(dark[1] / dark[0] * ratio * 0.1));
        float[] black = shown(0, 0, 1, s -> s.set(FilmGrain.FILM.<Float>setting("intensity"), 1f));
        assertThat(stats(black, 0, SIZE)[1]).as("black stays black").isZero();
    }

    @Test
    void theGrainIsNewEachFrameAndOffAddsNone() {
        float[] first = shown(0.5f, 0.5f, 1, s -> { });
        float[] second = shown(0.5f, 0.5f, 2, s -> { });
        int differ = 0;
        for (int i = 1; i < first.length; i += 4) {
            differ += first[i] != second[i] ? 1 : 0;
        }
        assertThat(differ).as("a new grain").isGreaterThan(SIZE * SIZE / 2);
        float[] off = shown(0.5f, 0.5f, 1, s -> FilmGrain.SLOT.choose(s, null));
        assertThat(stats(off, 0, SIZE)[1]).isZero();
        assertThat(stats(off, 0, SIZE)[0]).isCloseTo(0.5, within(1e-3));
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(FilmGrain.SLOT.chosen(settings)).as("off by default").isNull();
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        FilmGrain.register(registry);
        assertThat(registry.all()).contains(FilmGrain.SLOT.setting(), FilmGrain.FILM.setting("size"));
    }
}
