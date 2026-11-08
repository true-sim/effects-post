package com.truesim.effects.post;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.RenderAssets;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The scene's asset path for tests: an asset cache, path assets with a {@code test://} mount of files held in memory,
 * the {@code .cube} and PNG importers, and scene resources holding what post effects name ({@link PostTextures}).
 */
final class PostAssetsFixture implements AutoCloseable {

    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache cache = new AssetCache(tasks);
    final PathAssets paths = new PathAssets(cache);
    final Map<String, byte[]> files = new HashMap<>();
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final SceneResources resources;
    final PostTextures post;

    PostAssetsFixture(VulkanDevice device) {
        paths.mounts().add("test", path -> {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });
        PostTextures.install(paths);
        paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE);
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1024, 64, 4));
        materials = new Materials(device, 4);
        textures = new Textures(device);
        resources = new SceneResources(cache, pool, materials, textures);
        post = new PostTextures(resources, paths);
    }

    /** Adds file {@code path} (in the {@code test://} mount) of {@code text}. */
    PostAssetsFixture file(String path, String text) {
        files.put(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return this;
    }

    /** Loads what {@code held} hold: the asset loads and the uploads done, waiting at most a minute. */
    void load(PostTextures.Held... held) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        for (PostTextures.Held each : held) {
            while (each.slot() < 0) {
                tasks.drainCompleted();
                resources.flush();
                if (cache.state(each.ref()) == com.truesim.scene.assets.AssetState.FAILED) {
                    throw new IllegalStateException(each.ref() + " failed to load");
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new IllegalStateException(each.ref() + " did not load: " + cache.state(each.ref()));
                }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
        }
    }

    @Override
    public void close() {
        resources.close();
        textures.close();
        materials.close();
        pool.close();
        cache.close();
        tasks.close();
    }
}
