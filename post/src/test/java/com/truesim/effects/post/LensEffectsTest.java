package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LensEffectsTest {

    private static final int WIDTH = 192;
    private static final int HEIGHT = 128;
    private static final double FOV = Math.PI / 2.5;

    private PostFixture gpu;
    private final Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, FOV, WIDTH, HEIGHT, 0.1);

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    private float[] render(PostFixture.Pattern pattern, Consumer<EngineSettings> set) {
        return gpu.render(WIDTH, HEIGHT, pattern, (graph, port) -> {
            set.accept(graph.settings());
            port.add(new LensEffects(camera));
        }, 1);
    }

    /** Red, green and blue all the pixel's x over the width: what a position shows is where it was read. */
    private static void rampX(int x, int y, int w, int h, float[] out) {
        float v = (x + 0.5f) / WIDTH;
        out[0] = v;
        out[1] = v;
        out[2] = v;
    }

    /** The same along y. */
    private static void rampY(int x, int y, int w, int h, float[] out) {
        float v = (y + 0.5f) / HEIGHT;
        out[0] = v;
        out[1] = v;
        out[2] = v;
    }

    private static float at(float[] rgba, int x, int y, int c) {
        return rgba[4 * (y * WIDTH + x) + c];
    }

    /** The centered, aspect-scaled position of pixel x on a row: y from -1 to 1. */
    private static double centered(int x) {
        return ((x + 0.5) / WIDTH * 2 - 1) * WIDTH / HEIGHT;
    }

    /** What the x ramp shows at centered position v. */
    private static double rampAt(double v) {
        return v * HEIGHT / WIDTH * 0.5 + 0.5;
    }

    @Test
    void offChangesNothing() {
        float[] out = render(LensEffectsTest::rampX, s -> { });
        assertThat(at(out, 10, 64, 0)).isEqualTo((10 + 0.5f) / WIDTH, within(1e-3f));
    }

    @Test
    void theNaturalVignetteIsTheCosineFourthLaw() {
        float[] out = render((x, y, w, h, rgb) -> {
            rgb[0] = 1;
            rgb[1] = 1;
            rgb[2] = 1;
        }, s -> LensEffects.VIGNETTE.choose(s, LensEffects.NATURAL));
        double tanHalf = Math.tan(FOV / 2);
        for (int[] p : new int[][] {{0, 0}, {96, 64}, {191, 64}, {150, 20}}) {
            double vx = ((p[0] + 0.5) / WIDTH * 2 - 1) * WIDTH / HEIGHT;
            double vy = (p[1] + 0.5) / HEIGHT * 2 - 1;
            double t = Math.sqrt(vx * vx + vy * vy) * tanHalf;
            double cos = 1 / Math.sqrt(1 + t * t);
            assertThat((double) at(out, p[0], p[1], 1)).as(p[0] + ", " + p[1]).isCloseTo(Math.pow(cos, 4),
                within(2e-3));
        }
    }

    @Test
    void theArtisticVignetteDarkensOnlyItsOuterPart() {
        float[] out = render((x, y, w, h, rgb) -> {
            rgb[0] = 1;
            rgb[1] = 1;
            rgb[2] = 1;
        }, s -> {
            LensEffects.VIGNETTE.choose(s, LensEffects.ARTISTIC);
            s.set(LensEffects.ARTISTIC.<Float>setting("intensity"), 1f);
            s.set(LensEffects.ARTISTIC.<Float>setting("smoothness"), 0.4f);
        });
        assertThat(at(out, 96, 64, 0)).as("the middle untouched").isEqualTo(1, within(1e-3f));
        assertThat(at(out, 0, 0, 0)).as("black at the corner").isLessThan(0.02f);
        assertThat(at(out, 70, 50, 0)).as("inside the falloff's reach").isEqualTo(1, within(1e-3f));
    }

    @Test
    void distortionAndAberrationMoveWhatIsReadAsTheirFormulas() {
        float k = 0.2f;
        float amount = 0.02f;
        float[] out = render(LensEffectsTest::rampX, s -> {
            LensEffects.DISTORTION.choose(s, LensEffects.BROWN_CONRADY);
            s.set(LensEffects.BROWN_CONRADY.<Float>setting("k"), k);
            LensEffects.ABERRATION.choose(s, LensEffects.RADIAL);
            s.set(LensEffects.RADIAL.<Float>setting("amount"), amount);
        });
        float aspect = (float) WIDTH / HEIGHT;
        double corner = aspect * aspect + 1;
        double scale = LensEffects.scale(k, aspect) * (1 + amount);
        for (int x : new int[] {20, 60, 140, 170}) {
            double v = centered(x);
            double vy = (64 + 0.5) / HEIGHT * 2 - 1;
            double r2 = v * v + vy * vy;
            double d = v * (1 + k * r2 / corner) / scale;
            double shift = amount * r2 / corner;
            assertThat((double) at(out, x, 64, 1)).as("green at " + x).isCloseTo(rampAt(d), within(2e-3));
            assertThat((double) at(out, x, 64, 0)).as("red at " + x).isCloseTo(rampAt(d * (1 + shift)), within(2e-3));
            assertThat((double) at(out, x, 64, 2)).as("blue at " + x).isCloseTo(rampAt(d * (1 - shift)), within(2e-3));
        }
    }

    @Test
    void distortionKeepsTheFrameFilledAndNeverFolds() {
        // Along the middle row and column, and along the top row, what each pixel reads must grow with the pixel
        // (no fold), and the frame's edges must read within a pixel of the image's edges where the scale meets them:
        // pincushion at the corners, barrel at the middles of the nearer (top and bottom) edges.
        for (float k : new float[] {-1f / 3, -0.15f, 0.3f, 1f}) {
            Consumer<EngineSettings> set = s -> {
                LensEffects.DISTORTION.choose(s, LensEffects.BROWN_CONRADY);
                s.set(LensEffects.BROWN_CONRADY.<Float>setting("k"), k);
            };
            float[] across = render(LensEffectsTest::rampX, set);
            float[] down = render(LensEffectsTest::rampY, set);
            for (int row : new int[] {0, 64}) {
                for (int x = 1; x < WIDTH; x++) {
                    assertThat(at(across, x, row, 0)).as(k + ": growing along row " + row + " at " + x)
                        .isGreaterThan(at(across, x - 1, row, 0));
                }
            }
            for (int y = 1; y < HEIGHT; y++) {
                assertThat(at(down, 96, y, 0)).as(k + ": growing down the middle at " + y)
                    .isGreaterThan(at(down, 96, y - 1, 0));
            }
            float pixelX = 1f / WIDTH;
            float pixelY = 1f / HEIGHT;
            if (k > 0) {
                assertThat(at(across, 0, 0, 0)).as(k + ": the corner reads the corner").isLessThan(1.5f * pixelX);
                assertThat(at(down, 0, 0, 0)).isLessThan(1.5f * pixelY);
            } else {
                assertThat(at(down, 96, 0, 0)).as(k + ": the top's middle reads the top").isLessThan(1.5f * pixelY);
                assertThat(at(across, 0, 64, 0)).as(k + ": the left middle reads inside").isGreaterThan(0.5f * pixelX);
            }
        }
    }

    @Test
    void spectralAberrationKeepsAUniformColor() {
        float[] out = render((x, y, w, h, rgb) -> {
            rgb[0] = 0.3f;
            rgb[1] = 0.6f;
            rgb[2] = 0.9f;
        }, s -> {
            LensEffects.ABERRATION.choose(s, LensEffects.SPECTRAL);
            s.set(LensEffects.SPECTRAL.<Float>setting("amount"), 0.05f);
        });
        for (int x = 0; x < WIDTH; x += 17) {
            assertThat(at(out, x, 5, 0)).isCloseTo(0.3f, within(2e-3f));
            assertThat(at(out, x, 5, 1)).isCloseTo(0.6f, within(2e-3f));
            assertThat(at(out, x, 5, 2)).isCloseTo(0.9f, within(2e-3f));
        }
    }

    @Test
    void spectralFringesLieBetweenRadialOnes() {
        // At an edge near the frame's side, spectral aberration spreads red over the positions between the three
        // taps': its red there lies between radial's and none.
        PostFixture.Pattern edge = (x, y, w, h, rgb) -> {
            float v = x < 170 ? 0 : 1;
            rgb[0] = v;
            rgb[1] = v;
            rgb[2] = v;
        };
        float[] radial = render(edge, s -> {
            LensEffects.ABERRATION.choose(s, LensEffects.RADIAL);
            s.set(LensEffects.RADIAL.<Float>setting("amount"), 0.05f);
        });
        float[] spectral = render(edge, s -> {
            LensEffects.ABERRATION.choose(s, LensEffects.SPECTRAL);
            s.set(LensEffects.SPECTRAL.<Float>setting("amount"), 0.05f);
        });
        double radialRed = 0;
        double spectralRed = 0;
        for (int x = 150; x < 192; x++) {
            radialRed += at(radial, x, 64, 0) - at(radial, x, 64, 2);
            spectralRed += at(spectral, x, 64, 0) - at(spectral, x, 64, 2);
        }
        assertThat(radialRed).as("red outward of blue").isPositive();
        assertThat(spectralRed).as("spectral: a smaller split, spread").isPositive().isLessThan(radialRed);
    }

    @Test
    void theSlotsAndTheirTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(LensEffects.DISTORTION.chosen(settings)).as("off by default").isNull();
        assertThat(LensEffects.ABERRATION.chosen(settings)).isNull();
        assertThat(LensEffects.VIGNETTE.chosen(settings)).isNull();
        assertThat(settings.techniques().variants(LensEffects.ABERRATION)).containsExactly(LensEffects.RADIAL,
            LensEffects.SPECTRAL);
        assertThat(LensEffects.BROWN_CONRADY.<Float>setting("k").name()).isEqualTo(
            "render.lens.distortion.brown_conrady.k");
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        LensEffects.register(registry);
        assertThat(registry.all()).contains(LensEffects.VIGNETTE.setting(), LensEffects.ARTISTIC.setting("roundness"));
    }
}
