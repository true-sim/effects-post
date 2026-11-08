# true-sim effects-post

Post-processing for true-sim: the post effects of a view, each a technique slot with `off` as a choice, in the
engine's frame graph. It is an optional library over the engine's core and uses only the core's generic hooks:

- the frame graph's modifiers of `Keys.SCENE_COLOR` at `Anchor.POST` (scene light, before tone mapping);
- `DisplayTransform`'s display-space stage (`DisplayTransform.displayEffects()`, `DisplayTransform.DISPLAY_COLOR`, at
  `Anchor.TONE_MAP`) for effects in display light, and its exposure input (`DisplayTransform.EXPOSURE_INPUT`) for auto
  exposure;
- the technique registry and the settings registry of engine-settings: every tunable is a setting, listed with every
  other one;
- the engine's Slang modules (`bindless`), imported from its jars; the shaders are under `post/` and load as the
  engine's do.

| Module | Contents |
|---|---|
| `effects-post` | the post effects (`com.truesim.effects.post`) |

```kotlin
dependencies {
    implementation("com.truesim.effects:effects-post:0.1.0")
}
```

The plan, what is ported and what is still to come, is [true-sim/engine#447](https://github.com/true-sim/engine/issues/447).

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene` and `engine` are used as included builds when present
(override with `-Ptruesim.<repo>=<path>`, or `false` to use the published packages); otherwise the published packages
are used. The GPU tests run validated on any Vulkan 1.3 device (CI: Mesa's lavapipe, in test shards balanced by
`ci/test-timings.txt`); the benchmarks run with `TRUESIM_BENCHMARK=true`.

Before changing the shaders, read [docs/pitfalls.md](docs/pitfalls.md) (and the engine's): mistakes made here once,
with their evidence and the rule that follows.

No libraries beyond the JDK, true-sim's own and LWJGL.

## Licence

MIT, see [LICENSE](LICENSE).
