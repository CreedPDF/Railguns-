#version 150
// Gungnir impact frames: the scene thresholded to molten orange and black with an ordered dither, edges burnt
// white; or inverted to ice blue; or torn by horizontal glitch slices; or reduced to glowing orange edges.
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float Mode;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

float bayer(vec2 p) {
    ivec2 i = ivec2(mod(p, 4.0));
    int m[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    return (float(m[i.y * 4 + i.x]) + 0.5) / 16.0;
}

float hash(float x) { return fract(sin(x * 12.9898) * 43758.5453); }

void main() {
    vec2 uv = texCoord;
    float frame = floor(Time * 24.0);
    if (Mode > 1.5 && Mode < 2.5) {
        float slice = floor(uv.y * InSize.y / 9.0);
        float h = hash(slice + frame * 7.13);
        if (h > 0.78) uv.x += (h - 0.78) * 0.55 * (hash(slice * 3.1 + frame) - 0.5);
    }
    vec3 c = texture(DiffuseSampler, uv).rgb;
    float l = lum(c);
    vec2 t = oneTexel * 1.5;
    float gx = lum(texture(DiffuseSampler, uv + vec2(t.x, 0.0)).rgb) - lum(texture(DiffuseSampler, uv - vec2(t.x, 0.0)).rgb);
    float gy = lum(texture(DiffuseSampler, uv + vec2(0.0, t.y)).rgb) - lum(texture(DiffuseSampler, uv - vec2(0.0, t.y)).rgb);
    float e = sqrt(gx * gx + gy * gy);
    float d = bayer(uv * InSize / 2.0);
    float th = 0.38 + (d - 0.5) * 0.42;
    vec3 orange = vec3(1.0, 0.33, 0.07), black = vec3(0.03, 0.012, 0.008), hot = vec3(1.0, 0.93, 0.84);
    vec3 o;
    if (Mode < 0.5 || (Mode > 1.5 && Mode < 2.5)) {
        o = l > th ? orange : black;
        if (e > 0.22) o = hot;
        if (Mode > 1.5) {
            float s = floor(uv.y * InSize.y / 3.0);
            if (hash(s + frame) > 0.985) o = vec3(0.5, 0.95, 1.0);
        }
    } else if (Mode < 1.5) {
        o = l > th ? vec3(0.86, 0.97, 1.0) : vec3(0.06, 0.38, 0.8);
        if (e > 0.22) o = vec3(0.0, 0.07, 0.2);
    } else {
        o = e > 0.12 ? mix(orange, hot, clamp((e - 0.12) * 3.0, 0.0, 1.0)) : black;
    }
    fragColor = vec4(o, 1.0);
}
