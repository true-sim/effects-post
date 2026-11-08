// For tests: a screen transition by a grayscale mask (texture 0): where the mask is below `progress` the screen shows
// `color`, with a soft edge of `softness` (a dissolve with a noise mask, a wipe with a gradient).
module wipe;

import materials;
import post.material;

[MaterialParameters(0)]
public struct WipeParameters
{
    [Color] public float3 color = float3(0, 0, 0);
    [Range(0, 1)] public float progress = 0;
    [Range(0, 1)] public float softness = 0.05;
};

public struct Wipe : IPostMaterial
{
    public typealias Parameters = WipeParameters;

    public static float3 shade(WipeParameters p, PostInput input)
    {
        float mask = input.texture(0, input.uv).r;
        // Covered where the mask is below the progress; the edge spread over softness, 0 a hard cut.
        float covered = p.softness > 0 ? saturate((p.progress - mask) / p.softness + 0.5) : (mask < p.progress ? 1 : 0);
        return lerp(input.color(), p.color, covered);
    }
};
