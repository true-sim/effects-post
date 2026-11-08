// Post materials: a game's own post effects in Slang (effects-post's PostMaterial). A material is a struct
// implementing IPostMaterial, its parameters a [MaterialParameters(0)] struct (the build makes it a Java record, as
// for material types), shading each pixel of the render region from a PostInput: the image as the effects before it
// left it, the scene depth (before tone mapping), up to four textures, time and size. Its entry point is a few lines
// (effects-post's src/test/slang/test/post has three):
//
//     import post.material;
//     import game.haze;
//     struct HazePush { PostHeader header; HazeParameters parameters; };
//     [[vk::push_constant]] ConstantBuffer<HazePush, ScalarDataLayout> push;
//     (a compute shader of 8 x 8 threads) void hazeMain(uint3 id : SV_DispatchThreadID)
//         { runPost<Haze>(id, push.header, push.parameters); }
//
// The push constants are in scalar layout, as material records are written: the header's 64 bytes, then the
// parameters (64 bytes at most).
module material;

import bindless;

/** What the engine tells every post material, first in its push constants: 64 bytes. */
public struct PostHeader
{
    public uint source;         // sampled slot of the image as the effects before left it
    public uint target;         // storage slot of the result
    public uint depth;          // sampled slot of the scene depth (reverse: near / distance), if FLAG_DEPTH
    public uint flags;          // FLAG_*
    public uint2 region;        // the render region, in pixels from the top left
    public uint2 size;          // the images' size
    public float time;          // seconds on the material's clock (real seconds since it was made, or game time)
    public uint frame;          // the frame's number
    public float paperWhite;    // after tone mapping on an HDR display: nits of 1 (display colors reach the peak)
    public float peak;          // the display's peak share of paper white (1 in SDR)
    public uint4 textures;      // the material's textures, sampled slots, as given
};

/** The scene depth is there. */
public static const uint FLAG_DEPTH = 1;
/** After tone mapping: display light, 1 at SDR white or paper white. */
public static const uint FLAG_DISPLAY = 2;

/** One pixel's view of the frame. */
public struct PostInput
{
    public uint2 pixel;         // the pixel shaded
    public float2 uv;           // its center in the region, 0 to 1
    public float time;
    public uint frame;
    PostHeader header;

    /** The region's size in pixels. */
    public float2 regionSize()
    {
        return float2(header.region);
    }

    /** After tone mapping (display light), or before (scene light). */
    public bool display()
    {
        return (header.flags & FLAG_DISPLAY) != 0;
    }

    /** The image at `uv` (0 to 1 over the region), filtered, clamped within the region. */
    public float3 color(float2 uv)
    {
        float2 p = clamp(uv * float2(header.region), 0.5, float2(header.region) - 0.5);
        return textures[header.source].SampleLevel(samplers[LINEAR_CLAMP], p / float2(header.size), 0).rgb;
    }

    /** The image at this pixel. */
    public float3 color()
    {
        return textures[header.source].Load(int3(pixel, 0)).rgb;
    }

    /** Whether depth() means something: scene light, the view drawing depth. */
    public bool hasDepth()
    {
        return (header.flags & FLAG_DEPTH) != 0;
    }

    /** The scene depth at `uv` (reverse: 1 near, 0 at infinity), or 0 without one. */
    public float depth(float2 uv)
    {
        if (!hasDepth())
        {
            return 0;
        }
        int2 p = clamp(int2(uv * float2(header.region)), 0, int2(header.region) - 1);
        return textures[header.depth].Load(int3(p, 0)).r;
    }

    /** The material's texture `i` (0 to 3) at `uv`, filtered, clamped at its edges. */
    public float4 texture(uint i, float2 uv)
    {
        return textures[header.textures[i]].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
    }

    /** The material's texture `i` at `uv`, filtered, repeating. */
    public float4 textureRepeat(uint i, float2 uv)
    {
        return textures[header.textures[i]].SampleLevel(samplers[LINEAR_REPEAT], uv, 0);
    }
};

/** A post material: the color of each pixel from its parameters and the frame. */
public interface IPostMaterial
{
    associatedtype Parameters;

    static float3 shade(Parameters p, PostInput input);
}

/** Runs material `M` for the pixel of invocation `id`: what its entry point does. */
public void runPost<M : IPostMaterial>(uint3 id, PostHeader header, M.Parameters parameters)
{
    if (any(id.xy >= header.region))
    {
        return;
    }
    PostInput input;
    input.pixel = id.xy;
    input.uv = (float2(id.xy) + 0.5) / float2(header.region);
    input.time = header.time;
    input.frame = header.frame;
    input.header = header;
    images[header.target][id.xy] = float4(M.shade(parameters, input), 1);
}
