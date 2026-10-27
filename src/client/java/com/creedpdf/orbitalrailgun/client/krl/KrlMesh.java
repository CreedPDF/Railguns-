package com.creedpdf.orbitalrailgun.client.krl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** A Blender-built KRL model ("krl_mesh"): named parts with per-corner position, uv and normal, in blocks, Minecraft axes. */
public final class KrlMesh {
    public interface Shade {
        /** rgba for a corner; n = normal after the current matrix (view space for the sky, world-ish otherwise). */
        void shade(float nx, float ny, float nz, float[] out);
    }

    public interface Pose {
        /** Transform the part about its pivot (the stack is already at the pivot). Return false to hide it. */
        boolean pose(String part, MatrixStack ms);
    }

    public static final class Part {
        public final String name;
        public final float[] pivot;
        final float[] v;
        final int[] q;

        Part(String name, float[] pivot, float[] v, int[] q) {
            this.name = name;
            this.pivot = pivot;
            this.v = v;
            this.q = q;
        }
    }

    private static final int S = 8;
    public final List<Part> parts = new ArrayList<>();
    /** Detail levels (0 near .. 2 far / shadow pass) and the glow-only subsets of each; null until {@link #prepare}. */
    private KrlMesh[] lods, glows;
    /** Minimum quad area (square blocks) kept at each level: bevel slivers and bolt heads drop out with distance. */
    private static final float[] LOD_AREA = {3e-6f, 1e-4f, 1e-3f}, GLOW_AREA = {1e-7f, 3e-5f, 1e-4f};

    /** This mesh at a detail level (0 = everything). */
    public KrlMesh lod(int level) {
        return lods == null ? this : lods[Math.max(0, Math.min(2, level))];
    }

    /** Only the quads that sample a lit part of the glow texture, at a detail level. */
    public KrlMesh glow(int level) {
        return glows == null ? lod(level) : glows[Math.max(0, Math.min(2, level))];
    }

    /** Builds the detail levels, and the glow subsets from the glow texture (may be null: then every quad counts). */
    public void prepare(NativeImage glowTex) {
        lods = new KrlMesh[3];
        glows = new KrlMesh[3];
        for (int l = 0; l < 3; l++) {
            KrlMesh lm = new KrlMesh(), gm = new KrlMesh();
            for (Part p : parts) {
                int[] keep = new int[p.q.length], glow = new int[p.q.length];
                int nk = 0, ng = 0;
                for (int i = 0; i + 3 < p.q.length; i += 4) {
                    float a = area(p, i);
                    if (a >= LOD_AREA[l]) {
                        System.arraycopy(p.q, i, keep, nk, 4);
                        nk += 4;
                    }
                    if (a >= GLOW_AREA[l] && (glowTex == null || lit(p, i, glowTex))) {
                        System.arraycopy(p.q, i, glow, ng, 4);
                        ng += 4;
                    }
                }
                lm.parts.add(new Part(p.name, p.pivot, p.v, java.util.Arrays.copyOf(keep, nk)));
                gm.parts.add(new Part(p.name, p.pivot, p.v, java.util.Arrays.copyOf(glow, ng)));
            }
            lods[l] = lm;
            glows[l] = gm;
        }
    }

