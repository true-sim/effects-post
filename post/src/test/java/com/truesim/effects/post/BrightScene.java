package com.truesim.effects.post;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Image;
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
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ShadowSpec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.SplittableRandom;

/**
 * A bright scene at dusk for bloom's pictures, films and benchmark, drawn by the engine's renderer straight (no scene
 * graph: a test's fixture): a low sun in a sky with a sun disc, its cascaded shadows, three lamp posts with glowing
 * bulbs and their point lights, two glowing orbs and rocks on a ground plane, shaded by clustered forward shading into
 * 16-bit floats, then {@link Bloom} at {@code Anchor.POST} and the engine's display transform (AgX where the engine has
 * it) into an sRGB target.
 */
final class BrightScene implements AutoCloseable {

    /** Towards the sun: low over the horizon, ahead of the camera. */
    private static final double[] SUN = normalize(0.25, 0.10, -1);
    private static final Key<Image> SHOWN = Key.image("test.shown");

    final VulkanInstance instance;
    final VulkanDevice device;
    final FrameGraph graph;
    final TestTarget target;
    final ViewPort port;
    private final GeometryPool pool;
    private final GpuScene scene;
    private final MeshletRenderer renderer;
    private final Materials materials;
    private final Textures textures;
    private final Lights lights;
    private final Shadows shadows;
    private final Camera camera;
    private final SceneView view;
    private final int width;
    private final int height;
    private final int firstBulb;
    private static final double[][] POSTS = {{-2.6, -3}, {2.8, -7}, {-2.2, -12}};
    private static final float[] BULB = {24, 14, 5};
    private static final float[] LAMP = {9, 5.2f, 1.8f};

