// Convolution bloom (like Unreal's): the scene color convolved with a lens' point spread function (a kernel: its
// core, diffraction spikes, a corona) through 2D FFTs, at a reduced, zero-padded power-of-two size N.
//
// The scene's render region is box-filtered (exact areas) into the top left of an N x N image, its long side over N / 2
// texels, the rest zeros, so a kernel reaching up to N / 2 texels (the screen's long side) convolves without wrapping.
// Each texel holds two complex numbers: red + i green and blue + i 0 (two real signals per transform; their spectra
// are separated by conjugate symmetry where they are multiplied). The kernel is centered at texel (0, 0), wrapped; its
// spectrum, each channel divided by its sum (the spectrum at 0), is made once per kernel. The convolution then moves
// light without adding or losing any.
//
// The FFT is radix-2 (Cooley-Tukey, decimation in time): one workgroup per row or column, the line loaded in
// bit-reversed order into shared memory, log2 N butterfly stages in place.
import bindless;

struct FftPush
{
    uint image;         // storage slot: the image transformed in place, or written
    uint source;        // sampled slot: what is read (the scene color, the spectrum)
    uint target2;       // storage slot of a second output (the kernel's blue spectrum)
    uint source2;       // sampled slot of a second input (the kernel's blue spectrum)
    uint n;             // the FFT's size
    uint log2n;
    uint inverse;       // 1: the inverse transform (scaled by 1 / n per axis)
    uint axis;          // 0: rows, 1: columns
    uint2 region;       // input: the scene's render region
    float scale;        // input: scene pixels per FFT texel
    uint blades;        // kernel: aperture blades
    float rotation;     // kernel: the blades' rotation, radians
    float core;         // kernel, in FFT texels: the core's sigma
    float spikeLength;  // the spikes' length
    float spikeWidth;   // their width (sigma across)
    float spikeFalloff; // the power their brightness falls with along them
    float spikes;       // their share of the light
    float coronaRadius; // the corona's radius
    float coronaWidth;  // its width (sigma)
    float corona;       // its share of the light
    float spread;       // chromatic spread: 0 none, 1 each channel's kernel scaled by its wavelength
    float coronaRed;    // the corona's color
    float coronaGreen;
    float coronaBlue;
};

[[vk::push_constant]]
ConstantBuffer<FftPush> push;

groupshared float4 samples[1024];

float2 complexMul(float2 a, float2 b)
{
    return float2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

float2 conjugate(float2 a)
{
    return float2(a.x, -a.y);
}

/** The forward or inverse FFT of the rows or columns of `image`, in place: workgroup x transforms line x. */
[shader("compute")]
[numthreads(256, 1, 1)]
void fftMain(uint3 group : SV_GroupID, uint index : SV_GroupIndex)
{
    uint n = push.n;
    uint which = group.x;
    for (uint i = index; i < n; i += 256)
    {
        uint2 at = push.axis == 0 ? uint2(i, which) : uint2(which, i);
        samples[reversebits(i) >> (32 - push.log2n)] = images[push.image][at];
    }
    GroupMemoryBarrierWithGroupSync();
    float sign = push.inverse != 0 ? 1 : -1;
    for (uint s = 1; s <= push.log2n; s++)
    {
        uint span = 1u << (s - 1);
        for (uint b = index; b < n / 2; b += 256)
        {
            uint j = b % span;
            uint i0 = (b / span) * (span * 2) + j;
            uint i1 = i0 + span;
            float angle = sign * 3.14159265358979 * j / span;
            float2 w = float2(cos(angle), sin(angle));
            float4 u = samples[i0];
            float4 v = samples[i1];
            float4 t = float4(complexMul(w, v.xy), complexMul(w, v.zw));
            samples[i0] = u + t;
            samples[i1] = u - t;
        }
        GroupMemoryBarrierWithGroupSync();
    }
    float norm = push.inverse != 0 ? 1.0 / n : 1;
    for (uint i = index; i < n; i += 256)
    {
        uint2 at = push.axis == 0 ? uint2(i, which) : uint2(which, i);
        images[push.image][at] = samples[i] * norm;
    }
}

/** The scene's render region, box-filtered by exact areas into the top left; zeros elsewhere. */
[shader("compute")]
[numthreads(8, 8, 1)]
void inputMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.n))
    {
        return;
    }
    float2 from = float2(id.xy) * push.scale;
    float2 to = from + push.scale;
    float3 sum = 0;
    if (all(from < float2(push.region)))
    {
        int2 first = int2(floor(from));
        int2 last = min(int2(ceil(to)) - 1, int2(push.region) - 1);
        for (int y = first.y; y <= last.y; y++)
        {
            float wy = min(to.y, y + 1) - max(from.y, y);
            for (int x = first.x; x <= last.x; x++)
            {
                float wx = min(to.x, x + 1) - max(from.x, x);
                sum += max(textures[push.source].Load(int3(x, y, 0)).rgb, 0) * (wx * wy);
            }
        }
        sum /= push.scale * push.scale; // the average over the texel's footprint
    }
    images[push.image][id.xy] = float4(sum.r, sum.g, sum.b, 0);
}

