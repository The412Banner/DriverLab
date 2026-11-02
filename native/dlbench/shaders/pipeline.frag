#version 450
// Shader for the pipeline-creation test: each pipeline gets other constants.
layout(constant_id = 0) const int ROUNDS = 16;
layout(constant_id = 1) const float SCALE = 1.0;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 o;
void main()
{
    vec3 c = vec3(uv, 0.5);
    for (int k = 0; k < ROUNDS; k++) {
        c = fract(c * SCALE + vec3(0.1, 0.2, 0.3) * float(k));
        c = c * c * (3.0 - 2.0 * c);
    }
    o = vec4(c, 1.0);
}
