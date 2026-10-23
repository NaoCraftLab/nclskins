#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 cape = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (cape.a == 0.0) {
        discard;
    }
    vec2 foilUv = fract(texCoord0 * 8.0 + GameTime * vec2(180.0, -120.0));
    vec4 foil = texture(Sampler1, foilUv);
    fragColor = vec4(min(cape.rgb + foil.rgb * foil.a * GlintAlpha * 0.45, vec3(1.0)), cape.a);
}
