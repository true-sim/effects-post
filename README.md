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

## Bloom

`Bloom` modifies the view's scene color in place at `Anchor.POST`, in scene light before tone mapping, as the kind
chosen for its slot `render.bloom` does it: `(1 - intensity) scene + intensity bloom`. Every filter's weights sum to
one, so bloom moves light without adding or losing any (tested to 1%), and with no threshold (the default) all light
blooms, as in a lens. It works within the view's render region (dynamic resolution: nothing bleeds in from beyond it).

```java
port.add(new Bloom());                                         // after shading, before the display transform
Bloom.SLOT.choose(graph.settings(), Bloom.DUAL_KAWASE);        // render.bloom=dual_kawase (off: null)
graph.settings().set(Bloom.DUAL_KAWASE.setting("intensity"), 0.08f);   // render.bloom.dual_kawase.intensity
Bloom.register(registry);                                      // the slot and its tunables, for settings screens
```

| Kind | What it does |
|---|---|
| `physical` (default) | Jimenez's chain (Call of Duty: Advanced Warfare, 2014): a 13-tap downsample, Karis' average on the first level, 3 x 3 tent upsamples added level by level. Wide and smooth. |
| `dual_kawase` | Bjorge's dual Kawase filter (2015): 5 taps down, 8 up. The cheapest; one wide blur with less of a long tail. |
| `gaussian` | A separable Gaussian at each level, summed: the reference the chains approximate, at more taps. |
| `fft` | Convolution with a lens' point spread function through radix-2 FFTs in compute at a padded power-of-two size (`size`, at most 1024: a line of 1024 complex pairs is the 16 KiB of shared memory every Vulkan device has). The kernel is procedural: a core, the diffraction spikes of an aperture's blades, a corona, chromatic spread by wavelength. Its spectrum is made once per kernel. Optionally mixed with the physical chain's wide glow (`chain`). |
| `off` | No passes at all (a rebuild). |

Each kind's tunables are settings under `render.bloom.<kind>.`: `intensity`, `levels`, `radius`, `threshold` (with
its soft `knee`), `antiFlicker` (Karis' average), and for `fft` the kernel's (`blades`, `rotation`, `core`,
`spikeLength`, `spikeWidth`, `spikeFalloff`, `spikes`, `coronaRadius`, `coronaWidth`, `corona`, `coronaRed`,
`coronaGreen`, `coronaBlue`, `spread`), its `size` and the chain's share. The kind, the levels, the FFT's size and
the kernel are read when the graph builds (a change rebuilds it); the others as frames record.

On an RX 9070 XT (2026-11-08, `BloomPictures.benchmark`, the bright test scene at dusk: shading, shadows, sky, bloom
and the display transform), the median of 300 frames: bloom's own passes (their GPU timestamps summed) and the frame's
GPU span. The default kinds' tunables; `fft` at its default size 512 unless named. At this scene's small cost the
span varies by about 0.03 ms between runs, more than the cheapest blooms add.

| Kind | 1920 x 1080: bloom ms | frame ms | 3840 x 2160: bloom ms | frame ms |
|---|---|---|---|---|
| off | 0 | 0.330 | 0 | 0.585 |
| physical | 0.089 | 0.333 | 0.222 | 0.811 |
| dual_kawase | 0.087 | 0.363 | 0.189 | 0.777 |
| gaussian | 0.100 | 0.397 | 0.194 | 0.774 |
| fft | 0.117 | 0.324 | 0.502 | 1.089 |
| fft, size 1024 | 0.297 | 0.486 | 0.887 | 1.468 |
| fft with the chain at 0.5 | 0.360 | 0.550 | 1.059 | 1.628 |

Every kind passes the same tests (`BloomTest`): light kept to 1%, symmetric spread, nothing from beyond the render
region, Karis' average holding back fireflies, and a pixel of light moving across the levels' cells spreading the same
light wherever it falls (within 1%; the convolution exactly). Pictures and films of each against off:
`BloomPictures` and `BloomFilms` (`TRUESIM_SCREENSHOTS=true`).

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
