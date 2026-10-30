#version 150

// ASTRAEA // The Worldsplitter - impact frames (orbital_railgun). The real rendered frame hard-cut through stylised
// versions of itself, drawn like rough ink on paper rather than clean line art:
//   Kind 0 - ASCENSION: the scene reduced to a rugged, boiling outline (you, the column of light, the sigils), ritual
//            circles and runic ticks round the bearer, the light burned out to white, on bone / ink / gold cards.
//   Kind 1 - THE SPLIT: the frame cracked along a jagged fault through the impact and torn apart, a crazed web of
//            fractures, radial blast streaks, ember and ink cards, the light blown out.
// Mode picks the card (changed every few frames by the client), Strength fades it, Seed reshuffles the noise per card.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 oneTexel;

uniform vec2 InSize;
uniform float Mode;
uniform float Strength;
uniform float Seed;
uniform float Clock;
uniform vec2 Center;
uniform float Kind;
uniform float Tilt;

out vec4 fragColor;

const vec3 BONE = vec3(1.0, 0.975, 0.93);
const vec3 INK = vec3(0.025, 0.02, 0.018);
const vec3 GOLD = vec3(1.0, 0.78, 0.36);
const vec3 EMBER = vec3(1.0, 0.36, 0.06);
const vec3 MAGMA = vec3(0.55, 0.06, 0.01);

