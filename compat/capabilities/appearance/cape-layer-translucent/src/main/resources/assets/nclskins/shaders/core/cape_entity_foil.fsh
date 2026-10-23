#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
uniform float GlintAlpha;
uniform mat4 TextureMat;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    vec3 foil = GlintAlpha * texture(Sampler3, (TextureMat * vec4(texCoord0, 0.0, 1.0)).xy).rgb;
    color.rgb += foil * foil;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
