#version 450
// Grid point (x, z) lifted by a moving wave; lit per vertex.
layout(location = 0) in vec2 g;
layout(push_constant) uniform PC { mat4 mvp; vec4 t; } pc;
layout(location = 0) out vec3 col;
void main()
{
    float a = g.x * 12.0 + pc.t.x;
    float b = g.y * 9.0 + pc.t.x * 0.7;
    float h = sin(a) * cos(b) * 0.15;
    float dx = cos(a) * cos(b) * 12.0 * 0.15;
    float dz = -sin(a) * sin(b) * 9.0 * 0.15;
    vec3 nrm = normalize(vec3(-dx, 1.0, -dz));
    col = vec3(0.3, 0.6, 0.9) * (max(dot(nrm, normalize(vec3(0.3, 1.0, 0.2))), 0.0) * 0.8 + 0.2);
    gl_Position = pc.mvp * vec4(g.x, h, g.y, 1.0);
}
