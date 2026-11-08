// Bloom in scene light: a chain of half-size levels of the scene color, filtered down and back up, mixed into the
// scene color by intensity. Every filter's weights sum to one, so the chain moves light without adding or losing any:
// the mix, (1 - intensity) scene + intensity bloom, keeps the image's total (energy-conserving). Each level is read
// within its render region (dynamic resolution): taps past it are clamped to its last texels.
//
// Techniques (BloomPush.mode):
// - PHYSICAL (Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014): a 13-tap
//   downsample (five overlapping 2 x 2 boxes, the middle one weighted 0.5, the corners 0.125 each), a 3 x 3 tent
//   upsample added to each level, the levels averaged.
// - DUAL_KAWASE (Bjorge, "Bandwidth-Efficient Rendering", SIGGRAPH 2015): 5 taps down, 8 taps up, one wide blur.
// - GAUSSIAN: a 2 x 2 box down, a separable Gaussian at each level, each added to the next finer one, averaged: the
//   reference the others approximate, at more taps.
// The first downsample can weight its boxes (taps) by 1 / (1 + luma), Karis' average, so single very bright pixels
// (fireflies) do not flicker as large blobs; and apply a soft-knee threshold (none by default: all light blooms).
import bindless;

struct BloomPush
{
    uint source;        // sampled slot: the level read (one mip's view)
    uint target;        // storage slot: the level written
    uint added;         // sampled slot of the level added to the result (upsample, Gaussian's vertical pass), or ~0
    uint mode;          // MODE_*
    uint2 size;         // the target's render region, in texels
    uint2 sourceRegion; // the source's render region, in texels
    uint2 addedRegion;  // the added level's
    uint first;         // 1 on the first downsample: Karis' average (if antiFlicker) and the threshold
    uint antiFlicker;
    float threshold;    // scene color brightness where bloom starts; 0: everything blooms
    float knee;         // the soft knee's width, as a fraction of the threshold
    float radius;       // the filters' spread, in source texels (1: as published)
    float scale;        // composite: what the finest level is multiplied by (1 / levels averaged)
    float intensity;    // composite: the share of bloom in the result
    int2 direction;     // Gaussian: the blur's axis
    uint fft;           // composite: sampled slot of the convolution bloom (fft.slang), or ~0
    float fftShare;     // composite: the convolution's share of the bloom (1: no chain)
    float fftScale;     // composite: FFT texels per scene pixel
    uint2 fftRegion;    // composite: the FFT texels the scene's region covers
};

static const uint MODE_PHYSICAL = 0;
static const uint MODE_DUAL_KAWASE = 1;
static const uint MODE_GAUSSIAN = 2;
static const uint MODE_FFT = 3; // composite: the convolution only, no chain
static const uint NONE = 0xffffffffu;

[[vk::push_constant]]
ConstantBuffer<BloomPush> push;

/** The level in `slot` at `texel` (in its texels, 0 at its top left corner), filtered, clamped within `region`. */
float3 sampleAt(uint slot, float2 texel, uint2 region)
{
    uint width;
    uint height;
    textures[slot].GetDimensions(width, height);
    float2 p = clamp(texel, 0.5, float2(region) - 0.5);
    return textures[slot].SampleLevel(samplers[LINEAR_CLAMP], p / float2(width, height), 0).rgb;
}

/** One texel of the level in `slot`, clamped within `region`. */
float3 texelAt(uint slot, int2 texel, uint2 region)
{
    return textures[slot].Load(int3(clamp(texel, 0, int2(region) - 1), 0)).rgb;
}

