package com.creedpdf.orbitalrailgun.client.grs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** A Blender-built GRS model ("grs_mesh"): named parts with per-corner position, uv and normal, in blocks, Minecraft axes. */
public final class GrsMesh {
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

    public static GrsMesh parse(JsonObject o) {
        GrsMesh m = new GrsMesh();
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
