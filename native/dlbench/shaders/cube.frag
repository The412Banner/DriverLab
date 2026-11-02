#version 450
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(location = 0) in vec3 n;
layout(location = 1) in vec2 uv;
layout(location = 0) out vec4 o;
void main()
{
    float l = max(dot(normalize(n), normalize(vec3(0.4, 0.6, 0.7))), 0.0) * 0.8 + 0.2;
    o = vec4(texture(tex, uv).rgb * l, 1.0);
}
