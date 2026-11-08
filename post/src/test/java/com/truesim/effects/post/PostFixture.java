package com.truesim.effects.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalBuffer;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A GPU, validation on, for post effect tests: a view whose scene color is a pattern computed on the CPU (each frame,
 * knowing the render region), techniques added to it, and an output read back.
 */
final class PostFixture implements AutoCloseable {

    /** A scene color's texel at ({@code x}, {@code y}), the render region being {@code width} x {@code height}. */
    @FunctionalInterface
    interface Pattern {
        void rgb(int x, int y, int width, int height, float[] out);
    }

    /** A scene depth's texel at ({@code x}, {@code y}): reverse depth, near plane over distance. */
    @FunctionalInterface
    interface Depth {
        float at(int x, int y, int width, int height);
    }

    final VulkanInstance instance;
    final VulkanDevice device;

    PostFixture() {
        VulkanInstance made = null;
        try {
            made = VulkanInstance.builder().applicationName("tests").validation(true).synchronizationValidation(true)
                .surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
        }
        instance = made;
        if (instance.gpus().isEmpty()) {
            instance.close();
            assumeThat(false).as("no Vulkan 1.3 GPU").isTrue();
        }
        device = VulkanDevice.createHeadless(instance.bestGpu());
    }

    /** The scene color: the pattern, uploaded each frame into an image of the view's (allocated) size. */
    private record Scene(Pattern pattern, ExternalBuffer upload, GpuBuffer memory) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public void build(GraphBuilder g) {
            ViewPort view = g.view();
            Image image = g.create("pattern", ImageDesc.of(Format.R16G16B16A16_SFLOAT, view.width(), view.height()));
            com.truesim.engine.graph.Buffer buffer = g.importBuffer(upload);
            g.pass("pattern").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> {
                    int w = image.width();
                    int h = image.height();
                    int rw = ctx.regionWidth(image);
                    int rh = ctx.regionHeight(image);
                    float[] rgb = new float[3];
                    MemorySegment m = memory.memory();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            pattern.rgb(x, y, rw, rh, rgb);
                            long at = 8L * (y * (long) w + x);
                            for (int c = 0; c < 3; c++) {
                                m.set(ValueLayout.JAVA_SHORT_UNALIGNED, at + 2L * c, Float.floatToFloat16(rgb[c]));
                            }
                            m.set(ValueLayout.JAVA_SHORT_UNALIGNED, at + 6, Float.floatToFloat16(1));
                        }
                    }
                    ctx.copy(buffer, 0, image.whole());
                });
            g.provide(Keys.SCENE_COLOR, image);
        }
    }

    /**
     * Renders {@code frames} frames of a view of {@code width} x {@code height} showing {@code pattern}, with what
     * {@code setup} adds to the view and sets, and returns {@code output} of the last, read back from an image of
     * {@code format} (16-bit floats or 8 bits a channel), as floats: 4 per pixel, row by row.
     */
    float[] render(int width, int height, Pattern pattern, BiConsumer<FrameGraph, ViewPort> setup, Key<Image> output,
        Format format, int frames) {
        int texel = format == Format.R16G16B16A16_SFLOAT ? 8 : 4;
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        long most = 4L * width * height * 8; // room for a render scale above 1 or headroom
        try (GpuImage target = new GpuImage(device, width, height, format.vk(), usage, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, (long) width * height * texel,
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             GpuBuffer upload = GpuBuffer.hostVisible(device, most, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             FrameGraph graph = new FrameGraph(device, 2)) {
            ExternalImage out = new ExternalImage("output", width, height, format, usage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            ViewPort port = graph.addView("main").size(width, height);
            port.add(new Scene(pattern, new ExternalBuffer("pattern upload", upload), upload));
            setup.accept(graph, port);
            port.output(output, out);
            for (int frame = 0; frame < frames; frame++) {
                boolean last = frame == frames - 1;
                device.submitAndWait(device.graphics(), commands -> {
                    graph.record(commands);
                    if (last) {
                        try (MemoryStack stack = stackPush()) {
                            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                            region.imageExtent().set(width, height, 1);
                            vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                pixels.handle(), region);
                        }
                    }
                });
            }
            float[] result = new float[width * height * 4];
            for (int i = 0; i < result.length; i++) {
                result[i] = texel == 8 ? Float.float16ToFloat(pixels.memory().get(ValueLayout.JAVA_SHORT_UNALIGNED, 2L * i))
                    : (pixels.memory().get(ValueLayout.JAVA_BYTE, i) & 0xff) / 255f;
            }
            return result;
        }
    }

    /** The scene depth: a pattern uploaded each frame, in 32-bit floats (sampled as a depth image would be). */
    private record SceneDepth(Depth pattern, ExternalBuffer upload, GpuBuffer memory) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(Keys.SCENE_DEPTH);
        }

        @Override
        public void build(GraphBuilder g) {
            ViewPort view = g.view();
            Image image = g.create("depth pattern", ImageDesc.of(Format.R32_SFLOAT, view.width(), view.height()));
            com.truesim.engine.graph.Buffer buffer = g.importBuffer(upload);
            g.pass("depth pattern").read(buffer, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .record(ctx -> {
                    int w = image.width();
                    int rw = ctx.regionWidth(image);
                    int rh = ctx.regionHeight(image);
                    for (int y = 0; y < image.height(); y++) {
                        for (int x = 0; x < w; x++) {
                            memory.memory().set(ValueLayout.JAVA_FLOAT_UNALIGNED, 4L * (y * (long) w + x),
                                pattern.at(x, y, rw, rh));
                        }
                    }
                    ctx.copy(buffer, 0, image.whole());
                });
            g.provide(Keys.SCENE_DEPTH, image);
        }
    }

    /** {@link #render} of the scene color over {@code depth}, in 16-bit floats. */
    float[] render(int width, int height, Pattern pattern, Depth depth, BiConsumer<FrameGraph, ViewPort> setup,
        int frames) {
        try (GpuBuffer upload = GpuBuffer.hostVisible(device, 4L * width * height * 4,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            return render(width, height, pattern, (graph, port) -> {
                port.add(new SceneDepth(depth, new ExternalBuffer("depth upload", upload), upload));
                setup.accept(graph, port);
            }, Keys.SCENE_COLOR, Format.R16G16B16A16_SFLOAT, frames);
        }
    }

    /** {@link #render} of the scene color, in 16-bit floats. */
    float[] render(int width, int height, Pattern pattern, BiConsumer<FrameGraph, ViewPort> setup, int frames) {
        return render(width, height, pattern, setup, Keys.SCENE_COLOR, Format.R16G16B16A16_SFLOAT, frames);
    }

    @Override
    public void close() {
        device.waitIdle();
        int errors = instance.validationErrors();
        device.close();
        instance.close();
        assertThat(errors).as("validation errors").isZero();
    }
}
