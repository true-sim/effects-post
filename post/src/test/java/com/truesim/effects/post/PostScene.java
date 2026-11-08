package com.truesim.effects.post;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.RenderAssets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A day scene for the post chain's films and benchmark, drawn by the engine's renderer straight (a test's fixture): a
 * house with a doorway towards a sun high enough to light the ground (its inside dark but for a dim lamp and the sky
 * through the door), lamp posts, glowing orbs and rocks on a ground plane, with near and far subjects for depth of
 * field; shaded by clustered forward shading into 16-bit floats, then every post effect of this library and the
 * engine's display transform with its display-space stage (AgX), into an sRGB target.
 *
 * <p>It has {@code tiles} views of one camera, each in a frame graph of its own, so each has settings of its own (a
 * technique chosen in one, another in the next) and temporal state of its own (auto exposure's adaptation,
 * autofocus). The scene's content is shared. Textures post effects name load through the asset cache from files held
 * in memory ({@link #file}, mount {@code film://}).
 */
final class PostScene implements AutoCloseable {

    /** Towards the sun: ahead of the house's door (+z), 27 degrees up. */
    static final double[] SUN = normalize(0.3, 0.5, 1);
    /** The fixed exposure of every tile until a film sets another: the sunlit outside's. */
    static final float FIXED_EXPOSURE = 0.25f;
    private static final Key<Image> SHOWN = Key.image("test.shown");
    private static final double[][] POSTS = {{-2.6, 7}, {2.8, 12}, {-2.2, 18}};
    private static final float[] BULB = {24, 14, 5};
    private static final float[] LAMP = {9, 5.2f, 1.8f};

    final VulkanInstance instance;
    final VulkanDevice device;
    final int width;
    final int height;
    final Camera camera = new Camera();
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache cache = new AssetCache(tasks);
    final PathAssets paths = new PathAssets(cache);
    final Map<String, byte[]> files = new HashMap<>();
    final SceneResources resources;
    final PostTextures textures;
    final List<Tile> tiles = new ArrayList<>();
    /** The scene's clock, seconds: what auto exposure, autofocus and post materials follow. */
    double seconds;
    private final GeometryPool pool;
    private final GpuScene scene;
    private final Materials materials;
    private final Textures gpuTextures;
    private final Lights lights;
    private final Environment environment;
    private final int firstBulb;
    private int nextId;

    /** One view of the scene, in a frame graph of its own, with every post effect. */
    final class Tile implements AutoCloseable {
        final FrameGraph graph;
        final ViewPort port;
        final TestTarget target;
        final SceneView view;
        final Shadows shadows;
        final MeshletRenderer renderer; // a renderer per graph: its views' lists are the renderer's
        final ScreenFlash flash = new ScreenFlash();

        private Tile(String name, List<Technique> extra) {
            graph = new FrameGraph(device, 2);
            target = new TestTarget(device, width, height, Format.R8G8B8A8_SRGB);
            renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            port = graph.addView(name).size(width, height);
            view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 20));
            view.set(1, MeshletRenderer.MODE_LIT);
            shadows = new Shadows(geometry, lights);
            var s = graph.settings();
            s.set(DisplayTransform.CURVE, "agx").set(DisplayTransform.EXPOSURE, FIXED_EXPOSURE);
            // Every effect off but bloom until a film chooses: a fixed exposure, no grading, no depth of field
            AutoExposure.SLOT.choose(s, null);
            ColorGrading.SLOT.choose(s, null);
            DepthOfField.SLOT.choose(s, null);
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view))
                .add(new ClusteredLights(lights, view)).add(shadows.view(view))
                .add(new DepthOfField(camera, () -> seconds)).add(new Bloom(textures)).add(new LensEffects(camera))
                .add(new ColorGrading(textures)).add(new AutoExposure(() -> seconds)).add(new FilmGrain())
                .add(flash.display()).add(flash.scene());
            for (Technique each : extra) {
                port.add(each);
            }
            for (Technique each : new DisplayTransform().displayEffects().transforms(Keys.SCENE_COLOR, SHOWN,
                DisplayEncoding.SDR_SRGB)) {
                port.add(each);
            }
            port.output(SHOWN, target.output());
        }

        /** Its settings. */
        com.truesim.engine.settings.EngineSettings settings() {
            return graph.settings();
        }

        /** One frame, read back. */
        BufferedImage frame() {
            view.set(1, MeshletRenderer.MODE_LIT);
            target.frame(graph);
            return target.shot();
        }

        @Override
        public void close() {
            device.waitIdle();
            graph.close();
            shadows.close();
            target.close();
            renderer.close();
        }
    }

    PostScene(int width, int height) {
        instance = VulkanInstance.builder().applicationName("post films").surfaces(false).build();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        this.width = width;
        this.height = height;
        System.out.println("post scene on " + instance.bestGpu().name() + ", " + width + " x " + height);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 18, 8192, 8));
        scene = new GpuScene(device, pool, 1024);
        materials = new Materials(device, 32);
        gpuTextures = new Textures(device);
        lights = new Lights(device, 16, 4);
        scene.setMaterials(materials);
        paths.mounts().add("film", path -> {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
        PostTextures.install(paths);
        paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE);
        resources = new SceneResources(cache, pool, materials, gpuTextures);
        textures = new PostTextures(resources, paths);

        int box = add(TestMeshes.box());
        int rock = add(TestMeshes.rock(3));
        MetallicRoughnessParameters mr = MetallicRoughnessParameters.DEFAULTS;
        int ground = materials.add(new Material(mr.withBaseColor(new Color(0.30f, 0.29f, 0.25f, 1)).withRoughness(0.85f)
            .withMetallic(0)));
        // Dark timber: inside, lit by the sky through the door and the dim lamp only, the house is about 7 stops
        // darker than the sunlit ground (the sky's light reaches inside unshadowed: the engine's environment lighting)
        int plaster = materials.add(new Material(mr.withBaseColor(new Color(0.12f, 0.10f, 0.08f, 1))
            .withRoughness(0.9f).withMetallic(0)));
        int stone = materials.add(new Material(mr.withBaseColor(new Color(0.45f, 0.42f, 0.40f, 1)).withRoughness(0.6f)
            .withMetallic(0)));
        int red = materials.add(new Material(mr.withBaseColor(new Color(0.55f, 0.08f, 0.06f, 1)).withRoughness(0.5f)
            .withMetallic(0)));
        int blue = materials.add(new Material(mr.withBaseColor(new Color(0.06f, 0.12f, 0.5f, 1)).withRoughness(0.4f)
            .withMetallic(0)));
        int pole = materials.add(new Material(mr.withBaseColor(new Color(0.08f, 0.08f, 0.09f, 1)).withRoughness(0.35f)
            .withMetallic(1)));
        firstBulb = materials.add(bulb(1));
        int bulb = materials.add(bulb(1));
        int cyan = materials.add(new Material(mr.withBaseColor(new Color(0.05f, 0.05f, 0.05f, 1))
            .withEmissive(new Color(2, 10, 16, 1))));
        int magenta = materials.add(new Material(mr.withBaseColor(new Color(0.05f, 0.05f, 0.05f, 1))
            .withEmissive(new Color(14, 1.5f, 8, 1))));
        set(box, ground, 0, -0.05, 0, 300, 0.05f, 300);
        // The house: inside x -3 to 3, z -7 to 0, 3 high; a door 1.6 wide, 2.2 high in the front wall (z = 0)
        set(box, plaster, 0, 1.5, -7.1, 3.2f, 1.5f, 0.1f);
        set(box, plaster, -3.1, 1.5, -3.5, 0.1f, 1.5f, 3.6f);
        set(box, plaster, 3.1, 1.5, -3.5, 0.1f, 1.5f, 3.6f);
        set(box, plaster, 0, 3.1, -3.5, 3.2f, 0.1f, 3.7f);
        set(box, plaster, -1.95, 1.5, 0.1, 1.15f, 1.5f, 0.1f);
        set(box, plaster, 1.95, 1.5, 0.1, 1.15f, 1.5f, 0.1f);
        set(box, plaster, 0, 2.6, 0.1, 0.8f, 0.4f, 0.1f);
        // Inside: a table and boxes
        set(box, red, -1.5, 0.4, -5, 0.6f, 0.4f, 0.6f);
        set(box, blue, 1.8, 0.3, -2.5, 0.4f, 0.3f, 0.4f);
        set(rock, stone, 0.8, 0.35, -5.5, 0.35f, 0.3f, 0.35f);
        // Outside: lamp posts, orbs, a near subject and rocks
        for (int i = 0; i < POSTS.length; i++) {
            double[] p = POSTS[i];
            set(box, pole, p[0], 1.3, p[1], 0.05f, 1.3f, 0.05f);
            set(box, i == 0 ? firstBulb : bulb, p[0], 2.72, p[1], 0.12f, 0.12f, 0.12f);
        }
        set(rock, cyan, 1.1, 0.45, 9, 0.4f, 0.4f, 0.4f);
        set(rock, magenta, -0.9, 0.3, 14, 0.3f, 0.3f, 0.3f);
        set(rock, red, -0.7, 0.45, 3.2, 0.3f, 0.45f, 0.3f); // the near subject, 1.5 m from a camera at the door
        set(box, blue, 0.9, 0.75, 22, 0.75f, 0.75f, 0.75f); // the far one
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 0; i < 30; i++) {
            double x = random.nextDouble(-12, 12);
            double z = random.nextDouble(3, 30);
            float s = (float) random.nextDouble(0.15, 0.6);
            set(rock, stone, x, s * 0.5, z, s, s * 0.7f, s);
        }

        lights.directional(0, (float) -SUN[0], (float) -SUN[1], (float) -SUN[2], 30, 27.5f, 24);
        lights.shadow(0, ShadowSpec.cascades(4, 2048, 60));
        for (int i = 0; i < POSTS.length; i++) {
            lights.point(1 + i, POSTS[i][0], 2.6, POSTS[i][1], 9, LAMP[0], LAMP[1], LAMP[2]);
        }
        lights.point(1 + POSTS.length, 0, 2.6, -4.5, 8, 2, 1.6f, 1.1f); // the dim lamp inside
        lights.setCount(2 + POSTS.length);
        int sky = gpuTextures.add(TextureFormat.RGBA16F, 2048, 1024, Textures.GENERATE, sky(2048, 1024));
        environment = Environment.panorama("day sky", sky, 2048);
        look(0, 1.6, -4, 180, 0);
    }

    /** Adds a tile: a view of its own, with every post effect and {@code extra} (post materials) before the display. */
    Tile tile(String name, Technique... extra) {
        Tile tile = new Tile(name, List.of(extra));
        tiles.add(tile);
        return tile;
    }

    /** A file in the {@code film://} mount. */
    PostScene file(String path, byte[] bytes) {
        files.put(path, bytes);
        return this;
    }

    /** Waits until {@code held} hold loaded textures. */
    void load(PostTextures.Held... held) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        for (PostTextures.Held each : held) {
            while (each.slot() < 0) {
                tasks.drainCompleted();
                resources.flush();
                if (System.nanoTime() - deadline > 0) {
                    throw new IllegalStateException(each.ref() + " did not load: " + cache.state(each.ref()));
                }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
        }
    }

    /** Every tile's next frame, the clock moved on by {@code step} seconds first. */
    BufferedImage[] frame(double step) {
        seconds += step;
        tasks.drainCompleted();
        resources.flush();
        BufferedImage[] shots = new BufferedImage[tiles.size()];
        for (int i = 0; i < shots.length; i++) {
            Tile tile = tiles.get(i);
            tile.flash.advance((float) step);
            shots[i] = tile.frame();
        }
        return shots;
    }

    /**
     * The camera at ({@code x}, {@code y}, {@code z}), turned {@code yaw} degrees left of -z (180: looking along +z,
     * out of the door), {@code pitch} up.
     */
    void look(double x, double y, double z, double yaw, double pitch) {
        double a = Math.toRadians(yaw);
        double b = Math.toRadians(pitch);
        camera.position(x, y, z).set(-Math.sin(a) * Math.cos(b), Math.sin(b), -Math.cos(a) * Math.cos(b), 0, 1, 0,
            Math.toRadians(60), width, height, 0.1);
    }

    /** The camera at ({@code x}, {@code y}, {@code z}) looking at ({@code tx}, {@code ty}, {@code tz}), {@code fov} degrees high. */
    void lookAt(double x, double y, double z, double tx, double ty, double tz, double fov) {
        camera.position(x, y, z).set(tx - x, ty - y, tz - z, 0, 1, 0, Math.toRadians(fov), width, height, 0.1);
    }

    /** The first lamp's bulb and light at {@code scale} times their normal brightness. */
    void lamp(float scale) {
        materials.set(firstBulb, bulb(scale));
        lights.point(1, POSTS[0][0], 2.6, POSTS[0][1], 9, LAMP[0] * scale, LAMP[1] * scale, LAMP[2] * scale);
    }

    private int add(MeshData mesh) {
        try (mesh) {
            return pool.add(mesh);
        }
    }

    private void set(int mesh, int material, double x, double y, double z, float sx, float sy, float sz) {
        scene.set(nextId++, mesh, material, x, y, z, 0, 0, 0, 1, sx, sy, sz);
    }

    private static Material bulb(float scale) {
        return new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(0.1f, 0.1f, 0.1f, 1))
            .withEmissive(new Color(BULB[0] * scale, BULB[1] * scale, BULB[2] * scale, 1)));
    }

    private static double[] normalize(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / l, y / l, z / l};
    }

    /**
     * A panorama (the engine's mapping: u from atan2(z, x), v from the zenith) of a clear day: blue at the zenith to a
     * pale horizon, a dim ground, and the sun's disc of 1 degree at 3000 with a glow around it.
     */
    private static MemorySegment sky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double theta = Math.PI * (y + 0.5) / height;
            for (int x = 0; x < width; x++) {
                double phi = 2 * Math.PI * ((x + 0.5) / width - 0.5);
                double dx = Math.sin(theta) * Math.cos(phi);
                double dy = Math.cos(theta);
                double dz = Math.sin(theta) * Math.sin(phi);
                double up = Math.max(dy, 0);
                double h = Math.pow(1 - up, 3);
                double[] c = dy >= 0 ? new double[] {0.10 + 0.35 * h, 0.22 + 0.33 * h, 0.55 + 0.20 * h}
                    : new double[] {0.08, 0.075, 0.07};
                double cos = dx * SUN[0] + dy * SUN[1] + dz * SUN[2];
                double angle = Math.acos(Math.min(1, cos));
                double glow = 1.5 * Math.exp(-angle * angle / (2 * 0.08 * 0.08));
                double disc = angle < Math.toRadians(1) ? 3000 : 0;
                long at = 8L * (y * (long) width + x);
                double[] tint = {1, 0.95, 0.85};
                for (int k = 0; k < 3; k++) {
                    texels.set(half, at + 2L * k, Float.floatToFloat16((float) (c[k] + (glow + disc) * tint[k])));
                }
                texels.set(half, at + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    @Override
    public void close() {
        device.waitIdle();
        for (Tile tile : tiles) {
            tile.close();
        }
        resources.close();
        lights.close();
        gpuTextures.close();
        materials.close();
        scene.close();
        pool.close();
        cache.close();
        tasks.close();
        device.close();
        instance.close();
    }
}
