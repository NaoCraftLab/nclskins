#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec4 ColorModulator;
uniform float FoilTime;

in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    vec4 cape = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (cape.a == 0.0) {
        discard;
    }
    vec2 foilUv = fract(texCoord0 * 8.0 + FoilTime * vec2(15.0, -10.0));
    vec4 foil = texture(Sampler1, foilUv);
    fragColor = vec4(min(cape.rgb + foil.rgb * foil.a * 0.45, vec3(1.0)), cape.a);
}
