// Lens effects in scene light, after bloom, before grading: radial distortion, lateral chromatic aberration and
// vignetting (each a technique slot of LensEffects), in one pass over the render region (reading the scene color,
// writing a new one; built only while one of them is on). Positions are in the
// region's normalized coordinates around its center, x scaled by the aspect so the effects are round.
// - Distortion (Brown-Conrady, radial): a pixel at radius r (1 at the corners) shows the scene at r (1 + k r^2), scaled
//   so the image stays filled: by its corners (pincushion, k > 0) or the middles of its nearer edges (barrel). k is
//   at least -1/3, where r (1 + k r^2) stops growing at the corners: the image never folds.
// - Chromatic aberration (lateral): each wavelength magnified differently, red outward, blue inward, by `amount` of
//   the radius at the corner: RADIAL samples the three channels at their own scale; SPECTRAL integrates 8 wavelengths
//   between them, each weighted into r, g, b (the weights summing to one per channel), so edges fringe smoothly. The
//   image is scaled by 1 / (1 + amount) with it, so red stays inside it at the corners.
// - Vignetting: NATURAL is the cos^4 law of the angle off the axis (the camera's field of view), ARTISTIC a falloff of
//   over the outer `smoothness` of the way to the corners, `roundness` 1 round, 0 following the frame; both by
//   intensity. Natural vignetting darkens (it moves no light); the others keep a uniform image as it is.
import bindless;

struct LensPush
{
    uint source;        // sampled slot of the scene color
    uint target;        // storage slot of the result
    uint2 region;       // the render region
    float distortion;   // k (radius 1 at the corners)
    float scale;        // what positions are divided by after distortion: keeps the image filled
    uint aberration;    // ABERRATION_*
    float amount;       // the aberration's shift at the corner, a share of the radius
    uint vignette;      // VIGNETTE_*
    float intensity;
    float smoothness;
    float roundness;
    float tanHalfFov;   // NATURAL: tan of half the vertical field of view
};

static const uint ABERRATION_OFF = 0;
static const uint ABERRATION_RADIAL = 1;
static const uint ABERRATION_SPECTRAL = 2;
static const uint VIGNETTE_OFF = 0;
static const uint VIGNETTE_NATURAL = 1;
static const uint VIGNETTE_ARTISTIC = 2;

[[vk::push_constant]]
ConstantBuffer<LensPush> push;

/** The scene at `p` (region pixels), filtered, clamped within the region. */
float3 sceneAt(float2 p)
{
    uint width;
    uint height;
    textures[push.source].GetDimensions(width, height);
    float2 q = clamp(p, 0.5, float2(push.region) - 0.5);
    return textures[push.source].SampleLevel(samplers[LINEAR_CLAMP], q / float2(width, height), 0).rgb;
}

/** Position `v` (centered, aspect-scaled: y from -1 to 1) back to region pixels. */
float2 toPixels(float2 v, float aspect)
{
    return (float2(v.x / aspect, v.y) * 0.5 + 0.5) * float2(push.region);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void lensMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.region))
    {
        return;
    }
    float aspect = float(push.region.x) / push.region.y;
    float2 uv = (float2(id.xy) + 0.5) / float2(push.region);
    float2 v = (uv * 2 - 1) * float2(aspect, 1); // y from -1 to 1
    float r2 = dot(v, v);
    float corner = aspect * aspect + 1;
    float2 distorted = v * (1 + push.distortion * r2 / corner) / push.scale;
    // With aberration, red (magnified by up to 1 + amount at the corners) must stay inside as well
    distorted /= push.aberration == ABERRATION_OFF ? 1 : 1 + push.amount;
    float3 color;
    if (push.aberration == ABERRATION_OFF)
    {
        color = sceneAt(toPixels(distorted, aspect));
    }
    else
    {
        // The shift at this radius: amount of the radius at the corner, growing with r (lateral color).
        float shift = push.amount * r2 / corner;
        if (push.aberration == ABERRATION_RADIAL)
        {
            color = float3(sceneAt(toPixels(distorted * (1 + shift), aspect)).r,
                sceneAt(toPixels(distorted, aspect)).g, sceneAt(toPixels(distorted * (1 - shift), aspect)).b);
        }
        else
        {
            // 8 wavelengths from red (t = 1) to blue (t = -1); each one's color a tent around its channel's center,
            // the weights of each channel summing to one.
            float3 sum = 0;
            float3 weights = 0;
            for (int i = 0; i < 8; i++)
            {
                float t = 1 - 2 * (i + 0.5) / 8;
                float3 w = saturate(1 - abs(t - float3(1, 0, -1)) * 1.5);
                sum += sceneAt(toPixels(distorted * (1 + shift * t), aspect)) * w;
                weights += w;
            }
            color = sum / weights;
        }
    }
    if (push.vignette == VIGNETTE_NATURAL)
    {
        // The angle off the axis: tan = |v| tan(half the vertical field of view)
        float t = sqrt(r2) * push.tanHalfFov;
        float c = 1 / sqrt(1 + t * t);
        color *= lerp(1, c * c * c * c, push.intensity);
    }
    else if (push.vignette == VIGNETTE_ARTISTIC)
    {
        // 0 at the center, 1 at the corners: round (roundness 1) or following the frame (0)
        float rho = lerp(length(uv * 2 - 1) * 0.70710678, sqrt(r2 / (aspect * aspect + 1)), push.roundness);
        float falloff = smoothstep(1 - push.smoothness, 1, rho);
        color *= 1 - push.intensity * falloff;
    }
    images[push.target][id.xy] = float4(color, 1);
}
