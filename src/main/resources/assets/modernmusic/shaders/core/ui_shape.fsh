#version 150

// Vanilla position_tex_color discards fragments under 10% alpha, which cuts off soft shadow tails, gradient
// fades and faint hairlines. UI shapes keep every non-zero fragment.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
