#version 450
// Texture times a weight (pc.color), added up by blending.
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform PC { vec4 color; } pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 o;
void main()
{
    o = texture(tex, uv) * pc.color;
}