/** Texel `t`'s offset from the kernel's center at (0, 0), wrapped: -n/2 to n/2. */
float2 centered(uint2 t)
{
    int2 p = int2(t);
    int span = int(push.n / 2);
    return float2(p.x >= span ? p.x - int(push.n) : p.x, p.y >= span ? p.y - int(push.n) : p.y);
}

/**
 * The procedural kernel at offset `p` (FFT texels), for one channel: scaled by `stretch` (its wavelength's), the
 * corona's light times `tint` (the corona's color in that channel). Each part integrates to its share of the light.
 */
float procedural(float2 p, float stretch, float tint)
{
    p /= stretch;
    float r = length(p);
    float core = exp(-0.5 * r * r / (push.core * push.core)) / (6.2831853 * push.core * push.core);
    // Spikes: an aperture of B straight blades diffracts into B spikes for even B, 2 B for odd B (the pairs
    // overlap for even counts), each a line from the center, Gaussian across, falling off along by a power.
    uint count = push.blades % 2 == 0 ? push.blades : 2 * push.blades;
    float spikes = 0;
    if (push.blades >= 3 && push.spikes > 0)
    {
        for (uint k = 0; k < count; k++)
        {
            float a = push.rotation + 6.2831853 * k / count;
            float2 d = float2(cos(a), sin(a));
            float along = dot(p, d);
            float across = dot(p, float2(-d.y, d.x));
            if (along > 0 && along < push.spikeLength)
            {
                spikes += pow(1 - along / push.spikeLength, push.spikeFalloff)
                    * exp(-0.5 * across * across / (push.spikeWidth * push.spikeWidth));
            }
        }
        // Each spike's light: sqrt(2 pi) width times length / (falloff + 1)
        spikes /= count * 2.5066283 * push.spikeWidth * push.spikeLength / (push.spikeFalloff + 1);
    }
    float ring = r - push.coronaRadius;
    float coronaArea = 6.2831853 * push.coronaRadius * 2.5066283 * push.coronaWidth;
    float corona = push.corona > 0 ? exp(-0.5 * ring * ring / (push.coronaWidth * push.coronaWidth)) / coronaArea : 0;
    return ((1 - push.spikes - push.corona) * core + push.spikes * spikes + push.corona * tint * corona)
        / (stretch * stretch);
}

/** The kernel, packed as the scene is (red + i green, blue + i 0), centered at texel (0, 0). */
[shader("compute")]
[numthreads(8, 8, 1)]
void kernelMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.n))
    {
        return;
    }
    float2 p = centered(id.xy);
    // Diffraction scales with wavelength: red (610 nm) wider than green (550), blue (465) narrower.
    float3 stretch = 1 + push.spread * (float3(610, 550, 465) / 550 - 1);
    float3 k = float3(procedural(p, stretch.r, push.coronaRed), procedural(p, stretch.g, push.coronaGreen),
        procedural(p, stretch.b, push.coronaBlue));
    images[push.image][id.xy] = float4(k.r, k.g, k.b, 0);
}

/**
 * The kernel's spectrum, separated: red and green from their packed transform by conjugate symmetry, each channel
 * divided by its sum (its spectrum at 0) so the convolution keeps each channel's light.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void spectrumMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.n))
    {
        return;
    }
    float4 z = textures[push.source].Load(int3(id.xy, 0));
    float4 m = textures[push.source].Load(int3((push.n - id.xy) % push.n, 0));
    float4 dc = textures[push.source].Load(int3(0, 0, 0));
    float2 red = (z.xy + conjugate(m.xy)) * 0.5;
    float2 diff = (z.xy - conjugate(m.xy)) * 0.5;
    float2 green = float2(diff.y, -diff.x); // divided by i
    float2 blue = z.zw;
    red /= max(dc.x, 1e-20);
    green /= max(dc.y, 1e-20);
    blue /= max(dc.z, 1e-20);
    images[push.image][id.xy] = float4(red, green);
    images[push.target2][id.xy] = float4(blue, 0, 0);
}

/** The scene's spectrum times the kernel's, channel by channel, packed again (red + i green, blue + i 0). */
[shader("compute")]
[numthreads(8, 8, 1)]
void multiplyMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.n))
    {
        return;
    }
    float4 z = images[push.target2][id.xy];
    float4 m = images[push.target2][(push.n - id.xy) % push.n];
    float2 red = (z.xy + conjugate(m.xy)) * 0.5;
    float2 diff = (z.xy - conjugate(m.xy)) * 0.5;
    float2 green = float2(diff.y, -diff.x);
    float4 kernel = textures[push.source].Load(int3(id.xy, 0));
    float2 kernelBlue = textures[push.source2].Load(int3(id.xy, 0)).xy;
    red = complexMul(red, kernel.xy);
    green = complexMul(green, kernel.zw);
    float2 blue = complexMul(z.zw, kernelBlue);
    images[push.image][id.xy] = float4(red + float2(-green.y, green.x), blue); // red + i green
}
