package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.common.math.Maths;
import com.truesim.effects.post.layout.DofPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Depth of field: what lies before and behind the focus blurred as a lens does, in scene light, before bloom. It
 * modifies {@link Keys#SCENE_COLOR} in place at {@link Anchor#POST}, before {@link Bloom}, reading
 * {@link Keys#SCENE_DEPTH} (reverse depth, near over distance) and the view's perspective camera as frames record, as
 * the kind chosen for {@link #SLOT} ({@code render.dof}) does it:
 *
 * <ul>
 *   <li>{@link #GATHER} ({@code gather}, the default): after Jimenez's (Call of Duty: Advanced Warfare, 2014): near and
 *       far fields apart at half resolution, 49 taps over a disc (a round bokeh), the near field spread over sharp
 *       edges by its largest circles in tiles of 16 pixels around, dilated as far as {@code maxRadius} reaches, a 3 x 3
 *       median postfilter.</li>
 *   <li>{@link #GAUSSIAN} ({@code gaussian}): a separable Gaussian weighted by circle size at half resolution: cheaper
 *       and soft, the near field not spread over sharp edges.</li>
 *   <li>{@code off}: no passes.</li>
 * </ul>
 *
 * <p>The circle of confusion is the thin lens' from the camera: the f-number ({@link #F_STOP}), the focal length that
 * the camera's field of view gives on a sensor of {@link #SENSOR_HEIGHT}, and the focus distance
 * ({@link #FOCUS_DISTANCE}) or, with {@link #AUTOFOCUS}, the distance at the middle of the view (the median of nine
 * taps over {@link #AUTOFOCUS_AREA} of it), found on the GPU and followed at {@link #AUTOFOCUS_SPEED} in log distance.
 * Those are the camera's settings, not a technique's: the same lens whichever blur draws it. Works within the render
 * region.
 *
 * <pre>{@code
 * port.add(new DepthOfField(camera));
 * graph.settings().set(DepthOfField.F_STOP, 1.8f).set(DepthOfField.AUTOFOCUS, false).set(DepthOfField.FOCUS_DISTANCE, 3f);
 * DepthOfField.SLOT.choose(graph.settings(), DepthOfField.GAUSSIAN);
 * }</pre>
 */
public final class DepthOfField implements Technique {

    /** A view's depth of field. */
    public static final Id<DepthOfField> ID = Id.of("truesim.depthOfField", DepthOfField.class);

    /** The technique, a kind's id, or {@code off}. */
    public static final TechniqueSlot<DepthOfField, Technique> SLOT = TechniqueSlot.of("render.dof", "gather", true,
        "depth of field: off, gather (bokeh, near and far fields) or gaussian (cheaper)",
        techniques -> techniques.register(DepthOfField.SLOT, DepthOfField.GATHER)
            .register(DepthOfField.SLOT, DepthOfField.GAUSSIAN));

    /** The f-number: the focal length over the aperture's diameter. Lower blurs more. */
    public static final Setting<Float> F_STOP = Setting.decimal("camera.fStop", 5.6f, 0.7f, 64,
        "the lens' f-number (focal length over aperture): lower blurs more out of focus");
    /** The sensor's height, millimeters (24: full frame), which with the field of view gives the focal length. */
    public static final Setting<Float> SENSOR_HEIGHT = Setting.decimal("camera.sensorHeight", 24, 1, 100,
        "the camera sensor's height in millimeters (24: full frame), giving the focal length with the field of view");
    /** Where the lens focuses, meters, without autofocus. */
    public static final Setting<Float> FOCUS_DISTANCE = Setting.decimal("camera.focusDistance", 10, 0.05f, 100000,
        "the focus distance in meters, without autofocus");
    /** The focus follows what is at the middle of the view. */
    public static final Setting<Boolean> AUTOFOCUS = Setting.bool("camera.autofocus", true,
        "focus on what is at the middle of the view");
    /** How fast autofocus follows: the share of the way (in log distance) it moves in a second is 1 - e^-speed. */
    public static final Setting<Float> AUTOFOCUS_SPEED = Setting.decimal("camera.autofocusSpeed", 4, 0.1f, 1000,
        "how fast autofocus follows, per second");
    /** The share of the view's height autofocus looks at, around its middle. */
    public static final Setting<Float> AUTOFOCUS_AREA = Setting.decimal("camera.autofocusArea", 0.1f, 0, 1,
        "the share of the view's height autofocus looks at, around its middle");
    /** The camera's settings. */
    public static final List<Setting<?>> CAMERA = List.of(F_STOP, SENSOR_HEIGHT, FOCUS_DISTANCE, AUTOFOCUS,
        AUTOFOCUS_SPEED, AUTOFOCUS_AREA);

    /**
     * A blur's tunables, read as frames record but {@code maxRadius}, read when the graph builds (the tiles' reach).
     *
     * @param maxRadius the largest blur radius, in pixels of the region (full resolution): what costs grow with
     */
    public record Blur(
        @Tunable(value = "the largest depth of field blur radius, in pixels", min = 2, max = 64) float maxRadius)
        implements TechniqueConfig {
    }

    private static final int MODE_GATHER = 0;
    private static final int MODE_GAUSSIAN = 1;
    /** The near field's tiles: dof.slang's TILE. */
    private static final int TILE = 16;
    private static final String SHADER = "shaders/post/dof.spv";

    /** The gather with near and far fields, the default. */
    public static final TechniqueKind<Blur, DepthOfField, Technique> GATHER = TechniqueKind.of(SLOT, "gather",
        new Blur(24), "near and far fields gathered over a disc of 49 taps: round bokeh, the near field over edges",
        (dof, config) -> dof.new Blurred(MODE_GATHER, config));
    /** A separable Gaussian: cheaper. */
    public static final TechniqueKind<Blur, DepthOfField, Technique> GAUSSIAN = TechniqueKind.of(SLOT, "gaussian",
        new Blur(24), "a separable Gaussian weighted by circle size: cheaper, soft",
        (dof, config) -> dof.new Blurred(MODE_GAUSSIAN, config));

    private final Camera camera;
    private final DoubleSupplier clock;
    private double last = Double.NaN;

    /** Depth of field for a view seen through {@code camera}, autofocus following over real seconds. */
    public DepthOfField(Camera camera) {
        this(camera, new DoubleSupplier() {
            private final long started = System.nanoTime();

            @Override
            public double getAsDouble() {
                return (System.nanoTime() - started) * 1e-9;
            }
        });
    }

    /**
     * Depth of field for a view seen through {@code camera} (its near plane and field of view, read as frames record),
     * autofocus following over the seconds of {@code clock} (game time; it must not allocate).
     */
    public DepthOfField(Camera camera, DoubleSupplier clock) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers its settings in {@code registry} for discovery: the slot with its kinds' tunables, the camera's. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT).addHidden(CAMERA.toArray(new Setting<?>[0]));
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
        return Order.at(Anchor.POST).before(Bloom.ID);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, DepthOfField, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            make(kind, g.settings()).build(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, DepthOfField, Technique> kind,
        EngineSettings settings) {
        return kind.create(this, () -> kind.config(settings));
    }

    /** The seconds since the frame before, by the clock (0 on the first). Allocates nothing. */
    private float step() {
        double now = clock.getAsDouble();
        float seconds = Double.isNaN(last) ? 0 : (float) Math.max(0, now - last);
        last = now;
        return seconds;
    }

    /** Tiles over {@code pixels}. */
    private static int tiles(int pixels) {
        return (pixels + TILE - 1) / TILE;
    }

    /** The blur of {@code mode}. */
    private final class Blurred implements Technique {
        private final int mode;
        private final java.util.function.Supplier<Blur> config;

        Blurred(int mode, java.util.function.Supplier<Blur> config) {
            this.mode = mode;
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
            Image depth = g.require(Keys.SCENE_DEPTH);
            ViewPort view = g.view();
            EngineSettings settings = g.settings();
            // (read for the build: a change rebuilds, the tiles' reach with it)
            float most = g.setting((mode == MODE_GATHER ? GATHER : GAUSSIAN).<Float>setting("maxRadius"));
            int dilate = (int) Math.ceil(most / TILE);
            Pipeline focus = g.computeProgram(SHADER, "focusMain", DofPush.BYTES);
            Pipeline circles = g.computeProgram(SHADER, "cocMain", DofPush.BYTES);
            Pipeline down = g.computeProgram(SHADER, "downsampleMain", DofPush.BYTES);
            Pipeline compose = g.computeProgram(SHADER, "compositeMain", DofPush.BYTES);
            Buffer focused = g.persistentBuffer("depth of field focus", 16);
            Image coc = g.create("circles of confusion", ImageDesc.like(scene, Ratio.ONE).format(Format.R16_SFLOAT));
            ImageDesc halfDesc = ImageDesc.like(scene, Ratio.of(1, 2)).format(Format.R16G16B16A16_SFLOAT);
            Image half = g.create("depth of field half", halfDesc);
            DofPush focusPush = new DofPush();
            g.pass("depth of field focus").read(depth, Access.SAMPLED_COMPUTE)
                .write(focused, BufferAccess.STORAGE_WRITE)
                .record(ctx -> {
                    float seconds = step();
                    if (!settings.get(AUTOFOCUS)) {
                        return;
                    }
                    lens(focusPush, settings, ctx, scene, view).depth(ctx.sampled(depth)).focusAt(ctx.address(focused))
                        .size(ctx.regionWidth(depth), ctx.regionHeight(depth)).speed(settings.get(AUTOFOCUS_SPEED))
                        .seconds(seconds).area(settings.get(AUTOFOCUS_AREA));
                    ctx.dispatch(focus, focusPush, 1, 1, 1);
                });
            DofPush cocPush = new DofPush();
            g.pass("depth of field circles").read(depth, Access.SAMPLED_COMPUTE)
                .read(focused, BufferAccess.STORAGE_READ).write(coc, Access.STORAGE_WRITE)
                .record(ctx -> {
                    int width = ctx.regionWidth(coc);
                    int height = ctx.regionHeight(coc);
                    lens(cocPush, settings, ctx, scene, view).depth(ctx.sampled(depth)).focusAt(ctx.address(focused))
                        .target(ctx.storage(coc)).size(width, height);
                    ctx.dispatch(circles, cocPush, (width + 7) / 8, (height + 7) / 8, 1);
                });
            DofPush downPush = new DofPush();
            g.pass("depth of field downsample").read(scene, Access.SAMPLED_COMPUTE).read(coc, Access.SAMPLED_COMPUTE)
                .write(half, Access.STORAGE_WRITE)
                .record(ctx -> {
                    int width = ctx.regionWidth(half);
                    int height = ctx.regionHeight(half);
                    downPush.source(ctx.sampled(scene)).coc(ctx.sampled(coc)).target(ctx.storage(half))
                        .size(width, height).sourceRegion(ctx.regionWidth(scene), ctx.regionHeight(scene));
                    ctx.dispatch(down, downPush, (width + 7) / 8, (height + 7) / 8, 1);
                });
            Image far;
            Image near;
            if (mode == MODE_GATHER) {
                Pipeline tile = g.computeProgram(SHADER, "tileMain", DofPush.BYTES);
                Pipeline dilating = g.computeProgram(SHADER, "dilateMain", DofPush.BYTES);
                Pipeline gather = g.computeProgram(SHADER, "gatherMain", DofPush.BYTES);
                Pipeline median = g.computeProgram(SHADER, "medianMain", DofPush.BYTES);
                ImageDesc tileDesc = ImageDesc.like(scene, Ratio.of(1, TILE)).format(Format.R16G16_SFLOAT);
                Image tiles = g.create("depth of field tiles", tileDesc);
                Image dilated = g.create("depth of field dilated tiles", tileDesc);
                Image gatheredFar = g.create("depth of field far", halfDesc);
                Image gatheredNear = g.create("depth of field near", halfDesc);
                far = g.create("depth of field far, filtered", halfDesc);
                near = g.create("depth of field near, filtered", halfDesc);
                DofPush tilePush = new DofPush();
                g.pass("depth of field tiles").read(coc, Access.SAMPLED_COMPUTE).write(tiles, Access.STORAGE_WRITE)
                    .record(ctx -> {
                        int width = tiles(ctx.regionWidth(coc));
                        int height = tiles(ctx.regionHeight(coc));
                        tilePush.coc(ctx.sampled(coc)).target(ctx.storage(tiles)).size(width, height)
                            .sourceRegion(ctx.regionWidth(coc), ctx.regionHeight(coc));
                        ctx.dispatch(tile, tilePush, (width + 7) / 8, (height + 7) / 8, 1);
                    });
                DofPush dilatePush = new DofPush().dilate(dilate);
                g.pass("depth of field dilate").read(tiles, Access.SAMPLED_COMPUTE)
                    .write(dilated, Access.STORAGE_WRITE)
                    .record(ctx -> {
                        int width = tiles(ctx.regionWidth(coc));
                        int height = tiles(ctx.regionHeight(coc));
                        dilatePush.source(ctx.sampled(tiles)).target(ctx.storage(dilated)).size(width, height);
                        ctx.dispatch(dilating, dilatePush, (width + 7) / 8, (height + 7) / 8, 1);
                    });
                DofPush gatherPush = new DofPush().mode(mode);
                g.pass("depth of field gather").read(half, Access.SAMPLED_COMPUTE)
                    .read(dilated, Access.SAMPLED_COMPUTE).write(gatheredFar, Access.STORAGE_WRITE)
                    .write(gatheredNear, Access.STORAGE_WRITE)
                    .record(ctx -> {
                        int width = ctx.regionWidth(half);
                        int height = ctx.regionHeight(half);
                        gatherPush.source(ctx.sampled(half)).aux(ctx.sampled(dilated))
                            .target(ctx.storage(gatheredFar)).target2(ctx.storage(gatheredNear)).size(width, height)
                            .sourceRegion(width, height).maxRadius(config.get().maxRadius());
                        ctx.dispatch(gather, gatherPush, (width + 7) / 8, (height + 7) / 8, 1);
                    });
                for (int field = 0; field < 2; field++) {
                    Image from = field == 0 ? gatheredFar : gatheredNear;
                    Image to = field == 0 ? far : near;
                    DofPush medianPush = new DofPush();
                    g.pass("depth of field median " + (field == 0 ? "far" : "near"))
                        .read(from, Access.SAMPLED_COMPUTE).write(to, Access.STORAGE_WRITE)
                        .record(ctx -> {
                            int width = ctx.regionWidth(half);
                            int height = ctx.regionHeight(half);
                            medianPush.source(ctx.sampled(from)).target(ctx.storage(to)).size(width, height)
                                .sourceRegion(width, height);
                            ctx.dispatch(median, medianPush, (width + 7) / 8, (height + 7) / 8, 1);
                        });
                }
            } else {
                Pipeline gaussian = g.computeProgram(SHADER, "gaussianMain", DofPush.BYTES);
                Image across = g.create("depth of field across", halfDesc);
                far = g.create("depth of field blurred", halfDesc);
                near = null;
                for (int axis = 0; axis < 2; axis++) {
                    Image from = axis == 0 ? half : across;
                    Image to = axis == 0 ? across : far;
                    DofPush blurPush = new DofPush().mode(mode).direction(axis == 0 ? 1 : 0, axis);
                    g.pass("depth of field blur " + (axis == 0 ? "across" : "down"))
                        .read(from, Access.SAMPLED_COMPUTE).write(to, Access.STORAGE_WRITE)
                        .record(ctx -> {
                            int width = ctx.regionWidth(half);
                            int height = ctx.regionHeight(half);
                            blurPush.source(ctx.sampled(from)).target(ctx.storage(to)).size(width, height)
                                .sourceRegion(width, height).maxRadius(config.get().maxRadius());
                            ctx.dispatch(gaussian, blurPush, (width + 7) / 8, (height + 7) / 8, 1);
                        });
                }
            }
            DofPush composePush = new DofPush().mode(mode);
            GraphBuilder.PassBuilder pass = g.pass("depth of field composite").read(coc, Access.SAMPLED_COMPUTE)
                .read(far, Access.SAMPLED_COMPUTE).write(scene, Access.STORAGE_WRITE);
            if (near != null) {
                pass.read(near, Access.SAMPLED_COMPUTE);
            }
            Image nearField = near;
            Image farField = far;
            pass.record(ctx -> {
                int width = ctx.regionWidth(scene);
                int height = ctx.regionHeight(scene);
                composePush.coc(ctx.sampled(coc)).aux(ctx.sampled(farField))
                    .aux2(nearField == null ? ctx.sampled(farField) : ctx.sampled(nearField))
                    .target(ctx.storage(scene)).size(width, height)
                    .sourceRegion(ctx.regionWidth(half), ctx.regionHeight(half));
                ctx.dispatch(compose, composePush, (width + 7) / 8, (height + 7) / 8, 1);
            });
        }

        /** The lens as the settings and the camera have it now, for the region of {@code scene}. */
        private DofPush lens(DofPush push, EngineSettings settings, PassContext ctx, Image scene, ViewPort view) {
            float sensor = settings.get(SENSOR_HEIGHT) / 1000;
            float focal = (float) (sensor / 2 / Maths.tan(camera.fovY() / 2));
            return push.near((float) camera.near()).focal(focal).aperture(focal / settings.get(F_STOP))
                .pixels(ctx.regionHeight(scene) / sensor).focus(settings.get(FOCUS_DISTANCE))
                .maxRadius(config.get().maxRadius()).autofocus(settings.get(AUTOFOCUS) ? 1 : 0);
        }
    }

    /**
     * The thin lens' circle of confusion radius in pixels, as the shader computes it: a point at {@code distance}, the
     * lens of {@code focal} meters at {@code fStop} focused at {@code focus}, on a sensor of {@code sensorHeight}
     * meters imaged over {@code pixels} rows; negative before the focus.
     */
    public static double circleOfConfusion(double distance, double focus, double focal, double fStop,
        double sensorHeight, int pixels) {
        double diameter = focal / fStop * focal * (distance - focus) / (distance * (focus - focal));
        return 0.5 * diameter * pixels / sensorHeight;
    }
}
