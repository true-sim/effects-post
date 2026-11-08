# true-sim effects-post

Post-processing for true-sim: the post effects of a view, each a technique slot with `off` as a choice, in the
engine's frame graph. It is an optional library over the engine's core and uses only the core's generic hooks:

- the frame graph's modifiers of `Keys.SCENE_COLOR` at `Anchor.POST` (scene light, before tone mapping), reading
  `Keys.SCENE_DEPTH` where they need depth;
- `DisplayTransform`'s display-space stage (`DisplayTransform.displayEffects()`, `DisplayTransform.DISPLAY_COLOR`, at
  `Anchor.TONE_MAP`) for effects in display light, and its exposure input (`DisplayTransform.EXPOSURE_INPUT`) for auto
  exposure;
- the technique registry and the settings registry of engine-settings: every tunable is a setting, listed with every
  other one;
- the engine's Slang modules (`bindless`, `materials`), imported from its jars; the shaders are under `post/` and load
  as the engine's do;
- the scene's asset cache and resources (`SceneResources`) for the textures effects name by asset path: grading
  tables (`.cube`), lens kernel images, post materials' textures (`PostTextures`).

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

The convolution's kernel can be an image instead (`render.bloom.fft.kernel`, an asset path: a PNG or Radiance HDR
texture loaded through `PostTextures`, `new Bloom(textures)`), its width `kernelSize` of the screen's long side, read
from its mip chain at the FFT's scale and normalised per channel like the procedural one (light kept: tested with a
streak kernel). Until it has loaded the procedural kernel stands in; the graph rebuilds once it is there
(`buildVersion`), making its spectrum once.

## The chain

