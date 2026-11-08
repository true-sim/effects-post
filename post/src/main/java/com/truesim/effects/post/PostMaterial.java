package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MaterialParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.PushConstants;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * A game's own post effect, written in Slang ({@code post/material.slang}): an {@code IPostMaterial} struct shading
 * each pixel of the render region, its parameters a {@code [MaterialParameters(0)]} struct the build makes a Java record
 * of, its entry point a few lines calling {@code runPost}. It reads the image as the effects before it left it, the
 * scene depth (before tone mapping), up to four textures, the time and the size, and provides a new version of the
 * image.
 *
 * <p>Where it runs is its anchor: before {@link Anchor#TONE_MAP} it modifies the scene color in scene light
 * ({@link Keys#SCENE_COLOR}, e.g. at {@link Anchor#POST}); at {@link Anchor#TONE_MAP} or {@link Anchor#UI} the display
 * color after tone mapping ({@link DisplayTransform#DISPLAY_COLOR}, which needs the window's transform made with
 * {@link DisplayTransform#displayEffects()}), in display light, 1 at white. Several chain in their order: by anchor,
 * then {@link #after}/{@link #before} other techniques by id, then as added.
 *
 * <p>Each material is a technique slot of its own, named as the material ({@link #slot()}): its one kind {@code on}
 * (the default) or {@code off}, read when the graph builds (it provides a new image, so it switches off by a rebuild,
 * leaving the image as it was). Its parameters change while it runs ({@link #parameters}): they are the game's state
 * (a wipe's progress, a hit's strength), not a player's settings.
 *
 * <p>Its textures are asset paths loaded through {@link PostTextures} ({@link #textures(PostTextures, String...)}), or
 * sampled slots the game holds already (a scene's resources give them for its texture assets); a texture not loaded yet
 * reads as the image itself.
 *
 * <pre>{@code
 * PostMaterial haze = new PostMaterial("game.heatHaze", "shaders/game/hazeEntry.spv", "hazeMain", Anchor.POST,
 *     HazeParameters.DEFAULTS).textures(postTextures, "game://textures/haze_normals.png#linear");
 * port.add(haze);
 * haze.parameters(HazeParameters.DEFAULTS.withStrength(0.02f));
 * }</pre>
 */
public final class PostMaterial implements Technique {

    /** The header's bytes, before the parameters. */
    static final int HEADER_BYTES = 64;
    /** The most textures a material reads: the header's slots. */
    public static final int TEXTURES = 4;
    private static final int FLAG_DEPTH = 1;
    private static final int FLAG_DISPLAY = 2;
    private static final int NONE = -1;

    /** A material's tunables: none (its parameters are the game's). */
    public record On() implements TechniqueConfig {
    }

    private final Id<PostMaterial> id;
    private final TechniqueSlot<PostMaterial, Boolean> slot;
    private final TechniqueKind<On, PostMaterial, Boolean> on;
    private final String shader;
    private final String entry;
    private final Anchor anchor;
    private final int[] slots = {NONE, NONE, NONE, NONE};
    private final PostTextures.Held[] held = new PostTextures.Held[TEXTURES];
    private final String[] paths = new String[TEXTURES];
    private final int parameterBytes;
    private final DoubleSupplier clock;
    private volatile MaterialParameters parameters;
    private Order order;

    /**
     * Material {@code name} (its id and its slot's name), shaded by entry point {@code entry} of compiled shader
     * {@code shader}, at {@code anchor}, with {@code parameters} (its parameters record, 64 bytes at most), its time the
     * real seconds since it was made.
     */
    public PostMaterial(String name, String shader, String entry, Anchor anchor, MaterialParameters parameters) {
        this(name, shader, entry, anchor, parameters, new DoubleSupplier() {
            private final long started = System.nanoTime();

            @Override
            public double getAsDouble() {
                return (System.nanoTime() - started) * 1e-9;
            }
        });
    }

    /** Like {@link #PostMaterial(String, String, String, Anchor, MaterialParameters)}, its time {@code clock}'s. */
    @AllowAllocation("made rarely")
    public PostMaterial(String name, String shader, String entry, Anchor anchor, MaterialParameters parameters,
        DoubleSupplier clock) {
        this.id = Id.of(Objects.requireNonNull(name, "name"), PostMaterial.class);
        this.shader = Objects.requireNonNull(shader, "shader");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.parameterBytes = parameters.bytes();
        if (parameterBytes > PushConstants.MAX_BYTES - HEADER_BYTES) {
            throw new IllegalArgumentException("A post material takes parameters of up to "
                + (PushConstants.MAX_BYTES - HEADER_BYTES) + " bytes: " + parameterBytes);
        }
        this.order = Order.at(anchor);
        @SuppressWarnings("unchecked")
        TechniqueKind<On, PostMaterial, Boolean>[] kind = new TechniqueKind[1];
        @SuppressWarnings("unchecked")
        TechniqueSlot<PostMaterial, Boolean>[] made = new TechniqueSlot[1];
        made[0] = TechniqueSlot.of(name, "on", true, "the post material " + name + ": on or off",
            techniques -> techniques.register(made[0], kind[0]));
        kind[0] = TechniqueKind.of(made[0], "on", new On(), "the post material " + name, (material, config) -> Boolean.TRUE);
        slot = made[0];
        on = kind[0];
    }

    /** Its slot: {@code on} or {@code off}, named as the material. */
    public TechniqueSlot<PostMaterial, Boolean> slot() {
        return slot;
    }

    /** Its slot's one kind. */
    public TechniqueKind<On, PostMaterial, Boolean> on() {
        return on;
    }

    /** Registers its slot in {@code registry} for discovery, hidden. */
    public void register(SettingRegistry registry) {
        registry.addHiddenSlots(slot);
    }

    /**
     * Its textures 0 to 3 from sampled slots the game holds (a scene's resources give them for texture assets), the
     * rest none. Before the graph builds.
     */
    public PostMaterial textures(int... textures) {
        if (textures.length > TEXTURES) {
            throw new IllegalArgumentException("A post material takes " + TEXTURES + " textures: " + textures.length);
        }
        for (int i = 0; i < TEXTURES; i++) {
            slots[i] = i < textures.length ? textures[i] : NONE;
            paths[i] = null;
        }
        return this;
    }

    /** Its textures 0 to 3 from asset paths, held through {@code textures} while it runs. Before the graph builds. */
    @AllowAllocation("set up rarely")
    public PostMaterial textures(PostTextures textures, String... paths) {
        if (paths.length > TEXTURES) {
            throw new IllegalArgumentException("A post material takes " + TEXTURES + " textures: " + paths.length);
        }
        for (int i = 0; i < TEXTURES; i++) {
            slots[i] = NONE;
            this.paths[i] = i < paths.length ? Objects.requireNonNull(paths[i], "path") : null;
            if (this.paths[i] != null && held[i] == null) {
                held[i] = textures.held();
            }
        }
        return this;
    }

    /** Its parameters from the next frame on: a record of the type it was made with. */
    public PostMaterial parameters(MaterialParameters next) {
        if (next.getClass() != parameters.getClass()) {
            throw new IllegalArgumentException("Parameters of " + parameters.getClass().getSimpleName() + ", not "
                + next.getClass().getSimpleName());
        }
        parameters = next;
        return this;
    }

    /** Its parameters now. */
    public MaterialParameters parameters() {
        return parameters;
    }

    /** Runs after the technique of id {@code other} at its anchor. Before the graph builds. */
    public PostMaterial after(Id<? extends Technique> other) {
        order = order.after(other);
        return this;
    }

    /** Runs before the technique of id {@code other} at its anchor. Before the graph builds. */
    public PostMaterial before(Id<? extends Technique> other) {
        order = order.before(other);
        return this;
    }

    @Override
    public Id<? extends Technique> id() {
        return id;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(key());
    }

    @Override
    public Order order() {
        return order;
    }

    /** The image it modifies: the scene color before tone mapping, the display color after. */
    private Key<Image> key() {
        return anchor.rank() < Anchor.TONE_MAP.rank() ? Keys.SCENE_COLOR : DisplayTransform.DISPLAY_COLOR;
    }

    /** The push constants: the header's 64 bytes, then the parameters. */
    private static final class Push extends PushConstants {
        private final int size;

        Push(int parameterBytes) {
            size = HEADER_BYTES + (parameterBytes + 3) / 4 * 4;
        }

        @Override
        public int size() {
            return size;
        }

        void header(int source, int target, int depth, int flags, int width, int height, int imageWidth,
            int imageHeight, float time, int frame, float paperWhite, float peak) {
            putInt(0, source);
            putInt(4, target);
            putInt(8, depth);
            putInt(12, flags);
            putInt(16, width);
            putInt(20, height);
            putInt(24, imageWidth);
            putInt(28, imageHeight);
            putFloat(32, time);
            putInt(36, frame);
            putFloat(40, paperWhite);
            putFloat(44, peak);
        }

        void texture(int index, int slot) {
            putInt(48 + 4L * index, slot);
        }

        void parameters(MaterialParameters parameters) {
            parameters.write(memory(), HEADER_BYTES);
        }
    }

    /** Texture {@code i}'s sampled slot now, or NONE. Allocates nothing while its path stays. */
    private int texture(int i) {
        if (paths[i] != null) {
            return held[i].follow(paths[i]).slot();
        }
        return slots[i];
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Key<Image> key = key();
        boolean display = key == DisplayTransform.DISPLAY_COLOR;
        Image in = g.modify(key);
        if (g.technique(slot) == null) {
            for (PostTextures.Held each : held) {
                if (each != null) {
                    each.release();
                }
            }
            return; // the image as it was: no new version
        }
        Image out = g.create(id.name(), ImageDesc.like(in, Ratio.ONE));
        Image depth = display ? null : g.optional(Keys.SCENE_DEPTH);
        Pipeline pipeline = g.computeProgram(shader, entry, new Push(parameterBytes).size());
        Push push = new Push(parameterBytes);
        EngineSettings settings = g.settings();
        GraphBuilder.PassBuilder pass = g.pass(id.name()).read(in, Access.SAMPLED_COMPUTE)
            .write(out, Access.STORAGE_WRITE);
        if (depth != null) {
            pass.read(depth, Access.SAMPLED_COMPUTE);
        }
        pass.record(ctx -> {
            int width = ctx.regionWidth(in);
            int height = ctx.regionHeight(in);
            int source = ctx.sampled(in);
            for (int i = 0; i < TEXTURES; i++) {
                int texture = texture(i);
                push.texture(i, texture < 0 ? source : texture); // not there (yet): the image, a valid slot
            }
            float paperWhite = settings.get(DisplayTransform.PAPER_WHITE);
            push.header(source, ctx.storage(out), depth == null ? source : ctx.sampled(depth),
                (depth == null ? 0 : FLAG_DEPTH) | (display ? FLAG_DISPLAY : 0), width, height, in.width(),
                in.height(), (float) clock.getAsDouble(), (int) ctx.frame(), paperWhite,
                settings.get(DisplayTransform.PEAK) / paperWhite);
            push.parameters(parameters);
            ctx.dispatch(pipeline, push, (width + 7) / 8, (height + 7) / 8, 1);
        });
        g.provide(key, out);
    }
}
