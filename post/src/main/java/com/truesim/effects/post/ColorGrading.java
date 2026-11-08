package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.post.layout.GradePush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.function.Supplier;

/**
 * Color grading in scene light, before tone mapping, so it works for HDR output as for SDR: white balance, contrast,
 * lift, gamma and gain per channel, saturation and an authored lookup table ({@link ColorLut}, a {@code .cube} asset
 * named by path), applied in that order. It modifies {@link Keys#SCENE_COLOR} in place at {@link Anchor#POST}, after
 * {@link Bloom} and {@link LensEffects}, as the kind chosen for {@link #SLOT} ({@code render.grading}) does it:
 *
 * <ul>
 *   <li>{@link #BAKED} ({@code baked}, the default): every operation baked each frame into a table of
 *       {@code tableSize}^3 cells over a log encoding of scene light ({@link ColorLut#encode}: 24 stops around middle
 *       gray), each pixel looked up through it (trilinear), so the cost per pixel does not depend on what is graded
 *       (Unreal's way).</li>
 *   <li>{@link #DIRECT} ({@code direct}): every operation on every pixel: exact, the reference the table
 *       approximates.</li>
 *   <li>{@code off}: the scene color as it is (no passes).</li>
 * </ul>
 *
 * <ul>
 *   <li>White balance: {@code temperature} and {@code tint} are the white of the scene's light, adapted to D65
 *       (Bradford): 3200 K light shows neutral, a scene lit at 6504 K bluer. CIE daylight from 4000 K, a black body
 *       below; tint moves the white off that locus by Duv = 0.02 tint (positive: greener light, a more magenta
 *       image).</li>
 *   <li>Contrast around middle gray and lift, gamma, gain in the log encoding x: {@code y = gain (x + lift (1 - x))},
 *       then {@code y^(1 / gamma)}.</li>
 *   <li>Saturation around Rec. 709 luminance; the authored table ({@code table}, an asset path through
 *       {@link PostTextures}; empty: none) mixed in by {@code tableShare}, in the log encoding.</li>
 * </ul>
 *
 * <p>The tunables are read as frames record; {@code tableSize} when the graph builds.
 *
 * <pre>{@code
 * port.add(new ColorGrading(textures));                                    // or new ColorGrading(): no tables
 * graph.settings().set(ColorGrading.BAKED.setting("temperature"), 5000f)
 *     .set(ColorGrading.BAKED.setting("table"), "game://grades/dusk.cube");
 * }</pre>
 */
public final class ColorGrading implements Technique {

    /** A view's color grading. */
    public static final Id<ColorGrading> ID = Id.of("truesim.colorGrading", ColorGrading.class);

    /** The grading technique, a kind's id, or {@code off}. */
    public static final TechniqueSlot<ColorGrading, Technique> SLOT = TechniqueSlot.of("render.grading", "baked", true,
        "color grading: off, baked (a table baked each frame, the cost fixed) or direct (each pixel, exact)",
        techniques -> techniques.register(ColorGrading.SLOT, ColorGrading.BAKED)
            .register(ColorGrading.SLOT, ColorGrading.DIRECT));

