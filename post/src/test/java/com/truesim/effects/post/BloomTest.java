package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.graph.Technique;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BloomTest {

    private static final int SIZE = 256;
    private static final List<TechniqueKind<?, Bloom, Technique>> KINDS = List.of(Bloom.PHYSICAL, Bloom.DUAL_KAWASE,
        Bloom.GAUSSIAN, Bloom.FFT);
    private static final List<TechniqueKind<?, Bloom, Technique>> CHAINS = List.of(Bloom.PHYSICAL, Bloom.DUAL_KAWASE,
        Bloom.GAUSSIAN);

    /** The most of a firefly's light Karis' average lets bloom (measured: 28% for the 13 taps' boxes). */
    private static final double MOST_KEPT = 0.3;

    private PostFixture gpu;

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.close();
    }

    /** Sets {@code kind}'s tunable {@code field} to {@code value} in {@code settings}. */
    private static <V> void set(EngineSettings settings, TechniqueKind<?, Bloom, Technique> kind, String field,
        V value) {
        settings.set(kind.<V>setting(field), value);
    }

    /** Chooses {@code kind} at full intensity (the bloom alone), 4 levels, Karis' average as {@code antiFlicker}. */
    private static void choose(EngineSettings settings, TechniqueKind<?, Bloom, Technique> kind,
        boolean antiFlicker) {
        Bloom.SLOT.choose(settings, kind);
        set(settings, kind, "intensity", 1f);
        set(settings, kind, "levels", 4);
        set(settings, kind, "antiFlicker", antiFlicker);
    }

    private boolean symmetric; // the convolution's kernel without blades: round

    /** The scene color after bloom of {@code kind} over {@code pattern}, at full intensity: the bloom alone. */
    private float[] bloomed(TechniqueKind<?, Bloom, Technique> kind, boolean antiFlicker, PostFixture.Pattern pattern) {
        return gpu.render(SIZE, SIZE, pattern, (graph, port) -> {
            choose(graph.settings(), kind, antiFlicker);
            if (kind == Bloom.FFT) {
                set(graph.settings(), kind, "blades", symmetric ? 0 : 6);
            }
            port.add(new Bloom());
        }, 1);
    }

    /** A bright 8 x 8 square in the middle, aligned to every level of the chain: (124..131)^2 at 10. */
    private static void square(int x, int y, int w, int h, float[] out) {
        float v = x >= 124 && x < 132 && y >= 124 && y < 132 ? 10 : 0;
        out[0] = v;
        out[1] = v * 0.5f;
        out[2] = v * 0.25f;
    }

    private static double sum(float[] rgba, int channel) {
        double sum = 0;
        for (int i = channel; i < rgba.length; i += 4) {
            sum += rgba[i];
        }
        return sum;
    }

    private static float at(float[] rgba, int x, int y) {
        return rgba[4 * (y * SIZE + x)];
    }

    @Test
    void bloomMovesLightWithoutAddingOrLosingAny() {
        double before = 64 * 10;
        for (TechniqueKind<?, Bloom, Technique> kind : KINDS) {
            float[] out = bloomed(kind, false, BloomTest::square);
            assertThat(sum(out, 0)).as(kind.id() + ": red's total").isCloseTo(before, within(before * 0.01));
            assertThat(sum(out, 2)).as(kind.id() + ": blue's total").isCloseTo(before / 4, within(before / 4 * 0.01));
            // (the convolution's default kernel keeps most of the light in its core)
            assertThat(at(out, 128, 128)).as(kind.id() + ": spread out").isLessThan(kind == Bloom.FFT ? 8 : 5);
            assertThat(at(out, 100, 128)).as(kind.id() + ": reaching far").isGreaterThan(0);
        }
        // Karis' average weighs the square's edges less: a little light lost there, nothing gained.
        float[] karis = bloomed(Bloom.PHYSICAL, true, BloomTest::square);
        assertThat(sum(karis, 0)).isBetween(before * 0.8, before * 1.001);
    }

    @Test
    void aBrightSquareSpreadsSymmetrically() {
        symmetric = true;
        for (TechniqueKind<?, Bloom, Technique> kind : KINDS) {
            float[] out = bloomed(kind, true, BloomTest::square);
            for (int d = 0; d < 60; d += 3) {
                float right = at(out, 128 + d, 128);
                float tolerance = right * 0.02f + 1e-4f;
                assertThat(at(out, 127 - d, 128)).as(kind.id() + ": left and right, " + d).isCloseTo(right,
                    within(tolerance));
                assertThat(at(out, 128, 128 + d)).as(kind.id() + ": across and down, " + d).isCloseTo(right,
                    within(tolerance));
                assertThat(at(out, 128, 127 - d)).as(kind.id() + ": up and down, " + d).isCloseTo(right,
                    within(tolerance));
                if (d > 0) {
                    assertThat(right).as(kind.id() + ": falling off, " + d)
                        .isLessThanOrEqualTo(at(out, 128 + d - 3, 128) + 1e-5f);
                }
            }
        }
    }

    @Test
    void karisAverageKeepsAFireflyFromBlooming() {
        // One pixel 10,000 times brighter than a dim floor, at each place in a 2 x 2 cell of the first level: without
        // Karis' average all its light blooms; with it, most is held back (measured: Jimenez's partial average on the
        // 13 taps' five boxes keeps 28%, the per-tap average of the others under 1%).
        double floor = 0.1 * SIZE * SIZE;
        for (TechniqueKind<?, Bloom, Technique> kind : CHAINS) {
            for (int cell = 0; cell < 4; cell++) {
                int fx = 100 + (cell & 1);
                int fy = 76 + (cell >> 1);
                PostFixture.Pattern firefly = (x, y, w, h, out) -> {
                    float v = x == fx && y == fy ? 1000 : 0.1f;
                    out[0] = v;
                    out[1] = v;
                    out[2] = v;
                };
                double plain = sum(bloomed(kind, false, firefly), 0) - floor;
                double karis = sum(bloomed(kind, true, firefly), 0) - floor;
                assertThat(plain).as(kind.id() + ": all of it, plain").isCloseTo(1000 - 0.1, within(15.0));
                assertThat(karis).as(kind.id() + ": held back with Karis' average").isLessThan(plain * MOST_KEPT);
            }
        }
    }

    @Test
    void aMovingLightBloomsSteadily() {
        // A pixel of light 100 stepping a pixel at a time across the half-size grid: the light each technique spreads
        // around it (4 pixels and more away) must not pulse with where the pixel falls in the levels' cells, or moving
        // highlights shimmer (measured: under 0.2% for the chains, the convolution exact).
        for (TechniqueKind<?, Bloom, Technique> kind : KINDS) {
            double[] halo = new double[8];
            for (int step = 0; step < 8; step++) {
                int px = 120 + step;
                float[] out = bloomed(kind, true, (x, y, w, h, rgb) -> {
                    float v = x == px && y == 128 ? 100 : 0;
                    rgb[0] = v;
                    rgb[1] = v;
                    rgb[2] = v;
                });
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        if (Math.max(Math.abs(x - px), Math.abs(y - 128)) >= 4) {
                            halo[step] += out[4 * (y * SIZE + x)];
                        }
                    }
                }
            }
            double least = java.util.Arrays.stream(halo).min().orElseThrow();
            double most = java.util.Arrays.stream(halo).max().orElseThrow();
            assertThat(least).as(kind.id() + ": some light spread").isPositive();
            assertThat(most / least).as(kind.id() + ": the same wherever the pixel falls " + java.util.Arrays
                .toString(halo)).isLessThan(1.01);
        }
    }

    @Test
    void bloomStaysWithinTheRenderRegion() {
        // Inside the region a dim floor, outside it light 10,000 times brighter: none of it may bleed in.
        PostFixture.Pattern walled = (x, y, w, h, out) -> {
            float v = x < w && y < h ? 0.1f : 1000;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        };
        for (TechniqueKind<?, Bloom, Technique> kind : KINDS) {
            float[] out = gpu.render(SIZE, SIZE, walled, (graph, port) -> {
                Bloom.SLOT.choose(graph.settings(), kind);
                set(graph.settings(), kind, "intensity", 1f);
                graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.5f);
                port.add(new Bloom()).renderScale(0.6f);
            }, 3);
            float least = Float.MAX_VALUE;
            float most = 0;
            for (int i = 0; i < out.length; i += 4) {
                least = Math.min(least, out[i]);
                most = Math.max(most, out[i]);
            }
            if (kind == Bloom.FFT) {
                // The convolution pads the region with black: its edges get no light from beyond the screen, and
                // lose what the kernel spreads past them (half, at an edge, of a kernel this small).
                assertThat(least).as(kind.id()).isBetween(0.04f, 0.1f);
            } else {
                assertThat(least).as(kind.id()).isCloseTo(0.1f, within(1e-3f));
            }
            assertThat(most).as(kind.id() + ": nothing from beyond the region").isCloseTo(0.1f, within(1e-3f));
        }
    }

    /** One pixel of light 1000 in the middle of a black scene, bloomed by the convolution only, with {@code set}. */
    private float[] point(Consumer<EngineSettings> settings) {
        return gpu.render(SIZE, SIZE, (x, y, w, h, out) -> {
            float v = x == 128 && y == 128 ? 1000 : 0;
            out[0] = v;
            out[1] = v;
            out[2] = v;
        }, (graph, port) -> {
            Bloom.SLOT.choose(graph.settings(), Bloom.FFT);
            set(graph.settings(), Bloom.FFT, "intensity", 1f);
            set(graph.settings(), Bloom.FFT, "size", 512);
            settings.accept(graph.settings());
            port.add(new Bloom());
        }, 1);
    }

    @Test
    void theSpikesTurnWithTheBlades() {
        // Four blades: four spikes, along the axes unturned, along the diagonals turned by 45 degrees.
        for (float rotation : new float[] {0, 45}) {
            float[] out = point(s -> {
                set(s, Bloom.FFT, "blades", 4);
                set(s, Bloom.FFT, "rotation", rotation);
                set(s, Bloom.FFT, "spikes", 0.5f);
                set(s, Bloom.FFT, "corona", 0f);
                set(s, Bloom.FFT, "spread", 0f);
            });
            float axis = at(out, 128 + 30, 128) + at(out, 128, 128 + 30) + at(out, 128 - 30, 128);
            float diagonal = at(out, 128 + 21, 128 + 21) + at(out, 128 - 21, 128 - 21) + at(out, 128 + 21, 128 - 21);
            if (rotation == 0) {
                assertThat(axis).as("unturned: along the axes").isGreaterThan(diagonal * 20);
            } else {
                assertThat(diagonal).as("turned: along the diagonals").isGreaterThan(axis * 20);
            }
            assertThat(sum(out, 1)).as("energy kept").isCloseTo(1000, within(10.0));
        }
    }

    @Test
    void theConvolutionMixesInThePhysicalChainsGlowByItsShare() {
        PostFixture.Pattern pattern = BloomTest::square;
        float[] chain = gpu.render(SIZE, SIZE, pattern, (graph, port) -> {
            choose(graph.settings(), Bloom.PHYSICAL, false);
            port.add(new Bloom());
        }, 1);
        float[] convolution = bloomed(Bloom.FFT, false, pattern);
        float[] mixed = gpu.render(SIZE, SIZE, pattern, (graph, port) -> {
            choose(graph.settings(), Bloom.FFT, false);
            set(graph.settings(), Bloom.FFT, "chain", 0.5f);
            port.add(new Bloom());
        }, 1);
        for (int x : new int[] {100, 120, 128, 140}) {
            assertThat(at(mixed, x, 128)).as("half of each at " + x).isCloseTo(
                (at(chain, x, 128) + at(convolution, x, 128)) / 2, within(at(chain, x, 128) * 0.01f + 1e-3f));
        }
        assertThat(sum(mixed, 0)).as("energy kept").isCloseTo(640, within(6.4));
    }

    @Test
    void theIntensityIsReadAsFramesRecord() {
        // Half: half the scene, half the bloom; the bloom's total is the scene's, so the totals stay.
        float[] half = gpu.render(SIZE, SIZE, BloomTest::square, (graph, port) -> {
            choose(graph.settings(), Bloom.PHYSICAL, false);
            set(graph.settings(), Bloom.PHYSICAL, "intensity", 0.5f);
            port.add(new Bloom());
        }, 1);
        float[] full = bloomed(Bloom.PHYSICAL, false, BloomTest::square);
        assertThat(at(half, 128, 128)).isCloseTo(5 + at(full, 128, 128) / 2, within(0.01f));
        assertThat(sum(half, 0)).isCloseTo(640, within(6.4));
    }

    @Test
    void offLeavesTheSceneAsItIs() {
        float[] out = gpu.render(SIZE, SIZE, BloomTest::square, (graph, port) -> {
            Bloom.SLOT.choose(graph.settings(), null);
            port.add(new Bloom());
        }, 1);
        assertThat(at(out, 128, 128)).isEqualTo(10);
        assertThat(at(out, 100, 128)).isZero();
        assertThat(Bloom.levels(6, 960, 540)).isEqualTo(6);
        assertThat(Bloom.levels(6, 8, 8)).as("down to 2 texels").isEqualTo(3);
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(Bloom.SLOT.chosen(settings)).as("the default").isSameAs(Bloom.PHYSICAL);
        assertThat(settings.techniques().variants(Bloom.SLOT)).containsExactly(Bloom.PHYSICAL, Bloom.DUAL_KAWASE,
            Bloom.GAUSSIAN, Bloom.FFT);
        assertThat(Bloom.PHYSICAL.config(settings).intensity()).isEqualTo(0.05f);
        assertThat(Bloom.FFT.config(settings).size()).isEqualTo(512);
        assertThat(Bloom.DUAL_KAWASE.<Float>setting("radius").name()).isEqualTo("render.bloom.dual_kawase.radius");
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        Bloom.register(registry);
        assertThat(registry.all()).contains(Bloom.SLOT.setting(), Bloom.FFT.setting("blades"),
            Bloom.GAUSSIAN.setting("levels"));
        assertThat(new EngineSettings().load(java.util.Map.of("render.bloom", "OFF")).get(Bloom.SLOT.setting()))
            .isEqualToIgnoringCase("off");
    }
}
