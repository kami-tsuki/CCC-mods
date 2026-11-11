#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec4 Tint;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord0);
    if (c.a < 0.01) {
        discard;
    }
    float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    vec3 grey = vec3(l);
    vec3 o = mix(grey, grey * Tint.rgb, Tint.a);
    fragColor = vec4(o * vertexColor.rgb, c.a * vertexColor.a) * ColorModulator;
}