float hash(float n) { return fract(sin(n) * 43758.5453123); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
vec2 hash22(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash2(i), b = hash2(i + vec2(1, 0)), c = hash2(i + vec2(0, 1)), d = hash2(i + vec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return s;
}

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec3 tap(vec2 uv) { return texture(DiffuseSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb; }

// Sobel on luminance and on colour, sampled at a wobbling offset: a rough, inky line rather than a clean one
float edges(vec2 uv, float wid) {
    vec2 o = oneTexel * wid;
    vec3 tl = tap(uv + vec2(-o.x, o.y)), t = tap(uv + vec2(0.0, o.y)), tr = tap(uv + vec2(o.x, o.y));
    vec3 l = tap(uv + vec2(-o.x, 0.0)), r = tap(uv + vec2(o.x, 0.0));
    vec3 bl = tap(uv + vec2(-o.x, -o.y)), b = tap(uv + vec2(0.0, -o.y)), br = tap(uv + vec2(o.x, -o.y));
    vec3 gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    vec3 gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    return length(gx) * 0.6 + length(gy) * 0.6;
}

vec2 aspect(vec2 d) { return d * vec2(InSize.x / InSize.y, 1.0); }

// the rugged outline: coordinates boiled by noise, the line width breathing along its length, dry-brush breaks
float rugged(vec2 uv, float seed) {
    vec2 q = uv * vec2(InSize.x / InSize.y, 1.0);
    vec2 wob = (vec2(fbm(q * 9.0 + seed), fbm(q * 9.0 - seed + 4.1)) - 0.5) * 0.006;
    float wid = 1.4 + 2.6 * fbm(q * 14.0 + seed * 1.7);
    float e = edges(uv + wob, wid);
    float dry = smoothstep(0.25, 0.65, fbm(q * 40.0 + seed * 3.1));
    return smoothstep(0.22, 0.55, e) * (0.55 + 0.45 * dry);
}

// ritual circles round the bearer: concentric rings (broken, uneven), runic ticks, a star of chords
float ritual(vec2 uv, float seed, float t) {
    vec2 d = aspect(uv - Center);
    float r = length(d);
    float a = atan(d.y, d.x);
    float v = 0.0;
    float rough = (fbm(vec2(a * 3.0, seed)) - 0.5) * 0.008;
    for (int i = 0; i < 4; i++) {
        float R = 0.16 + 0.11 * float(i) + 0.02 * t;
        float w = 0.0022 + 0.0018 * float(i % 2);
        float gap = step(0.18, fract(a * (1.0 + float(i)) / 6.2831 + hash(float(i) + seed)));
        v = max(v, (1.0 - smoothstep(w, w + 0.0015, abs(r - R + rough))) * gap);
    }
    // ticks between the second and third rings
    float n = 72.0;
    float f = fract((a / 6.2831 + 0.5) * n + t * 0.4);
    float tick = step(abs(f - 0.5), 0.12) * step(0.29, r) * step(r, 0.32 + 0.025 * step(0.5, hash(floor((a / 6.2831 + 0.5) * n) + seed)));
    v = max(v, tick);
    // a seven-pointed star of chords inside the inner ring
    for (int k = 0; k < 7; k++) {
        float a0 = 6.2831 * float(k) / 7.0 + t * 0.15, a1 = 6.2831 * float(k + 3) / 7.0 + t * 0.15;
        vec2 p0 = vec2(cos(a0), sin(a0)) * 0.27, p1 = vec2(cos(a1), sin(a1)) * 0.27;
        vec2 pa = d - p0, ba = p1 - p0;
        float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
        float dl = length(pa - ba * h);
        v = max(v, (1.0 - smoothstep(0.0015, 0.003, dl + rough * 0.4)) * 0.8);
    }
    return v;
}

// a column of light straight up through the bearer, ragged at its edges
float column(vec2 uv, float seed) {
    vec2 d = aspect(uv - Center);
    float w = 0.035 + 0.02 * fbm(vec2(d.y * 12.0, seed));
    return smoothstep(w, w * 0.6, abs(d.x)) * step(-0.05, d.y);
}

// radial streaks out of the impact
float streaks(vec2 uv, float density, float seed) {
    vec2 d = aspect(uv - Center);
    float r = length(d);
    float a = atan(d.y, d.x) / 6.2831853 + 0.5;
    float cell = floor(a * density);
    float f = fract(a * density);
    float on = step(0.5, hash(cell * 1.37 + seed));
    float w = 0.1 + 0.6 * hash(cell * 7.11 + seed * 1.3);
    float start = 0.1 + 0.5 * hash(cell * 3.17 + seed * 0.7);
    float body = step(abs(f - 0.5) * 2.0, w * smoothstep(start, start + 0.3, r));
    return on * body * step(start, r);
}

// the fault: signed distance from a jagged line through the centre at angle Tilt
float fault(vec2 uv, float seed) {
    vec2 d = aspect(uv - Center);
    vec2 dir = vec2(cos(Tilt), sin(Tilt)), nrm = vec2(-dir.y, dir.x);
    float s = dot(d, dir);
    float jag = (fbm(vec2(s * 6.0, seed)) - 0.5) * 0.09 + (vnoise(vec2(s * 40.0, seed * 2.0)) - 0.5) * 0.018;
    return dot(d, nrm) - jag;
}

// a crazed web of cracks (cell borders of a jittered grid), denser near the fault
float cracks(vec2 uv, float seed, float scale) {
    vec2 q = aspect(uv) * scale + seed;
    vec2 i = floor(q), f = fract(q);
    float d1 = 8.0, d2 = 8.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(x, y);
            vec2 o = hash22(i + g);
            float dd = length(g + o - f);
            if (dd < d1) { d2 = d1; d1 = dd; } else if (dd < d2) d2 = dd;
        }
    return 1.0 - smoothstep(0.0, 0.05, d2 - d1);
}

void main() {
    vec3 src = texture(DiffuseSampler, texCoord).rgb;
    if (Strength <= 0.001) {
        fragColor = vec4(src, 1.0);
        return;
    }
    int mode = int(Mode + 0.5);
    float grain = hash2(texCoord * InSize + vec2(Clock * 91.7, Clock * 37.3)) - 0.5;
    vec2 dv = aspect(texCoord - Center);
    float vign = smoothstep(1.2, 0.2, length(dv));
    vec3 col;

    if (Kind < 0.5) {
        // ------------------------------------------------------------- ASCENSION
        float t = Clock;
        float L = lum(src);
        float burn = smoothstep(0.62, 0.9, L);       // the light: the beam, the star, the sigils
        float line = rugged(texCoord, Seed);
        float rit = ritual(texCoord, Seed, t);
        float col1 = column(texCoord, Seed);
        if (mode == 0) {
            // settling back: the scene washed toward white, the outline fading over it
            col = mix(src, BONE, 0.35 * burn);
            col = mix(col, INK, line * 0.5);
        } else if (mode == 1) {
            // bone card: the rugged black outline of everything, the light as white holes in a grey wash
            float wash = clamp(0.92 - L * 0.25 + grain * 0.1, 0.0, 1.0);
            col = BONE * wash;
            col = mix(col, BONE, burn);
            col = mix(col, INK, max(line, rit * 0.85));
            col = mix(col, INK, col1 * (1.0 - burn) * 0.25);
        } else if (mode == 2) {
            // ink card: the outline in white, the light in gold, the ritual circles burning
            col = INK + grain * 0.04;
            col = mix(col, GOLD, burn * 0.9);
            col = mix(col, BONE, max(line, rit));
            col = mix(col, BONE, col1 * 0.85);
        } else if (mode == 3) {
            // gold card: posterised to three tones of gold, ink outline
            float g = floor(clamp(L * 1.3 + grain * 0.15, 0.0, 0.999) * 3.0) / 2.0;
            col = mix(GOLD * 0.35, BONE, g) * (0.85 + 0.15 * vign);
            col = mix(col, INK, max(line, rit * 0.7));
        } else {
            // white-out: everything burned to white except a ragged ink silhouette and the column
            float sil = 1.0 - step(0.35 + grain * 0.1, L);
            col = BONE;
            col = mix(col, INK, max(line * 0.9, sil * 0.92 * vign));
            col = mix(col, BONE, col1);
            col = mix(col, GOLD, rit * 0.9);
        }
    } else {
        // ------------------------------------------------------------- THE SPLIT
        float fd = fault(texCoord, Seed);
        float side = sign(fd);
        // the two sides of the frame shoved apart along the fault
        vec2 dir = vec2(cos(Tilt), sin(Tilt)), nrm = vec2(-dir.y, dir.x);
        float shove = 0.018 + 0.03 * hash(Seed);
        vec2 uv = texCoord + (nrm * side * shove + dir * side * shove * 0.6) / vec2(InSize.x / InSize.y, 1.0);
        vec3 s = tap(uv);
        float L = lum(s);
        float gap = 1.0 - smoothstep(0.004, 0.012, abs(fd));
        float lip = 1.0 - smoothstep(0.012, 0.03, abs(fd));
        float web = cracks(texCoord, Seed, 9.0 + 6.0 * hash(Seed + 1.0)) * smoothstep(0.55, 0.02, abs(fd));
        float fine = cracks(texCoord, Seed + 7.0, 26.0) * smoothstep(0.25, 0.0, abs(fd));
        float st = streaks(texCoord, 140.0, Seed);
        float line = rugged(uv, Seed);
        float burn = smoothstep(0.55, 0.88, L);
        if (mode == 0) {
            // settling: the scene, the fault still glowing across it
            col = s;
            col = mix(col, EMBER, lip * 0.6);
            col = mix(col, BONE, gap);
        } else if (mode == 1) {
            // ink card: white fault, ember cracks, white streaks, the scene as rough white line art
            col = INK + grain * 0.05;
            col = mix(col, BONE * 0.9, st * 0.8);
            col = mix(col, BONE, line * 0.8);
            col = mix(col, EMBER, max(web, fine * 0.7));
            col = mix(col, BONE, max(gap, lip * 0.5));
        } else if (mode == 2) {
            // ember card: the scene burned to magma and ink silhouettes, black cracks, a white-hot fault
            float g = clamp(L * 1.4 + grain * 0.15, 0.0, 1.0);
            col = mix(MAGMA, EMBER, g) * (0.75 + 0.25 * vign);
            col = mix(col, INK, max(1.0 - step(0.3, L + grain * 0.1), line) * 0.9);
            col = mix(col, INK, max(web, st * 0.6));
            col = mix(col, BONE, max(gap, lip * 0.8));
        } else if (mode == 3) {
            // negative: inverted, grainy, the fault black
            col = vec3(1.0) - s;
            col = mix(vec3(lum(col)), col, 0.25) + grain * 0.12;
            col = mix(col, INK, max(gap, web * 0.8));
            col = mix(col, BONE, st * 0.5);
        } else {
            // white-out: blown to white, the fault and the cracks in ink, the halves' silhouettes faint
            col = BONE;
            col = mix(col, vec3(0.75, 0.72, 0.68), (1.0 - burn) * 0.4);
            col = mix(col, INK, max(max(gap, lip * 0.7), max(web, fine)) * 0.95);
            col = mix(col, INK, line * 0.6);
        }
    }
    fragColor = vec4(mix(src, clamp(col, 0.0, 1.0), clamp(Strength, 0.0, 1.0)), 1.0);
}