    /**
     * The baked table's tunables.
     *
     * @param temperature the white of the scene's light, kelvin: 6504 (D65) changes nothing
     * @param tint its tint off the locus: positive, greener light (a more magenta image)
     * @param contrast contrast around middle gray, in the log encoding
     * @param saturation saturation around luminance: 0 gray, 1 unchanged
     * @param liftRed lift of red
     * @param liftGreen lift of green
     * @param liftBlue lift of blue
     * @param gammaRed gamma of red
     * @param gammaGreen gamma of green
     * @param gammaBlue gamma of blue
     * @param gainRed gain of red
     * @param gainGreen gain of green
     * @param gainBlue gain of blue
     * @param table the authored table's asset path ({@code .cube}), or empty
     * @param tableShare how much of the authored table is mixed in
     * @param tableSize the baked table's cells per axis: finer follows sharp grades better, at more bake work (a
     *     rebuild)
     */
    public record Baked(
        @Tunable(value = "the white of the scene's light in kelvin (6504: neutral; lower makes the image bluer)",
            min = 1667, max = 25000) float temperature,
        @Tunable(value = "the tint of the scene's light (positive: greener light, a more magenta image)", min = -1,
            max = 1) float tint,
        @Tunable(value = "contrast around middle gray", min = 0, max = 4) float contrast,
        @Tunable(value = "saturation: 0 gray, 1 unchanged", min = 0, max = 4) float saturation,
        @Tunable(value = "lift of red", min = -1, max = 1) float liftRed,
        @Tunable(value = "lift of green", min = -1, max = 1) float liftGreen,
        @Tunable(value = "lift of blue", min = -1, max = 1) float liftBlue,
        @Tunable(value = "gamma of red", min = 0.1, max = 10) float gammaRed,
        @Tunable(value = "gamma of green", min = 0.1, max = 10) float gammaGreen,
        @Tunable(value = "gamma of blue", min = 0.1, max = 10) float gammaBlue,
        @Tunable(value = "gain of red", min = 0, max = 4) float gainRed,
        @Tunable(value = "gain of green", min = 0, max = 4) float gainGreen,
        @Tunable(value = "gain of blue", min = 0, max = 4) float gainBlue,
        @Tunable("the authored grading table's asset path (.cube), or empty") String table,
        @Tunable(value = "how much of the authored grading table is mixed in", min = 0, max = 1) float tableShare,
        @Tunable(value = "the baked color grading table's cells per axis", min = 8, max = 64) int tableSize)
        implements TechniqueConfig {
    }

    /**
     * The direct grading's tunables: {@link Baked}'s without the baked table's size.
     *
     * @param temperature the white of the scene's light, kelvin
     * @param tint its tint off the locus
     * @param contrast contrast around middle gray
     * @param saturation saturation around luminance
     * @param liftRed lift of red
     * @param liftGreen lift of green
     * @param liftBlue lift of blue
     * @param gammaRed gamma of red
     * @param gammaGreen gamma of green
     * @param gammaBlue gamma of blue
     * @param gainRed gain of red
     * @param gainGreen gain of green
     * @param gainBlue gain of blue
     * @param table the authored table's asset path, or empty
     * @param tableShare how much of it is mixed in
     */
    public record Direct(
        @Tunable(value = "the white of the scene's light in kelvin (6504: neutral; lower makes the image bluer)",
            min = 1667, max = 25000) float temperature,
        @Tunable(value = "the tint of the scene's light (positive: greener light, a more magenta image)", min = -1,
            max = 1) float tint,
        @Tunable(value = "contrast around middle gray", min = 0, max = 4) float contrast,
        @Tunable(value = "saturation: 0 gray, 1 unchanged", min = 0, max = 4) float saturation,
        @Tunable(value = "lift of red", min = -1, max = 1) float liftRed,
        @Tunable(value = "lift of green", min = -1, max = 1) float liftGreen,
        @Tunable(value = "lift of blue", min = -1, max = 1) float liftBlue,
        @Tunable(value = "gamma of red", min = 0.1, max = 10) float gammaRed,
        @Tunable(value = "gamma of green", min = 0.1, max = 10) float gammaGreen,
        @Tunable(value = "gamma of blue", min = 0.1, max = 10) float gammaBlue,
        @Tunable(value = "gain of red", min = 0, max = 4) float gainRed,
        @Tunable(value = "gain of green", min = 0, max = 4) float gainGreen,
        @Tunable(value = "gain of blue", min = 0, max = 4) float gainBlue,
        @Tunable("the authored grading table's asset path (.cube), or empty") String table,
        @Tunable(value = "how much of the authored grading table is mixed in", min = 0, max = 1) float tableShare)
        implements TechniqueConfig {
    }

