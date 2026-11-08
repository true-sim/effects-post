// Depth of field in scene light, before bloom and tone mapping, within the render region.
//
// The circle of confusion is the thin lens': a point at distance z, the lens focused at zf, with focal length f and
// aperture diameter A = f / N (N the f-number), images as a disc of diameter A f |z - zf| / (z (zf - f)) on the
// sensor; in pixels, over the sensor's height. Signed: negative in front of the focus (the near field), positive
// behind it (the far field).
//
// Techniques (DofPush.mode):
// - GATHER (after Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014): at
//   half resolution, the near and far fields gathered apart over a disc of 49 taps (rings of 8, 16, 24 around the
//   center), each tap weighted by whether its own circle reaches the pixel (scatter as gather); the near field spread
//   over in-focus pixels by the largest near circle of the tiles of 16 pixels around, as far as the largest radius
//   reaches (its coverage the alpha over the far field); a 3 x 3 median against undersampling; composited over the sharp image by circle size.
// - GAUSSIAN: a separable Gaussian at half resolution, each pixel's sigma half its circle, taps weighted by whether
//   their circle reaches it; cheaper, no near field spreading over sharp edges.
import bindless;

struct DofPush
{
    uint source;        // sampled slot: what the pass reads
    uint target;        // storage slot: what it writes
    uint depth;         // sampled slot of the scene depth (reverse: near / distance)
    uint coc;           // sampled slot of the circles of confusion (full resolution), or the tiles
    uint aux;           // a second sampled slot (the dilated tiles, the near field, the far field)
    uint aux2;
    uint target2;       // a second storage slot (the gather's near field)
    uint mode;          // MODE_*
    uint2 size;         // the target's render region
    uint2 sourceRegion; // the source's render region
    float near;         // the camera's near plane, meters
    float focal;        // focal length, meters
    float aperture;     // aperture diameter, meters: focal / f-number
    float pixels;       // pixels per meter on the sensor: the region's height over the sensor's
    float focus;        // the focus distance, meters (used without autofocus)
    float maxRadius;    // the largest circle radius drawn, in full resolution pixels
    float speed;        // autofocus: how fast it follows, per second
    float seconds;      // autofocus: since the last frame
    uint autofocus;     // 1: the focus is read from (and written to) `focusAt`
    float* focusAt;     // autofocus: [0] the focus distance, [1] 1 once kept; kept from frame to frame
    int2 direction;     // Gaussian: the axis
    int dilate;         // the tiles' reach in tiles: the largest radius over the tile size, rounded up
    float area;         // autofocus: the share of the view's height its taps spread over
};

static const uint MODE_GATHER = 0;
static const uint MODE_GAUSSIAN = 1;
static const int TILE = 16;

[[vk::push_constant]]
ConstantBuffer<DofPush> push;

float distanceAt(float depth)
{
    return push.near / max(depth, 1e-7);
}

/** The circle of confusion's radius in full resolution pixels at `distance`, focused at `focus`; signed. */
float circleAt(float distance, float focus)
{
    float f = push.focal;
    float diameter = push.aperture * f * (distance - focus) / (distance * max(focus - f, 1e-4));
    return clamp(0.5 * diameter * push.pixels, -push.maxRadius, push.maxRadius);
}

float focusDistance()
{
    return push.autofocus != 0 ? push.focusAt[0] : push.focus;
}

/**
 * Autofocus: the distance at the region's middle (the median of 9 taps over its central `area`, so a thin object or a
 * hole does not pull it), and the kept focus moved towards it by 1 - e^(-speed seconds) of the way (in log distance,
 * so racking from 1 m to 100 m takes as long as back). One thread.
 */
[shader("compute")]
[numthreads(1, 1, 1)]
void focusMain(uint3 id : SV_DispatchThreadID)
{
    float d[9];
    float2 middle = float2(push.size) * 0.5;
    float2 spread = float2(push.size.y, push.size.y) * push.area * 0.5;
    for (int i = 0; i < 9; i++)
    {
        int2 at = clamp(int2(middle + float2(i % 3 - 1, i / 3 - 1) * spread), 0, int2(push.size) - 1);
        d[i] = distanceAt(textures[push.depth].Load(int3(at, 0)).r);
    }
    for (int i = 1; i < 9; i++) // insertion sort: nine values
    {
        float v = d[i];
        int j = i - 1;
        while (j >= 0 && d[j] > v)
        {
            d[j + 1] = d[j];
            j--;
        }
        d[j + 1] = v;
    }
    float seen = min(d[4], 1e5);
    float kept = push.focusAt[0];
    push.focusAt[0] = push.focusAt[1] > 0 ? exp2(lerp(log2(kept), log2(seen), 1 - exp(-push.speed * push.seconds)))
        : seen;
    push.focusAt[1] = 1;
}

