// Color grading in scene light, before tone mapping (so SDR and HDR output grade alike).
//
// The log encoding (every table's input and output): log2((c + f) / 0.18) over 24 stops, f = 0.18 / 2^12, so 0 encodes
// to 0 exactly (a toe, as ACEScct's) and 0.18 * 2^12 - f (737) to 1; middle gray near 0.5. Brighter colors take the
// brightest cell.
//
// The operations, in order (each the identity at its default):
// - white balance (linear, per pixel before the table, exactly: a matrix interpolates badly over log cells): a Bradford
//   adaptation from the white of the chosen temperature and tint to that of 6504 K (D65);
// - contrast (log): around middle gray;
// - lift, gamma, gain (log, per channel): y = gain (x + lift (1 - x)), then y^(1 / gamma);
// - saturation (linear): around Rec. 709 luminance;
// - an authored table (log in, log out: a .cube asset, a 3D texture), mixed in by its share.
//
// Techniques: BAKED (Unreal's way) applies the operations to each cell of a size^3 table each frame, then each pixel is
// encoded, looked up (trilinear) and decoded, so the cost per pixel does not depend on what is graded; DIRECT applies
// them to each pixel (exact, the reference).
import bindless;

struct GradePush
{
    uint table;         // storage slot of the baked table (bake), sampled (apply): size^2 x size texels
    uint source;        // apply: storage slot of the scene color
    uint size;          // the baked table's cells per axis; 0: DIRECT
    uint authored;      // sampled slot of the authored table (a 3D texture), or NONE
    uint2 region;       // apply: the render region
    float4 lift;        // rgb; w: contrast
    float4 gamma;       // rgb; w: saturation
    float4 gain;        // rgb; w: the authored table's share
    float4 balance0;    // the white balance matrix's rows (linear Rec. 709)
    float4 balance1;
    float4 balance2;
};

static const float STOPS = 24;
static const float MIDDLE = 0.18;
static const uint NONE = 0xffffffffu;

[[vk::push_constant]]
ConstantBuffer<GradePush> push;

static const float FLOOR = MIDDLE / 4096; // 0.18 / 2^(STOPS / 2)

float3 encodeLog(float3 c)
{
    return log2((max(c, 0) + FLOOR) / MIDDLE) / STOPS + 0.5;
}

float3 decodeLog(float3 x)
{
    return max(MIDDLE * exp2((x - 0.5) * STOPS) - FLOOR, 0);
}

/**
 * The authored table at log color `x` (0 to 1 each), trilinear over its cells, by hand: a sampler's filtering weighs
 * by 8 bits of the fraction, 1/512 of a cell (measured: 2.5% at white for a table of 2 cells).
 */
float3 authoredAt(float3 x)
{
    uint width;
    uint height;
    uint depth;
    volumes[push.authored].GetDimensions(width, height, depth);
    uint n = width;
    float3 p = saturate(x) * (n - 1);
    uint3 a = min(uint3(p), n - 2);
    float3 t = p - a;
    float3 sum = 0;
    for (uint corner = 0; corner < 8; corner++)
    {
        uint3 o = uint3(corner & 1, (corner >> 1) & 1, corner >> 2);
        float3 w3 = lerp(1 - t, t, float3(o));
        sum += volumes[push.authored].Load(int4(a + o, 0)).rgb * (w3.x * w3.y * w3.z);
    }
    return sum;
}

/** The grading operations after white balance on scene color `c`. */
float3 grade(float3 c)
{
    float3 x = encodeLog(max(c, 0));
    x = (x - 0.5) * push.lift.w + 0.5;
    float3 y = push.gain.rgb * (x + push.lift.rgb * (1 - x));
    x = sign(y) * pow(abs(y), 1 / push.gamma.rgb);
    c = decodeLog(x);
    float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = max(luma + push.gamma.w * (c - luma), 0);
    if (push.authored != NONE && push.gain.w > 0)
    {
        float3 l = encodeLog(c);
        c = decodeLog(lerp(l, authoredAt(l), push.gain.w));
    }
    return c;
}

/** One cell of the baked table: its color graded, log encoded. Cell (r, g, b) at texel (r + b size, g). */
[shader("compute")]
[numthreads(4, 4, 4)]
void bakeMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id >= push.size))
    {
        return;
    }
    float3 x = float3(id) / (push.size - 1);
    images[push.table][uint2(id.x + id.z * push.size, id.y)] = float4(encodeLog(grade(decodeLog(x))), 1);
}

/** The baked table at log color `x`, trilinear over its cells (loaded: 32-bit floats need not filter). */
float3 tableAt(float3 x)
{
    uint n = push.size;
    float3 p = saturate(x) * (n - 1);
    uint3 a = min(uint3(p), n - 2);
    float3 t = p - a;
    float3 sum = 0;
    for (uint corner = 0; corner < 8; corner++)
    {
        uint3 o = uint3(corner & 1, (corner >> 1) & 1, corner >> 2);
        uint3 q = a + o;
        float3 w3 = lerp(1 - t, t, float3(o));
        sum += textures[push.table].Load(int3(q.x + q.z * n, q.y, 0)).rgb * (w3.x * w3.y * w3.z);
    }
    return sum;
}

/** The scene color graded in place: through the baked table, or directly. */
[shader("compute")]
[numthreads(8, 8, 1)]
void applyMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.region))
    {
        return;
    }
    float4 scene = images[push.source][id.xy];
    float3 c = max(scene.rgb, 0);
    c = max(float3(dot(push.balance0.xyz, c), dot(push.balance1.xyz, c), dot(push.balance2.xyz, c)), 0);
    c = push.size == 0 ? grade(c) : decodeLog(tableAt(encodeLog(c)));
    images[push.source][id.xy] = float4(c, scene.a);
}
