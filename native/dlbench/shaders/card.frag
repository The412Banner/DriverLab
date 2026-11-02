#version 450
// Test card background: colour bars, ramps, checkerboard, rings, grey border.
// Bars, checkerboard and border are exact 0 / 0.5 / 1 colours.
layout(push_constant) uniform PC { layout(offset = 32) vec4 res; } pc;
layout(location = 0) out vec4 o;
const vec3 BARS[8] = vec3[](vec3(1, 1, 1), vec3(1, 1, 0), vec3(0, 1, 1), vec3(0, 1, 0),
                            vec3(1, 0, 1), vec3(1, 0, 0), vec3(0, 0, 1), vec3(0, 0, 0));
void main()
{
    vec2 fc = gl_FragCoord.xy;
    vec2 p = fc / pc.res.xy;
    vec3 c;
    if (fc.x < 4.0 || fc.y < 4.0 || fc.x > pc.res.x - 4.0 || fc.y > pc.res.y - 4.0) {
        c = vec3(0.5);
    } else if (p.y < 0.30) {
        c = BARS[clamp(int(p.x * 8.0), 0, 7)];
    } else if (p.y < 0.50) {
        int band = clamp(int((p.y - 0.30) / 0.05), 0, 3);
        float r = p.x;
        c = band == 0 ? vec3(r, 0, 0) : band == 1 ? vec3(0, r, 0) : band == 2 ? vec3(0, 0, r) : vec3(r);
    } else if (p.y < 0.58) {
        ivec2 q = ivec2(fc) / 16;
        c = vec3(float((q.x + q.y) & 1));
    } else {
        float d = length(fc - vec2(0.5, 0.79) * pc.res.xy);
        c = (d < 0.2 * pc.res.y && mod(d, 36.0) < 6.0) ? vec3(1.0) : vec3(0.12);
    }
    o = vec4(c, 1.0);
}
