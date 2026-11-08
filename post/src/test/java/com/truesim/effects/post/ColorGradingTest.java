package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.graph.Technique;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.scene.render.TextureData;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ColorGradingTest {

    private static final int SIZE = 16;
    private static final List<TechniqueKind<?, ColorGrading, Technique>> KINDS = List.of(ColorGrading.BAKED,
        ColorGrading.DIRECT);
    /** Colors from black to far above white, saturated and not. */
    private static final float[][] COLORS = {
        {0, 0, 0}, {0.001f, 0.002f, 0.004f}, {0.18f, 0.18f, 0.18f}, {1, 0.5f, 0.25f}, {0.05f, 0.9f, 0.3f},
        {5, 5, 5}, {40, 2, 0.1f}, {0, 0, 1}};

    private float[][] colors = COLORS;

    private static <V> void set(EngineSettings s, TechniqueKind<?, ColorGrading, Technique> kind, String field,
        V value) {
        s.set(kind.<V>setting(field), value);
    }

    /** The graded color of each of {@link #colors} (one per row of pixels) by {@code kind}, set by {@code set}. */
    private float[][] graded(PostFixture gpu, ColorGrading grading, TechniqueKind<?, ColorGrading, Technique> kind,
        Consumer<EngineSettings> set) {
        float[] out = gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            float[] c = colors[Math.min(y, colors.length - 1)];
            rgb[0] = c[0];
            rgb[1] = c[1];
            rgb[2] = c[2];
        }, (graph, port) -> {
            ColorGrading.SLOT.choose(graph.settings(), kind);
            set.accept(graph.settings());
            port.add(grading);
        }, 1);
        float[][] result = new float[colors.length][3];
        for (int i = 0; i < colors.length; i++) {
            for (int c = 0; c < 3; c++) {
                result[i][c] = out[4 * (i * SIZE + 3) + c];
            }
        }
        return result;
    }

    /** The log encoding's grading of one channel: contrast, then lift, gamma and gain, as the shader does. */
    private static double logGrade(double c, double contrast, double lift, double gamma, double gain) {
        double x = (ColorLut.encode(c) - 0.5) * contrast + 0.5;
        double y = gain * (x + lift * (1 - x));
        return ColorLut.decode(Math.signum(y) * Math.pow(Math.abs(y), 1 / gamma));
    }

    @Test
    void theWhiteBalanceIsBradfordsAdaptationFromTheLightsWhite() {
        // Lindbloom's Bradford matrix from D50 to D65 (brucelindbloom.com, "Chromatic Adaptation")
        double[] expected = {0.9555766, -0.0230393, 0.0631636, -0.0282895, 1.0099416, 0.0210077, 0.0122982,
            -0.0204830, 1.3299098};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertThat(ColorGrading.adaptation(0.96422, 0.82521, 0.95047, 1.08883, r, c)).as(r + ", " + c)
                    .isCloseTo(expected[3 * r + c], within(2e-5));
            }
        }
        // CIE daylight at 5003 K is D50 and at 6504 K D65; a black body at 2856 K is illuminant A.
        assertThat(ColorGrading.locusX(5003)).isCloseTo(0.3457, within(2e-4));
        assertThat(ColorGrading.locusY(5003, ColorGrading.locusX(5003))).isCloseTo(0.3585, within(2e-4));
        assertThat(ColorGrading.locusX(6504)).isCloseTo(0.3127, within(1e-4));
        assertThat(ColorGrading.locusY(6504, ColorGrading.locusX(6504))).isCloseTo(0.3290, within(2e-4));
        assertThat(ColorGrading.locusX(2856)).isCloseTo(0.44757, within(1e-3));
        assertThat(ColorGrading.locusY(2856, ColorGrading.locusX(2856))).isCloseTo(0.40745, within(1e-3));
    }

    @Test
    void theDefaultsChangeNothingAndOffLeavesTheScene() {
        try (PostFixture gpu = new PostFixture()) {
            for (TechniqueKind<?, ColorGrading, Technique> kind : KINDS) {
                float[][] out = graded(gpu, new ColorGrading(), kind, s -> { });
                for (int i = 0; i < COLORS.length; i++) {
                    for (int c = 0; c < 3; c++) {
                        assertThat(out[i][c]).as(kind.id() + ": color " + i + ", channel " + c)
                            .isCloseTo(COLORS[i][c], within(COLORS[i][c] * 2e-3f + 1e-6f));
                    }
                }
            }
            float[][] off = graded(gpu, new ColorGrading(), null, s -> set(s, ColorGrading.BAKED, "saturation", 0f));
            assertThat(off[3]).containsExactly(COLORS[3], within(1e-3f));
        }
    }

    @Test
    void contrastLiftGammaGainAndSaturationFollowTheirFormulas() {
        try (PostFixture gpu = new PostFixture()) {
            for (TechniqueKind<?, ColorGrading, Technique> kind : KINDS) {
                float[][] out = graded(gpu, new ColorGrading(), kind, s -> {
                    set(s, kind, "contrast", 1.3f);
                    set(s, kind, "liftRed", 0.02f);
                    set(s, kind, "gammaGreen", 1.2f);
                    set(s, kind, "gainBlue", 1.05f);
                });
                // The baked table's cells are 0.75 stops apart: trilinear between them, within 1.5%; direct, exact
                // to 16-bit floats.
                double tolerance = kind == ColorGrading.BAKED ? 0.015 : 2e-3;
                for (int i = 2; i < 7; i++) {
                    double[] expected = {logGrade(COLORS[i][0], 1.3, 0.02, 1, 1),
                        logGrade(COLORS[i][1], 1.3, 0, 1.2, 1), logGrade(COLORS[i][2], 1.3, 0, 1, 1.05)};
                    for (int c = 0; c < 3; c++) {
                        assertThat((double) out[i][c]).as(kind.id() + ": color " + i + ", channel " + c)
                            .isCloseTo(expected[c], within(expected[c] * tolerance + 1e-4));
                    }
                }
                float[][] gray = graded(gpu, new ColorGrading(), kind, s -> {
                    set(s, kind, "saturation", 0f);
                    if (kind == ColorGrading.BAKED) {
                        set(s, kind, "tableSize", 64); // saturation is linear, the table's cells log
                    }
                });
                double luma = 0.2126 * 1 + 0.7152 * 0.5 + 0.0722 * 0.25;
                assertThat(gray[3]).as(kind.id() + ", saturation 0: luminance").containsExactly(new float[] {
                    (float) luma, (float) luma, (float) luma}, within((float) luma * 0.01f));
            }
        }
    }

    @Test
    void aFinerTableFollowsTheDirectGradeCloser() {
        // A strong grade: the baked table's error against the direct one falls as its cells get finer.
        try (PostFixture gpu = new PostFixture()) {
            Consumer<EngineSettings> strong = s -> {
                for (TechniqueKind<?, ColorGrading, Technique> kind : KINDS) {
                    set(s, kind, "contrast", 2f);
                    set(s, kind, "gammaRed", 2f);
                    set(s, kind, "saturation", 1.8f);
                }
            };
            float[][] direct = graded(gpu, new ColorGrading(), ColorGrading.DIRECT, strong);
            double[] errors = new double[2];
            int[] sizes = {8, 64};
            for (int k = 0; k < 2; k++) {
                int size = sizes[k];
                float[][] baked = graded(gpu, new ColorGrading(), ColorGrading.BAKED, strong.andThen(s ->
                    set(s, ColorGrading.BAKED, "tableSize", size)));
                for (int i = 1; i < COLORS.length; i++) {
                    for (int c = 0; c < 3; c++) {
                        errors[k] = Math.max(errors[k], Math.abs(ColorLut.encode(baked[i][c])
                            - ColorLut.encode(direct[i][c])));
                    }
                }
            }
            assertThat(errors[1]).as("64 cells against 8, in the log encoding " + java.util.Arrays.toString(errors))
                .isLessThan(errors[0] / 10).isLessThan(5e-3);
        }
    }

    @Test
    void theLightsWhiteShowsNeutral() {
        // Light of 3200 K (a black body), as linear Rec. 709: graded for 3200 K it comes out gray.
        double x = ColorGrading.locusX(3200);
        double y = ColorGrading.locusY(3200, x);
        double bx = x / y;
        double bz = (1 - x - y) / y;
        float r = (float) (3.2404542 * bx - 1.5371385 - 0.4985314 * bz);
        float g = (float) (-0.9692660 * bx + 1.8760108 + 0.0415560 * bz);
        float b = (float) (0.0556434 * bx - 0.2040259 + 1.0572252 * bz);
        colors = COLORS.clone();
        colors[4] = new float[] {r * 0.3f, g * 0.3f, b * 0.3f};
        try (PostFixture gpu = new PostFixture()) {
            for (TechniqueKind<?, ColorGrading, Technique> kind : KINDS) {
                float[][] out = graded(gpu, new ColorGrading(), kind, s -> set(s, kind, "temperature", 3200f));
                assertThat(out[4][0]).as(kind.id() + ": warm light, neutral").isCloseTo(out[4][1],
                    within(out[4][1] * 0.01f));
                assertThat(out[4][2]).isCloseTo(out[4][1], within(out[4][1] * 0.01f));
                float[][] tinted = graded(gpu, new ColorGrading(), kind, s -> set(s, kind, "tint", 0.5f));
                assertThat(tinted[2][1]).as(kind.id() + ": greener light, a more magenta image")
                    .isLessThan(tinted[2][0]);
            }
        }
    }

    @Test
    void anAuthoredTableLoadsAsAnAssetAndIsAppliedInTheLogEncoding() {
        String cube = "TITLE \"swap red and blue\"\n# a comment\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\nLUT_3D_SIZE 2\n"
            + "0 0 0\n0 0 1\n0 1 0\n0 1 1\n1 0 0\n1 0 1\n1 1 0\n1 1 1\n";
        ColorLut swap = ColorLut.parseCube(cube);
        assertThat(swap.size()).isEqualTo(2);
        assertThat(ColorLut.parseCube(ColorLut.identity(3).toCube()).values())
            .containsExactly(ColorLut.identity(3).values(), within(1e-6f));
        TextureData texture = swap.texture();
        assertThat(texture.depth()).as("a 3D texture").isEqualTo(2);
        try (PostFixture gpu = new PostFixture(); PostAssetsFixture assets = new PostAssetsFixture(gpu.device)) {
            assets.file("grades/swap.cube", cube).file("grades/identity.cube", ColorLut.identity(17).toCube());
            // Held and loaded before the frames, as a running game's would be by then
            PostTextures.Held held = assets.post.held().follow("test://grades/swap.cube");
            PostTextures.Held identity = assets.post.held().follow("test://grades/identity.cube");
            assets.load(held, identity);
            assertThat(held.slot()).as("on the GPU").isNotNegative();
            for (TechniqueKind<?, ColorGrading, Technique> kind : KINDS) {
                float[][] out = graded(gpu, new ColorGrading(assets.post), kind, s ->
                    set(s, kind, "table", "test://grades/swap.cube"));
                // (baked: through the table's cells, 1.5%, as every grade)
                assertThat(out[3]).as(kind.id()).containsExactly(new float[] {0.25f, 0.5f, 1},
                    within(kind == ColorGrading.BAKED ? 0.015f : 5e-3f));
                float[][] half = graded(gpu, new ColorGrading(assets.post), kind, s -> {
                    set(s, kind, "table", "test://grades/swap.cube");
                    set(s, kind, "tableShare", 0.5f);
                });
                double mixed = ColorLut.decode((ColorLut.encode(1) + ColorLut.encode(0.25)) / 2);
                assertThat((double) half[3][0]).as(kind.id() + ": half way in the log encoding").isCloseTo(mixed,
                    within(mixed * 0.015));
                float[][] same = graded(gpu, new ColorGrading(assets.post), kind, s ->
                    set(s, kind, "table", "test://grades/identity.cube"));
                for (int i = 1; i < COLORS.length; i++) {
                    for (int c = 0; c < 3; c++) {
                        // 17 cells over 24 stops, in half floats: the identity within 1% (baked 1.5%)
                        assertThat(same[i][c]).as(kind.id() + ": the identity table, color " + i).isCloseTo(
                            COLORS[i][c], within(COLORS[i][c] * 0.015f + 1e-5f));
                    }
                }
            }
            held.release();
            identity.release();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> ColorLut.parseCube("LUT_3D_SIZE 2\n0 0 0\n"));
        assertThatIllegalArgumentException().isThrownBy(() -> ColorLut.parseCube("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n"));
    }

    @Test
    void theSlotAndItsTunablesAreSettings() {
        EngineSettings settings = new EngineSettings();
        assertThat(ColorGrading.SLOT.chosen(settings)).isSameAs(ColorGrading.BAKED);
        assertThat(settings.techniques().variants(ColorGrading.SLOT)).containsExactly(ColorGrading.BAKED,
            ColorGrading.DIRECT);
        assertThat(ColorGrading.BAKED.<String>setting("table").name()).isEqualTo("render.grading.baked.table");
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        ColorGrading.register(registry);
        assertThat(registry.all()).contains(ColorGrading.SLOT.setting(), ColorGrading.DIRECT.setting("temperature"));
    }
}
