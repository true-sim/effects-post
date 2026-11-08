package com.truesim.effects.post;

import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of bloom over the {@link BrightScene}, as PNG frames at 30 per second in grids of 640 x 360 tiles (1280 x 720),
 * each tile one technique, into {@code build/films/bloom} (or {@code TRUESIM_BLOOM_FILMS}), one folder per film:
 *
 * <ul>
 *   <li>{@code pan-chains}: the camera turning past the lamps, the orbs and the sun, off, physical, dual Kawase and
 *       Gaussian;</li>
 *   <li>{@code pan-fft}: the same, off, the convolution, the convolution with the chain, and physical at 0.25;</li>
 *   <li>{@code ramp}: a lamp brightening from a hundredth to 50 times its brightness, off, physical, physical with a
 *       threshold of 1, the convolution;</li>
 *   <li>{@code curves}: the engine's tone curves in a 3 x 3 grid (426 x 240 tiles) over the scene with physical bloom,
 *       the exposure sweeping from 1/8 to 8.</li>
 * </ul>
 *
 * <p>Run with {@code TRUESIM_SCREENSHOTS=true ./gradlew :effects-post:test --tests '*BloomFilms'}; encode each with
 * {@code ffmpeg -framerate 30 -i <film>/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 20 <film>.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class BloomFilms {

    private static final int FRAMES = 180;

    /** A tile's technique: its label and how it is chosen. */
    private record Tile(String label, Consumer<EngineSettings> choose) {
    }

    private static <V> Consumer<EngineSettings> set(Setting<V> setting, V value) {
        return s -> s.set(setting, value);
    }

    private static final Tile OFF = new Tile("off", s -> Bloom.SLOT.choose(s, null));
    private static final Tile PHYSICAL = new Tile("physical", s -> Bloom.SLOT.choose(s, Bloom.PHYSICAL));
    private static final Tile DUAL_KAWASE = new Tile("dual_kawase", s -> Bloom.SLOT.choose(s, Bloom.DUAL_KAWASE));
    private static final Tile GAUSSIAN = new Tile("gaussian", s -> Bloom.SLOT.choose(s, Bloom.GAUSSIAN));
    private static final Tile FFT = new Tile("fft", s -> {
        Bloom.SLOT.choose(s, Bloom.FFT);
        s.set(Bloom.FFT.<Float>setting("chain"), 0f);
    });
    private static final Tile FFT_CHAIN = new Tile("fft + chain 0.5", s -> {
        Bloom.SLOT.choose(s, Bloom.FFT);
        s.set(Bloom.FFT.<Float>setting("chain"), 0.5f);
    });
    private static final Tile STRONG = new Tile("physical, intensity 0.25", s -> {
        Bloom.SLOT.choose(s, Bloom.PHYSICAL);
        s.set(Bloom.PHYSICAL.<Float>setting("intensity"), 0.25f);
    });
    private static final Tile THRESHOLD = new Tile("physical, threshold 1", s -> {
        Bloom.SLOT.choose(s, Bloom.PHYSICAL);
        s.set(Bloom.PHYSICAL.<Float>setting("threshold"), 1f);
    });

    private static Path out(String film) throws IOException {
        String dir = System.getenv("TRUESIM_BLOOM_FILMS");
        Path root = dir == null || dir.isBlank() ? Path.of("build", "films", "bloom") : Path.of(dir);
        return Files.createDirectories(root.resolve(film));
    }

    @Test
    void films() throws IOException {
        try (BrightScene scene = new BrightScene(640, 360)) {
            pan(scene, "pan-chains", List.of(OFF, PHYSICAL, DUAL_KAWASE, GAUSSIAN));
            pan(scene, "pan-fft", List.of(OFF, FFT, FFT_CHAIN, STRONG));
            ramp(scene, List.of(OFF, PHYSICAL, THRESHOLD, FFT));
        }
        try (BrightScene scene = new BrightScene(426, 240)) {
            curves(scene);
        }
    }

    /** The camera turning from 40 degrees left to 40 right, moving forward, each frame for each tile. */
    private static void pan(BrightScene scene, String film, List<Tile> tiles) throws IOException {
        Path out = out(film);
        BufferedImage[] shots = new BufferedImage[tiles.size()];
        for (int f = 0; f < FRAMES; f++) {
            double t = f / (FRAMES - 1.0);
            double eased = 0.5 - 0.5 * Math.cos(Math.PI * t);
            scene.look(0.4 - 0.6 * eased, 1.4, 3 - 4 * eased, 40 - 80 * eased, 6);
            for (int i = 0; i < tiles.size(); i++) {
                shots[i] = render(scene, tiles.get(i), 2);
            }
            write(grid(shots, tiles, 2, 640, 360, null), out, f);
        }
        System.out.println(film + ": " + FRAMES + " frames in " + out.toAbsolutePath());
    }

    /** A lamp brightening exponentially from 0.01 to 50 times its brightness, the camera still. */
    private static void ramp(BrightScene scene, List<Tile> tiles) throws IOException {
        Path out = out("ramp");
        scene.look(-0.6, 1.6, 1.5, 15, 8);
        BufferedImage[] shots = new BufferedImage[tiles.size()];
        for (int f = 0; f < FRAMES; f++) {
            float scale = (float) (0.01 * Math.pow(5000, f / (FRAMES - 1.0)));
            scene.lamp(scale);
            for (int i = 0; i < tiles.size(); i++) {
                shots[i] = render(scene, tiles.get(i), 2);
            }
            write(grid(shots, tiles, 2, 640, 360, String.format(Locale.ROOT, "lamp x %.2f", scale)), out, f);
        }
        scene.lamp(1);
        System.out.println("ramp: " + FRAMES + " frames in " + out.toAbsolutePath());
    }

    /** Every tone curve, the exposure sweeping from 1/8 to 8 over 8 s, physical bloom. */
    private static void curves(BrightScene scene) throws IOException {
        Path out = out("curves");
        String[] curves = {"off", "reinhard", "aces", "reinhard_extended", "aces_fitted", "agx", "pbr_neutral", "gt",
            "hable"};
        List<Tile> tiles = new java.util.ArrayList<>();
        for (String curve : curves) {
            tiles.add(new Tile(curve, set(DisplayTransform.CURVE, curve)));
        }
        Bloom.SLOT.choose(scene.graph.settings(), Bloom.PHYSICAL);
        scene.look(0.2, 1.5, 2.5, 8, 5);
        scene.frames(6);
        int frames = 240;
        BufferedImage[] shots = new BufferedImage[curves.length];
        for (int f = 0; f < frames; f++) {
            float exposure = (float) Math.pow(2, -3 + 6.0 * f / (frames - 1));
            scene.graph.settings().set(DisplayTransform.EXPOSURE, exposure);
            for (int i = 0; i < curves.length; i++) {
                shots[i] = render(scene, tiles.get(i), 1); // read as frames record: no rebuild
            }
            write(grid(shots, tiles, 3, 426, 240, String.format(Locale.ROOT, "exposure %.2f", exposure)), out, f);
        }
        System.out.println("curves: " + frames + " frames in " + out.toAbsolutePath());
    }

    /** The scene with {@code tile}'s choice, after {@code frames} frames (culling settles where the camera moved). */
    private static BufferedImage render(BrightScene scene, Tile tile, int frames) {
        tile.choose().accept(scene.graph.settings());
        scene.frames(frames);
        BufferedImage shot = scene.target.shot();
        // the tile's own tunables back to their defaults for the next
        scene.graph.settings().set(Bloom.PHYSICAL.<Float>setting("intensity"), 0.05f)
            .set(Bloom.PHYSICAL.<Float>setting("threshold"), 0f);
        return shot;
    }

    private static BufferedImage grid(BufferedImage[] shots, List<Tile> tiles, int columns, int w, int h,
        String caption) {
        int rows = (shots.length + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * w, rows * h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(g.getFont().deriveFont(18f));
        for (int i = 0; i < shots.length; i++) {
            int x = (i % columns) * w;
            int y = (i / columns) * h;
            g.drawImage(shots[i], x, y, null);
            label(g, tiles.get(i).label(), x + 8, y + 22);
        }
        if (caption != null) {
            label(g, caption, columns * w - 200, rows * h - 12);
        }
        g.dispose();
        return sheet;
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setColor(java.awt.Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, x, y);
    }

    private static void write(BufferedImage frame, Path out, int f) throws IOException {
        ImageIO.write(frame, "png", out.resolve(String.format(Locale.ROOT, "f%04d.png", f)).toFile());
    }
}
