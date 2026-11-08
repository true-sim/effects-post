package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.scene.SceneResources;
import com.truesim.scene.assets.AssetLoader;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The textures post effects name by asset path ({@code mount://folder/file.ext}): a grading table ({@code .cube},
 * {@link ColorGrading}), a lens kernel image ({@link Bloom}'s convolution: any texture the app's importers read, PNG
 * or Radiance HDR). They load through the scene's asset cache like any other asset ({@link RenderAssets#TEXTURE}),
 * held in the scene's {@link SceneResources} while an effect names them, and are sampled by the slot those give.
 *
 * <pre>{@code
 * PostTextures.install(paths);                              // once: the .cube importer
 * PostTextures textures = new PostTextures(resources, paths);
 * port.add(new ColorGrading(textures));
 * graph.settings().set(ColorGrading.BAKED.setting("table"), "game://grades/dusk.cube");
 * }</pre>
 *
 * <p>Used on the thread recording frames, which runs the scene's resources. A path's texture is held from the frame
 * an effect first names it until it names another, so it may take frames to load: effects work without it meanwhile.
 */
public final class PostTextures {

    /** Reads {@code .cube} tables into 3D textures ({@link ColorLut#texture}). */
    public static final AssetLoader<TextureData> CUBE = input -> {
        try (InputStream in = input.open()) {
            return ColorLut.parseCube(new String(in.readAllBytes(), StandardCharsets.UTF_8)).texture();
        }
    };

    private final SceneResources resources;
    private final PathAssets paths;

    /** Textures held in {@code resources}, named by paths of {@code paths}. */
    public PostTextures(SceneResources resources, PathAssets paths) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /** Registers the {@code .cube} importer of grading tables with {@code paths}. */
    public static void install(PathAssets paths) {
        paths.importers().add(RenderAssets.TEXTURE, "cube", CUBE);
    }

    /** One effect's hold on a texture named by path: follows the path it is given, holding its texture. */
    public final class Held {
        private String path = "";
        private AssetRef<TextureData> ref;

        private Held() {
        }

        /**
         * Holds the texture at {@code path} (empty: none) from now on, letting go of the one before; allocates nothing
         * while the path stays.
         */
        public Held follow(String path) {
            if (!path.equals(this.path)) {
                change(path);
            }
            return this;
        }

        @AllowAllocation("an effect names another texture")
        private void change(String next) {
            if (ref != null) {
                resources.release(ref);
                ref = null;
            }
            path = next;
            if (!next.isEmpty()) {
                ref = paths.ref(RenderAssets.TEXTURE, next);
                resources.hold(ref);
            }
        }

        /** The sampled slot of the texture held, or -1: none named, loading, or failed. Allocates nothing. */
        public int slot() {
            return ref == null ? -1 : resources.texture(ref);
        }

        /** The texture held, or null. */
        public AssetRef<TextureData> ref() {
            return ref;
        }

        /** Lets go of the texture held. */
        public void release() {
            change("");
        }
    }

    /** A hold for one effect, holding nothing yet. */
    @AllowAllocation("set up once per effect")
    public Held held() {
        return new Held();
    }

    /** A count that changes whenever what slots say may have: the resources' residency. */
    public long residency() {
        return resources.residency();
    }
}
