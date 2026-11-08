// Auto exposure (eye adaptation), on the GPU, in the frame it is used: the scene color's luminance measured over the
// render region, the exposure that brings it to the key (middle gray, or Krawczyk's key from its absolute luminance)
// times 2^compensation, clamped, and the kept exposure moved towards it, exponentially in stops, at the speed of
// adapting up (to brighter) or down, over the frame's seconds. No readback: the display transform reads the exposure
// from the state buffer in the same frame (DisplayTransform.EXPOSURE_INPUT, its first float).
//
// The exposure is kept in absolute terms (per cd/m²: scene color times `scale`), so a change of the scene's units (an
// atmosphere's EV100 used as a pre-exposure) does not restart the adaptation: the image stays as it was.
// - HISTOGRAM: 256 bins of log2 luminance; the average over the pixels between the low and high shares of them (the
//   darkest and the brightest left out: a black sky, the sun, a lamp).
// - AVERAGE: the log average (geometric mean) of every pixel's luminance.
import bindless;

struct ExposurePush
{
    uint source;        // sampled slot of the scene color
    uint mode;          // MODE_*
    uint2 size;         // the render region
    uint* bins;         // HISTOGRAM: 256 counts; AVERAGE: a float sum and a count per workgroup
    float* state;       // [0] the factor scene color is multiplied by; [1] the kept exposure, log2 per cd/m²; [2] 1 once
                        // kept; [3] this frame's target, log2 per cd/m²
    uint groups;        // AVERAGE: the workgroups' sums
    float minLog;       // the measure's range of log2 luminance (scene units)
    float maxLog;
    float low;          // HISTOGRAM: the share of the darkest pixels left out (0 to 1)
    float high;         // HISTOGRAM: the share up to which pixels count, from the darkest (0 to 1)
    float compensation; // stops added to what the measure asks
    float minEv;        // the exposure's range, log2 per cd/m² (per scene unit with a scale of 1)
    float maxEv;
    float up;           // adaptation speed towards a brighter image, per second
    float down;         // towards a darker one
    float seconds;      // since the last frame
    uint key;           // KEY_*
    float keyValue;     // KEY_MIDDLE_GRAY: what the average is shown as
    float scale;        // cd/m² per unit of scene color
};

static const uint MODE_HISTOGRAM = 0;
static const uint MODE_AVERAGE = 1;
static const uint KEY_MIDDLE_GRAY = 0;
static const uint KEY_KRAWCZYK = 1;
static const uint BINS = 256;

[[vk::push_constant]]
ConstantBuffer<ExposurePush> push;

groupshared uint counts[BINS];
groupshared float sums[256];

float luminance(float3 c)
{
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

/** The bin of luminance `l`: 0 below the range (black), 1 to 255 over it. */
uint binOf(float l)
{
    if (l < exp2(push.minLog))
    {
        return 0;
    }
    float t = saturate((log2(l) - push.minLog) / (push.maxLog - push.minLog));
    return 1 + uint(t * 254 + 0.5);
}

/** The log2 luminance at the middle of bin `b` (1 to 255). */
float logOf(uint b)
{
    return push.minLog + (b - 1) / 254.0 * (push.maxLog - push.minLog);
}

[shader("compute")]
[numthreads(16, 16, 1)]
void measureMain(uint3 id : SV_DispatchThreadID, uint index : SV_GroupIndex, uint3 group : SV_GroupID)
{
    bool inside = all(id.xy < push.size);
    float l = inside ? luminance(max(textures[push.source].Load(int3(id.xy, 0)).rgb, 0)) : 0;
    if (push.mode == MODE_HISTOGRAM)
    {
        counts[index] = 0;
        GroupMemoryBarrierWithGroupSync();
        if (inside)
        {
            InterlockedAdd(counts[binOf(l)], 1);
        }
        GroupMemoryBarrierWithGroupSync();
        if (counts[index] != 0)
        {
            InterlockedAdd(push.bins[index], counts[index]);
        }
        return;
    }
    sums[index] = inside ? clamp(log2(max(l, 1e-30)), push.minLog, push.maxLog) : 0;
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = 128; stride > 0; stride >>= 1)
    {
        if (index < stride)
        {
            sums[index] += sums[index + stride];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (index == 0)
    {
        uint g = group.y * ((push.size.x + 15) / 16) + group.x;
        push.bins[2 * g] = asuint(sums[0]);
        push.bins[2 * g + 1] = min(push.size.x - group.x * 16, 16) * min(push.size.y - group.y * 16, 16);
    }
}

/** Krawczyk, Myszkowski and Seidel 2005 (Eq. 11): the key for a log average of `luminance` cd/m². */
float krawczyk(float luminance)
{
    return 1.03 - 2 / (2 + log10(max(luminance, 0) + 1));
}

/** The exposure for this frame, from the measure, adapted from the last: one workgroup of 256. */
[shader("compute")]
[numthreads(256, 1, 1)]
void adaptMain(uint3 id : SV_DispatchThreadID, uint index : SV_GroupIndex)
{
    float average; // log2 luminance, scene units
    if (push.mode == MODE_HISTOGRAM)
    {
        if (index != 0)
        {
            return;
        }
        uint total = 0;
        for (uint b = 0; b < BINS; b++)
        {
            total += push.bins[b];
        }
        // The pixels between the low and high shares of the sorted luminances, each bin's part of them.
        float from = push.low * total;
        float to = push.high * total;
        float below = 0;
        float sum = 0;
        float count = 0;
        for (uint b = 0; b < BINS; b++)
        {
            float n = push.bins[b];
            float kept = max(min(below + n, to) - max(below, from), 0);
            sum += kept * (b == 0 ? push.minLog : logOf(b));
            count += kept;
            below += n;
        }
        average = count > 0 ? sum / count : push.minLog;
    }
    else
    {
        float sum = 0;
        uint count = 0;
        for (uint g = index; g < push.groups; g += 256)
        {
            sum += asfloat(push.bins[2 * g]);
            count += push.bins[2 * g + 1];
        }
        sums[index] = sum;
        counts[index] = count;
        GroupMemoryBarrierWithGroupSync();
        for (uint stride = 128; stride > 0; stride >>= 1)
        {
            if (index < stride)
            {
                sums[index] += sums[index + stride];
                counts[index] += counts[index + stride];
            }
            GroupMemoryBarrierWithGroupSync();
        }
        if (index != 0)
        {
            return;
        }
        average = counts[0] > 0 ? sums[0] / counts[0] : push.minLog;
    }
    float units = log2(push.scale);
    float absolute = average + units; // log2 cd/m²
    float key = push.key == KEY_KRAWCZYK ? max(krawczyk(exp2(absolute)), 1e-3) : push.keyValue;
    // Exposure per cd/m²: key / L, in stops
    float target = clamp(log2(key) - absolute + push.compensation, push.minEv, push.maxEv);
    float kept = push.state[1];
    float speed = target > kept ? push.up : push.down;
    // The first frame (nothing kept) starts at the target.
    float ev = push.state[2] > 0 ? lerp(kept, target, 1 - exp(-speed * push.seconds)) : target;
    push.state[0] = exp2(ev + units);
    push.state[1] = ev;
    push.state[2] = 1;
    push.state[3] = target;
}