/** The circle of confusion of each pixel of the region, full resolution. */
[shader("compute")]
[numthreads(8, 8, 1)]
void cocMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float distance = distanceAt(textures[push.depth].Load(int3(id.xy, 0)).r);
    images[push.target][id.xy] = float4(circleAt(distance, focusDistance()), 0, 0, 0);
}

/** Per tile of 16 x 16 pixels: the largest near circle (as a positive radius) and the largest far one. */
[shader("compute")]
[numthreads(8, 8, 1)]
void tileMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float nearest = 0;
    float farthest = 0;
    int2 corner = int2(id.xy) * TILE;
    for (int y = 0; y < TILE; y++)
    {
        for (int x = 0; x < TILE; x++)
        {
            int2 p = min(corner + int2(x, y), int2(push.sourceRegion) - 1);
            float c = textures[push.coc].Load(int3(p, 0)).r;
            nearest = max(nearest, -c);
            farthest = max(farthest, c);
        }
    }
    images[push.target][id.xy] = float4(nearest, farthest, 0, 0);
}

/** The tiles' largest circles over the tiles within `dilate` of them: how far near blur can reach into a tile. */
[shader("compute")]
[numthreads(8, 8, 1)]
void dilateMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float2 most = 0;
    for (int y = -push.dilate; y <= push.dilate; y++)
    {
        for (int x = -push.dilate; x <= push.dilate; x++)
        {
            int2 p = clamp(int2(id.xy) + int2(x, y), 0, int2(push.size) - 1);
            most = max(most, textures[push.source].Load(int3(p, 0)).rg);
        }
    }
    images[push.target][id.xy] = float4(most, 0, 0);
}

/**
 * Half resolution: the 2 x 2 pixels' color and circle (in half resolution pixels, alpha), the color weighted towards
 * pixels whose circle is like the one kept (the farthest's), so in-focus colors do not bleed into a blurred field.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void downsampleMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float3 colors[4];
    float circles[4];
    for (int i = 0; i < 4; i++)
    {
        int2 p = min(int2(id.xy) * 2 + int2(i & 1, i >> 1), int2(push.sourceRegion) - 1);
        colors[i] = textures[push.source].Load(int3(p, 0)).rgb;
        circles[i] = textures[push.coc].Load(int3(p, 0)).r;
    }
    float kept = circles[0];
    for (int i = 1; i < 4; i++)
    {
        kept = abs(circles[i]) > abs(kept) ? circles[i] : kept;
    }
    float3 sum = 0;
    float weights = 0;
    for (int i = 0; i < 4; i++)
    {
        float w = saturate(1 - abs(circles[i] - kept) * 0.5);
        sum += colors[i] * w;
        weights += w;
    }
    images[push.target][id.xy] = float4(sum / weights, kept * 0.5);
}

/** The half resolution image at `p`, clamped within the region: color and circle (half resolution pixels). */
float4 halfAt(int2 p)
{
    return textures[push.source].Load(int3(clamp(p, 0, int2(push.sourceRegion) - 1), 0));
}

