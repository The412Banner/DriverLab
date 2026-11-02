#version 450
// Small squares, one per instance, on a grid over the whole image, so every
// screen tile has some drawing. grid = columns, rows, square size in clip space.
layout(push_constant) uniform PC { vec4 color; vec4 grid; } pc;
void main()
{
    vec2 c = vec2(gl_VertexIndex & 1, (gl_VertexIndex >> 1) & 1);
    int cols = int(pc.grid.x);
    vec2 cell = vec2(gl_InstanceIndex % cols, gl_InstanceIndex / cols);
    vec2 center = -1.0 + 2.0 * (cell + 0.5) / pc.grid.xy;
    gl_Position = vec4(center + (c - 0.5) * pc.grid.zw, 0.0, 1.0);
}
