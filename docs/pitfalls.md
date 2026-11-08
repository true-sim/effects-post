# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository, in the engine's
[docs/pitfalls.md](https://github.com/true-sim/engine/blob/main/docs/pitfalls.md) form: what went wrong, how that was
established, the rule that follows, and where the proof is. Only verified things go here: a measurement, a reproduced
failure, a test that fails without the fix, or a reading of the code that shows the fault.

The engine's entries apply here too; two of them came from the post effects: a switched-off modifier that provides a
new version of a key, and `[shader(` in a module's comment.

## Ported from the bloom-and-tone branch: faults its tests did not catch

The post chain came from the unpushed `bloom-and-tone` branch (true-sim/engine#447). Porting it with tests that
measure what they claim found four faults there; each test below fails with the branch's code and passes now.

**A sampler's trilinear filtering of a 3D table is 8-bit exact.** The authored grading table was sampled through
`SampleLevel` on its 3D texture: the hardware weighs the eight cells by the fraction's 8 bits, 1/512 of a cell, and a
table of 2 cells swapping red and blue gave 0.9746 instead of 1 (2.5%) at white.
- Proof: `ColorGradingTest.anAuthoredTableLoadsAsAnAssetAndIsAppliedInTheLogEncoding`, kind `direct`, on the RX 9070
  XT; with the eight cells loaded and weighed in the shader (`grading.slang`, `authoredAt`) within 5e-3.
- Rule: interpolate lookup tables by hand where their values matter to more than a few bits; a sampler is for images.

**Barrel distortion's fill scale read beyond the frame's top and bottom.** The branch scaled positions by
`1 + k max(aspect^2, 1)` for k < 0, which keeps the left and right edges' middles inside but sends the top and bottom
middles outside the image (clamped: repeated rows) on a landscape frame.
- Proof: `LensEffectsTest.distortionKeepsTheFrameFilledAndNeverFolds` with that scale: "growing down the middle at 1:
  0.00390625 not greater than 0.00390625" (two rows read the same); with `min(aspect^2, 1)` (the radius 1 at the
  corners) every row and column grows.
- Rule: a distortion's fill test checks that what each edge pixel reads grows along every edge, not only that it
  stays inside (clamping hides reads beyond).

**Reduced flashes fell as fast as the game asked.** With `accessibility.flashes` REDUCED a flash rose over a quarter
second but then decayed over the game's duration: a 0.1 s flash dropped its tenth of the luminance at 0.5 a second,
above the 0.4 its rise allowed.
- Proof: `ScreenFlashTest.reducedFlashesChangeTheShownLuminanceByATenthAtMostWhateverTheKind`, the shown luminance
  frame by frame through the display transform: 0.49988 per second against at most 0.45; decaying no quicker than it
  rose (`reducedRise`), 0.4.
- Rule: a limit on how fast something changes holds for both directions; test it on what the display shows.

**Depth of field's bilinear upsample put the sharp foreground onto the blurred background.** The composite read the
half-resolution far field bilinearly, so the first background pixel beside an in-focus edge took a quarter of the
foreground's texel: a dark fringe of 0.75 where the background was 1.
- Proof: `DepthOfFieldTest.aSharpForegroundGetsNoHaloFromTheBlurredBackground`, gather, the edge at x = 96: 0.7495
  against more than 0.98; with the four texels weighed by how alike their circles are (`dof.slang`, `farAt`) both
  kinds pass at even and odd edges. The branch had no such test; this one's first version began two pixels from the
  edge and passed with the fault.
- Rule: halo tests start at the edge pixel itself, with edges on and between the half-resolution texels.

## Lavapipe

**One compute shader for two modes crashed lavapipe on one of them; its cause was not found.** Auto exposure's
`measureMain` and `adaptMain` served both the histogram and the log average, branching on a push constant: every frame
of the histogram crashed the test JVM in lavapipe's compute threads (SIGSEGV on CI's Linux runners, an access
violation on Windows' Mesa 26.2.6), with each histogram path emptied in turn and with the clear pass and the buffer's
size made the average's; the average ran. An observation, not a cause: the histogram path returned before the
average's barriers (uniformly, by the push constant).
- Proof: CI run 42468640123 of true-sim/effects-post#2, shard 1, twice; `AutoExposureTest` on the local lavapipe
  crashed in every histogram test and passed with entry points of their own per mode (`histogramMain`,
  `adaptHistogramMain`, `averageMain`, `adaptAverageMain`).
- Rule: give each mode of a compute shader that uses barriers an entry point of its own; run new shaders on the local
  lavapipe before CI.
