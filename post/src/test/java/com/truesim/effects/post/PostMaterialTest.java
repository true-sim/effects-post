package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.effects.post.materials.DamageParameters;
import com.truesim.effects.post.materials.HazeParameters;
import com.truesim.effects.post.materials.WipeParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.common.math.color.Color;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

class PostMaterialTest {

    private static final int SIZE = 64;
    private static final Key<Image> SHOWN = Key.image("test.shown");

    private PostFixture gpu;
    private final List<GpuImage> images = new ArrayList<>();

    @BeforeEach
    void start() {
        gpu = new PostFixture();
    }

    @AfterEach
    void stop() {
        gpu.device.waitIdle();
        for (GpuImage image : images) {
            image.close();
        }
        gpu.close();
    }

    /** A texture of {@code size}^2 texels, rgba of each from {@code texel}, resting readable; its sampled slot. */
    private int texture(int size, PostFixture.Pattern texel) {
        GpuImage image = new GpuImage(gpu.device, size, size, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        images.add(image);
        try (GpuBuffer staging = GpuBuffer.hostVisible(gpu.device, 8L * size * size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            float[] rgba = new float[4];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    rgba[3] = 1;
                    texel.rgb(x, y, size, size, rgba);
                    for (int c = 0; c < 4; c++) {
                        staging.memory().set(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * (y * size + x) + 2L * c,
                            Float.floatToFloat16(rgba[c]));
                    }
                }
            }
            gpu.device.submitAndWait(gpu.device.graphics(), commands -> {
                Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(size, size, 1);
                    vkCmdCopyBufferToImage(commands, staging.handle(), image.handle(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                }
                Images.transition(commands, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            });
        }
        return gpu.device.bindless().addSampled(image.view());
    }

    /** A gray scene, 0.5, as a ramp in red: red is x over the size. */
    private static void ramp(int x, int y, int w, int h, float[] out) {
        out[0] = (x + 0.5f) / SIZE;
        out[1] = 0.5f;
        out[2] = 0.5f;
    }

    private static float at(float[] rgba, int x, int y, int c) {
        return rgba[4 * (y * SIZE + x) + c];
    }

    private static PostMaterial wipe(String name, float r, float g, float b, float progress, int mask) {
        return new PostMaterial(name, "shaders/test/post/wipeEntry.spv", "wipeMain", Anchor.POST,
            WipeParameters.DEFAULTS.withColor(new Color(r, g, b, 1)).withProgress(progress).withSoftness(0f))
            .textures(mask);
    }

    /** A PNG of {@code size}^2 texels, gray from black at the left to white at the right. */
    private static byte[] gradient(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = Math.round((x + 0.5f) / size * 255);
                image.setRGB(x, y, 0xff000000 | v << 16 | v << 8 | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aMaskedTransitionCoversWhereTheMaskIsBelowItsProgress() throws IOException {
        // The mask an asset: a PNG through the asset cache, as data (linear)
        try (PostAssetsFixture assets = new PostAssetsFixture(gpu.device)) {
            assets.files.put("masks/wipe.png", gradient(SIZE));
            String path = "test://masks/wipe.png#linear";
            PostTextures.Held loading = assets.post.held().follow(path);
            assets.load(loading);
            PostMaterial wipe = new PostMaterial("test.wipe", "shaders/test/post/wipeEntry.spv", "wipeMain",
                Anchor.POST, WipeParameters.DEFAULTS.withColor(new Color(1, 0, 0, 1)).withProgress(0.5f)
                    .withSoftness(0f)).textures(assets.post, path);
            float[] out = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> port.add(wipe), 1);
            assertThat(at(out, 10, 30, 1)).as("covered: red").isZero();
            assertThat(at(out, 10, 30, 0)).isEqualTo(1);
            assertThat(at(out, 50, 30, 1)).as("not yet: the scene").isEqualTo(0.5f);
            assertThat(at(out, 50, 30, 0)).isCloseTo((50 + 0.5f) / SIZE, within(1e-3f));
            loading.release();
        }
    }

    @Test
    void materialsChainInTheirOrderAndSwitchOff() {
        int mask = texture(SIZE, (x, y, w, h, rgba) -> rgba[0] = 0.25f);
        PostMaterial red = wipe("test.red", 1, 0, 0, 1, mask);
        PostMaterial blue = wipe("test.blue", 0, 0, 1, 1, mask).before(red.id());
        // Added red first, but blue runs before it: red covers blue.
        float[] out = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> port.add(red).add(blue), 1);
        assertThat(at(out, 5, 5, 0)).isEqualTo(1);
        float[] reversed = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> port
            .add(wipe("test.red", 1, 0, 0, 1, mask)).add(wipe("test.blue", 0, 0, 1, 1, mask)), 1);
        assertThat(at(reversed, 5, 5, 2)).as("as added: blue last").isEqualTo(1);
        float[] off = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> {
            PostMaterial second = wipe("test.red", 1, 0, 0, 1, mask);
            second.slot().choose(graph.settings(), null);
            port.add(second);
        }, 1);
        assertThat(at(off, 5, 5, 1)).as("switched off: the scene, not an unwritten image").isEqualTo(0.5f);
        assertThat(at(off, 5, 5, 0)).isCloseTo((5 + 0.5f) / SIZE, within(1e-3f));
        assertThatIllegalArgumentException().isThrownBy(() -> red.parameters(HazeParameters.DEFAULTS));
    }

