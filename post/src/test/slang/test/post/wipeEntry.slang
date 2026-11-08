// For tests: the wipe's entry point.
import post.material;
import test.post.wipe;

struct WipePush
{
    PostHeader header;
    WipeParameters parameters;
};

[[vk::push_constant]]
ConstantBuffer<WipePush, ScalarDataLayout> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void wipeMain(uint3 id : SV_DispatchThreadID)
{
    runPost<Wipe>(id, push.header, push.parameters);
}
