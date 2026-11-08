package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.common.math.Maths;
import com.truesim.effects.post.layout.ExposurePush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Auto exposure (eye adaptation): the view's exposure from what its scene color shows, measured on the GPU in the frame
 * it is used and adapting over time like an eye. It provides {@link DisplayTransform#EXPOSURE_INPUT}, a buffer whose
 * first float the display transform multiplies scene color by in place of {@code display.exposure}; with its slot
 * {@link #SLOT} ({@code render.exposure}) off that buffer holds {@code display.exposure} (one 4-byte fill). It measures
 * the scene color as the post effects left it (after bloom, grading, lens effects: every modifier of
 * {@link Keys#SCENE_COLOR}), within the render region, and works the same for SDR and HDR output.
 *
 * <ul>
 *   <li>{@link #HISTOGRAM} ({@code histogram}, the default): 256 bins of log2 luminance, averaged between the
 *       {@code low} and {@code high} shares of the pixels, so the darkest and brightest (a black sky, the sun, a lamp)
 *       leave the exposure alone.</li>
 *   <li>{@link #AVERAGE} ({@code average}): the log average of every pixel: simpler, pulled by large dark or bright
 *       areas.</li>
 * </ul>
 *
 * <p>The average is shown at the {@link KeyValue key}: {@code middle_gray} (a fixed {@code keyValue}, 0.18 as Reinhard's),
 * or {@code krawczyk}: Krawczyk, Myszkowski and Seidel's key for the scene's absolute luminance (2005, Eq. 11), so night
 * stays dark and noon bright instead of both becoming middle gray. The absolute luminance is scene color times
 * {@code luminanceScale} (cd/m² per unit): with effects-atmosphere, {@code 1.2 * 2^ev100} of its
 * {@code render.sky.atmosphere.ev100}. The exposure is kept per cd/m², so changing that scale (and the atmosphere's
 * EV100 with it, a pre-exposure keeping 16-bit scene color in range from noon to night) leaves the image as it was,
 * even mid-adaptation. Then {@code compensation} stops, limits ({@code minEv} to {@code maxEv}, log2 of the exposure
 * per cd/m²: {@code -EV100 - log2 1.2}), and the exposure moves there exponentially in stops at {@code speedUp} towards
 * brighter images and {@code speedDown} towards darker ones, per second of the clock it is given.
 *
 * <pre>{@code
 * port.add(new AutoExposure());                                       // a clock of real seconds; or new AutoExposure(gameSeconds)
 * AutoExposure.SLOT.choose(graph.settings(), AutoExposure.HISTOGRAM);  // render.exposure=histogram
 * graph.settings().set(AutoExposure.HISTOGRAM.setting("compensation"), 0.5f);
 * }</pre>
 */
public final class AutoExposure implements Technique {

    /** A view's auto exposure. */
    public static final Id<AutoExposure> ID = Id.of("truesim.autoExposure", AutoExposure.class);

    private static final int MODE_HISTOGRAM = 0;
    private static final int MODE_AVERAGE = 1;

    /** The auto exposure technique, a kind's id, or {@code off} ({@code display.exposure}). */
    public static final TechniqueSlot<AutoExposure, Technique> SLOT = TechniqueSlot.of("render.exposure",
        "histogram", true, "auto exposure: off (display.exposure), histogram (percentiles) or average (log average)",
        techniques -> techniques.register(AutoExposure.SLOT, AutoExposure.HISTOGRAM)
            .register(AutoExposure.SLOT, AutoExposure.AVERAGE));

    /** What the measured average is shown as. */
    public enum KeyValue {
        /** A fixed key: {@code keyValue}, 0.18 by default (middle gray). */
        MIDDLE_GRAY,
        /** Krawczyk et al. 2005's key for the scene's absolute luminance: night stays dark, noon bright. */
        KRAWCZYK
    }

    /**
     * The histogram's tunables, read as frames record.
     *
     * @param compensation stops added to what the measure asks: brighter above 0
     * @param minEv the least exposure, log2 per cd/m² (per scene unit with a luminance scale of 1)
     * @param maxEv the most
     * @param speedUp how fast the exposure rises (adapting to the dark), per second: 1 - e^(-speed seconds) of the way
     * @param speedDown how fast it falls (adapting to brightness)
     * @param low the share of the darkest pixels left out
     * @param high the share of pixels counted, from the darkest: above it the brightest are left out
     * @param minLog the measure's darkest luminance, log2 of scene color: darker counts as black
     * @param maxLog its brightest: brighter counts as it
     * @param key what the average is shown as
     * @param keyValue the fixed key
     * @param luminanceScale cd/m² per unit of scene color, for Krawczyk's key
     */
    public record Histogram(
        @Tunable(value = "auto exposure's compensation, in stops", min = -10, max = 10) float compensation,
        @Tunable(value = "the least auto exposure, log2 per cd/m² (per scene unit at scale 1)", min = -40, max = 40)
        float minEv,
        @Tunable(value = "the most auto exposure, log2 per cd/m² (per scene unit at scale 1)", min = -40, max = 40)
        float maxEv,
        @Tunable(value = "how fast auto exposure brightens the image, per second", min = 0.01, max = 100) float speedUp,
        @Tunable(value = "how fast auto exposure darkens the image, per second", min = 0.01, max = 100) float speedDown,
        @Tunable(value = "the share of the darkest pixels auto exposure leaves out", min = 0, max = 1) float low,
        @Tunable(value = "the share of pixels auto exposure counts, from the darkest", min = 0, max = 1) float high,
        @Tunable(value = "the darkest luminance auto exposure tells apart, log2 of scene color", min = -40, max = 40)
        float minLog,
        @Tunable(value = "the brightest luminance auto exposure tells apart, log2 of scene color", min = -40, max = 40)
        float maxLog,
        @Tunable("what the average is shown as: middle_gray (keyValue) or krawczyk (by absolute luminance)")
        KeyValue key,
        @Tunable(value = "the fixed key the average is shown as", min = 0.001, max = 1) float keyValue,
        @Tunable(value = "cd/m² per unit of scene color (Krawczyk's key)", min = 1e-9, max = 1e12) float luminanceScale)
        implements TechniqueConfig {
    }

    /**
     * The log average's tunables, read as frames record: {@link Histogram}'s but the shares.
     *
     * @param compensation stops added to what the measure asks
     * @param minEv the least exposure, log2 per cd/m²
     * @param maxEv the most
     * @param speedUp how fast the exposure rises, per second
     * @param speedDown how fast it falls
     * @param minLog the measure's darkest luminance, log2 of scene color
     * @param maxLog its brightest
     * @param key what the average is shown as
     * @param keyValue the fixed key
     * @param luminanceScale cd/m² per unit of scene color
     */
    public record Average(
        @Tunable(value = "auto exposure's compensation, in stops", min = -10, max = 10) float compensation,
        @Tunable(value = "the least auto exposure, log2 per cd/m² (per scene unit at scale 1)", min = -40, max = 40)
        float minEv,
        @Tunable(value = "the most auto exposure, log2 per cd/m² (per scene unit at scale 1)", min = -40, max = 40)
        float maxEv,
        @Tunable(value = "how fast auto exposure brightens the image, per second", min = 0.01, max = 100) float speedUp,
        @Tunable(value = "how fast auto exposure darkens the image, per second", min = 0.01, max = 100) float speedDown,
        @Tunable(value = "the darkest luminance auto exposure tells apart, log2 of scene color", min = -40, max = 40)
        float minLog,
        @Tunable(value = "the brightest luminance auto exposure tells apart, log2 of scene color", min = -40, max = 40)
        float maxLog,
        @Tunable("what the average is shown as: middle_gray (keyValue) or krawczyk (by absolute luminance)")
        KeyValue key,
        @Tunable(value = "the fixed key the average is shown as", min = 0.001, max = 1) float keyValue,
        @Tunable(value = "cd/m² per unit of scene color (Krawczyk's key)", min = 1e-9, max = 1e12) float luminanceScale)
        implements TechniqueConfig {
    }

    /** The histogram with percentiles, the default. */
    public static final TechniqueKind<Histogram, AutoExposure, Technique> HISTOGRAM = TechniqueKind.of(SLOT,
        "histogram", new Histogram(0, -16, 16, 3, 1.5f, 0.5f, 0.95f, -16, 16, KeyValue.MIDDLE_GRAY, 0.18f, 1),
        "a histogram of log luminance, averaged between percentiles: the darkest and brightest pixels left out",
        AutoExposure::histogram);
    /** The log average of every pixel. */
    public static final TechniqueKind<Average, AutoExposure, Technique> AVERAGE = TechniqueKind.of(SLOT, "average",
        new Average(0, -16, 16, 3, 1.5f, -16, 16, KeyValue.MIDDLE_GRAY, 0.18f, 1),
        "the log average of every pixel's luminance",
        AutoExposure::average);

    private static final String SHADER = "shaders/post/exposure.spv";

    private final DoubleSupplier clock;
    private final Live live = new Live();
    private double last = Double.NaN; // the clock at the last frame measured

    /** Auto exposure adapting over real seconds ({@link System#nanoTime}). */
    public AutoExposure() {
        this(new DoubleSupplier() {
            private final long started = System.nanoTime();

            @Override
            public double getAsDouble() {
                return (System.nanoTime() - started) * 1e-9;
            }
        });
    }

    /**
     * Auto exposure adapting over the seconds of {@code clock}, read as frames record: game time (a paused game's eye
     * holds), or a test's steps. It must not allocate.
     */
    public AutoExposure(DoubleSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers its settings in {@code registry} for discovery: the slot, its kinds' tunables with it, hidden.
     */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT);
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(DisplayTransform.EXPOSURE_INPUT);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Buffer state = g.persistentBuffer("auto exposure", 16);
        TechniqueKind<?, AutoExposure, Technique> kind = g.technique(SLOT);
        if (kind == null) {
            EngineSettings settings = g.settings();
            g.pass("exposure, fixed").write(state, BufferAccess.TRANSFER_DST).record(ctx ->
                ctx.fill(state, 0, 4, Float.floatToRawIntBits(settings.get(DisplayTransform.EXPOSURE))));
            g.provide(DisplayTransform.EXPOSURE_INPUT, state);
            return;
        }
        Measured measured = (Measured) make(kind, g.settings());
        measured.build(g, state);
        g.provide(DisplayTransform.EXPOSURE_INPUT, state);
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, AutoExposure, Technique> kind,
        EngineSettings settings) {
        return kind.create(this, () -> kind.config(settings));
    }

    @AllowAllocation("building the graph, when it changes")
    private static Technique histogram(AutoExposure exposure, java.util.function.Supplier<Histogram> config) {
        return exposure.new Measured(MODE_HISTOGRAM, () -> {
            Histogram c = config.get();
            return exposure.live.set(c.compensation(), c.minEv(), c.maxEv(), c.speedUp(), c.speedDown(), c.low(),
                c.high(), c.minLog(), c.maxLog(), c.key(), c.keyValue(), c.luminanceScale());
        });
    }

    @AllowAllocation("building the graph, when it changes")
    private static Technique average(AutoExposure exposure, java.util.function.Supplier<Average> config) {
        return exposure.new Measured(MODE_AVERAGE, () -> {
            Average c = config.get();
            return exposure.live.set(c.compensation(), c.minEv(), c.maxEv(), c.speedUp(), c.speedDown(), 0, 1,
                c.minLog(), c.maxLog(), c.key(), c.keyValue(), c.luminanceScale());
        });
    }

    /** The seconds since the frame before, by the clock (0 on the first). Allocates nothing. */
    private float step() {
        double now = clock.getAsDouble();
        float seconds = Double.isNaN(last) ? 0 : (float) Math.max(0, now - last);
        last = now;
        return seconds;
    }

    /** A kind's tunables as one shape, for the shader. */
    private static final class Live {
        float compensation;
        float minEv;
        float maxEv;
        float up;
        float down;
        float low;
        float high;
        float minLog;
        float maxLog;
        KeyValue key;
        float keyValue;
        float scale;

        Live set(float compensation, float minEv, float maxEv, float up, float down, float low, float high,
            float minLog, float maxLog, KeyValue key, float keyValue, float scale) {
            this.compensation = compensation;
            this.minEv = minEv;
            this.maxEv = Math.max(minEv, maxEv);
            this.up = up;
            this.down = down;
            this.low = low;
            this.high = Math.max(low, high);
            this.minLog = minLog;
            this.maxLog = Math.max(minLog + 1, maxLog);
            this.key = key;
            this.keyValue = keyValue;
            this.scale = scale;
            return this;
        }
    }

    /** The measure of {@code mode} and the adaptation, into the state buffer. */
    private final class Measured implements Technique {
        private final int mode;
        private final java.util.function.Supplier<Live> config;

        Measured(int mode, java.util.function.Supplier<Live> config) {
            this.mode = mode;
            this.config = config;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public void build(GraphBuilder g) {
            throw new UnsupportedOperationException("built by AutoExposure");
        }

        @AllowAllocation("building the graph, when it changes")
        void build(GraphBuilder g, Buffer state) {
            Image scene = g.require(Keys.SCENE_COLOR);
            int groups = ((scene.width() + 15) / 16) * ((scene.height() + 15) / 16);
            Buffer measure = g.createBuffer("exposure measure", mode == MODE_HISTOGRAM ? 256 * 4L : groups * 8L);
            Pipeline measuring = g.computeProgram(SHADER, "measureMain", ExposurePush.BYTES);
            Pipeline adapting = g.computeProgram(SHADER, "adaptMain", ExposurePush.BYTES);
            ExposurePush measurePush = new ExposurePush().mode(mode);
            ExposurePush adaptPush = new ExposurePush().mode(mode);
            if (mode == MODE_HISTOGRAM) {
                g.pass("exposure clear").write(measure, BufferAccess.TRANSFER_DST).record(ctx -> ctx.fill(measure, 0));
            }
            g.pass("exposure measure").read(scene, Access.SAMPLED_COMPUTE).write(measure, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    Live c = config.get();
                    int width = ctx.regionWidth(scene);
                    int height = ctx.regionHeight(scene);
                    measurePush.source(ctx.sampled(scene)).size(width, height).bins(ctx.address(measure))
                        .minLog(c.minLog).maxLog(c.maxLog);
                    ctx.dispatch(measuring, measurePush, (width + 15) / 16, (height + 15) / 16, 1);
                });
            g.pass("exposure adapt").read(measure, BufferAccess.STORAGE_READ).write(state, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    Live c = config.get();
                    int width = ctx.regionWidth(scene);
                    int height = ctx.regionHeight(scene);
                    adaptPush.size(width, height).bins(ctx.address(measure)).state(ctx.address(state))
                        .groups(((width + 15) / 16) * ((height + 15) / 16)).minLog(c.minLog).maxLog(c.maxLog)
                        .low(c.low).high(c.high).compensation(c.compensation).minEv(c.minEv).maxEv(c.maxEv)
                        .up(c.up).down(c.down).seconds(step()).key(c.key.ordinal()).keyValue(c.keyValue)
                        .scale(c.scale);
                    ctx.dispatch(adapting, adaptPush, 1, 1, 1);
                });
        }
    }

    /** Krawczyk et al. 2005's key value for a log average of {@code luminance} cd/m², as the shader computes it. */
    public static double krawczyk(double luminance) {
        return 1.03 - 2 / (2 + Maths.log10(Math.max(luminance, 0) + 1));
    }
}