    private static float area(Part p, int i) {
        int a = p.q[i] * S, b = p.q[i + 1] * S, c = p.q[i + 2] * S, d = p.q[i + 3] * S;
        float ux = p.v[c] - p.v[a], uy = p.v[c + 1] - p.v[a + 1], uz = p.v[c + 2] - p.v[a + 2];
        float vx = p.v[d] - p.v[b], vy = p.v[d + 1] - p.v[b + 1], vz = p.v[d + 2] - p.v[b + 2];
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5f * (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Does the quad sample anything brighter than black in the glow texture (corners and middle)? */
    private static boolean lit(Part p, int i, NativeImage img) {
        float mu = 0, mv = 0;
        for (int k = 0; k < 4; k++) {
            int b = p.q[i + k] * S;
            float u = p.v[b + 3], v = p.v[b + 4];
            mu += u / 4;
            mv += v / 4;
            if (bright(img, u, v)) return true;
        }
        return bright(img, mu, mv);
    }

    private static boolean bright(NativeImage img, float u, float v) {
        int w = img.getWidth(), h = img.getHeight();
        int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int c = img.getColor(Math.floorMod(x + dx, w), Math.floorMod(y + dy, h)); // ABGR
                int a = (c >>> 24) & 255, r = c & 255, g = (c >> 8) & 255, bl = (c >> 16) & 255;
                if (a > 8 && Math.max(r, Math.max(g, bl)) > 14) return true;
            }
        return false;
    }

    public static KrlMesh parse(JsonObject o) {
        KrlMesh m = new KrlMesh();
        for (JsonElement el : o.getAsJsonArray("parts")) {
            JsonObject p = el.getAsJsonObject();
            JsonArray va = p.getAsJsonArray("verts"), qa = p.getAsJsonArray("quads"), pv = p.getAsJsonArray("pivot");
            float[] v = new float[va.size()];
            for (int i = 0; i < v.length; i++) v[i] = va.get(i).getAsFloat();
            int[] q = new int[qa.size()];
            for (int i = 0; i < q.length; i++) q[i] = qa.get(i).getAsInt();
            m.parts.add(new Part(p.get("name").getAsString(), new float[]{pv.get(0).getAsFloat(), pv.get(1).getAsFloat(), pv.get(2).getAsFloat()}, v, q));
        }
        return m;
    }

    /** Entity-format layers (POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL): cutout, eyes. */
    public void render(MatrixStack ms, VertexConsumer vc, int light, Pose pose, Shade shade) {
        draw(ms, vc, light, pose, shade, true);
    }

    /** Beam-format layers (POSITION_COLOR_TEXTURE_LIGHT_NORMAL): the mod's solid / additive layers. */
    public void renderNoOverlay(MatrixStack ms, VertexConsumer vc, int light, Pose pose, Shade shade) {
        draw(ms, vc, light, pose, shade, false);
    }

    public Part part(String name) {
        for (Part p : parts) if (p.name.equals(name)) return p;
        return null;
    }

    /** Lowest and highest corner along y of one part (model units). */
    public float[] yRange(String name) {
        Part p = part(name);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        if (p == null) return new float[]{0, 0};
        for (int i = 1; i < p.v.length; i += S) {
            lo = Math.min(lo, p.v[i]);
            hi = Math.max(hi, p.v[i]);
        }
        return new float[]{lo, hi};
    }

    private void draw(MatrixStack ms, VertexConsumer vc, int light, Pose pose, Shade shade, boolean overlay) {
        float[] c = {1, 1, 1, 1};
        Vector3f n = new Vector3f();
        for (Part p : parts) {
            ms.push();
            ms.translate(p.pivot[0], p.pivot[1], p.pivot[2]);
            boolean show = pose == null || pose.pose(p.name, ms);
            ms.translate(-p.pivot[0], -p.pivot[1], -p.pivot[2]);
            if (show) {
                MatrixStack.Entry en = ms.peek();
                Matrix4f m = en.getPositionMatrix();
                Matrix3f nm = en.getNormalMatrix();
                for (int i = 0; i < p.q.length; i++) {
                    int b = p.q[i] * S;
                    n.set(p.v[b + 5], p.v[b + 6], p.v[b + 7]);
                    nm.transform(n);
                    if (shade != null) shade.shade(n.x, n.y, n.z, c);
                    VertexConsumer x = vc.vertex(m, p.v[b], p.v[b + 1], p.v[b + 2]).color(cl(c[0]), cl(c[1]), cl(c[2]), cl(c[3])).texture(p.v[b + 3], p.v[b + 4]);
                    if (overlay) x = x.overlay(OverlayTexture.DEFAULT_UV);
                    x.light(light).normal(n.x, n.y, n.z).next();
                }
            }
            ms.pop();
        }
    }

    private static float cl(float x) {
        return x < 0 ? 0 : Math.min(1, x);
    }
}
