package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueKind;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DepthOfFieldTest {

    private static final int SIZE = 256;
    private static final double NEAR = 0.1;
    private static final double FOV = Math.PI / 3;
    private static final List<TechniqueKind<?, DepthOfField, Technique>> KINDS = List.of(DepthOfField.GATHER,
        DepthOfField.GAUSSIAN);

    private PostFixture gpu;
    private final Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, FOV, SIZE, SIZE, NEAR);
    private int frame;

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    private static float depthAt(double distance) {
        return (float) (NEAR / distance);
    }

    /** The focal length the camera's field of view gives on a sensor of 24 mm. */
    private static double focal() {
        return 0.012 / Math.tan(FOV / 2);
    }

    private float[] render(TechniqueKind<?, DepthOfField, Technique> kind, float fStop, float focus,
        PostFixture.Pattern color, PostFixture.Depth depth) {
        return render(kind, color, depth, s -> s.set(DepthOfField.F_STOP, fStop).set(DepthOfField.FOCUS_DISTANCE, focus)
            .set(DepthOfField.AUTOFOCUS, false), 1);
    }

    private float[] render(TechniqueKind<?, DepthOfField, Technique> kind, PostFixture.Pattern color,
        PostFixture.Depth depth, Consumer<EngineSettings> set, int frames) {
        frame = 0;
        double[] now = {0};
        return gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            if (x == 0 && y == 0) {
                frame++;
            }
            color.rgb(x, y, w, h, rgb);
        }, depth, (graph, port) -> {
            DepthOfField.SLOT.choose(graph.settings(), kind);
            if (kind != null) {
                graph.settings().set(kind.<Float>setting("maxRadius"), 32f);
            }
            set.accept(graph.settings());
            port.add(new DepthOfField(camera, () -> {
                now[0] += 0.1;
                return now[0];
            }));
        }, frames);
    }

    private static float at(float[] rgba, int x, int y) {
        return rgba[4 * (y * SIZE + x)];
    }

    /** Left half dark, right half bright: an edge down the middle. */
    private static void edge(int x, int y, int w, int h, float[] out) {
        float v = x < SIZE / 2 ? 0 : 1;
        out[0] = v;
        out[1] = v;
        out[2] = v;
    }

    /** Stripes 2 pixels wide. */
    private static void stripes(int x, int y, int w, int h, float[] out) {
        float v = (x / 2) % 2 == 0 ? 0.2f : 0.8f;
        out[0] = v;
        out[1] = v;
        out[2] = v;
    }

    /** How far stripes of 0.2 and 0.8 are from sharp along row 128 from 100 to 140: 0 sharp, 0.3 a flat gray. */
    private static double blur(float[] out) {
        double most = 0;
        for (int x = 100; x < 140; x++) {
            most = Math.max(most, Math.abs(at(out, x, 128) - ((x / 2) % 2 == 0 ? 0.2f : 0.8f)));
        }
        return most;
    }

    @Test
    void theCircleOfConfusionIsTheThinLens() {
        // 50 mm at f/2 focused at 2 m, a point at 10 m, a 24 mm sensor over 1080 rows: a disc of 0.513 mm, 11.5 pixels
        // in radius.
        assertThat(DepthOfField.circleOfConfusion(10, 2, 0.05, 2, 0.024, 1080)).isCloseTo(11.54, within(0.01));
        assertThat(DepthOfField.circleOfConfusion(1, 2, 0.05, 2, 0.024, 1080)).as("before the focus: negative")
            .isCloseTo(-14.42, within(0.01));
        assertThat(DepthOfField.circleOfConfusion(2, 2, 0.05, 2, 0.024, 1080)).isZero();
    }

    @Test
    void whatIsInFocusStaysSharp() {
        for (TechniqueKind<?, DepthOfField, Technique> kind : KINDS) {
            float[] out = render(kind, 0.7f, 3, DepthOfFieldTest::stripes, (x, y, w, h) -> depthAt(3));
            assertThat(blur(out)).as(kind.id()).isLessThan(1e-3);
        }
    }

    @Test
    void theFarFieldBlursAsWideAsTheLensSays() {
        double radius = DepthOfField.circleOfConfusion(10, 0.5, focal(), 0.7, 0.024, SIZE);
        assertThat(radius).as("the setup: a blur worth measuring").isBetween(5.0, 10.0);
        for (TechniqueKind<?, DepthOfField, Technique> kind : KINDS) {
            float[] out = render(kind, 0.7f, 0.5f, DepthOfFieldTest::edge, (x, y, w, h) -> depthAt(10));
            int from = -1;
            int to = -1;
            double sum = 0;
            for (int x = 0; x < SIZE; x++) {
                float v = at(out, x, 128);
                sum += v;
                if (from < 0 && v > 0.05f) {
                    from = x;
                }
                if (to < 0 && v > 0.95f) {
                    to = x;
                }
            }
            int width = to - from;
            // A disc's edge goes from dark to bright over its diameter; a Gaussian of sigma r / 2 over about 1.6 r.
            assertThat((double) width).as(kind.id() + ": the edge's spread, the circle " + radius)
                .isBetween(radius * (kind == DepthOfField.GATHER ? 1.3 : 1.0), radius * 2.6);
            assertThat(sum).as(kind.id() + ": light kept").isCloseTo(SIZE / 2.0, within(SIZE * 0.01));
            assertThat(at(out, 128 - (int) (radius * 1.6), 128)).as(kind.id() + ": far from the edge, untouched")
                .isZero();
        }
    }

    @Test
    void theNearFieldSpreadsOverSharpEdgesWithTheGatherOnly() {
        // A bright square 0.4 m away, in front of a sharp dark background at the focus (3 m).
        PostFixture.Pattern square = (x, y, w, h, out) -> {
            float v = x >= 96 && x < 160 && y >= 96 && y < 160 ? 1 : 0;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        PostFixture.Depth depth = (x, y, w, h) -> x >= 96 && x < 160 && y >= 96 && y < 160 ? depthAt(0.4) : depthAt(3);
        double radius = -DepthOfField.circleOfConfusion(0.4, 3, focal(), 0.7, 0.024, SIZE);
        assertThat(radius).isGreaterThan(4);
        float[] gather = render(DepthOfField.GATHER, 0.7f, 3, square, depth);
        float[] gaussian = render(DepthOfField.GAUSSIAN, 0.7f, 3, square, depth);
        int outside = 96 - (int) (radius / 2); // on the sharp background, within the square's circle
        assertThat(at(gather, outside, 128)).as("gather: the near square's blur over the background")
            .isGreaterThan(0.1f);
        assertThat(at(gaussian, outside, 128)).as("Gaussian: the background stays sharp").isZero();
        assertThat(at(gather, 30, 128)).as("beyond its circle: nothing").isZero();
    }

    @Test
    void aSharpForegroundGetsNoHaloFromTheBlurredBackground() {
        // A dark square in focus at 0.5 m in front of a bright background far behind (10 m) and blurred: the
        // background's blur must not spread over the square (no bright halo inside its edge), and the square's dark
        // must not spread into the background (no dark halo around it) beyond the composite's one pixel.
        PostFixture.Pattern square = (x, y, w, h, out) -> {
            float v = x >= 96 && x < 160 && y >= 96 && y < 160 ? 0 : 1;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        PostFixture.Depth depth = (x, y, w, h) -> x >= 96 && x < 160 && y >= 96 && y < 160 ? depthAt(0.5)
            : depthAt(10);
        double radius = DepthOfField.circleOfConfusion(10, 0.5, focal(), 0.7, 0.024, SIZE);
        assertThat(radius).isGreaterThan(5);
        for (TechniqueKind<?, DepthOfField, Technique> kind : KINDS) {
            float[] out = render(kind, 0.7f, 0.5f, square, depth);
            for (int d = 2; d < 20; d++) {
                assertThat(at(out, 96 + d, 128)).as(kind.id() + ": inside the sharp square, " + d + " from its edge")
                    .isLessThan(0.02f);
                assertThat(at(out, 95 - d, 128)).as(kind.id() + ": the background, " + d + " from the square")
                    .isGreaterThan(0.98f);
            }
        }
    }

    @Test
    void autofocusFocusesOnTheMiddleAndFollowsAtItsSpeed() {
        // Everything 5 m away; the lens set to 50 m, but autofocus finds 5 m: the stripes stay sharp.
        for (TechniqueKind<?, DepthOfField, Technique> kind : KINDS) {
            float[] out = render(kind, DepthOfFieldTest::stripes, (x, y, w, h) -> depthAt(5), s -> s
                .set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.FOCUS_DISTANCE, 50f), 1);
            assertThat(blur(out)).as(kind.id()).isLessThan(1e-3);
        }
        // At 1 m for a frame, then at 5 m: quick autofocus has followed after 6 frames, slow has not.
        PostFixture.Depth moving = (x, y, w, h) -> depthAt(frame <= 1 ? 1 : 5);
        float[] quick = render(DepthOfField.GATHER, DepthOfFieldTest::stripes, moving, s -> s
            .set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.AUTOFOCUS_SPEED, 200f), 6);
        float[] slow = render(DepthOfField.GATHER, DepthOfFieldTest::stripes, moving, s -> s
            .set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.AUTOFOCUS_SPEED, 0.5f), 6);
        assertThat(blur(quick)).as("followed").isLessThan(1e-3);
        assertThat(blur(slow)).as("still racking").isGreaterThan(0.1);
        // Off: the focus distance as set
        float[] fixed = render(DepthOfField.GATHER, DepthOfFieldTest::stripes, moving, s -> s
            .set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.AUTOFOCUS, false).set(DepthOfField.FOCUS_DISTANCE, 1f), 3);
        assertThat(blur(fixed)).as("focused at 1 m, the stripes at 5 m").isGreaterThan(0.1);
    }

    @Test
    void depthOfFieldStaysWithinTheRenderRegion() {
        // Far and blurred everywhere; light 10,000 times brighter beyond the region must not bleed in.
        PostFixture.Pattern walled = (x, y, w, h, out) -> {
            float v = x < w && y < h ? 0.1f : 1000;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        for (TechniqueKind<?, DepthOfField, Technique> kind : KINDS) {
            float[] out = gpu.render(SIZE, SIZE, walled, (x, y, w, h) -> x < w && y < h ? depthAt(10) : depthAt(0.2),
                (graph, port) -> {
                    DepthOfField.SLOT.choose(graph.settings(), kind);
                    graph.settings().set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.FOCUS_DISTANCE, 0.5f)
                        .set(DepthOfField.AUTOFOCUS, false).set(ResolutionSettings.DYNAMIC, true)
                        .set(ResolutionSettings.HEADROOM, 1.5f);
                    port.add(new DepthOfField(camera)).renderScale(0.6f);
                }, 3);
            float most = 0;
            for (int i = 0; i < out.length; i += 4) {
                most = Math.max(most, out[i]);
            }
            assertThat(most).as(kind.id() + ": nothing from beyond the region").isCloseTo(0.1f, within(1e-3f));
        }
    }

    @Test
    void offLeavesTheSceneAsItIs() {
        float[] out = render(null, DepthOfFieldTest::edge, (x, y, w, h) -> depthAt(10), s -> s
            .set(DepthOfField.F_STOP, 0.7f).set(DepthOfField.AUTOFOCUS, false).set(DepthOfField.FOCUS_DISTANCE, 0.5f),
            1);
        assertThat(at(out, 127, 128)).isZero();
        assertThat(at(out, 128, 128)).isEqualTo(1);
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(DepthOfField.SLOT.chosen(settings)).isSameAs(DepthOfField.GATHER);
        assertThat(settings.techniques().variants(DepthOfField.SLOT)).containsExactly(DepthOfField.GATHER,
            DepthOfField.GAUSSIAN);
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        DepthOfField.register(registry);
        assertThat(registry.all()).contains(DepthOfField.SLOT.setting(), DepthOfField.GATHER.setting("maxRadius"),
            DepthOfField.F_STOP, DepthOfField.AUTOFOCUS_AREA);
    }
}
