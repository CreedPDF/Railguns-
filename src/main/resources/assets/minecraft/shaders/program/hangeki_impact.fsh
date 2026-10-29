#version 150

// HANGEKI // Reversal Edge - impact frames (orbital_railgun). Turns the real rendered frame into hard-cut anime impact
// frames: the scene crushed to grainy monochrome, or burned out to silhouettes and line art, with radial speed lines
// tearing out of the point of impact. Crimson is the only colour. Mode picks the frame type (changed every few frames
// by the client), Strength fades the whole effect, Seed reshuffles the speed lines per frame.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 oneTexel;

uniform vec2 InSize;
uniform float Mode;
uniform float Strength;
uniform float Seed;
uniform float Clock;
uniform vec2 Center;

out vec4 fragColor;

const vec3 CRIMSON = vec3(0.86, 0.04, 0.07);
const vec3 BONE = vec3(1.0, 0.97, 0.93);

float hash(float n) { return fract(sin(n) * 43758.5453123); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// edge strength (Sobel on luminance): the line art of the scene
float edges(vec2 uv) {
    vec2 o = oneTexel * 1.5;
    float tl = lum(texture(DiffuseSampler, uv + vec2(-o.x,  o.y)).rgb);
    float t  = lum(texture(DiffuseSampler, uv + vec2( 0.0,  o.y)).rgb);
    float tr = lum(texture(DiffuseSampler, uv + vec2( o.x,  o.y)).rgb);
    float l  = lum(texture(DiffuseSampler, uv + vec2(-o.x,  0.0)).rgb);
    float r  = lum(texture(DiffuseSampler, uv + vec2( o.x,  0.0)).rgb);
    float bl = lum(texture(DiffuseSampler, uv + vec2(-o.x, -o.y)).rgb);
    float b  = lum(texture(DiffuseSampler, uv + vec2( 0.0, -o.y)).rgb);
    float br = lum(texture(DiffuseSampler, uv + vec2( o.x, -o.y)).rgb);
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    return sqrt(gx * gx + gy * gy);
}

// radial speed lines out of the impact point: 1 on a line, 0 between; thick, uneven, starting at varied radii
float speedLines(vec2 uv, float density, float seed) {
    vec2 d = uv - Center;
    d.x *= InSize.x / InSize.y;
    float r = length(d);
    float a = atan(d.y, d.x) / 6.2831853 + 0.5;
    float n = density;
    float cell = floor(a * n);
    float f = fract(a * n);
    float on = step(0.42, hash(cell * 1.37 + seed));
    float w = 0.15 + 0.75 * hash(cell * 7.11 + seed * 1.3);
    float start = 0.06 + 0.42 * hash(cell * 3.17 + seed * 0.7);
    // ragged ends and a slight taper toward the centre
    float rag = 0.04 * hash2(vec2(cell, floor(r * 60.0) + seed));
    float body = step(abs(f - 0.5) * 2.0, w * smoothstep(start, start + 0.25, r));
    return on * body * step(start + rag, r);
}

void main() {
    vec3 src = texture(DiffuseSampler, texCoord).rgb;
    if (Strength <= 0.001) {
        fragColor = vec4(src, 1.0);
        return;
    }
    float L = lum(src);
    float e = edges(texCoord);
    float grain = hash2(texCoord * InSize + vec2(Clock * 91.7, Clock * 37.3)) - 0.5;
    vec2 dv = (texCoord - Center) * vec2(InSize.x / InSize.y, 1.0);
    float vign = smoothstep(1.15, 0.25, length(dv));
    int mode = int(Mode + 0.5);
    vec3 col;

    if (mode == 0) {
        // calm frame: crushed, grainy monochrome of the scene, shadows bleeding crimson
        float g = clamp((L - 0.12) * 1.55, 0.0, 1.0);
        g = g * g * (3.0 - 2.0 * g);
        g = g * 0.78 * (0.55 + 0.45 * vign) + grain * 0.22;
        col = mix(CRIMSON * 0.22, BONE, clamp(g, 0.0, 1.0));
        col = max(col, vec3(smoothstep(0.35, 0.9, e)) * 0.85);
    } else if (mode == 1) {
        // black frame, white speed lines; the scene only as faint line art
        float s = speedLines(texCoord, 150.0, Seed);
        col = vec3(smoothstep(0.4, 1.0, e) * 0.35);
        col = mix(col, BONE, s);
        col += grain * 0.08;
    } else if (mode == 2) {
        // burned out: the scene as white silhouettes and line art on black, speed lines behind it
        float sil = step(0.42, L + grain * 0.12);
        float s = speedLines(texCoord, 120.0, Seed);
        col = vec3(0.02);
        col = mix(col, BONE * 0.85, s * (1.0 - sil));
        col = mix(col, BONE, sil);
        col = mix(col, vec3(0.0), smoothstep(0.3, 0.8, e) * sil);
    } else if (mode == 3) {
        // crimson frame: black silhouettes and black speed lines on red
        float sil = step(0.4, L + grain * 0.1);
        float s = speedLines(texCoord, 110.0, Seed);
        col = CRIMSON * (0.85 + 0.15 * vign);
        col = mix(col, vec3(0.0), max(sil, s));
        col = mix(col, BONE, smoothstep(0.5, 1.0, e) * (1.0 - sil) * 0.6);
    } else {
        // inverted: bone-white frame, black ink silhouettes and lines
        float sil = step(0.45, L);
        float s = speedLines(texCoord, 170.0, Seed);
        col = BONE;
        col = mix(col, vec3(0.03), max(sil * 0.92, s));
        col = mix(col, CRIMSON, smoothstep(0.45, 1.0, e) * 0.8);
        col += grain * 0.06;
    }
    fragColor = vec4(mix(src, clamp(col, 0.0, 1.0), clamp(Strength, 0.0, 1.0)), 1.0);
}