    /** The table baked each frame, the default. */
    public static final TechniqueKind<Baked, ColorGrading, Technique> BAKED = TechniqueKind.of(SLOT, "baked",
        new Baked(6504, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, "", 1, 32),
        "every grading operation baked each frame into a table over a log encoding: a fixed cost per pixel",
        ColorGrading::baked);
    /** Every operation on every pixel: exact. */
    public static final TechniqueKind<Direct, ColorGrading, Technique> DIRECT = TechniqueKind.of(SLOT, "direct",
        new Direct(6504, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, "", 1),
        "every grading operation on every pixel: exact, the reference",
        ColorGrading::direct);

    private static final int NONE = -1;
    private static final String SHADER = "shaders/post/grading.spv";
    /** Bradford's cone response matrix (Lam 1985). */
    private static final double[] BRADFORD = {
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296};
    private static final double[] BRADFORD_INVERSE = invert(BRADFORD);
    /** Linear Rec. 709 to XYZ (D65), IEC 61966-2-1. */
    private static final double[] RGB_TO_XYZ = {
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041};
    private static final double[] XYZ_TO_RGB = invert(RGB_TO_XYZ);
    /** The neutral white: daylight at 6504 K, D65 (within 1e-4 of its tabulated chromaticity). */
    private static final double NEUTRAL_X = locusX(6504);
    private static final double NEUTRAL_Y = locusY(6504, NEUTRAL_X);

    private final PostTextures.Held table; // or null: no tables
    private final Grade grade = new Grade();

    /** Grading by the settings, without authored tables (their paths are ignored). */
    public ColorGrading() {
        table = null;
    }

    /** Grading by the settings, its authored tables loaded through {@code textures}. */
    public ColorGrading(PostTextures textures) {
        table = textures.held();
    }

    /** Registers its settings in {@code registry} for discovery: the slot, its kinds' tunables with it, hidden. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT);
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
        return Order.at(Anchor.POST).after(Bloom.ID, LensEffects.ID);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, ColorGrading, Technique> kind = g.technique(SLOT);
        if (kind == null) {
            if (table != null) {
                table.release();
            }
            return;
        }
        make(kind, g.settings()).build(g);
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, ColorGrading, Technique> kind,
        EngineSettings settings) {
        return kind.create(this, () -> kind.config(settings));
    }

    @AllowAllocation("building the graph, when it changes")
    private static Technique baked(ColorGrading grading, Supplier<Baked> config) {
        return grading.new Graded(true, () -> {
            Baked c = config.get();
            return grading.grade.set(c.temperature(), c.tint(), c.contrast(), c.saturation(), c.liftRed(),
                c.liftGreen(), c.liftBlue(), c.gammaRed(), c.gammaGreen(), c.gammaBlue(), c.gainRed(), c.gainGreen(),
                c.gainBlue(), c.table(), c.tableShare());
        });
    }

    @AllowAllocation("building the graph, when it changes")
    private static Technique direct(ColorGrading grading, Supplier<Direct> config) {
        return grading.new Graded(false, () -> {
            Direct c = config.get();
            return grading.grade.set(c.temperature(), c.tint(), c.contrast(), c.saturation(), c.liftRed(),
                c.liftGreen(), c.liftBlue(), c.gammaRed(), c.gammaGreen(), c.gammaBlue(), c.gainRed(), c.gainGreen(),
                c.gainBlue(), c.table(), c.tableShare());
        });
    }

    /** The grading as the chosen kind's tunables have it now, in one shape. */
    private static final class Grade {
        float temperature;
        float tint;
        float contrast;
        float saturation;
        final float[] lift = new float[3];
        final float[] gamma = new float[3];
        final float[] gain = new float[3];
        String table;
        float tableShare;

