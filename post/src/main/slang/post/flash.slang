// A screen flash and fade (ScreenFlash), blended in place over the display color after tone mapping (display light,
// 1 at white, HDR displays' paper white) or over the scene color in scene light: the flash's color by its amount,
// then the fade's by its.
import bindless;

struct FlashPush
{
    uint image;         // storage slot of the display or scene color
    uint2 region;       // its render region
    float4 flash;       // the flash's color (in the image's light) and amount
    float4 fade;        // the fade's color and amount
};

[[vk::push_constant]]
ConstantBuffer<FlashPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void flashMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.region))
    {
        return;
    }
    float4 c = images[push.image][id.xy];
    float3 shown = lerp(c.rgb, push.flash.rgb, push.flash.a);
    images[push.image][id.xy] = float4(lerp(shown, push.fade.rgb, push.fade.a), c.a);
}
