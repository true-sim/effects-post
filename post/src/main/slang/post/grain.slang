// Film grain in display light, after tone mapping (DisplayTransform.DISPLAY_COLOR, at Anchor.TONE_MAP), in place: Unity's
// form, d + d n intensity lerp(1, 1 - sqrt(luma), response), n value noise of -1 to 1 over cells of `size` pixels (a
// smoothstep between their corners), new each frame: stronger in the shadows and middle than in the highlights, scaled
// with the color so black stays black. Its mean over the cells is 0: the image keeps its average.
import bindless;

struct GrainPush
{
    uint image;         // storage slot of the display color
    uint frame;         // varies the grain from frame to frame
    uint2 region;       // the display color's region
    float intensity;    // 0: none
    float response;     // how much less grain the highlights get (0 to 1)
    float size;         // the grain's cells, in pixels
};

[[vk::push_constant]]
ConstantBuffer<GrainPush> push;

/** Jarzynski and Olano's PCG3D hash (2020). */
uint3 pcg3d(uint3 v)
{
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void grainMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.region))
    {
        return;
    }
    float4 d = images[push.image][id.xy];
    float2 p = (float2(id.xy) + 0.5) / max(push.size, 0.5);
    int2 cell = int2(floor(p));
    float2 t = p - cell;
    t = t * t * (3 - 2 * t);
    float v[4];
    for (int i = 0; i < 4; i++)
    {
        uint3 h = pcg3d(uint3(uint2(cell + int2(i & 1, i >> 1)), push.frame * 7919u + 17u));
        v[i] = ((h.x >> 8) + 0.5) * (2.0 / 16777216.0) - 1;
    }
    float n = lerp(lerp(v[0], v[1], t.x), lerp(v[2], v[3], t.x), t.y);
    float luma = saturate(dot(d.rgb, float3(0.2126, 0.7152, 0.0722)));
    images[push.image][id.xy] = float4(max(d.rgb + d.rgb * n * push.intensity * lerp(1, 1 - sqrt(luma),
        push.response), 0), d.a);
}