        Grade set(float temperature, float tint, float contrast, float saturation, float liftRed, float liftGreen,
            float liftBlue, float gammaRed, float gammaGreen, float gammaBlue, float gainRed, float gainGreen,
            float gainBlue, String table, float tableShare) {
            this.temperature = temperature;
            this.tint = tint;
            this.contrast = contrast;
            this.saturation = saturation;
            lift[0] = liftRed;
            lift[1] = liftGreen;
            lift[2] = liftBlue;
            gamma[0] = gammaRed;
            gamma[1] = gammaGreen;
            gamma[2] = gammaBlue;
            gain[0] = gainRed;
            gain[1] = gainGreen;
            gain[2] = gainBlue;
            this.table = table;
            this.tableShare = tableShare;
            return this;
        }
    }

    /** The grading, baked into a table ({@code baked}) or on each pixel. */
    private final class Graded implements Technique {
        private final boolean baked;
        private final Supplier<Grade> config;

        Graded(boolean baked, Supplier<Grade> config) {
            this.baked = baked;
            this.config = config;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image scene = g.modify(Keys.SCENE_COLOR);
            Pipeline apply = g.computeProgram(SHADER, "applyMain", GradePush.BYTES);
            GradePush applyPush = new GradePush();
            if (!baked) {
                g.pass("color grading").write(scene, Access.STORAGE_WRITE).record(ctx -> {
                    int width = ctx.regionWidth(scene);
                    int height = ctx.regionHeight(scene);
                    parameters(applyPush.size(0), config.get());
                    applyPush.source(ctx.storage(scene)).region(width, height);
                    ctx.dispatch(apply, applyPush, (width + 7) / 8, (height + 7) / 8, 1);
                });
                return;
            }
            int size = g.setting(BAKED.<Integer>setting("tableSize"));
            Image tableImage = g.create("color grading table", ImageDesc.of(Format.R32G32B32A32_SFLOAT, size * size,
                size));
            Pipeline bake = g.computeProgram(SHADER, "bakeMain", GradePush.BYTES);
            GradePush bakePush = new GradePush().size(size);
            applyPush.size(size);
            g.pass("color grading bake").write(tableImage, Access.STORAGE_WRITE).record(ctx -> {
                parameters(bakePush.table(ctx.storage(tableImage)), config.get());
                ctx.dispatch(bake, bakePush, (size + 3) / 4, (size + 3) / 4, (size + 3) / 4);
            });
            g.pass("color grading").read(tableImage, Access.SAMPLED_COMPUTE).write(scene, Access.STORAGE_WRITE)
                .record(ctx -> {
                    int width = ctx.regionWidth(scene);
                    int height = ctx.regionHeight(scene);
                    parameters(applyPush, config.get());
                    applyPush.table(ctx.sampled(tableImage)).source(ctx.storage(scene)).region(width, height);
                    ctx.dispatch(apply, applyPush, (width + 7) / 8, (height + 7) / 8, 1);
                });
        }

        /** The grading's parameters as the tunables have them now, the authored table's slot as it is loaded. */
        private void parameters(GradePush push, Grade c) {
            int authored = table == null ? NONE : table.follow(c.table).slot();
            push.authored(authored).lift(c.lift[0], c.lift[1], c.lift[2], c.contrast)
                .gamma(c.gamma[0], c.gamma[1], c.gamma[2], c.saturation)
                .gain(c.gain[0], c.gain[1], c.gain[2], c.tableShare);
            balance(push, c.temperature, c.tint);
        }
    }

