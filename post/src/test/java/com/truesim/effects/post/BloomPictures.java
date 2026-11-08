package com.truesim.effects.post;

import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.TechniqueKind;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Bloom's pictures and benchmark over the {@link BrightScene}: each technique and off.
 *
 * <ul>
 *   <li>{@code TRUESIM_SCREENSHOTS=true ./gradlew :effects-post:test --tests '*BloomPictures.pictures'}: one PNG per
 *       technique and a sheet of all side by side, into {@code build/screenshots/bloom} (or
 *       {@code TRUESIM_BLOOM_SHOTS}).</li>
 *   <li>{@code TRUESIM_BENCHMARK=true ./gradlew :effects-post:test --tests '*BloomPictures.benchmark'}: the GPU time
 *       of bloom's passes and of the whole frame, median of 300 frames after 60, at 1920 x 1080 and 3840 x 2160.</li>
 * </ul>
 */
class BloomPictures {

    /** A technique as pictured and measured: its name, how it is chosen and tuned. */
    private record Variant(String name, Consumer<EngineSettings> choose) {
    }

    private static Variant kind(String name, TechniqueKind<?, Bloom, Technique> kind,
        Consumer<EngineSettings> tune) {
        return new Variant(name, s -> {
            Bloom.SLOT.choose(s, kind);
            tune.accept(s);
        });
    }

    private static final List<Variant> VARIANTS = List.of(
        new Variant("off", s -> Bloom.SLOT.choose(s, null)),
        kind("physical", Bloom.PHYSICAL, s -> { }),
        kind("dual_kawase", Bloom.DUAL_KAWASE, s -> { }),
        kind("gaussian", Bloom.GAUSSIAN, s -> { }),
        kind("fft", Bloom.FFT, s -> { }),
        kind("fft 1024", Bloom.FFT, s -> s.set(Bloom.FFT.<Integer>setting("size"), 1024)),
        kind("fft + chain", Bloom.FFT, s -> s.set(Bloom.FFT.<Float>setting("chain"), 0.5f)));

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
    void pictures() throws IOException {
        String dir = System.getenv("TRUESIM_BLOOM_SHOTS");
        Path out = Files.createDirectories(dir == null || dir.isBlank() ? Path.of("build", "screenshots", "bloom")
            : Path.of(dir));
        int width = 1280;
        int height = 720;
        List<BufferedImage> shots = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (BrightScene scene = new BrightScene(width, height)) {
            for (Variant variant : VARIANTS) {
                if (variant.name().equals("fft 1024")) {
                    continue; // as fft, at a finer FFT: the same picture
                }
                variant.choose().accept(scene.graph.settings());
                scene.frames(12); // culling and shadows settle
                BufferedImage shot = scene.target.shot();
                ImageIO.write(shot, "png", out.resolve("bloom-" + variant.name().replace(' ', '-')
                    .replace("+", "plus") + ".png").toFile());
                shots.add(shot);
                names.add(variant.name());
                // and with a stronger bloom, to see its shape
                if (!variant.name().equals("off")) {
                    scene.graph.settings().set(intensity(variant), 0.25f);
                    scene.frames(2);
                    ImageIO.write(scene.target.shot(), "png", out.resolve("bloom-" + variant.name().replace(' ', '-')
                        .replace("+", "plus") + "-strong.png").toFile());
                    scene.graph.settings().set(intensity(variant), 0.05f); // the default again
                }
            }
        }
        // The sheet: two columns, each picture at half size, its name on it
        int w = width / 2;
        int h = height / 2;
        int rows = (shots.size() + 1) / 2;
        BufferedImage sheet = new BufferedImage(2 * w, rows * h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(g.getFont().deriveFont(20f));
        for (int i = 0; i < shots.size(); i++) {
            int x = (i % 2) * w;
            int y = (i / 2) * h;
            g.drawImage(shots.get(i), x, y, w, h, null);
            g.setColor(java.awt.Color.BLACK);
            g.drawString(names.get(i), x + 11, y + 27);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(names.get(i), x + 10, y + 26);
        }
        g.dispose();
        ImageIO.write(sheet, "png", out.resolve("bloom-sheet.png").toFile());
        System.out.println("bloom pictures in " + out.toAbsolutePath());
    }

    /** The intensity setting of {@code variant}'s kind. */
    private static com.truesim.engine.settings.Setting<Float> intensity(Variant variant) {
        return (variant.name().startsWith("fft") ? Bloom.FFT : variant.name().equals("physical") ? Bloom.PHYSICAL
            : variant.name().equals("dual_kawase") ? Bloom.DUAL_KAWASE : Bloom.GAUSSIAN).setting("intensity");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
    void benchmark() {
        StringBuilder table = new StringBuilder();
        table.append("| technique | 1920 x 1080: bloom ms | frame ms | 3840 x 2160: bloom ms | frame ms |\n");
        table.append("|---|---|---|---|---|\n");
        double[][][] results = new double[VARIANTS.size()][2][];
        int[][] sizes = {{1920, 1080}, {3840, 2160}};
        for (int s = 0; s < sizes.length; s++) {
            try (BrightScene scene = new BrightScene(sizes[s][0], sizes[s][1])) {
                scene.graph.timings(true);
                for (int v = 0; v < VARIANTS.size(); v++) {
                    VARIANTS.get(v).choose().accept(scene.graph.settings());
                    results[v][s] = measure(scene, 60, 300);
                }
            }
        }
        for (int v = 0; v < VARIANTS.size(); v++) {
            table.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n", VARIANTS.get(v).name(),
                results[v][0][0], results[v][0][1], results[v][1][0], results[v][1][1]));
        }
        System.out.println("BLOOM BENCHMARK\n" + table);
    }

    /** The median GPU time of bloom's passes and of the frame, over {@code frames} frames after {@code warm}. */
    private static double[] measure(BrightScene scene, int warm, int frames) {
        for (int i = 0; i < warm; i++) {
            scene.target.frame(scene.graph);
        }
        double[] bloom = new double[frames];
        double[] total = new double[frames];
        for (int i = 0; i < frames; i++) {
            scene.target.frame(scene.graph);
            GpuTimings t = scene.graph.timings();
            double sum = 0;
            for (int p = 0; p < t.count(); p++) {
                if (t.pass(p).startsWith("bloom") && !Double.isNaN(t.millis(p))) {
                    sum += t.millis(p);
                }
            }
            bloom[i] = sum;
            total[i] = t.spanMillis();
        }
        return new double[] {median(bloom), median(total)};
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