    @Test
    void anOverlayIsLaidOverTheDisplayColorAfterToneMapping() {
        // A damage vignette: red at the edges (alpha 1), clear in the middle (alpha 0)
        int vignette = texture(SIZE, (x, y, w, h, rgba) -> {
            double dx = (x + 0.5) / SIZE - 0.5;
            double dy = (y + 0.5) / SIZE - 0.5;
            rgba[0] = 1;
            rgba[1] = 1;
            rgba[2] = 1;
            rgba[3] = (float) Math.min(1, Math.sqrt(dx * dx + dy * dy) * 2);
        });
        PostMaterial damage = new PostMaterial("test.damage", "shaders/test/post/damageEntry.spv", "damageMain",
            Anchor.UI, DamageParameters.DEFAULTS.withStrength(0.5f)).textures(vignette);
        float[] out = gpu.render(SIZE, SIZE, (x, y, w, h, rgb) -> {
            rgb[0] = 0.4f;
            rgb[1] = 0.4f;
            rgb[2] = 0.4f;
        }, (graph, port) -> {
            // scRGB at 80 nits paper white, no curve: what is shown is the display color
            graph.settings().set(DisplayTransform.CURVE, "off")
                .set(DisplayTransform.PAPER_WHITE, 80f).set(DisplayTransform.DITHER, false);
            port.add(damage);
            for (var each : new DisplayTransform().displayEffects().transforms(Keys.SCENE_COLOR, SHOWN,
                DisplayEncoding.SCRGB_LINEAR)) {
                port.add(each);
            }
        }, SHOWN, Format.R16G16B16A16_SFLOAT, 1);
        // In the middle the scene; at a corner half way to red (alpha 1, strength 0.5)
        assertThat(at(out, 32, 32, 0)).isCloseTo(0.4f, within(0.02f));
        assertThat(at(out, 0, 0, 0)).isCloseTo(0.4f + (1 - 0.4f) * 0.5f, within(5e-3f));
        assertThat(at(out, 0, 0, 1)).isCloseTo(0.4f * 0.5f, within(5e-3f));
    }

    @Test
    void heatHazeReadsThroughTheNormalMapWhereTheSceneIsNear() {
        // A normal map leaning right everywhere: x 0.75 (slope 0.5), y 0.5 (none)
        int normals = texture(16, (x, y, w, h, rgba) -> {
            rgba[0] = 0.75f;
            rgba[1] = 0.5f;
        });
        float strength = 0.05f;
        PostMaterial haze = new PostMaterial("test.haze", "shaders/test/post/hazeEntry.spv", "hazeMain",
            Anchor.POST, HazeParameters.DEFAULTS.withStrength(strength).withSpeed(0f).withNear(0.5f))
            .textures(normals);
        // Near (depth 0.8) in the left half, far (0.2) in the right
        float[] out = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (x, y, w, h) -> x < SIZE / 2 ? 0.8f : 0.2f,
            (graph, port) -> port.add(haze), 1);
        // Shifted by slope times strength of the height (square: of the width)
        float shift = 0.5f * strength;
        assertThat(at(out, 10, 20, 0)).as("near: hazy").isCloseTo((10 + 0.5f) / SIZE + shift, within(2e-3f));
        assertThat(at(out, 50, 20, 0)).as("far: clear").isCloseTo((50 + 0.5f) / SIZE, within(1e-3f));
    }

    @Test
    void theHazeScrollsWithItsClock() {
        // A normal map whose slope changes along y: what a pixel reads moves as the map scrolls, by the clock's time
        int normals = texture(16, (x, y, w, h, rgba) -> {
            rgba[0] = (y + 0.5f) / 16;
            rgba[1] = 0.5f;
        });
        double[] now = {0};
        PostMaterial haze = new PostMaterial("test.haze", "shaders/test/post/hazeEntry.spv", "hazeMain", Anchor.POST,
            HazeParameters.DEFAULTS.withStrength(0.05f).withSpeed(0.25f).withTiling(1f), () -> now[0])
            .textures(normals);
        float[] still = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> port.add(haze), 1);
        now[0] = 1; // a quarter of the map further on
        float[] moved = gpu.render(SIZE, SIZE, PostMaterialTest::ramp, (graph, port) -> port.add(haze), 1);
        assertThat(at(moved, 20, 10, 0)).as("scrolled").isNotCloseTo(at(still, 20, 10, 0), within(1e-3f));
        assertThat(at(moved, 20, 10, 0)).as("as the map a quarter down shows").isCloseTo(at(still, 20, 26, 0),
            within(2e-3f));
    }

    @Test
    void eachMaterialIsASlotOfItsOwn() {
        PostMaterial red = wipe("test.red", 1, 0, 0, 1, 0);
        EngineSettings settings = new EngineSettings();
        assertThat(red.slot().name()).isEqualTo("test.red");
        assertThat(red.slot().chosen(settings)).as("on by default").isSameAs(red.on());
        SettingRegistry registry = new SettingRegistry().techniques(settings.techniques());
        red.register(registry);
        assertThat(registry.all()).contains(red.slot().setting());
        assertThatIllegalArgumentException().isThrownBy(() -> red.textures(1, 2, 3, 4, 5));
    }
}