/**
 * The gather (half resolution): the far field (color, its weight's share) and the near field (color, its coverage),
 * over a disc of 49 taps as large as the pixel's own far circle and the near circles of the tiles around it.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void gatherMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float4 center = halfAt(int2(id.xy));
    uint2 lastTile = (push.sourceRegion * 2 + TILE - 1) / TILE - 1;
    float2 tiles = textures[push.aux].Load(int3(min(id.xy * 2 / TILE, lastTile), 0)).rg * 0.5;
    float farRadius = max(center.a, 0);
    float radius = max(farRadius, tiles.x);
    float3 far = 0;
    float farWeight = 0;
    float3 near = 0;
    float nearWeight = 0;
    float nearCoverage = 0;
    const float GOLDEN = 2.39996323; // spreads the rings' taps, not aligned from ring to ring
    for (int n = 0; n < 49; n++)
    {
        // Rings of 8, 16 and 24 taps at a third, two thirds and all of the radius, and the center.
        int ring = n == 0 ? 0 : n <= 8 ? 1 : n <= 24 ? 2 : 3;
        int first = ring == 0 ? 0 : ring == 1 ? 1 : ring == 2 ? 9 : 25;
        int count = ring == 0 ? 1 : 8 * ring;
        float angle = 6.2831853 * (n - first) / count + ring * GOLDEN;
        float distance = radius * ring / 3.0;
        float4 tap = halfAt(int2(round(float2(id.xy) + distance * float2(cos(angle), sin(angle)))));
        // Each tap stands for the share of the disc around it: pi r^2 / 49.
        float area = 3.14159265 * max(radius * radius, 1) / 49;
        float farCircle = max(tap.a, 0);
        float nearCircle = max(-tap.a, 0);
        // Far: taps whose circle reaches here, and that do not lie in front of a sharper center (no background over
        // the in-focus foreground).
        float w = saturate(farCircle - distance + 1) * (distance <= farRadius + 0.5 ? 1 : 0);
        far += tap.rgb * w;
        farWeight += w;
        // Near: taps whose circle reaches here, spread over their circle's area (scatter as gather).
        float v = saturate(nearCircle - distance + 1) * (nearCircle > 0.5 ? 1 : 0);
        near += tap.rgb * v;
        nearWeight += v;
        nearCoverage += v * area / (3.14159265 * max(nearCircle * nearCircle, 1));
    }
    images[push.target][id.xy] = float4(farWeight > 0 ? far / farWeight : center.rgb, farRadius);
    images[push.target2][id.xy] = float4(nearWeight > 0 ? near / nearWeight : center.rgb, saturate(nearCoverage));
}

/** Gaussian: one axis at half resolution, sigma half the pixel's circle, taps weighted by whether they reach it. */
[shader("compute")]
[numthreads(8, 8, 1)]
void gaussianMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float4 center = halfAt(int2(id.xy));
    float radius = abs(center.a);
    float sigma = max(radius * 0.5, 0.5);
    int taps = int(ceil(min(radius, push.maxRadius * 0.5)));
    float3 sum = center.rgb;
    float weights = 1;
    for (int i = 1; i <= taps; i++)
    {
        float g = exp(-0.5 * i * i / (sigma * sigma));
        for (int s = -1; s <= 1; s += 2)
        {
            float4 tap = halfAt(int2(id.xy) + push.direction * i * s);
            float w = g * saturate(abs(tap.a) - i + 1);
            sum += tap.rgb * w;
            weights += w;
        }
    }
    images[push.target][id.xy] = float4(sum / weights, center.a);
}

/** The median of nine values, by a sorting network's minima and maxima (Paeth). */
float3 median9(float3 v[9])
{
    #define SWAP(a, b) { float3 lo = min(v[a], v[b]); v[b] = max(v[a], v[b]); v[a] = lo; }
    SWAP(1, 2) SWAP(4, 5) SWAP(7, 8) SWAP(0, 1) SWAP(3, 4) SWAP(6, 7) SWAP(1, 2) SWAP(4, 5) SWAP(7, 8)
    SWAP(0, 3) SWAP(5, 8) SWAP(4, 7) SWAP(3, 6) SWAP(1, 4) SWAP(2, 5) SWAP(4, 7) SWAP(4, 2) SWAP(6, 4) SWAP(4, 2)
    #undef SWAP
    return v[4];
}

/** The gather's 3 x 3 median (Jimenez' postfilter), of the field in `source`; its alpha kept from the middle. */
[shader("compute")]
[numthreads(8, 8, 1)]
void medianMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float3 v[9];
    for (int i = 0; i < 9; i++)
    {
        v[i] = halfAt(int2(id.xy) + int2(i % 3 - 1, i / 3 - 1)).rgb;
    }
    images[push.target][id.xy] = float4(median9(v), halfAt(int2(id.xy)).a);
}

/** A half resolution field at full resolution pixel `p`, filtered, clamped within its region. */
float4 fieldAt(uint slot, uint2 p)
{
    uint width;
    uint height;
    textures[slot].GetDimensions(width, height);
    uint fullWidth;
    uint fullHeight;
    images[push.target].GetDimensions(fullWidth, fullHeight);
    float2 at = clamp((float2(p) + 0.5) / float2(fullWidth, fullHeight) * float2(width, height), 0.5,
        float2(push.sourceRegion) - 0.5);
    return textures[slot].SampleLevel(samplers[LINEAR_CLAMP], at / float2(width, height), 0);
}

/**
 * The scene color, in place: the blurred far field over the sharp pixels by their circle (fully blurred past 1.5
 * pixels), the near field over it by its coverage (GATHER).
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float4 sharp = images[push.target][id.xy];
    float circle = textures[push.coc].Load(int3(id.xy, 0)).r;
    float4 far = fieldAt(push.aux, id.xy);
    float3 color = lerp(sharp.rgb, far.rgb, saturate(abs(push.mode == MODE_GATHER ? max(circle, 0) : circle) - 0.5));
    if (push.mode == MODE_GATHER)
    {
        float4 near = fieldAt(push.aux2, id.xy);
        color = lerp(color, near.rgb, near.a);
    }
    images[push.target][id.xy] = float4(color, sharp.a);
}