float luma(float3 c)
{
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

/** Karis' weight of `c`, where the first downsample uses it. */
float karis(float3 c)
{
    return push.first != 0 && push.antiFlicker != 0 ? 1 / (1 + luma(c)) : 1;
}

/** Scene color kept by the soft-knee threshold (Unity's): all of it without one. */
float3 threshold(float3 c)
{
    if (push.first == 0 || push.threshold <= 0)
    {
        return c;
    }
    float brightness = max(c.r, max(c.g, c.b));
    float knee = push.threshold * push.knee + 1e-5;
    float soft = clamp(brightness - push.threshold + knee, 0, 2 * knee);
    soft = soft * soft / (4 * knee);
    return c * max(soft, brightness - push.threshold) / max(brightness, 1e-5);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void downsampleMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float2 center = (float2(id.xy) + 0.5) * 2; // in source texels
    float r = push.radius;
    float3 result;
    if (push.mode == MODE_PHYSICAL)
    {
        // Each tap sits on a corner of four texels: a bilinear sample is their 2 x 2 box.
        float3 a = sampleAt(push.source, center + float2(-2, -2) * r, push.sourceRegion);
        float3 b = sampleAt(push.source, center + float2(0, -2) * r, push.sourceRegion);
        float3 c = sampleAt(push.source, center + float2(2, -2) * r, push.sourceRegion);
        float3 d = sampleAt(push.source, center + float2(-1, -1) * r, push.sourceRegion);
        float3 e = sampleAt(push.source, center + float2(1, -1) * r, push.sourceRegion);
        float3 f = sampleAt(push.source, center + float2(-2, 0) * r, push.sourceRegion);
        float3 g = sampleAt(push.source, center, push.sourceRegion);
        float3 h = sampleAt(push.source, center + float2(2, 0) * r, push.sourceRegion);
        float3 i = sampleAt(push.source, center + float2(-1, 1) * r, push.sourceRegion);
        float3 j = sampleAt(push.source, center + float2(1, 1) * r, push.sourceRegion);
        float3 k = sampleAt(push.source, center + float2(-2, 2) * r, push.sourceRegion);
        float3 l = sampleAt(push.source, center + float2(0, 2) * r, push.sourceRegion);
        float3 m = sampleAt(push.source, center + float2(2, 2) * r, push.sourceRegion);
        float3 boxes[5] = {(d + e + i + j) * 0.25, (a + b + f + g) * 0.25, (b + c + g + h) * 0.25,
            (f + g + k + l) * 0.25, (g + h + l + m) * 0.25};
        float share[5] = {0.5, 0.125, 0.125, 0.125, 0.125};
        float3 sum = 0;
        float weights = 0;
        for (int n = 0; n < 5; n++)
        {
            float w = share[n] * karis(boxes[n]);
            sum += boxes[n] * w;
            weights += w;
        }
        result = sum / weights;
    }
    else if (push.mode == MODE_DUAL_KAWASE)
    {
        float3 taps[5] = {
            sampleAt(push.source, center, push.sourceRegion),
            sampleAt(push.source, center + float2(-1, -1) * r, push.sourceRegion),
            sampleAt(push.source, center + float2(1, -1) * r, push.sourceRegion),
            sampleAt(push.source, center + float2(-1, 1) * r, push.sourceRegion),
            sampleAt(push.source, center + float2(1, 1) * r, push.sourceRegion)};
        float share[5] = {4, 1, 1, 1, 1};
        float3 sum = 0;
        float weights = 0;
        for (int n = 0; n < 5; n++)
        {
            float w = share[n] * karis(taps[n]);
            sum += taps[n] * w;
            weights += w;
        }
        result = sum / weights;
    }
    else
    {
        // The 2 x 2 box, texel by texel so Karis' average can weigh each.
        int2 corner = int2(id.xy) * 2;
        float3 sum = 0;
        float weights = 0;
        for (int n = 0; n < 4; n++)
        {
            float3 t = texelAt(push.source, corner + int2(n & 1, n >> 1), push.sourceRegion);
            float w = karis(t);
            sum += t * w;
            weights += w;
        }
        result = sum / weights;
    }
    images[push.target][id.xy] = float4(threshold(result), 1);
}

/** The coarser level `source` filtered up at target texel `p` of a level the size of `targetSize`. */
float3 filterUp(uint source, uint2 sourceRegion, uint2 p, float2 targetSize)
{
    uint width;
    uint height;
    textures[source].GetDimensions(width, height);
    float2 at = (float2(p) + 0.5) / targetSize * float2(width, height); // in source texels
    float r = push.radius;
    if (push.mode == MODE_PHYSICAL)
    {
        float3 sum = sampleAt(source, at, sourceRegion) * 4;
        sum += (sampleAt(source, at + float2(-r, 0), sourceRegion) + sampleAt(source, at + float2(r, 0), sourceRegion)
            + sampleAt(source, at + float2(0, -r), sourceRegion) + sampleAt(source, at + float2(0, r), sourceRegion))
            * 2;
        sum += sampleAt(source, at + float2(-r, -r), sourceRegion) + sampleAt(source, at + float2(r, -r), sourceRegion)
            + sampleAt(source, at + float2(-r, r), sourceRegion) + sampleAt(source, at + float2(r, r), sourceRegion);
        return sum / 16;
    }
    if (push.mode == MODE_DUAL_KAWASE)
    {
        float3 sum = sampleAt(source, at + float2(-2 * r, 0) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(2 * r, 0) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(0, -2 * r) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(0, 2 * r) * 0.5, sourceRegion);
        sum += (sampleAt(source, at + float2(-r, -r) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(r, -r) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(-r, r) * 0.5, sourceRegion)
            + sampleAt(source, at + float2(r, r) * 0.5, sourceRegion)) * 2;
        return sum / 12;
    }
    return sampleAt(source, at, sourceRegion);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void upsampleMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    uint width;
    uint height;
    images[push.target].GetDimensions(width, height);
    float3 result = filterUp(push.source, push.sourceRegion, id.xy, float2(width, height));
    if (push.added != NONE)
    {
        result += texelAt(push.added, int2(id.xy), push.addedRegion);
    }
    images[push.target][id.xy] = float4(result, 1);
}

/**
 * One axis of Gaussian's blur at a level (sigma 2 radius texels, taps to 3 sigma), plus, on the vertical pass, the
 * coarser level's result filtered up.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void gaussianMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float sigma = 2 * push.radius;
    int taps = int(ceil(3 * sigma));
    float3 sum = 0;
    float weights = 0;
    for (int i = -taps; i <= taps; i++)
    {
        float w = exp(-0.5 * i * i / (sigma * sigma));
        sum += w * texelAt(push.source, int2(id.xy) + push.direction * i, push.sourceRegion);
        weights += w;
    }
    float3 result = sum / weights;
    if (push.added != NONE)
    {
        uint width;
        uint height;
        images[push.target].GetDimensions(width, height);
        result += filterUp(push.added, push.addedRegion, id.xy, float2(width, height));
    }
    images[push.target][id.xy] = float4(result, 1);
}

/** The scene color, in place: (1 - intensity) of itself and intensity of the bloom, the finest level filtered up. */
[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    uint width;
    uint height;
    images[push.target].GetDimensions(width, height);
    float3 bloom = push.mode == MODE_FFT ? 0
        : filterUp(push.source, push.sourceRegion, id.xy, float2(width, height)) * push.scale;
    if (push.fft != NONE)
    {
        // The convolution's texel averages, bilinear (32-bit floats need not filter): what the pixel receives.
        float2 q = (float2(id.xy) + 0.5) * push.fftScale - 0.5;
        int2 a = int2(floor(q));
        float2 t = q - a;
        float3 sum = 0;
        for (int c = 0; c < 4; c++)
        {
            int2 o = int2(c & 1, c >> 1);
            int2 s = clamp(a + o, 0, int2(push.fftRegion) - 1);
            sum += textures[push.fft].Load(int3(s, 0)).xyz * ((o.x == 1 ? t.x : 1 - t.x) * (o.y == 1 ? t.y : 1 - t.y));
        }
        bloom = lerp(bloom, sum, push.fftShare);
    }
    float4 scene = images[push.target][id.xy];
    images[push.target][id.xy] = float4(lerp(scene.rgb, bloom, push.intensity), scene.a);
}