    /** The white balance matrix for light of {@code temperature} kelvin and {@code tint}, into {@code push}. */
    private static void balance(GradePush push, double temperature, double tintSetting) {
        // The white of the light, as xy, then XYZ with Y = 1
        double x = locusX(temperature);
        double y = locusY(temperature, x);
        double tint = tintSetting * 0.02;
        if (tint != 0) {
            // Off the locus along its normal in CIE 1960 uv, by Duv = tint (positive: towards green, +v)
            double x2 = locusX(temperature + 10);
            double y2 = locusY(temperature + 10, x2);
            double d = -2 * x + 12 * y + 3;
            double u = 4 * x / d;
            double v = 6 * y / d;
            double d2 = -2 * x2 + 12 * y2 + 3;
            double du = 4 * x2 / d2 - u;
            double dv = 6 * y2 / d2 - v;
            double length = Math.sqrt(du * du + dv * dv);
            double nu = -dv / length;
            double nv = du / length;
            if (nv < 0) {
                nu = -nu;
                nv = -nv;
            }
            u += nu * tint;
            v += nv * tint;
            double denominator = 2 * u - 8 * v + 4;
            x = 3 * u / denominator;
            y = 2 * v / denominator;
        }
        // The balance in linear Rec. 709: RGB -> XYZ -> adapted -> RGB
        double sx = x / y;
        double sz = (1 - x - y) / y;
        double dx = NEUTRAL_X / NEUTRAL_Y;
        double dz = (1 - NEUTRAL_X - NEUTRAL_Y) / NEUTRAL_Y;
        for (int r = 0; r < 3; r++) {
            double c0 = 0;
            double c1 = 0;
            double c2 = 0;
            for (int k = 0; k < 3; k++) {
                // (XYZ_TO_RGB * A)[r][k], A = Bradford adaptation from (sx, 1, sz) to (dx, 1, dz)
                double a0 = adaptation(sx, sz, dx, dz, k, 0);
                double a1 = adaptation(sx, sz, dx, dz, k, 1);
                double a2 = adaptation(sx, sz, dx, dz, k, 2);
                double toRgb = XYZ_TO_RGB[3 * r + k];
                c0 += toRgb * (a0 * RGB_TO_XYZ[0] + a1 * RGB_TO_XYZ[3] + a2 * RGB_TO_XYZ[6]);
                c1 += toRgb * (a0 * RGB_TO_XYZ[1] + a1 * RGB_TO_XYZ[4] + a2 * RGB_TO_XYZ[7]);
                c2 += toRgb * (a0 * RGB_TO_XYZ[2] + a1 * RGB_TO_XYZ[5] + a2 * RGB_TO_XYZ[8]);
            }
            float f0 = (float) c0;
            float f1 = (float) c1;
            float f2 = (float) c2;
            switch (r) {
                case 0 -> push.balance0(f0, f1, f2, 0);
                case 1 -> push.balance1(f0, f1, f2, 0);
                default -> push.balance2(f0, f1, f2, 0);
            }
        }
    }

    /**
     * Element ({@code r}, {@code c}) of the Bradford adaptation in XYZ from white ({@code sx}, 1, {@code sz}) to
     * ({@code dx}, 1, {@code dz}): {@code inverse(B) diag(B d / B s) B}.
     */
    static double adaptation(double sx, double sz, double dx, double dz, int r, int c) {
        double sum = 0;
        for (int k = 0; k < 3; k++) {
            double source = BRADFORD[3 * k] * sx + BRADFORD[3 * k + 1] + BRADFORD[3 * k + 2] * sz;
            double target = BRADFORD[3 * k] * dx + BRADFORD[3 * k + 1] + BRADFORD[3 * k + 2] * dz;
            sum += BRADFORD_INVERSE[3 * r + k] * (target / source) * BRADFORD[3 * k + c];
        }
        return sum;
    }

    /**
     * The chromaticity x of light at {@code kelvin}: CIE daylight (CIE 15:2004) from 4000 K, a black body (Kim et al.'s
     * cubic fit of the Planckian locus) below.
     */
    static double locusX(double kelvin) {
        double t = kelvin;
        if (t >= 4000) {
            return t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
        }
        return -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
    }

    /** The chromaticity y of light at {@code kelvin}, whose x is {@code x}. */
    static double locusY(double kelvin, double x) {
        if (kelvin >= 4000) {
            return -3 * x * x + 2.870 * x - 0.275;
        }
        return kelvin <= 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    }

    private static double[] invert(double[] m) {
        double a = m[0];
        double b = m[1];
        double c = m[2];
        double d = m[3];
        double e = m[4];
        double f = m[5];
        double g = m[6];
        double h = m[7];
        double i = m[8];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        return new double[] {
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
    }
}
