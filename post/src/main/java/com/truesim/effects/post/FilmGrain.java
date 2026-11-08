package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.post.layout.GrainPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.SettingRegistry;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;

/**
 * Film grain, in display light after tone mapping: it modifies {@link DisplayTransform#DISPLAY_COLOR} in place at
 * {@link Anchor#TONE_MAP}, so the window's display transform must be made with
 * {@link DisplayTransform#displayEffects()}. Off by default ({@link #SLOT}, {@code render.grain}).
 *
 * <ul>
 *   <li>{@link #FILM} ({@code film}): Unity's form, {@code d + d n intensity lerp(1, 1 - sqrt(luma), response)},
 *       {@code n} value noise of -1 to 1 over cells of {@code size} pixels, new each frame: stronger in the shadows and
 *       middle than in the highlights, scaled with the color so black stays black; its mean is 0, so the image keeps
 *       its average.</li>
 * </ul>
 *
 * <pre>{@code
 * presenter.outputTransform(new DisplayTransform().displayEffects());
 * port.add(new FilmGrain());
 * FilmGrain.SLOT.choose(graph.settings(), FilmGrain.FILM);
 * }</pre>
 */
public final class FilmGrain implements Technique {

    /** A view's film grain. */
    public static final Id<FilmGrain> ID = Id.of("truesim.filmGrain", FilmGrain.class);

    /** The grain, a kind's id, or {@code off} (the default). */
    public static final TechniqueSlot<FilmGrain, Technique> SLOT = TechniqueSlot.of("render.grain", TechniqueSlot.OFF,
        true, "film grain in display light: off or film (value noise, less in the highlights)",
        techniques -> techniques.register(FilmGrain.SLOT, FilmGrain.FILM));

    /**
     * The grain's tunables, read as frames record.
     *
     * @param intensity its strength: the noise's amplitude, a share of the color
     * @param response how much less grain the highlights get: 0 the same everywhere, 1 none at white
     * @param size the grain's cells, in pixels
     */
    public record Grain(
        @Tunable(value = "film grain's strength, a share of the color", min = 0, max = 1) float intensity,
        @Tunable(value = "how much less film grain the highlights get", min = 0, max = 1) float response,
        @Tunable(value = "film grain's size in pixels", min = 0.5, max = 8) float size) implements TechniqueConfig {
    }

    /** Value noise, less in the highlights. */
    public static final TechniqueKind<Grain, FilmGrain, Technique> FILM = TechniqueKind.of(SLOT, "film",
        new Grain(0.1f, 0.8f, 1.5f), "value noise over cells, new each frame, less in the highlights",
        (grain, config) -> new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of();
            }

            @Override
            @AllowAllocation("building the graph, when it changes")
            public void build(GraphBuilder g) {
                Image display = g.modify(DisplayTransform.DISPLAY_COLOR);
                Pipeline pipeline = g.computeProgram("shaders/post/grain.spv", "grainMain", GrainPush.BYTES);
                GrainPush push = new GrainPush();
                g.pass("film grain").write(display, Access.STORAGE_WRITE).record(ctx -> {
                    Grain c = config.get();
                    int width = ctx.regionWidth(display);
                    int height = ctx.regionHeight(display);
                    push.image(ctx.storage(display)).frame((int) ctx.frame()).region(width, height)
                        .intensity(c.intensity()).response(c.response()).size(c.size());
                    ctx.dispatch(pipeline, push, (width + 7) / 8, (height + 7) / 8, 1);
                });
            }
        });

    /** Registers its settings in {@code registry} for discovery: the slot, its kinds' tunables with it, hidden. */
    public static void register(SettingRegistry registry) {
        registry.addHiddenSlots(SLOT);
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(DisplayTransform.DISPLAY_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.TONE_MAP);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        TechniqueKind<?, FilmGrain, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            make(kind, g.settings()).build(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, FilmGrain, Technique> kind,
        EngineSettings settings) {
        return kind.create(this, () -> kind.config(settings));
    }
}
