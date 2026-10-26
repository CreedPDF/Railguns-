package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** GRS effect primitives: camera-facing sprites, streaks, flat rings and textured beams, in the GRS's own textures. */
public final class GrsDraw {
    private GrsDraw() {}

    public static final int FULL = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    public static final Identifier ORB = fx("orb"), FLARE = fx("flare"), STREAK = fx("streak"), SHOCK = fx("shock"), SCORCH = fx("scorch"),
            CRACKS = fx("cracks"), DUST = fx("dust"), BEAM = fx("beam");
    public static final float[] ORANGE = {1f, 0.45f, 0.08f}, HOT = {1f, 0.7f, 0.3f}, WHITE = {1f, 0.92f, 0.8f};

    private static Identifier fx(String n) {
        return RailgunMod.id("textures/grs/fx/" + n + ".png");
    }

    /** Camera right/up in world orientation. */
    public static final Vector3f R = new Vector3f(1, 0, 0), U = new Vector3f(0, 1, 0), F = new Vector3f(0, 0, -1);

    public static void camera(Camera c) {
        Quaternionf q = c.getRotation();
        // the camera looks down its rotated +Z; with +Y up, screen-right is its rotated -X
        R.set(-1, 0, 0).rotate(q);
        U.set(0, 1, 0).rotate(q);
        F.set(0, 0, 1).rotate(q);
    }

    public static VertexConsumer add(VertexConsumerProvider cp, Identifier tex) {
        return cp.getBuffer(BeamRenderLayers.textured(tex));
    }

    public static VertexConsumer blend(VertexConsumerProvider cp, Identifier tex) {
        return cp.getBuffer(BeamRenderLayers.translucent(tex));
    }

    public static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float vv, float r, float g, float b, float a) {
        vc.vertex(m, x, y, z).color(cl(r), cl(g), cl(b), cl(a)).texture(u, vv).light(FULL).normal(0, 1, 0).next();
    }

    /** A camera-facing sprite of half-size s, rotated by ang. Both windings. */
    public static void sprite(VertexConsumer vc, Matrix4f m, float x, float y, float z, float s, float ang, float r, float g, float b, float a) {
        if (a <= 0.003f || s <= 0) return;
        float c = MathHelper.cos(ang) * s, sn = MathHelper.sin(ang) * s;
        float rx = R.x * c + U.x * sn, ry = R.y * c + U.y * sn, rz = R.z * c + U.z * sn;
        float ux = -R.x * sn + U.x * c, uy = -R.y * sn + U.y * c, uz = -R.z * sn + U.z * c;
        v(vc, m, x - rx - ux, y - ry - uy, z - rz - uz, 0, 1, r, g, b, a);
        v(vc, m, x + rx - ux, y + ry - uy, z + rz - uz, 1, 1, r, g, b, a);
        v(vc, m, x + rx + ux, y + ry + uy, z + rz + uz, 1, 0, r, g, b, a);
        v(vc, m, x - rx + ux, y - ry + uy, z - rz + uz, 0, 0, r, g, b, a);
    }

    /** A streak from tail to head facing the camera (u = 0 at the tail, 1 at the head). */
    public static void streak(VertexConsumer vc, Matrix4f m, Vector3f tail, Vector3f head, Vector3f eye, float w, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        Vector3f d = new Vector3f(head).sub(tail);
        Vector3f mid = new Vector3f(tail).add(head).mul(0.5f).sub(eye);
        Vector3f s = d.cross(mid, new Vector3f());
        if (s.lengthSquared() < 1e-10f) return;
        s.normalize().mul(w);
        v(vc, m, tail.x - s.x, tail.y - s.y, tail.z - s.z, 0, 0, r, g, b, a);
        v(vc, m, head.x - s.x, head.y - s.y, head.z - s.z, 1, 0, r, g, b, a);
        v(vc, m, head.x + s.x, head.y + s.y, head.z + s.z, 1, 1, r, g, b, a);
        v(vc, m, tail.x + s.x, tail.y + s.y, tail.z + s.z, 0, 1, r, g, b, a);
    }

    /** A flat textured square centred at c in the plane (e1, e2), half-size s. */
    public static void flat(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f e1, Vector3f e2, float s, float r, float g, float b, float a) {
        if (a <= 0.003f || s <= 0) return;
        float[][] k = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        for (float[] q : k)
            v(vc, m, c.x + (e1.x * q[0] + e2.x * q[1]) * s, c.y + (e1.y * q[0] + e2.y * q[1]) * s, c.z + (e1.z * q[0] + e2.z * q[1]) * s, q[2], q[3], r, g, b, a);
    }

    /** A beam: two crossed camera-aligned ribbons from a to b with the beam texture (u across, v along). */
    public static void beam(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, Vector3f eye, float w, float vScroll, float vLen, float r, float g, float bl,
                            float al) {
        if (al <= 0.003f) return;
        Vector3f d = new Vector3f(b).sub(a);
        float len = d.length();
        if (len < 1e-4f) return;
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f).sub(eye);
        Vector3f s = d.cross(mid, new Vector3f());
        if (s.lengthSquared() < 1e-10f) return;
        s.normalize().mul(w);
        float v0 = vScroll, v1 = vScroll + len / vLen;
        v(vc, m, a.x - s.x, a.y - s.y, a.z - s.z, 0, v0, r, g, bl, al);
        v(vc, m, a.x + s.x, a.y + s.y, a.z + s.z, 1, v0, r, g, bl, al);
        v(vc, m, b.x + s.x, b.y + s.y, b.z + s.z, 1, v1, r, g, bl, al);
        v(vc, m, b.x - s.x, b.y - s.y, b.z - s.z, 0, v1, r, g, bl, al);
    }

    public static float cl(float x) {
        return x < 0 ? 0 : Math.min(1, x);
    }

    public static float smooth(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
