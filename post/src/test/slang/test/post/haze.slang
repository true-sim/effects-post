// For tests: heat haze, the image read through a normal map (texture 0, xy in 0 to 1) scrolling over time: each pixel
// shows the image `strength` of the screen's height away along the normal's slope; only where
// the depth is above `near` (closer).
module haze;

import materials;
import post.material;

[MaterialParameters(0)]
public struct HazeParameters
{
    [Range(0, 0.1)] public float strength = 0.01;
    [Range(0, 10)] public float speed = 0.2;      // screens a second the normal map scrolls up
    [Range(0.1, 64)] public float tiling = 4;     // normal map repeats over the screen's height
    [Range(0, 1)] public float near = 0;          // reverse depth: haze where the depth is above this (closer)
};

public struct Haze : IPostMaterial
{
    public typealias Parameters = HazeParameters;

    public static float3 shade(HazeParameters p, PostInput input)
    {
        float aspect = input.regionSize().x / input.regionSize().y;
        float2 at = float2(input.uv.x * aspect, input.uv.y + input.time * p.speed) * p.tiling;
        float2 slope = input.textureRepeat(0, at).xy * 2 - 1;
        bool hazy = !input.hasDepth() || input.depth(input.uv) >= p.near;
        float2 offset = hazy ? slope * p.strength * float2(1 / aspect, 1) : 0;
        return input.color(input.uv + offset);
    }
};
