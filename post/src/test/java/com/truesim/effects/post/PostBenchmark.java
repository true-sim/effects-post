package com.truesim.effects.post;

import com.truesim.common.math.color.Color;
import com.truesim.effects.post.materials.HazeParameters;
import com.truesim.effects.post.materials.WipeParameters;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.settings.EngineSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The post chain's benchmark over the {@link PostScene} (the house in the sun, every effect but the one measured off):
 * each technique's own passes (their GPU timestamps summed, by name) and the frame's GPU span, median of 300 frames
 * after 120, at 1920 x 1080 and 3840 x 2160.
 *
 * <p>{@code TRUESIM_BENCHMARK=true ./gradlew :effects-post:test --tests '*PostBenchmark'}
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class PostBenchmark {

    /** A technique as measured: its name, the prefix of its passes' names, how it is chosen. */
    private record Variant(String name, String passes, Consumer<EngineSettings> choose) {
    }

    private static final String WIPE = "bench.wipe";
    private static final String HAZE = "bench.haze";

    private static List<Variant> variants(PostMaterial wipe, PostMaterial haze) {
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("off (no post effect)", "-", s -> { }));
        v.add(new Variant("exposure: off (display.exposure)", "exposure", s -> { }));
        v.add(new Variant("exposure: histogram", "exposure", s -> AutoExposure.SLOT.choose(s,
            AutoExposure.HISTOGRAM)));
        v.add(new Variant("exposure: average", "exposure", s -> AutoExposure.SLOT.choose(s, AutoExposure.AVERAGE)));
        v.add(new Variant("grading: baked (32)", "color grading", s -> ColorGrading.SLOT.choose(s,
            ColorGrading.BAKED)));
        v.add(new Variant("grading: baked (64)", "color grading", s -> {
            ColorGrading.SLOT.choose(s, ColorGrading.BAKED);
            s.set(ColorGrading.BAKED.setting("tableSize"), 64);
        }));
        v.add(new Variant("grading: direct", "color grading", s -> ColorGrading.SLOT.choose(s, ColorGrading.DIRECT)));
        v.add(new Variant("grading: direct, .cube table", "color grading", s -> {
            ColorGrading.SLOT.choose(s, ColorGrading.DIRECT);
            s.set(ColorGrading.DIRECT.setting("table"), "film://grades/bench.cube");
        }));
        v.add(new Variant("dof: gather (radius 24)", "depth of field", s -> DepthOfField.SLOT.choose(s,
            DepthOfField.GATHER)));
        v.add(new Variant("dof: gather (radius 48)", "depth of field", s -> {
            DepthOfField.SLOT.choose(s, DepthOfField.GATHER);
            s.set(DepthOfField.GATHER.setting("maxRadius"), 48f);
        }));
        v.add(new Variant("dof: gaussian (radius 24)", "depth of field", s -> DepthOfField.SLOT.choose(s,
            DepthOfField.GAUSSIAN)));
        v.add(new Variant("lens: distortion", "lens", s -> LensEffects.DISTORTION.choose(s,
            LensEffects.BROWN_CONRADY)));
        v.add(new Variant("lens: aberration radial", "lens", s -> LensEffects.ABERRATION.choose(s,
            LensEffects.RADIAL)));
        v.add(new Variant("lens: aberration spectral", "lens", s -> LensEffects.ABERRATION.choose(s,
            LensEffects.SPECTRAL)));
        v.add(new Variant("lens: vignette natural", "lens", s -> LensEffects.VIGNETTE.choose(s,
            LensEffects.NATURAL)));
        v.add(new Variant("lens: all three (spectral, artistic)", "lens", s -> {
            LensEffects.DISTORTION.choose(s, LensEffects.BROWN_CONRADY);
            LensEffects.ABERRATION.choose(s, LensEffects.SPECTRAL);
            LensEffects.VIGNETTE.choose(s, LensEffects.ARTISTIC);
        }));
        v.add(new Variant("flash: display", "screen flash", s -> ScreenFlash.SLOT.choose(s, ScreenFlash.DISPLAY)));
        v.add(new Variant("flash: scene", "screen flash", s -> ScreenFlash.SLOT.choose(s, ScreenFlash.SCENE)));
        v.add(new Variant("grain: film", "film grain", s -> FilmGrain.SLOT.choose(s, FilmGrain.FILM)));
        v.add(new Variant("material: wipe", WIPE, s -> wipe.slot().choose(s, wipe.on())));
        v.add(new Variant("material: heat haze", HAZE, s -> haze.slot().choose(s, haze.on())));
        return v;
    }

    /** Every effect off: bloom, exposure (its fill stays), grading, depth of field, lens, flash, grain, materials. */
    private static void allOff(EngineSettings s, PostMaterial wipe, PostMaterial haze) {
        Bloom.SLOT.choose(s, null);
        AutoExposure.SLOT.choose(s, null);
        ColorGrading.SLOT.choose(s, null);
        DepthOfField.SLOT.choose(s, null);
        LensEffects.DISTORTION.choose(s, null);
        LensEffects.ABERRATION.choose(s, null);
        LensEffects.VIGNETTE.choose(s, null);
        ScreenFlash.SLOT.choose(s, null);
        FilmGrain.SLOT.choose(s, null);
        wipe.slot().choose(s, null);
        haze.slot().choose(s, null);
    }

    @Test
    void benchmark() {
        int[][] sizes = {{1920, 1080}, {3840, 2160}};
        List<String> names = new ArrayList<>();
        List<double[][]> results = new ArrayList<>();
        for (int s = 0; s < sizes.length; s++) {
            try (PostScene scene = new PostScene(sizes[s][0], sizes[s][1])) {
                scene.file("grades/bench.cube", ColorLut.identity(33).toCube().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
                scene.file("textures/bench.png", png());
                PostTextures.Held table = scene.textures.held().follow("film://grades/bench.cube");
                PostTextures.Held texture = scene.textures.held().follow("film://textures/bench.png#linear");
                scene.load(table, texture);
                PostMaterial wipe = new PostMaterial(WIPE, "shaders/test/post/wipeEntry.spv", "wipeMain", Anchor.POST,
                    WipeParameters.DEFAULTS.withColor(new Color(0, 0, 0, 1)).withProgress(0.5f), () -> scene.seconds)
                    .textures(texture.slot());
                PostMaterial haze = new PostMaterial(HAZE, "shaders/test/post/hazeEntry.spv", "hazeMain",
                    Anchor.POST, HazeParameters.DEFAULTS.withNear(0.004f), () -> scene.seconds).textures(texture.slot());
                PostScene.Tile tile = scene.tile("bench", wipe, haze);
                tile.graph.timings(true);
                scene.lookAt(1.5, 1.6, 9, -0.5, 1.4, 0, 60);
                List<Variant> variants = variants(wipe, haze);
                for (int v = 0; v < variants.size(); v++) {
                    allOff(tile.settings(), wipe, haze);
                    variants.get(v).choose().accept(tile.settings());
                    double[] measured = measure(scene, tile, variants.get(v).passes(), 120, 300);
                    if (s == 0) {
                        names.add(variants.get(v).name());
                        results.add(new double[2][]);
                    }
                    results.get(v)[s] = measured;
                    System.out.printf(Locale.ROOT, "%d x %d %s: %.3f ms, frame %.3f ms%n", sizes[s][0], sizes[s][1],
                        variants.get(v).name(), measured[0], measured[1]);
                }
                table.release();
                texture.release();
            }
        }
        StringBuilder out = new StringBuilder("| Technique | 1920 x 1080: own ms | frame ms | 3840 x 2160: own ms "
            + "| frame ms |\n|---|---|---|---|---|\n");
        for (int v = 0; v < names.size(); v++) {
            double[][] r = results.get(v);
            out.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n", names.get(v), r[0][0],
                r[0][1], r[1][0], r[1][1]));
        }
        System.out.println("POST BENCHMARK\n" + out);
    }

    /** A 256 x 256 PNG of gray noise: the materials' mask and normal map. */
    private static byte[] png() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(256, 256,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.util.SplittableRandom random = new java.util.SplittableRandom(5);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int g = random.nextInt(256);
                image.setRGB(x, y, 0xff000000 | g << 16 | g << 8 | g);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The median GPU time of the passes named from {@code prefix} and of the frame, over {@code frames} after {@code warm}. */
    private static double[] measure(PostScene scene, PostScene.Tile tile, String prefix, int warm, int frames) {
        for (int i = 0; i < warm; i++) {
            scene.frame(1 / 60.0);
        }
        double[] own = new double[frames];
        double[] total = new double[frames];
        for (int i = 0; i < frames; i++) {
            scene.frame(1 / 60.0);
            GpuTimings t = tile.graph.timings();
            double sum = 0;
            for (int p = 0; p < t.count(); p++) {
                if (t.pass(p).startsWith(prefix) && !Double.isNaN(t.millis(p))) {
                    sum += t.millis(p);
                }
            }
            own[i] = sum;
            total[i] = t.spanMillis();
        }
        return new double[] {median(own), median(total)};
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
