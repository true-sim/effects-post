// For tests: the damage overlay's entry point.
import post.material;
import test.post.damage;

struct DamagePush
{
    PostHeader header;
    DamageParameters parameters;
};

[[vk::push_constant]]
ConstantBuffer<DamagePush, ScalarDataLayout> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void damageMain(uint3 id : SV_DispatchThreadID)
{
    runPost<Damage>(id, push.header, push.parameters);
}
