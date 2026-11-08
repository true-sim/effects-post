package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.post.layout.BloomPush;
import com.truesim.effects.post.layout.FftPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Ratio;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bloom: the glare of bright light in a lens and an eye, in scene light before tone mapping. It modifies
 * {@link Keys#SCENE_COLOR} in place at {@link Anchor#POST}, as the kind chosen for {@link #SLOT} ({@code render.bloom})
 * does it: {@code (1 - intensity) scene + intensity bloom}. Every filter's weights sum to one, so bloom spreads light
 * without adding or losing any (energy-conserving), and with no threshold (the default) all light blooms, as in a lens.
 * It works within the view's render region (dynamic resolution).
 *
 * <ul>
 *   <li>{@link #PHYSICAL} ({@code physical}, the default): Jimenez's chain (Call of Duty: Advanced Warfare, 2014), a
 *       13-tap downsample with Karis' average on the first level and 3 x 3 tent upsamples added level by level.</li>
 *   <li>{@link #DUAL_KAWASE} ({@code dual_kawase}): Bjorge's dual Kawase filter (2015), 5 taps down, 8 up; the
 *       cheapest, one wide blur with less of a long tail.</li>
 *   <li>{@link #GAUSSIAN} ({@code gaussian}): a separable Gaussian at each level, summed: the reference the chains
 *       approximate, at more taps.</li>
 *   <li>{@link #FFT} ({@code fft}): convolution with a lens' point spread function through 2D FFTs (as Unreal's
 *       convolution bloom), the kernel procedural (an aperture's diffraction spikes, a corona, chromatic spread),
 *       optionally mixed with the physical chain's wide glow.</li>
 *   <li>{@code off}: the scene color as it is (a rebuild: no passes at all).</li>
 * </ul>
 *
 * <p>Changing the kind, the levels or the convolution's size and kernel rebuilds the graph; the other tunables
 * (intensity, radius, threshold, knee, Karis' average, the chain's share) are read as frames record.
 *
 * <pre>{@code
 * port.add(new Bloom());                                         // after the scene's shading, before tone mapping
 * Bloom.SLOT.choose(graph.settings(), Bloom.DUAL_KAWASE);        // render.bloom=dual_kawase
 * graph.settings().set(Bloom.DUAL_KAWASE.setting("intensity"), 0.08f);
 * }</pre>
 */
public final class Bloom implements Technique {

    /** bloom.slang's techniques. */
    private static final int MODE_PHYSICAL = 0;
    private static final int MODE_DUAL_KAWASE = 1;
    private static final int MODE_GAUSSIAN = 2;
    private static final int MODE_FFT = 3;

    /** A view's bloom. */
    public static final Id<Bloom> ID = Id.of("truesim.bloom", Bloom.class);

    /** The bloom technique, a kind's id, or {@code off}. */
    public static final TechniqueSlot<Bloom, Technique> SLOT = TechniqueSlot.of("render.bloom", "physical", true,
        "bloom: off, physical (Call of Duty's 13-tap chain), dual_kawase (cheapest), gaussian (reference) or fft "
            + "(convolution with a lens' spikes and corona)",
        techniques -> techniques.register(Bloom.SLOT, Bloom.PHYSICAL).register(Bloom.SLOT, Bloom.DUAL_KAWASE)
            .register(Bloom.SLOT, Bloom.GAUSSIAN).register(Bloom.SLOT, Bloom.FFT));

    /**
     * A chain's tunables: {@code levels} is read when the graph builds (it rebuilds when it changes), the others as
     * frames record.
     *
     * @param intensity the share of bloom in the result: 0 none, 1 only bloom
     * @param levels the levels of the chain, each half the size of the one before: how far bloom reaches
     * @param radius the filters' spread, in texels of each level: 1 as published, more for a wider, softer glow
     * @param threshold scene color brightness where bloom starts, with a soft knee; 0: all light blooms
     * @param knee the soft knee's width, a fraction of the threshold
     * @param antiFlicker Karis' average on the first downsample: single very bright pixels do not flicker as blobs
     */
    public record Chain(
        @Tunable(value = "the share of bloom mixed into the scene's light", min = 0, max = 1) float intensity,
        @Tunable(value = "how many half-size levels bloom spreads over: how far it reaches", min = 1, max = 16)
        int levels,
        @Tunable(value = "the spread of bloom's filters, in texels of each level (1: as published)", min = 0.25,
            max = 4) float radius,
        @Tunable(value = "the brightness where bloom starts (0: all light blooms, as in a lens)", min = 0, max = 1000)
        float threshold,
        @Tunable(value = "the soft knee of bloom's threshold, as a fraction of it", min = 0, max = 1) float knee,
        @Tunable("Karis' average on bloom's first level, against flickering fireflies") boolean antiFlicker)
        implements TechniqueConfig {
    }

    /**
     * The convolution's tunables: those of the kernel, its size and the chain's levels and share are read when the
     * graph builds (the kernel's spectrum is made again when they change), the others as frames record. Lengths are
     * shares of the screen's long side.
     *
     * @param intensity the share of bloom in the result: 0 none, 1 only bloom
     * @param size the FFT's size, a power of two (rounded down): the screen's long side over half of it; at most 1024,
     *     a line of 1024 complex pairs being the 16 KiB of shared memory every Vulkan device has
     * @param chain the physical chain's share of the bloom: its wide glow under the lens' shape (0: none, no chain)
     * @param levels the chain's levels
     * @param radius the chain's filters' spread
     * @param threshold the brightness where the chain's bloom starts (0: all light blooms)
     * @param knee the soft knee of the chain's threshold
     * @param antiFlicker Karis' average on the chain's first level
     * @param blades the aperture's blades: their straight edges diffract into spikes (B for even, 2 B for odd counts;
     *     below 3 none)
     * @param rotation the blades' rotation, degrees
     * @param core the kernel's core, its sigma
     * @param spikeLength the spikes' length
     * @param spikeWidth their width (sigma across)
     * @param spikeFalloff the power their brightness falls with along them: (1 - r / length)^falloff
     * @param spikes the spikes' share of the kernel's light
     * @param coronaRadius the corona's radius
     * @param coronaWidth its width (sigma)
     * @param corona its share of the kernel's light
     * @param coronaRed its red: how much of its share the channel keeps
     * @param coronaGreen its green
     * @param coronaBlue its blue
     * @param spread chromatic spread: 1 scales each channel's kernel by its wavelength (610, 550, 465 nm)
     * @param kernel an image kernel's asset path (a texture: PNG, Radiance HDR), loaded through {@link PostTextures};
     *     empty: the procedural kernel (also while the image loads)
     * @param kernelSize the image kernel's width, a share of the screen's long side
     */
    public record Convolution(
        @Tunable(value = "the share of bloom mixed into the scene's light", min = 0, max = 1) float intensity,
        @Tunable(value = "the convolution bloom's FFT size, a power of two: the screen's long side over half of it",
            min = 64, max = 1024) int size,
        @Tunable(value = "with the convolution bloom, the physical chain's share (its wide glow)", min = 0, max = 1)
        float chain,
        @Tunable(value = "how many half-size levels the convolution's chain spreads over", min = 1, max = 16)
        int levels,
        @Tunable(value = "the spread of the convolution's chain filters, in texels of each level", min = 0.25,
            max = 4) float radius,
        @Tunable(value = "the brightness where the convolution's chain blooms (0: all light)", min = 0, max = 1000)
        float threshold,
        @Tunable(value = "the soft knee of the chain's threshold, as a fraction of it", min = 0, max = 1) float knee,
        @Tunable("Karis' average on the convolution's chain's first level") boolean antiFlicker,
        @Tunable(value = "the lens aperture's blades: spikes for the convolution bloom (below 3: none)", min = 0,
            max = 32) int blades,
        @Tunable(value = "the aperture blades' rotation in degrees", min = -360, max = 360) float rotation,
        @Tunable(value = "the convolution kernel's core size, a share of the screen's long side", min = 0, max = 0.5)
        float core,
        @Tunable(value = "the diffraction spikes' length, a share of the screen's long side", min = 0, max = 1)
        float spikeLength,
        @Tunable(value = "the diffraction spikes' width, a share of the screen's long side", min = 0, max = 0.1)
        float spikeWidth,
        @Tunable(value = "how fast the diffraction spikes fade along their length (a power)", min = 0.1, max = 16)
        float spikeFalloff,
        @Tunable(value = "the diffraction spikes' share of the convolution kernel's light", min = 0, max = 1)
        float spikes,
        @Tunable(value = "the corona ring's radius, a share of the screen's long side", min = 0, max = 0.5)
        float coronaRadius,
        @Tunable(value = "the corona ring's width, a share of the screen's long side", min = 0, max = 0.1)
        float coronaWidth,
        @Tunable(value = "the corona ring's share of the convolution kernel's light", min = 0, max = 1) float corona,
        @Tunable(value = "the corona's red", min = 0, max = 1) float coronaRed,
        @Tunable(value = "the corona's green", min = 0, max = 1) float coronaGreen,
        @Tunable(value = "the corona's blue", min = 0, max = 1) float coronaBlue,
        @Tunable(value = "the convolution kernel's chromatic spread: 1 scales each channel by its wavelength",
            min = 0, max = 1) float spread,
        @Tunable("an image kernel's asset path (a texture), or empty for the procedural kernel") String kernel,
        @Tunable(value = "the image kernel's width, a share of the screen's long side", min = 0.01, max = 1)
        float kernelSize) implements TechniqueConfig {
    }

    /** Jimenez's 13-tap chain, the default. */
    public static final TechniqueKind<Chain, Bloom, Technique> PHYSICAL = TechniqueKind.of(SLOT, "physical",
        new Chain(0.05f, 6, 1, 0, 0.5f, true), "Call of Duty's 13-tap chain with tent upsamples: wide and smooth",
        (bloom, config) -> new Chained(MODE_PHYSICAL, config));
    /** Bjorge's dual Kawase filter: the cheapest. */
    public static final TechniqueKind<Chain, Bloom, Technique> DUAL_KAWASE = TechniqueKind.of(SLOT, "dual_kawase",
        new Chain(0.05f, 6, 1, 0, 0.5f, true), "the dual Kawase filter: 5 taps down, 8 up, the cheapest",
        (bloom, config) -> new Chained(MODE_DUAL_KAWASE, config));
    /** A separable Gaussian at each level: the reference. */
    public static final TechniqueKind<Chain, Bloom, Technique> GAUSSIAN = TechniqueKind.of(SLOT, "gaussian",
        new Chain(0.05f, 6, 1, 0, 0.5f, true), "a separable Gaussian at each level, summed: the reference",
        (bloom, config) -> new Chained(MODE_GAUSSIAN, config));
    /** Convolution with a lens' point spread function through FFTs. */
    public static final TechniqueKind<Convolution, Bloom, Technique> FFT = TechniqueKind.of(SLOT, "fft",
        new Convolution(0.05f, 512, 0, 6, 1, 0, 0.5f, true, 6, 0, 0.004f, 0.25f, 0.0015f, 3, 0.25f, 0.04f, 0.006f,
            0.1f, 1, 0.8f, 0.6f, 0.5f, "", 0.5f),
        "convolution with a lens' diffraction spikes and corona through 2D FFTs",
        (bloom, config) -> bloom.new Convolved(config));

    private static final int NONE = -1;
    private static final String SHADER = "shaders/post/bloom.spv";
    private static final String FFT_SHADER = "shaders/post/fft.spv";

    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen
    private final PostTextures.Held kernelImage; // the convolution's image kernel, or null: none

    /** Bloom by the settings, the convolution's kernels procedural (an image kernel's path is ignored). */
    public Bloom() {
        kernelImage = null;
    }

    /** Bloom by the settings, the convolution's image kernels loaded through {@code textures}. */
    public Bloom(PostTextures textures) {
        kernelImage = textures.held();
    }

    /**
     * The image kernel's slot, plus one (0: none): the graph rebuilds when it changes, making the kernel's spectrum
     * anew once the image has loaded. Allocates nothing.
     */
    @Override
    public long buildVersion() {
        return kernelImage == null ? 0 : kernelImage.slot() + 1;
    }

    /**
     * Registers its settings in {@code registry} for discovery: the slot, its kinds' tunables with it, hidden. An
     * application listing its settings calls this.
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
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.POST);
    }

    /** The chosen kind's technique, built in this one's place; off, nothing (the scene color as it was). */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, Bloom, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            made.computeIfAbsent(kind, k -> make(kind, g.settings())).build(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, Bloom, Technique> kind,
        EngineSettings settings) {
        return kind.create(this, () -> kind.config(settings));
    }

    /** The levels a chain of {@code wanted} has from a first level of {@code width} x {@code height}: 2 texels at least. */
    static int levels(int wanted, int width, int height) {
        int levels = 1;
        while (levels < wanted && Math.min(width, height) >> levels >= 2) {
            levels++;
        }
        return levels;
    }

    /** The region of level {@code level} of a chain whose first level's region is {@code size}. */
    private static int level(int size, int level) {
        return Math.max(1, (size + (1 << level) - 1) >> level);
    }

    /** A chain's tunables, as they are now. Allocates nothing until a value changes. */
    @FunctionalInterface
    private interface Live {
        void write(BloomPush push);
    }

    private static void write(BloomPush push, float intensity, float radius, float threshold, float knee,
        boolean antiFlicker) {
        push.antiFlicker(antiFlicker ? 1 : 0).threshold(threshold).knee(knee).radius(radius).intensity(intensity);
    }

    /** One of the chains, {@code mode}'s. */
    private record Chained(int mode, Supplier<Chain> config) implements Technique {

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image scene = g.modify(Keys.SCENE_COLOR);
            Live live = push -> {
                Chain c = config.get();
                write(push, c.intensity(), c.radius(), c.threshold(), c.knee(), c.antiFlicker());
            };
            chain(g, scene, mode, levelsOf(g, mode), live, null, null);
        }

        /** The chain's levels setting of this kind, read when the graph builds. */
        @AllowAllocation("building the graph, when it changes")
        private static int levelsOf(GraphBuilder g, int mode) {
            TechniqueKind<Chain, Bloom, Technique> kind = switch (mode) {
                case MODE_DUAL_KAWASE -> DUAL_KAWASE;
                case MODE_GAUSSIAN -> GAUSSIAN;
                default -> PHYSICAL;
            };
            return g.setting(kind.<Integer>setting("levels"));
        }
    }

    /**
     * The chain of {@code mode} over {@code scene}, then the composite into it in place, mixing the convolution
     * ({@code convolved}, or none) in by {@code convolutionShare}.
     */
    @AllowAllocation("building the graph, when it changes")
    private static void chain(GraphBuilder g, Image scene, int mode, int wantedLevels, Live live, Image convolved,
        Supplier<Convolution> convolution) {
        Pipeline composite = g.computeProgram(SHADER, "compositeMain", BloomPush.BYTES);
        ImageDesc half = ImageDesc.like(scene, Ratio.of(1, 2)).format(Format.R16G16B16A16_SFLOAT);
        int levels = levels(wantedLevels, half.width(), half.height());
        Image down = g.create("bloom down", half.mips(levels));
        // Upsampled levels: Gaussian keeps a blurred copy of every level; the others filter up from the coarsest.
        Image up = levels > 1 || mode == MODE_GAUSSIAN ? g.create("bloom up", half.mips(levels)) : null;
        Image across = mode == MODE_GAUSSIAN ? g.create("bloom across", half.mips(levels)) : null;
        Pipeline downsample = g.computeProgram(SHADER, "downsampleMain", BloomPush.BYTES);
        Pipeline upsample = g.computeProgram(SHADER, "upsampleMain", BloomPush.BYTES);
        Pipeline gaussian = g.computeProgram(SHADER, "gaussianMain", BloomPush.BYTES);
        for (int level = 0; level < levels; level++) {
            int at = level;
            BloomPush push = new BloomPush().mode(mode).added(NONE).first(level == 0 ? 1 : 0);
            g.pass("bloom down " + level)
                .read(level == 0 ? scene.whole() : down.mip(level - 1), Access.SAMPLED_COMPUTE)
                .write(down.mip(level), Access.STORAGE_WRITE)
                .record(ctx -> {
                    int sourceWidth = at == 0 ? ctx.regionWidth(scene) : level(ctx.regionWidth(down), at - 1);
                    int sourceHeight = at == 0 ? ctx.regionHeight(scene) : level(ctx.regionHeight(down), at - 1);
                    int width = level(ctx.regionWidth(down), at);
                    int height = level(ctx.regionHeight(down), at);
                    push.source(at == 0 ? ctx.sampled(scene) : ctx.sampled(down.mip(at - 1)))
                        .target(ctx.storage(down.mip(at))).size(width, height).sourceRegion(sourceWidth, sourceHeight);
                    live.write(push);
                    ctx.dispatch(downsample, push, (width + 7) / 8, (height + 7) / 8, 1);
                });
        }
        if (mode == MODE_GAUSSIAN) {
            for (int level = levels - 1; level >= 0; level--) {
                int at = level;
                BloomPush acrossPush = new BloomPush().mode(mode).added(NONE).direction(1, 0);
                g.pass("bloom horizontal " + level).read(down.mip(level), Access.SAMPLED_COMPUTE)
                    .write(across.mip(level), Access.STORAGE_WRITE)
                    .record(ctx -> {
                        int width = level(ctx.regionWidth(down), at);
                        int height = level(ctx.regionHeight(down), at);
                        acrossPush.source(ctx.sampled(down.mip(at))).target(ctx.storage(across.mip(at)))
                            .size(width, height).sourceRegion(width, height);
                        live.write(acrossPush);
                        ctx.dispatch(gaussian, acrossPush, (width + 7) / 8, (height + 7) / 8, 1);
                    });
                BloomPush downPush = new BloomPush().mode(mode).direction(0, 1);
                GraphBuilder.PassBuilder pass = g.pass("bloom vertical " + level)
                    .read(across.mip(level), Access.SAMPLED_COMPUTE).write(up.mip(level), Access.STORAGE_WRITE);
                if (level < levels - 1) {
                    pass.read(up.mip(level + 1), Access.SAMPLED_COMPUTE);
                }
                pass.record(ctx -> {
                    int width = level(ctx.regionWidth(down), at);
                    int height = level(ctx.regionHeight(down), at);
                    downPush.source(ctx.sampled(across.mip(at))).target(ctx.storage(up.mip(at)))
                        .size(width, height).sourceRegion(width, height);
                    if (at < levels - 1) {
                        downPush.added(ctx.sampled(up.mip(at + 1))).addedRegion(level(ctx.regionWidth(down), at + 1),
                            level(ctx.regionHeight(down), at + 1));
                    } else {
                        downPush.added(NONE);
                    }
                    live.write(downPush);
                    ctx.dispatch(gaussian, downPush, (width + 7) / 8, (height + 7) / 8, 1);
                });
            }
        } else {
            for (int level = levels - 2; level >= 0; level--) {
                int at = level;
                Image coarser = level == levels - 2 ? down : up; // the coarsest level is the downsample's
                BloomPush push = new BloomPush().mode(mode);
                g.pass("bloom up " + level).read(coarser.mip(level + 1), Access.SAMPLED_COMPUTE)
                    .read(down.mip(level), Access.SAMPLED_COMPUTE).write(up.mip(level), Access.STORAGE_WRITE)
                    .record(ctx -> {
                        int width = level(ctx.regionWidth(down), at);
                        int height = level(ctx.regionHeight(down), at);
                        push.source(ctx.sampled(coarser.mip(at + 1))).target(ctx.storage(up.mip(at)))
                            .size(width, height).sourceRegion(level(ctx.regionWidth(down), at + 1),
                                level(ctx.regionHeight(down), at + 1));
                        // Physical adds each level (averaged at the end); dual Kawase only filters up.
                        if (mode == MODE_PHYSICAL) {
                            push.added(ctx.sampled(down.mip(at))).addedRegion(width, height);
                        } else {
                            push.added(NONE);
                        }
                        live.write(push);
                        ctx.dispatch(upsample, push, (width + 7) / 8, (height + 7) / 8, 1);
                    });
            }
        }
        Image finest = up == null ? down : up;
        float scale = mode == MODE_DUAL_KAWASE ? 1 : 1f / levels; // the levels summed, averaged
        compose(g, composite, new BloomPush().mode(mode).scale(scale), scene, finest, down, convolved, convolution,
            live);
    }

    /**
     * The composite: the scene color, in place, mixed with the chain's finest level ({@code finest}, or none) and the
     * convolution ({@code convolved}, or none) by its share ({@code convolution}'s chain share, 1 - it).
     */
    @AllowAllocation("building the graph, when it changes")
    private static void compose(GraphBuilder g, Pipeline composite, BloomPush push, Image scene, Image finest,
        Image down, Image convolved, Supplier<Convolution> convolution, Live live) {
        GraphBuilder.PassBuilder pass = g.pass("bloom composite").write(scene, Access.STORAGE_WRITE);
        if (finest != null) {
            pass.read(finest.mip(0), Access.SAMPLED_COMPUTE);
        }
        if (convolved != null) {
            pass.read(convolved, Access.SAMPLED_COMPUTE);
        }
        int size = convolved == null ? 0 : convolved.width();
        pass.record(ctx -> {
            int width = ctx.regionWidth(scene);
            int height = ctx.regionHeight(scene);
            push.target(ctx.storage(scene)).size(width, height);
            if (finest != null) {
                push.source(ctx.sampled(finest.mip(0))).sourceRegion(ctx.regionWidth(down), ctx.regionHeight(down));
            }
            if (convolved != null) {
                float texels = (size / 2f) / Math.max(width, height); // half the FFT over the long side
                float share = finest == null ? 1 : 1 - convolution.get().chain();
                push.fft(ctx.sampled(convolved)).fftShare(share).fftScale(texels)
                    .fftRegion((int) Math.ceil(width * texels), (int) Math.ceil(height * texels));
            } else {
                push.fft(NONE).fftShare(0);
            }
            live.write(push);
            ctx.dispatch(composite, push, (width + 7) / 8, (height + 7) / 8, 1);
        });
    }

    /** The convolution bloom, with the physical chain under it where its share is above 0. */
    private final class Convolved implements Technique {
        private final Supplier<Convolution> config;

        Convolved(Supplier<Convolution> config) {
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
            Image convolved = convolve(g, scene);
            Live live = push -> {
                Convolution c = config.get();
                write(push, c.intensity(), c.radius(), c.threshold(), c.knee(), c.antiFlicker());
            };
            // The chain's share is read as frames record, but whether there is a chain at all when the graph builds
            if (g.setting(FFT.<Float>setting("chain")) <= 0) {
                compose(g, g.computeProgram(SHADER, "compositeMain", BloomPush.BYTES),
                    new BloomPush().mode(MODE_FFT).added(NONE), scene, null, null, convolved, config, live);
                return;
            }
            chain(g, scene, MODE_PHYSICAL, g.setting(FFT.<Integer>setting("levels")), live, convolved, config);
        }
    }

    /** A setting of the convolution read when the graph builds. */
    @AllowAllocation("building the graph, when it changes")
    private static <V> V fft(GraphBuilder g, String field) {
        return g.setting(FFT.<V>setting(field));
    }

    /**
     * The convolution: the scene color, box-filtered into the top left of an N x N image, convolved with the kernel
     * through forward and inverse FFTs; the kernel's spectrum made once per kernel (its settings name a persistent
     * image, made anew when they change).
     */
    @AllowAllocation("building the graph, when it changes")
    private Image convolve(GraphBuilder g, Image scene) {
        int n = Integer.highestOneBit(Bloom.<Integer>fft(g, "size"));
        int log2n = Integer.numberOfTrailingZeros(n);
        float texels = n / 2f; // the screen's long side
        int blades = fft(g, "blades");
        float rotation = (float) (Bloom.<Float>fft(g, "rotation") * Math.PI / 180);
        float core = Math.max(0.5f, Bloom.<Float>fft(g, "core") * texels);
        float spikeLength = Math.max(1, Bloom.<Float>fft(g, "spikeLength") * texels);
        float spikeWidth = Math.max(0.5f, Bloom.<Float>fft(g, "spikeWidth") * texels);
        float spikeFalloff = fft(g, "spikeFalloff");
        float spikes = blades >= 3 ? fft(g, "spikes") : 0;
        float coronaRadius = Math.max(1, Bloom.<Float>fft(g, "coronaRadius") * texels);
        float coronaWidth = Math.max(0.5f, Bloom.<Float>fft(g, "coronaWidth") * texels);
        float corona = Math.min(fft(g, "corona"), 1 - spikes);
        float spread = fft(g, "spread");
        float red = fft(g, "coronaRed");
        float green = fft(g, "coronaGreen");
        float blue = fft(g, "coronaBlue");
        String path = fft(g, "kernel");
        float imageTexels = Bloom.<Float>fft(g, "kernelSize") * texels;
        // An image kernel once loaded (buildVersion rebuilds then); procedural before, or without one
        int image = kernelImage == null ? NONE : kernelImage.follow(path).slot();
        String kernel = image != NONE ? "image " + path + " " + image + " " + imageTexels
            : java.util.Arrays.toString(new float[] {blades, rotation, core, spikeLength, spikeWidth, spikeFalloff,
                spikes, coronaRadius, coronaWidth, corona, spread, red, green, blue});
        ImageDesc desc = ImageDesc.of(Format.R32G32B32A32_SFLOAT, n, n);
        Image spectrum = g.persistent("bloom kernel spectrum " + n + " " + kernel, desc);
        Image spectrumBlue = g.persistent("bloom kernel spectrum, blue, " + n + " " + kernel, desc);
        Image scratch = g.create("bloom kernel", desc);
        Image data = g.create("bloom convolution", desc);
        Image product = g.create("bloom convolution product", desc);
        Pipeline kernelProgram = g.computeProgram(FFT_SHADER, "kernelMain", FftPush.BYTES);
        Pipeline fft = g.computeProgram(FFT_SHADER, "fftMain", FftPush.BYTES);
        Pipeline separate = g.computeProgram(FFT_SHADER, "spectrumMain", FftPush.BYTES);
        Pipeline input = g.computeProgram(FFT_SHADER, "inputMain", FftPush.BYTES);
        Pipeline multiply = g.computeProgram(FFT_SHADER, "multiplyMain", FftPush.BYTES);
        int groups = n / 8;
        // The kernel and its spectrum: recorded only in the frame the spectrum is made for
        FftPush kernelPush = new FftPush().n(n).log2n(log2n).blades(blades).rotation(rotation).core(core)
            .spikeLength(spikeLength).spikeWidth(spikeWidth).spikeFalloff(spikeFalloff).spikes(spikes)
            .coronaRadius(coronaRadius).coronaWidth(coronaWidth).corona(corona).spread(spread)
            .coronaRed(red).coronaGreen(green).coronaBlue(blue).kernelImage(image).imageTexels(imageTexels);
        // (each reads the spectrum, to ask whether it is new)
        g.pass("bloom kernel").read(spectrum, Access.SAMPLED_COMPUTE).write(scratch, Access.STORAGE_WRITE)
            .record(ctx -> {
                if (ctx.fresh(spectrum)) {
                    kernelPush.image(ctx.storage(scratch));
                    ctx.dispatch(kernelProgram, kernelPush, groups, groups, 1);
                }
            });
        transform(g, "bloom kernel", fft, scratch, n, log2n, false, spectrum);
        FftPush separatePush = new FftPush().n(n);
        g.pass("bloom kernel spectrum").read(scratch, Access.SAMPLED_COMPUTE).write(spectrum, Access.STORAGE_WRITE)
            .write(spectrumBlue, Access.STORAGE_WRITE)
            .record(ctx -> {
                if (ctx.fresh(spectrum)) {
                    separatePush.source(ctx.sampled(scratch)).image(ctx.storage(spectrum))
                        .target2(ctx.storage(spectrumBlue));
                    ctx.dispatch(separate, separatePush, groups, groups, 1);
                }
            });
        // The scene, every frame
        FftPush inputPush = new FftPush().n(n);
        g.pass("bloom fft input").read(scene, Access.SAMPLED_COMPUTE).write(data, Access.STORAGE_WRITE)
            .record(ctx -> {
                int width = ctx.regionWidth(scene);
                int height = ctx.regionHeight(scene);
                inputPush.source(ctx.sampled(scene)).image(ctx.storage(data)).region(width, height)
                    .scale(Math.max(width, height) / texels);
                ctx.dispatch(input, inputPush, groups, groups, 1);
            });
        transform(g, "bloom fft", fft, data, n, log2n, false, null);
        FftPush multiplyPush = new FftPush().n(n);
        g.pass("bloom fft multiply").read(data, Access.STORAGE_READ).read(spectrum, Access.SAMPLED_COMPUTE)
            .read(spectrumBlue, Access.SAMPLED_COMPUTE).write(product, Access.STORAGE_WRITE)
            .record(ctx -> {
                multiplyPush.target2(ctx.storage(data)).source(ctx.sampled(spectrum))
                    .source2(ctx.sampled(spectrumBlue)).image(ctx.storage(product));
                ctx.dispatch(multiply, multiplyPush, groups, groups, 1);
            });
        transform(g, "bloom fft inverse", fft, product, n, log2n, true, null);
        return product;
    }

    /**
     * The 2D FFT of {@code image} in place, a pass per axis: rows, then columns (inverse: columns first); with
     * {@code onlyFor}, recorded only in the frame that persistent image is made for.
     */
    @AllowAllocation("building the graph, when it changes")
    private static void transform(GraphBuilder g, String name, Pipeline fft, Image image, int n, int log2n,
        boolean inverse, Image onlyFor) {
        for (int pass = 0; pass < 2; pass++) {
            int axis = inverse ? 1 - pass : pass;
            FftPush push = new FftPush().n(n).log2n(log2n).inverse(inverse ? 1 : 0).axis(axis);
            GraphBuilder.PassBuilder fftPass = g.pass(name + (axis == 0 ? " rows" : " columns"))
                .write(image, Access.STORAGE_WRITE);
            if (onlyFor != null) {
                fftPass.read(onlyFor, Access.SAMPLED_COMPUTE);
            }
            fftPass.record(ctx -> {
                if (onlyFor == null || ctx.fresh(onlyFor)) {
                    ctx.dispatch(fft, push.image(ctx.storage(image)), n, 1, 1);
                }
            });
        }
    }
}