At `Anchor.POST`, in scene light: depth of field, bloom, lens effects, grading, post materials (by their anchor and
order), the scene-light flash; auto exposure then measures what they left. At `Anchor.TONE_MAP`, in display light
(the window's `DisplayTransform` made with `displayEffects()`): film grain, the display-light flash, post materials
anchored there or at `Anchor.UI`. Each is a technique of the view, its kind a slot in the settings:

```java
PostTextures.install(paths);                                   // the .cube importer
PostTextures textures = new PostTextures(resources, paths);    // textures named by asset path, held in the scene's resources
ScreenFlash overlay = new ScreenFlash();
port.add(new DepthOfField(camera)).add(new Bloom(textures)).add(new LensEffects(camera))
    .add(new ColorGrading(textures)).add(new AutoExposure()).add(new FilmGrain())
    .add(overlay.display()).add(overlay.scene());
presenter.outputTransform(new DisplayTransform().displayEffects());
```

Effects with a clock (auto exposure's adaptation, autofocus, post materials' time) take a `DoubleSupplier` of seconds,
game time by choice; without one, real seconds.

## Auto exposure

`AutoExposure` provides `DisplayTransform.EXPOSURE_INPUT`, the factor scene color is multiplied by before the tone
curve, measured on the GPU in the frame that uses it (no readback). Slot `render.exposure`:

| Kind | What it does |
|---|---|
| `histogram` (default) | 256 bins of log2 luminance over the render region, averaged between the `low` and `high` shares of the pixels (0.5 and 0.95): the darkest and brightest (a black sky, the sun, a lamp) leave it alone. |
| `average` | The log average of every pixel: simpler, pulled by large dark or bright areas. |
| `off` | `display.exposure` (a 4-byte fill). |

The average is shown at the key (`key`): `middle_gray` (`keyValue`, 0.18) or `krawczyk`, Krawczyk, Myszkowski and
Seidel's key for the absolute luminance (2005, Eq. 11: 1.03 - 2 / (2 + log10(L + 1))), so night stays dark and noon
bright. The absolute luminance is scene color times `luminanceScale` (cd/m² per unit). Then `compensation` stops,
limits (`minEv`, `maxEv`), and adaptation exponential in stops at `speedUp` (towards brighter) and `speedDown`.

**With effects-atmosphere.** effects-atmosphere's `AtmosphereExposure` (v0.3.0) computes the same key on the CPU and
turns it into the atmosphere's EV100 (`render.sky.atmosphere.ev100`), which sets the scene's units: scene color is
luminance times 1 / (1.2 * 2^EV100). The two are layers, not alternatives, which is why the key is not a second copy
here: the EV100 is a pre-exposure keeping 16-bit scene color in range from noon (10^4 cd/m²) to a moonless night
(10^-4), which no exposure after rendering can recover; this auto exposure measures what was rendered (every light,
surface and bloom) in the frame that shows it. Use both by setting `luminanceScale` to 1.2 * 2^EV100 whenever the EV100
changes (each frame, if the app eases it with `AtmosphereExposure.adapt`) and choosing the `krawczyk` key: the
exposure is kept per cd/m², so a change of units leaves the image as it was, even mid-adaptation (tested), and the key
sees true luminances. Do not also bring both to middle gray: that exposes twice.

Tests (`AutoExposureTest`): the average at the key and a stop per stop of compensation for both kinds; the histogram
leaving a lamp out and the average not (the log average to 2e-4); the limits; off as the setting; the adaptation
following `target + (start - target) e^(-speed t)` in stops at each speed to 1%; Krawczyk's key at noon, dusk and night
to 0.5%; a pre-exposure change mid-adaptation leaving the image as it was to 1%; only the render region measured.

## Color grading

`ColorGrading` grades scene light in place after bloom and the lens: white balance (Bradford, from the light's white
at `temperature` and `tint` to D65), contrast, lift, gamma and gain per channel in a 24-stop log encoding, saturation,
and an authored table (`table`, a `.cube` asset path, mixed in by `tableShare`, in the log encoding). Slot
`render.grading`:

| Kind | What it does |
|---|---|
| `baked` (default) | Everything baked each frame into a `tableSize`^3 table (32) over the log encoding, each pixel looked up trilinearly: a fixed cost per pixel whatever is graded (Unreal's way). Within 1.5% of `direct` at 32 cells. |
| `direct` | Every operation on every pixel: exact, the reference. |
| `off` | No passes. |

`.cube` tables load as 3D textures of half floats (`PostTextures.CUBE`, `ColorLut.texture()`), held in the scene's
resources while a grading names them, interpolated by hand (a sampler's 3D filtering is 8-bit exact:
[docs/pitfalls.md](docs/pitfalls.md)). Tests (`ColorGradingTest`): Lindbloom's Bradford matrix to 2e-5, the daylight
and Planckian loci at D50, D65 and A; defaults and off change nothing; contrast, lift, gamma, gain and saturation
against their formulas; a finer table following `direct` closer; 3200 K light graded neutral; a table loaded through
the asset cache applied in the log encoding, at half share half way, and an identity table changing nothing.

## Depth of field

`DepthOfField` blurs scene light before bloom by the thin lens' circle of confusion from the camera's settings
(`camera.fStop`, `camera.sensorHeight` with the camera's field of view: the focal length; `camera.focusDistance`, or
`camera.autofocus`: the median of nine depth taps over `camera.autofocusArea` of the view, followed at
`camera.autofocusSpeed` in log distance, on the GPU). Those are the camera's settings, not a technique's: the same lens
whichever blur draws it. Slot `render.dof`:

| Kind | What it does |
|---|---|
| `gather` (default) | After Jimenez (2014): near and far fields at half resolution, 49 taps over a disc, the near field spread over sharp edges by tiles of 16 pixels dilated as far as `maxRadius` reaches, a 3 x 3 median; the far field upsampled bilaterally by circle, so in-focus edges get no fringe. |
| `gaussian` | A separable Gaussian weighted by circle at half resolution: cheaper and soft; the near field does not spread over sharp edges. |
| `off` | No passes. |

Tests (`DepthOfFieldTest`): the circle against the thin lens; in focus stays sharp; an edge's spread against the
circle, light kept; the near field over sharp edges (gather only); no halo from a sharp foreground onto a blurred
background or back, from its edge pixel on, at even and odd edges; autofocus finding the middle and following at its
speed; the render region.

## Lens effects

`LensEffects` (after bloom, before grading; one pass, built only while one is on) has three slots, all off by default:
`render.lens.distortion` (`brown_conrady`: r (1 + k r^2), the radius 1 at the corners, k from -1/3 so the image never
folds, scaled so the frame stays filled), `render.lens.aberration` (`radial`: three taps; `spectral`: eight
wavelengths, each channel's weights summing to one; the image scaled by 1 / (1 + amount) so red stays inside) and
`render.lens.vignette` (`natural`: the cos^4 law for the camera's field of view; `artistic`: a falloff over the outer
`smoothness`, `roundness`). Tests (`LensEffectsTest`): off; the cos^4 law to 2e-3; the artistic falloff's reach;
distortion and aberration moving what is read as their formulas; the frame filled and never folded from k -1/3 to 1;
spectral aberration keeping a uniform color and splitting less than radial.

## Screen flash and fade

`ScreenFlash` is a game's overlay (a flash that decays, a fade to or from a color), two techniques of the view
(`display()`, `scene()`), slot `render.flash`: `display` (default: over the display color, the color as given; on HDR
a color of 1 at paper white or the peak, `hdrWhite`) or `scene` (scene light, tone mapped with the scene). With
`accessibility.flashes` REDUCED every flash stays below WCAG 2.x's general flash threshold (at most a tenth of the
relative luminance, `reducedAmount`; rising and falling over a quarter second at least, `reducedRise`; the color at
most white); OFF shows none; with either, fades take half a second at least (`slowestFade`), and the overlay is
blended in display light, where those limits hold whatever the tone curve (tested frame by frame through the display
transform for both kinds). The limits' ranges stop at WCAG's.

## Film grain

`FilmGrain` (slot `render.grain`, off by default; `film`): Unity's form in display light, value noise over cells of
`size` pixels new each frame, `intensity` of the color, less in the highlights by `response`. Black stays black and the
mean is kept (tested to 2%), and the spread in the highlights is as the response says (to 10%).

## Post materials

`PostMaterial` runs a game's Slang post effect (`IPostMaterial` in `post/material.slang`; its parameters a
`[MaterialParameters(0)]` struct the build makes a record of; the scene depth, up to four textures, time, size) at its
anchor: scene light before `Anchor.TONE_MAP`, display light at it or at `Anchor.UI`. Each material is a slot of its
own, named as it (`on` or `off`: a rebuild, as it provides a new image). Its textures are asset paths through
`PostTextures` or slots the game holds. Tests (`PostMaterialTest`, its materials in `src/test/slang/test/post`): a wipe
by a PNG mask loaded through the asset cache; ordering and switching off (the scene, not an unwritten image); a damage
vignette in display light; heat haze through a normal map where the scene is near, and scrolling with its clock.

## Held back

The color vision filters of the bloom-and-tone branch (Machado 2009 simulation, Fidaner 2005 daltonizing) wait for the
color-blind correction review of true-sim/engine#403 before they land here.

## Films

`PostFilms` (`TRUESIM_SCREENSHOTS=true`, one film with `TRUESIM_FILM=<name>`) films each effect over `PostScene` (a
house with a doorway towards the sun), four views side by side, each in a frame graph of its own (its own settings and
temporal state): `exposure` (from the dark house into the sun and back: fixed, histogram, average, Krawczyk), `grading`,
`dof` (autofocus racking between a near and a far subject), `lens`, `flash` (FULL, REDUCED, OFF, scene light),
`grain`, `materials`.

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
