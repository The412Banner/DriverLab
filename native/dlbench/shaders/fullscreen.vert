#version 450
// One triangle that covers the screen; uv runs 0..1 across it, then scale + offset.
layout(push_constant) uniform PC { vec4 color; vec4 uvxf; vec4 rect; } pc;
layout(location = 0) out vec2 uv;
void main()
{
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = p * pc.uvxf.xy + pc.uvxf.zw;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