    BrightScene(int width, int height) {
        instance = VulkanInstance.builder().applicationName("bloom pictures").surfaces(false).build();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        this.width = width;
        this.height = height;
        System.out.println("bloom on " + instance.bestGpu().name() + ", " + width + " x " + height);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 18, 8192, 8));
        scene = new GpuScene(device, pool, 1024);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        materials = new Materials(device, 32);
        textures = new Textures(device);
        lights = new Lights(device, 16, 4);
        graph = new FrameGraph(device, 2);
        target = new TestTarget(device, width, height, Format.R8G8B8A8_SRGB);
        scene.setMaterials(materials);

        int box = add(TestMeshes.box());
        int rock = add(TestMeshes.rock(3));
        MetallicRoughnessParameters mr = MetallicRoughnessParameters.DEFAULTS;
        int ground = materials.add(new Material(mr.withBaseColor(new Color(0.32f, 0.30f, 0.27f, 1)).withRoughness(0.8f)
            .withMetallic(0)));
        int stone = materials.add(new Material(mr.withBaseColor(new Color(0.45f, 0.42f, 0.40f, 1)).withRoughness(0.6f)
            .withMetallic(0)));
        int pole = materials.add(new Material(mr.withBaseColor(new Color(0.08f, 0.08f, 0.09f, 1)).withRoughness(0.35f)
            .withMetallic(1)));
        firstBulb = materials.add(bulb(1));
        int bulb = materials.add(bulb(1));
        int cyan = materials.add(new Material(mr.withBaseColor(new Color(0.05f, 0.05f, 0.05f, 1))
            .withEmissive(new Color(2, 10, 16, 1))));
        int magenta = materials.add(new Material(mr.withBaseColor(new Color(0.05f, 0.05f, 0.05f, 1))
            .withEmissive(new Color(14, 1.5f, 8, 1))));
        int id = 0;
        scene.set(id++, box, ground, 0, -0.05, -10, 0, 0, 0, 1, 300, 0.05f, 300);
        double[][] posts = POSTS;
        for (int i = 0; i < posts.length; i++) {
            double[] p = posts[i];
            scene.set(id++, box, pole, p[0], 1.3, p[1], 0, 0, 0, 1, 0.05f, 1.3f, 0.05f);
            scene.set(id++, box, i == 0 ? firstBulb : bulb, p[0], 2.72, p[1], 0, 0, 0, 1, 0.12f, 0.12f, 0.12f);
        }
        scene.set(id++, rock, cyan, 1.1, 0.45, -4.2, 0, 0, 0, 1, 0.4f, 0.4f, 0.4f);
        scene.set(id++, rock, magenta, -0.9, 0.3, -6.5, 0, 0, 0, 1, 0.3f, 0.3f, 0.3f);
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 0; i < 24; i++) {
            double x = random.nextDouble(-9, 9);
            double z = random.nextDouble(-22, -2);
            float s = (float) random.nextDouble(0.15, 0.6);
            scene.set(id++, rock, stone, x, s * 0.5, z, 0, 0, 0, 1, s, s * 0.7f, s);
        }

        lights.directional(0, (float) -SUN[0], (float) -SUN[1], (float) -SUN[2], 1.8f, 1.0f, 0.45f); // dusk
        lights.shadow(0, ShadowSpec.cascades(3, 2048, 60));
        for (int i = 0; i < posts.length; i++) {
            lights.point(1 + i, posts[i][0], 2.6, posts[i][1], 9, LAMP[0], LAMP[1], LAMP[2]);
        }
        lights.setCount(1 + posts.length);
        int sky = textures.add(TextureFormat.RGBA16F, 2048, 1024, Textures.GENERATE, sky(2048, 1024));
        Environment environment = Environment.panorama("bright sky", sky, 2048);

        camera = new Camera();
        look(0, 1.5, 3, 0, 4);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 20));
        view.set(1, MeshletRenderer.MODE_LIT);
        shadows = new Shadows(geometry, lights);
        graph.settings().set(DisplayTransform.CURVE, "agx"); // the engine's AgX where it has it (engine#449)
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 0))
            .add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view))
            .add(new ClusteredLights(lights, view)).add(shadows.view(view)).add(new Bloom())
            .add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB))
            .output(SHOWN, target.output());
    }

    private int add(MeshData mesh) {
        try (mesh) {
            return pool.add(mesh);
        }
    }

    /** {@code count} frames, the last one read back. */
    void frames(int count) {
        for (int i = 0; i < count; i++) {
            view.set(1, MeshletRenderer.MODE_LIT);
            target.frame(graph);
        }
    }

    /** The camera at ({@code x}, {@code y}, {@code z}), turned {@code yaw} degrees left of -z, {@code pitch} up. */
    void look(double x, double y, double z, double yaw, double pitch) {
        double a = Math.toRadians(yaw);
        double b = Math.toRadians(pitch);
        camera.position(x, y, z).set(-Math.sin(a) * Math.cos(b), Math.sin(b), -Math.cos(a) * Math.cos(b), 0, 1, 0,
            Math.toRadians(60), width, height, 0.1);
    }

    /** The first lamp's bulb and light at {@code scale} times their normal brightness. */
    void lamp(float scale) {
        materials.set(firstBulb, bulb(scale));
        lights.point(1, POSTS[0][0], 2.6, POSTS[0][1], 9, LAMP[0] * scale, LAMP[1] * scale, LAMP[2] * scale);
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
     * A panorama (the engine's mapping: u from atan2(z, x), v from the zenith) of dusk: deep blue at the zenith
     * to a warm horizon, a dim ground, and the sun's disc of 1.5 degrees at 500 with a glow around it.
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
                double[] c = dy >= 0
                    ? new double[] {0.03 + 0.35 * Math.pow(1 - up, 4), 0.05 + 0.18 * Math.pow(1 - up, 4),
                        0.12 + 0.06 * Math.pow(1 - up, 4)}
                    : new double[] {0.02, 0.018, 0.016};
                double cos = dx * SUN[0] + dy * SUN[1] + dz * SUN[2];
                double angle = Math.acos(Math.min(1, cos));
                double glow = 2 * Math.exp(-angle * angle / (2 * 0.06 * 0.06));
                double disc = angle < Math.toRadians(1.5) ? 500 : 0;
                long at = 8L * (y * (long) width + x);
                double[] tint = {1, 0.85, 0.65};
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
        graph.close();
        shadows.close();
        target.close();
        lights.close();
        textures.close();
        materials.close();
        renderer.close();
        scene.close();
        pool.close();
        device.close();
        instance.close();
    }
}
