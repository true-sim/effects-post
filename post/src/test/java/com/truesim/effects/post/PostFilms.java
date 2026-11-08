package com.truesim.effects.post;

import com.truesim.common.math.color.Color;
import com.truesim.effects.post.materials.DamageParameters;
import com.truesim.effects.post.materials.HazeParameters;
import com.truesim.effects.post.materials.WipeParameters;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.settings.EngineSettings;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of the post chain over the {@link PostScene}, as PNG frames at 30 per second in grids of 640 x 360 tiles
 * (1280 x 720), each tile a view of its own (its own settings and temporal state), into {@code build/films/post} (or
 * {@code TRUESIM_POST_FILMS}), one folder per film: {@code exposure}, {@code grading}, {@code dof}, {@code lens},
 * {@code flash}, {@code grain}, {@code materials}.
 *
 * <p>Run with {@code TRUESIM_SCREENSHOTS=true ./gradlew :effects-post:test --tests '*PostFilms'} (one film:
 * {@code -Dtruesim.film=dof}); encode each with
 * {@code ffmpeg -framerate 30 -i <film>/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 18 <film>.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class PostFilms {

    private static final int W = 640;
    private static final int H = 360;
    private static final double STEP = 1 / 30.0;

    private static Path out(String film) throws IOException {
        String dir = System.getenv("TRUESIM_POST_FILMS");
        Path root = dir == null || dir.isBlank() ? Path.of("build", "films", "post") : Path.of(dir);
        return Files.createDirectories(root.resolve(film));
    }

    private static boolean wanted(String film) {
        String only = System.getProperty("truesim.film", System.getenv().getOrDefault("TRUESIM_FILM", ""));
        return only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(film);
    }

    private static double smooth(double t) {
        double x = Math.clamp(t, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Films {@code frames} frames: before each, {@code before} at its time t (0 to 1), labels by {@code caption}. */
    private static void film(PostScene scene, String name, String[] labels, int frames, Consumer<Double> before,
        java.util.function.DoubleFunction<String> caption) throws IOException {
        Path out = out(name);
        for (int f = 0; f < frames; f++) {
            double t = f / (frames - 1.0);
            before.accept(t);
            BufferedImage[] shots = scene.frame(STEP);
            write(grid(shots, labels, caption.apply(t)), out, f);
        }
        System.out.println(name + ": " + frames + " frames in " + out.toAbsolutePath());
    }

    @Test
    void films() throws IOException {
        if (wanted("exposure")) {
            exposure();
        }
        if (wanted("grading")) {
            grading();
        }
        if (wanted("dof")) {
            depthOfField();
        }
        if (wanted("lens")) {
            lens();
        }
        if (wanted("flash")) {
            flash();
        }
        if (wanted("grain")) {
            grain();
        }
        if (wanted("materials")) {
            materials();
        }
    }

    /**
     * Auto exposure: from the dark inside of the house out of the door into the sun, a look around, and back in;
     * a fixed exposure beside the histogram, the log average and the histogram with Krawczyk's key.
     */
    private static void exposure() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            scene.tile("fixed");
            AutoExposure.SLOT.choose(scene.tile("histogram").settings(), AutoExposure.HISTOGRAM);
            AutoExposure.SLOT.choose(scene.tile("average").settings(), AutoExposure.AVERAGE);
            EngineSettings k = scene.tile("krawczyk").settings();
            AutoExposure.SLOT.choose(k, AutoExposure.HISTOGRAM);
            k.set(AutoExposure.HISTOGRAM.setting("key"), AutoExposure.KeyValue.KRAWCZYK);
            k.set(AutoExposure.HISTOGRAM.<Float>setting("luminanceScale"), 200f); // sunlit ground near 1,000 cd/m²
            String[] labels = {"fixed exposure " + PostScene.FIXED_EXPOSURE + " (the outside's)", "auto: histogram", "auto: log average",
                "auto: histogram, Krawczyk's key"};
            // 12 s: inside looking out 2 s, walk out 3 s, look around 2 s, turn, walk back in 2.4 s, turn to the
            // door again and stay (the eye adapting to the dark, the door overexposed)
            film(scene, "exposure", labels, 360, t -> {
                double s = t * 12;
                double z;
                double yaw = 180;
                if (s < 2) {
                    z = -5;
                } else if (s < 5) {
                    z = lerp(-5, 5, smooth((s - 2) / 3));
                } else if (s < 7) {
                    z = 5;
                    yaw = 180 + 40 * Math.sin((s - 5) / 2 * 2 * Math.PI);
                } else if (s < 7.6) {
                    z = 5;
                    yaw = lerp(180, 360, smooth((s - 7) / 0.6));
                } else if (s < 10) {
                    z = lerp(5, -4, smooth((s - 7.6) / 2.4));
                    yaw = 360;
                } else {
                    z = -4;
                    yaw = lerp(360, 180, smooth((s - 10) / 0.8));
                }
                scene.look(0, 1.6, z, yaw, s >= 7.6 && s < 10.4 ? -12 : 2);
            }, t -> String.format(Locale.ROOT, "%.1f s", t * 12));
        }
    }

    /** A grading table as a .cube file: {@code grade} of each cell's linear color, in the grading's log encoding. */
    private static byte[] cube(int size, java.util.function.UnaryOperator<double[]> grade) {
        StringBuilder text = new StringBuilder("LUT_3D_SIZE " + size + "\n");
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    double[] c = {ColorLut.decode(r / (size - 1.0)), ColorLut.decode(g / (size - 1.0)),
                        ColorLut.decode(b / (size - 1.0))};
                    double[] o = grade.apply(c);
                    text.append(String.format(Locale.ROOT, "%.6f %.6f %.6f%n", ColorLut.encode(o[0]),
                        ColorLut.encode(o[1]), ColorLut.encode(o[2])));
                }
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static double luma(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Teal shadows, orange highlights, the luminance kept. */
    private static double[] tealOrange(double[] c) {
        double l = luma(c);
        double w = smooth((Math.log(l + 1e-6) / Math.log(2) + 6) / 6); // from 1/64 to 1
        double[] tint = {lerp(0.75, 1.25, w), lerp(1.0, 0.98, w), lerp(1.25, 0.65, w)};
        double[] o = {c[0] * tint[0], c[1] * tint[1], c[2] * tint[2]};
        double scale = l / Math.max(luma(o), 1e-9);
        return new double[] {o[0] * scale, o[1] * scale, o[2] * scale};
    }

    /** Bleach bypass: most of the color gone, more contrast. */
    private static double[] bleach(double[] c) {
        double l = luma(c);
        double contrast = Math.pow(l / 0.18, 1.3) * 0.18;
        double s = l > 0 ? contrast / l : 0;
        return new double[] {lerp(c[0], l, 0.6) * s, lerp(c[1], l, 0.6) * s, lerp(c[2], l, 0.6) * s};
    }

    /** Sepia: the luminance, tinted warm. */
    private static double[] sepia(double[] c) {
        double l = luma(c);
        return new double[] {l * 1.25, l * 0.98, l * 0.66};
    }

    /** Grading: white balance swept, lift, gamma, gain and saturation swept, and three tables crossfaded. */
    private static void grading() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            scene.file("grades/teal-orange.cube", cube(33, PostFilms::tealOrange))
                .file("grades/bleach.cube", cube(33, PostFilms::bleach))
                .file("grades/sepia.cube", cube(33, PostFilms::sepia));
            PostTextures.Held[] held = {scene.textures.held().follow("film://grades/teal-orange.cube"),
                scene.textures.held().follow("film://grades/bleach.cube"),
                scene.textures.held().follow("film://grades/sepia.cube")};
            scene.load(held);
            scene.tile("off");
            EngineSettings balance = scene.tile("balance").settings();
            ColorGrading.SLOT.choose(balance, ColorGrading.BAKED);
            EngineSettings tone = scene.tile("tone").settings();
            ColorGrading.SLOT.choose(tone, ColorGrading.DIRECT);
            EngineSettings table = scene.tile("table").settings();
            ColorGrading.SLOT.choose(table, ColorGrading.BAKED);
            String[] tables = {"teal-orange", "bleach", "sepia"};
            String[] caption = new String[1];
            film(scene, "grading", new String[] {"off", "white balance (baked)", "lift, gamma, gain, saturation "
                + "(direct)", "authored .cube tables (baked)"}, 300, t -> {
                    double s = t * 10;
                    scene.look(-4 + 2 * t, 1.7, 10, 160 + 20 * t, 3);
                    // The light's white from 2500 K to 12,000 K and back: warm light shows neutral, so the image cools
                    double kelvin = Math.exp(lerp(Math.log(2500), Math.log(12000), 0.5 - 0.5 * Math.cos(s / 10
                        * 2 * Math.PI)));
                    balance.set(ColorGrading.BAKED.setting("temperature"), (float) kelvin);
                    balance.set(ColorGrading.BAKED.setting("tint"), (float) (0.6 * Math.sin(s / 5 * 2 * Math.PI)));
                    double a = Math.sin(s / 10 * 2 * Math.PI);
                    // In the 24-stop log encoding, small numbers are large: a lift of 0.02 is half a stop in the dark
                    tone.set(ColorGrading.DIRECT.setting("contrast"), (float) (1 + 0.3 * a));
                    tone.set(ColorGrading.DIRECT.setting("saturation"), (float) (1 + 0.7 * Math.sin(s / 5 * Math.PI)));
                    tone.set(ColorGrading.DIRECT.setting("liftBlue"), (float) (0.02 * Math.max(a, 0)));
                    tone.set(ColorGrading.DIRECT.setting("gainRed"), (float) (1 + 0.04 * Math.max(-a, 0)));
                    tone.set(ColorGrading.DIRECT.setting("gammaGreen"), (float) (1 + 0.03 * a));
                    // Each table in for 10/3 s: up over its first second, down over its last
                    int which = Math.min((int) (s / (10 / 3.0)), 2);
                    double local = s - which * 10 / 3.0;
                    double share = Math.min(1, Math.min(local, 10 / 3.0 - local));
                    table.set(ColorGrading.BAKED.setting("table"), "film://grades/" + tables[which] + ".cube");
                    table.set(ColorGrading.BAKED.setting("tableShare"), (float) share);
                    caption[0] = String.format(Locale.ROOT, "%.0f K, tint %.2f | %s %.0f%%", kelvin,
                        0.6 * Math.sin(s / 5 * 2 * Math.PI), tables[which], share * 100);
                }, t -> caption[0]);
            for (PostTextures.Held each : held) {
                each.release();
            }
        }
    }

    /** Depth of field: the camera turning between a near and a far subject, autofocus racking between them. */
    private static void depthOfField() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            scene.tile("off");
            // A longer lens (30 degrees high: 67 mm on a 36 mm high sensor) at f/1.4: a shallow focus
            EngineSettings gather = scene.tile("gather").settings();
            DepthOfField.SLOT.choose(gather, DepthOfField.GATHER);
            gather.set(DepthOfField.F_STOP, 1.4f);
            EngineSettings gaussian = scene.tile("gaussian").settings();
            DepthOfField.SLOT.choose(gaussian, DepthOfField.GAUSSIAN);
            gaussian.set(DepthOfField.F_STOP, 1.4f);
            EngineSettings narrow = scene.tile("gather f/4").settings();
            DepthOfField.SLOT.choose(narrow, DepthOfField.GATHER);
            narrow.set(DepthOfField.F_STOP, 4f);
            for (PostScene.Tile tile : scene.tiles) {
                tile.settings().set(DepthOfField.SENSOR_HEIGHT, 36f).set(DisplayTransform.EXPOSURE, 0.8f);
            }
            // From (0.2, 1.0, 1.4): the near rock (-0.7, 0.45, 3.2) 2 m away, the far box (0.9, 0.75, 22) 21 m
            film(scene, "dof", new String[] {"off", "gather f/1.4, autofocus", "gaussian f/1.4, autofocus",
                "gather f/4, autofocus"}, 300, t -> {
                    // 10 s: near for 2.5 s, turn in 0.4 s, far for 2.1 s, and again
                    double s = t * 10 % 5;
                    double w = s < 2.5 ? 0 : smooth((s - 2.5) / 0.4);
                    if (s >= 4.6) {
                        w = 1 - smooth((s - 4.6) / 0.4);
                    }
                    scene.lookAt(0.2, 1.0, 1.4, lerp(-0.7, 0.9, w), lerp(0.55, 0.75, w), lerp(3.2, 22, w), 30);
                }, t -> String.format(Locale.ROOT, "%.1f s", t * 10));
        }
    }

    /** Lens effects ramping: distortion, chromatic aberration, vignetting. */
    private static void lens() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            scene.tile("off");
            EngineSettings distortion = scene.tile("distortion").settings();
            LensEffects.DISTORTION.choose(distortion, LensEffects.BROWN_CONRADY);
            EngineSettings aberration = scene.tile("aberration").settings();
            EngineSettings vignette = scene.tile("vignette").settings();
            String[] caption = new String[1];
            film(scene, "lens", new String[] {"off", "distortion (Brown-Conrady)", "chromatic aberration",
                "vignetting"}, 300, t -> {
                    // The house's sunlit front, its straight edges near the frame's, through a wide lens
                    scene.lookAt(0.6, 1.3, 3.6, -0.2, 2.1, -1, 80);
                    double k = t < 0.5 ? lerp(-1 / 3.0, 0, smooth(t * 2)) : lerp(0, 0.5, smooth(t * 2 - 1));
                    distortion.set(LensEffects.BROWN_CONRADY.setting("k"), (float) k);
                    boolean spectral = t >= 0.5;
                    LensEffects.ABERRATION.choose(aberration, spectral ? LensEffects.SPECTRAL : LensEffects.RADIAL);
                    double amount = 0.03 * Math.sin(Math.PI * (spectral ? t * 2 - 1 : t * 2));
                    aberration.set((spectral ? LensEffects.SPECTRAL : LensEffects.RADIAL).setting("amount"),
                        (float) amount);
                    boolean artistic = t >= 0.5;
                    LensEffects.VIGNETTE.choose(vignette, artistic ? LensEffects.ARTISTIC : LensEffects.NATURAL);
                    double intensity = Math.sin(Math.PI * (artistic ? t * 2 - 1 : t * 2));
                    vignette.set((artistic ? LensEffects.ARTISTIC : LensEffects.NATURAL).setting("intensity"),
                        (float) intensity);
                    caption[0] = String.format(Locale.ROOT, "k %.2f | %s %.3f | %s %.2f", k, spectral ? "spectral"
                        : "radial", amount, artistic ? "artistic" : "natural cos^4", intensity);
                }, t -> caption[0]);
        }
    }

    /** Flashes and fades with accessibility.flashes full, reduced and off, and in scene light. */
    private static void flash() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            PostScene.Tile[] tiles = {scene.tile("full"), scene.tile("reduced"), scene.tile("off"),
                scene.tile("scene")};
            tiles[1].settings().set(ScreenFlash.FLASHES, ScreenFlash.Flashes.REDUCED);
            tiles[2].settings().set(ScreenFlash.FLASHES, ScreenFlash.Flashes.OFF);
            ScreenFlash.SLOT.choose(tiles[3].settings(), ScreenFlash.SCENE);
            double[] events = {0.5, 2, 3.5, 3.7, 3.9, 5, 6.5};
            boolean[] done = new boolean[events.length];
            film(scene, "flash", new String[] {"accessibility.flashes FULL", "REDUCED (below WCAG's threshold)",
                "OFF", "FULL, in scene light"}, 300, t -> {
                    double s = t * 10;
                    scene.look(-1 + t, 1.6, 9, 190, 2);
                    for (int i = 0; i < events.length; i++) {
                        if (!done[i] && s >= events[i]) {
                            done[i] = true;
                            for (PostScene.Tile tile : tiles) {
                                switch (i) {
                                    case 0 -> tile.flash.flash(1, 1, 1, 0.9f, 0.3f, ScreenFlash.Curve.EASE_OUT);
                                    case 1 -> tile.flash.flash(1, 0.1f, 0.05f, 0.6f, 0.4f, ScreenFlash.Curve.EASE_OUT);
                                    case 2, 3, 4 -> tile.flash.flash(1, 1, 1, 1, 0.1f, ScreenFlash.Curve.LINEAR);
                                    case 5 -> tile.flash.fadeTo(0, 0, 0, 0.3f, ScreenFlash.Curve.SMOOTH);
                                    default -> tile.flash.fadeFrom(0, 0, 0, 1, ScreenFlash.Curve.SMOOTH);
                                }
                            }
                        }
                    }
                }, t -> {
                    double s = t * 10;
                    String what = s < 2 ? "white flash 0.3 s" : s < 3.5 ? "red hit 0.4 s" : s < 5
                        ? "three flashes in 0.4 s" : s < 6.5 ? "fade to black 0.3 s" : "fade in 1 s";
                    return String.format(Locale.ROOT, "%.1f s: %s", s, what);
                });
        }
    }

    /** Film grain at three settings beside none, the camera drifting. */
    private static void grain() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            scene.tile("off");
            EngineSettings light = scene.tile("light").settings();
            FilmGrain.SLOT.choose(light, FilmGrain.FILM);
            EngineSettings heavy = scene.tile("heavy").settings();
            FilmGrain.SLOT.choose(heavy, FilmGrain.FILM);
            heavy.set(FilmGrain.FILM.setting("intensity"), 0.3f).set(FilmGrain.FILM.setting("size"), 2.5f);
            EngineSettings flat = scene.tile("flat").settings();
            FilmGrain.SLOT.choose(flat, FilmGrain.FILM);
            flat.set(FilmGrain.FILM.setting("intensity"), 0.3f).set(FilmGrain.FILM.setting("response"), 0f);
            film(scene, "grain", new String[] {"off", "film 0.1 (default)", "film 0.3, size 2.5",
                "film 0.3, response 0 (highlights too)"}, 180,
                t -> scene.look(-1 + 0.5 * t, 1.6, 9, 190, 2), t -> "");
        }
    }

    private static byte[] png(int size, java.util.function.IntBinaryOperator argb) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, argb.applyAsInt(x, y));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int gray(double v, double alpha) {
        int g = (int) Math.round(Math.clamp(v, 0, 1) * 255);
        int a = (int) Math.round(Math.clamp(alpha, 0, 1) * 255);
        return a << 24 | g << 16 | g << 8 | g;
    }

    /** Post materials: a dissolve by a noise mask, a pulsing damage vignette, heat haze over the near ground. */
    private static void materials() throws IOException {
        try (PostScene scene = new PostScene(W, H)) {
            // Textures, as assets: a smooth noise mask, a vignette's alpha, a normal map (all data: linear)
            SplittableRandom random = new SplittableRandom(3);
            double[][] cells = new double[9][9];
            for (double[] row : cells) {
                for (int i = 0; i < row.length; i++) {
                    row[i] = random.nextDouble();
                }
            }
            DoubleUnaryOperator s3 = PostFilms::smooth;
            scene.file("textures/noise.png", png(256, (x, y) -> {
                double u = x / 32.0;
                double v = y / 32.0;
                int i = (int) u;
                int j = (int) v;
                double a = s3.applyAsDouble(u - i);
                double b = s3.applyAsDouble(v - j);
                double n = lerp(lerp(cells[j][i], cells[j][i + 1], a), lerp(cells[j + 1][i], cells[j + 1][i + 1], a),
                    b);
                return gray(n * 0.9 + 0.05 * (x / 256.0) + 0.05, 1);
            })).file("textures/vignette.png", png(256, (x, y) -> {
                double dx = (x + 0.5) / 256 - 0.5;
                double dy = (y + 0.5) / 256 - 0.5;
                return gray(1, smooth((Math.sqrt(dx * dx + dy * dy) - 0.25) / 0.45));
            })).file("textures/haze.png", png(128, (x, y) -> {
                double nx = 0.5 + 0.5 * Math.sin(2 * Math.PI * (x / 32.0 + 0.3 * Math.sin(2 * Math.PI * y / 64.0)));
                double ny = 0.5 + 0.5 * Math.cos(2 * Math.PI * (y / 32.0 + 0.3 * Math.sin(2 * Math.PI * x / 64.0)));
                int r = (int) Math.round(nx * 255);
                int g = (int) Math.round(ny * 255);
                return 0xff000000 | r << 16 | g << 8 | 128;
            }));
            PostMaterial wipe = new PostMaterial("film.wipe", "shaders/test/post/wipeEntry.spv", "wipeMain",
                Anchor.POST, WipeParameters.DEFAULTS.withColor(new Color(0.02f, 0.02f, 0.03f, 1)), () -> scene.seconds)
                .textures(scene.textures, "film://textures/noise.png#linear");
            PostMaterial damage = new PostMaterial("film.damage", "shaders/test/post/damageEntry.spv", "damageMain",
                Anchor.UI, DamageParameters.DEFAULTS, () -> scene.seconds)
                .textures(scene.textures, "film://textures/vignette.png#linear");
            PostMaterial haze = new PostMaterial("film.haze", "shaders/test/post/hazeEntry.spv", "hazeMain",
                Anchor.POST, HazeParameters.DEFAULTS.withStrength(0.006f).withSpeed(0.15f).withTiling(3f)
                    .withNear(0.004f), () -> scene.seconds)
                .textures(scene.textures, "film://textures/haze.png#linear");
            scene.tile("off");
            scene.tile("wipe", wipe);
            scene.tile("damage", damage);
            scene.tile("haze", haze);
            String[] caption = new String[1];
            film(scene, "materials", new String[] {"off", "wipe: a dissolve by a noise mask (scene light)",
                "damage vignette (display light)", "heat haze: a scrolling normal map, within 25 m (depth)"}, 300, t -> {
                    double s = t * 10;
                    scene.look(-1 + 0.4 * t, 1.5, 9, 190, -4);
                    float progress = (float) (0.5 - 0.5 * Math.cos(s / 5 * 2 * Math.PI));
                    wipe.parameters(WipeParameters.DEFAULTS.withColor(new Color(0.02f, 0.02f, 0.03f, 1))
                        .withProgress(progress).withSoftness(0.08f));
                    float hit = (float) Math.max(0, Math.sin(s / 2.5 * Math.PI));
                    damage.parameters(DamageParameters.DEFAULTS.withStrength(hit * hit));
                    caption[0] = String.format(Locale.ROOT, "wipe %.2f | damage %.2f", progress, hit * hit);
                }, t -> caption[0]);
        }
    }

    private static BufferedImage grid(BufferedImage[] shots, String[] labels, String caption) {
        BufferedImage sheet = new BufferedImage(2 * W, 2 * H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(g.getFont().deriveFont(17f));
        for (int i = 0; i < shots.length; i++) {
            int x = (i % 2) * W;
            int y = (i / 2) * H;
            g.drawImage(shots[i], x, y, null);
            label(g, labels[i], x + 8, y + 22);
        }
        if (caption != null && !caption.isEmpty()) {
            label(g, caption, 2 * W - 8 - g.getFontMetrics().stringWidth(caption), 2 * H - 12);
        }
        g.setColor(java.awt.Color.BLACK);
        g.drawLine(W, 0, W, 2 * H);
        g.drawLine(0, H, 2 * W, H);
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
