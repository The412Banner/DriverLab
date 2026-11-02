#version 450
// A cube built from gl_VertexIndex (36 vertices, 6 faces).
layout(push_constant) uniform PC { mat4 mvp; mat4 model; } pc;
layout(location = 0) out vec3 n;
layout(location = 1) out vec2 uv;
const vec3 N[6] = vec3[](vec3(1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0), vec3(0, 0, 1), vec3(0, 0, -1));
const vec3 U[6] = vec3[](vec3(0, 0, -1), vec3(0, 0, 1), vec3(1, 0, 0), vec3(1, 0, 0), vec3(1, 0, 0), vec3(-1, 0, 0));
const vec2 C[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(0, 1), vec2(0, 1), vec2(1, 0), vec2(1, 1));
void main()
{
    int f = gl_VertexIndex / 6;
    vec2 c = C[gl_VertexIndex % 6];
    vec3 v = cross(N[f], U[f]);
    vec3 p = N[f] + (c.x * 2.0 - 1.0) * U[f] + (c.y * 2.0 - 1.0) * v;
    n = mat3(pc.model) * N[f];
    uv = c;
    gl_Position = pc.mvp * vec4(p, 1.0);
}
