#version 450
// A rectangle (triangle strip, 4 vertices); rect = x0, y0, x1, y1 in clip space.
layout(push_constant) uniform PC { vec4 color; vec4 uvxf; vec4 rect; } pc;
void main()
{
    vec2 c = vec2(gl_VertexIndex & 1, (gl_VertexIndex >> 1) & 1);
    gl_Position = vec4(mix(pc.rect.xy, pc.rect.zw, c), 0.0, 1.0);
}
