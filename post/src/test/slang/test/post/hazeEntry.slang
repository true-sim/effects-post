// For tests: the heat haze's entry point.
import post.material;
import test.post.haze;

struct HazePush
{
    PostHeader header;
    HazeParameters parameters;
};

[[vk::push_constant]]
ConstantBuffer<HazePush, ScalarDataLayout> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void hazeMain(uint3 id : SV_DispatchThreadID)
{
    runPost<Haze>(id, push.header, push.parameters);
}
