// For tests: a full-screen texture overlay (texture 0, rgba): a damage vignette, its color tinted, laid over the image
// by its alpha times `strength`; after tone mapping, in display light (white at most).
module damage;

import materials;
import post.material;

[MaterialParameters(0)]
public struct DamageParameters
{
    [Color] public float3 tint = float3(1, 0, 0);
    [Range(0, 1)] public float strength = 1;
};

public struct Damage : IPostMaterial
{
    public typealias Parameters = DamageParameters;

    public static float3 shade(DamageParameters p, PostInput input)
    {
        float4 overlay = input.texture(0, input.uv);
        return lerp(input.color(), saturate(overlay.rgb * p.tint), overlay.a * p.strength);
    }
};
