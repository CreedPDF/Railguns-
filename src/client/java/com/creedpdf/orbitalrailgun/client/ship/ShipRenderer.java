package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.JetFlameFx;
import com.creedpdf.orbitalrailgun.client.space.PlanetBlastFx;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The ARES Kestrel starship: a smooth Blender-built mesh (tools/blender/build_ship.py, drawn with {@link KrlMesh}),
 * posed from the entity's flight model exactly like {@link ShipEntity#modelToWorld} - yaw, pitch and bank about the
 * model's centre, VTOL pods tilting nozzle-down to hover, gear folding into the belly, the rear hatch dropping into a
 * ramp - with its canopy and cabin window drawn translucent and its lights and engines glowing (engines with thrust).
 * Around it: 3D red engine flames (two tilting VTOL pods, twin dorsal main engines) that swell with thrust and blaze
 * white-hot as the jump drive charges, and, while it hovers over a landing zone, the holographic ground scan and the
 * wide levelling laser.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity> {
    private static final float RAMP_OPEN = (float) Math.toRadians(123.5);
    /** VTOL nozzle exit along the pod (model px, aft of the pivot) and where its flame points when horizontal. */
    private static final double NOZZLE_Z = -64, NOZZLE_AFT = -171;

    public ShipRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public Identifier getTexture(ShipEntity e) {
        return KrlModels.SHIP_TEX;
    }

    @Override
    public boolean shouldRender(ShipEntity e, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(ShipEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        float t = e.getPhaseTicks() + pt;
        float time = e.age + pt;
        KrlMesh mesh = KrlModels.SHIP;
        if (mesh != null) drawShip(e, mesh, t, time, ms, vcp, light);
        if (KrlCompat.shadowPass()) return;
        Vec3d cam = dispatcher.camera.getPos();
        Vec3d pos = e.getLerpedPos(pt);
        ms.push();
        ms.translate(cam.x - pos.x, cam.y - pos.y, cam.z - pos.z);   // camera-relative world space
        Matrix4f m = ms.peek().getPositionMatrix();
        flames(e, m, vcp, cam, t, time, pt);
        if (e.getPhase() == ShipEntity.Phase.SURVEY) survey(e, m, vcp, cam, t, time, pt);
        ms.pop();
    }

    // ---- the hull --------------------------------------------------------------------------------

    private void drawShip(ShipEntity e, KrlMesh mesh, float t, float time, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        float[] pose = e.pose(t);
        float th = e.thrust(t), hyper = e.hyperCharge(t);
        ShipEntity.Phase ph = e.getPhase();
        boolean pad = ph == ShipEntity.Phase.IGNITION || (ph == ShipEntity.Phase.ASCENT && t < 50)
                || ph == ShipEntity.Phase.TOUCHDOWN || (ph == ShipEntity.Phase.DEPARTING && t < 50);
        float rumble = pad ? 0.02f * th : 0f;
        float vt = e.vtol(t), fold = e.legFold(t), ramp = e.rampOpen(t);
        double d2 = dispatcher.camera.getPos().squaredDistanceTo(e.getLerpedPos(1f));
        boolean shadow = KrlCompat.shadowPass();
        int lod = shadow ? 2 : d2 < 24 * 24 ? 0 : d2 < 72 * 72 ? 1 : 2;

        ms.push();
        ms.translate(rumble * MathHelper.sin(time * 2.7f), 3.0, rumble * MathHelper.sin(time * 3.3f + 1f));
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(pose[0]));
        ms.multiply(RotationAxis.POSITIVE_X.rotation(pose[1]));
        ms.multiply(RotationAxis.POSITIVE_Z.rotation(-pose[2]));
        ms.translate(0, -3.0, 0);

        // which part is being drawn (the pose callback runs before each part's quads), for the glow's shade
        String[] cur = {""};
        KrlMesh.Pose body = (part, s) -> {
            cur[0] = part;
            switch (part) {
                case "eng_l", "eng_r" -> s.multiply(RotationAxis.POSITIVE_X.rotation(MathHelper.HALF_PI * vt));
                case "gear_f", "gear_l", "gear_r" -> {
                    s.translate(0, 0.32f * fold, 0);
                    s.multiply(RotationAxis.POSITIVE_X.rotation(MathHelper.HALF_PI * fold));
                }
                case "ramp" -> s.multiply(RotationAxis.POSITIVE_X.rotation(RAMP_OPEN * ramp));
                case "glass" -> {
                    return false;
                }
                default -> {
                }
            }
            return true;
        };
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.SHIP_TEX)), light, body, null);
        if (!shadow) {
            float engine = Math.min(1f, 0.3f + 0.7f * th + hyper);
            float pulse = 0.88f + 0.12f * MathHelper.sin(time * 0.12f);
            mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.SHIP_GLOW)), light, body, (nx, ny, nz, o) -> {
                float k = switch (cur[0]) {
                    case "main", "eng_l", "eng_r" -> engine;
                    default -> pulse;
                };
                o[0] = k;
                o[1] = k;
                o[2] = k;
                o[3] = 1f;
            });
            KrlMesh.Pose glassOnly = (part, s) -> part.equals("glass");
            mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityTranslucent(KrlModels.SHIP_TEX)), light, glassOnly,
                    (nx, ny, nz, o) -> {
                        o[0] = 1f;
                        o[1] = 1f;
                        o[2] = 1f;
                        o[3] = 0.55f;
                    });
        }
        ms.pop();
    }

    // ---- engines -------------------------------------------------------------------------------

    private static void flames(ShipEntity e, Matrix4f m, VertexConsumerProvider vcp, Vec3d cam, float t, float time, float pt) {
        float th = e.thrust(t);
        float hyper = e.hyperCharge(t);
        if (th <= 0.02f && hyper <= 0.02f) return;
        float vt = e.vtol(t);
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        float flick = 0.9f + 0.1f * MathHelper.sin(time * 3.1f) * MathHelper.sin(time * 1.7f);
        // nacelles
        for (int s = -1; s <= 1; s += 2) {
            Vec3d noz = e.modelToWorld(s * ShipEntity.NAC_X, ShipEntity.NAC_Y, NOZZLE_Z, s, pt);
            Vec3d aft = e.modelToWorld(s * ShipEntity.NAC_X, ShipEntity.NAC_Y, NOZZLE_AFT, s, pt);
            float len = (2.2f + 6.5f * th + 6f * hyper) * flick;
            flame(g, m, rel(noz, cam), dir(noz, aft), len, 0.95f, time + s * 5.1f, th, hyper);
        }
        // dorsal main engines: forward flight and the jump
        float main = Math.max(th * (1f - vt), hyper);
        if (main > 0.03f) {
            for (int s = -1; s <= 1; s += 2) {
                Vec3d noz = e.modelToWorld(s * 20, 111, -174, 0, pt);
                Vec3d aft = e.modelToWorld(s * 20, 111, -260, 0, pt);
                float len = (1.5f + 7f * main + 16f * hyper) * flick;
                flame(g, m, rel(noz, cam), dir(noz, aft), len, 0.7f, time + s * 2.3f, main, hyper);
            }
        }
        if (hyper > 0.02f) {
            Vector3f c = rel(e.modelToWorld(0, 111, -176, 0, pt), cam);
            PlanetBlastFx.glowBall(g, m, c, 3f + 5f * hyper, 1f, 0.35f + 0.5f * hyper, 0.2f + 0.6f * hyper, 0.35f * hyper);
        }
    }

    /** One red 3D flame: deep red envelope, red-orange body, orange inner cone, white-yellow core. */
    private static void flame(VertexConsumer g, Matrix4f m, Vector3f nozzle, Vector3f axis, float len, float r0, float time,
                              float th, float hyper) {
        float a = Math.min(1f, 0.25f + th);
        float wh = hyper;
        JetFlameFx.shell(g, m, nozzle, axis, len, r0 * 1.3f, time, 0.7f, 0.05f, 0.02f, 0.38f * a, 1.0f);
        JetFlameFx.shell(g, m, nozzle, axis, len * 0.82f, r0, time * 1.3f, 1f, 0.22f + 0.4f * wh, 0.05f + 0.4f * wh, 0.62f * a, 0.75f);
        JetFlameFx.shell(g, m, nozzle, axis, len * 0.55f, r0 * 0.7f, time * 1.6f, 1f, 0.55f + 0.35f * wh, 0.18f + 0.6f * wh, 0.8f * a, 0.5f);
        JetFlameFx.shell(g, m, nozzle, axis, len * 0.3f, r0 * 0.42f, time * 2f, 1f, 0.93f, 0.75f + 0.25f * wh, a, 0.3f);
        for (int k = 1; k <= 3; k++) {
            float s = k * 0.18f;
            Vector3f c = new Vector3f(axis).mul(len * s).add(nozzle);
            JetFlameFx.diamond(g, m, c, axis, r0 * (0.7f - 0.12f * k), len * 0.05f, 1f, 0.75f, 0.45f, (0.55f - 0.12f * k) * a);
        }
        PlanetBlastFx.glowBall(g, m, nozzle, r0 * 1.7f, 1f, 0.3f, 0.08f, 0.45f * a);
    }

    // ---- landing-zone scan and levelling laser --------------------------------------------------

    private static void survey(ShipEntity e, Matrix4f m, VertexConsumerProvider vcp, Vec3d cam, float t, float time, float pt) {
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        int f = e.getFacing();
        Vec3d H = ShipEntity.heading(f);
        Vec3d R = new Vec3d(-H.z, 0, H.x), B = H.negate();
        Vec3d c = e.site().add(0, 0.06, 0);
        float in = Math.min(1f, t / 10f) * Math.min(1f, (e.duration() - t) / 10f);
        if (in <= 0) return;
        double hw = ShipEntity.HALF_WIDTH + 0.5, z0 = ShipEntity.ROW_MIN - 0.5, z1 = ShipEntity.ROW_MAX + 0.5;
        float hr = 1f, hg = 0.3f, hb = 0.18f;
        // hologram grid on the ground
        for (int i = -ShipEntity.HALF_WIDTH; i <= ShipEntity.HALF_WIDTH + 1; i++) {
            double x = i - 0.5;
            line(g, m, rel(c.add(R.multiply(x)).add(B.multiply(z0)), cam), rel(c.add(R.multiply(x)).add(B.multiply(z1)), cam), 0.04f,
                    hr, hg, hb, 0.28f * in);
        }
        for (int j = ShipEntity.ROW_MIN; j <= ShipEntity.ROW_MAX + 1; j++) {
            double z = j - 0.5;
            line(g, m, rel(c.add(R.multiply(-hw)).add(B.multiply(z)), cam), rel(c.add(R.multiply(hw)).add(B.multiply(z)), cam), 0.04f,
                    hr, hg, hb, 0.28f * in);
        }
        // sweeping scan bar
        double sz = z0 + (z1 - z0) * ((t % 40f) / 40f);
        line(g, m, rel(c.add(R.multiply(-hw)).add(B.multiply(sz)), cam), rel(c.add(R.multiply(hw)).add(B.multiply(sz)), cam), 0.35f,
                1f, 0.55f, 0.35f, 0.75f * in);
        // scanner frustum from the belly
        Vector3f em = rel(e.modelToWorld(0, 10, 0, 0, pt), cam);
        Vector3f[] corners = {
                rel(c.add(R.multiply(-hw)).add(B.multiply(z0)), cam), rel(c.add(R.multiply(hw)).add(B.multiply(z0)), cam),
                rel(c.add(R.multiply(hw)).add(B.multiply(z1)), cam), rel(c.add(R.multiply(-hw)).add(B.multiply(z1)), cam)};
        for (int k = 0; k < 4; k++) {
            tri(g, m, em, corners[k], corners[(k + 1) % 4], hr, hg, hb, 0.06f * in, 0.02f * in);
            line(g, m, em, corners[k], 0.05f, hr, 0.4f, 0.3f, 0.35f * in);
        }
        PlanetBlastFx.glowBall(g, m, em, 0.6f, 1f, 0.4f, 0.25f, 0.6f * in);

        // the wide levelling laser, row by row
        if (!e.siteFlat() && t >= ShipEntity.SCAN_TICKS) {
            float rows = (t - ShipEntity.SCAN_TICKS) / ShipEntity.ROW_TICKS;
            double lz = ShipEntity.ROW_MIN + rows;
            if (lz <= ShipEntity.ROW_MAX + 0.5) {
                float fl = 0.8f + 0.2f * MathHelper.sin(time * 9f);
                Vector3f l0 = rel(c.add(R.multiply(-hw)).add(B.multiply(lz)), cam), l1 = rel(c.add(R.multiply(hw)).add(B.multiply(lz)), cam);
                tri(g, m, em, l0, l1, 1f, 0.15f, 0.04f, 0.45f * fl, 0.25f * fl);
                line(g, m, l0, l1, 0.3f, 1f, 0.85f, 0.6f, fl);
                line(g, m, l0, l1, 0.9f, 1f, 0.25f, 0.06f, 0.45f * fl);
                PlanetBlastFx.glowBall(g, m, em, 1.1f, 1f, 0.3f, 0.1f, 0.9f * fl);
            }
        }
    }

    /** Flat ribbon from a to b, turned to face the camera (the camera is the origin). */
    private static void line(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float w, float r, float gg, float bb, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = new Vector3f(d).cross(mid);
        if (side.lengthSquared() < 1e-9f) return;
        side.normalize().mul(w * 0.5f);
        g.vertex(m, a.x - side.x, a.y - side.y, a.z - side.z).color(r, gg, bb, al).next();
        g.vertex(m, b.x - side.x, b.y - side.y, b.z - side.z).color(r, gg, bb, al).next();
        g.vertex(m, b.x + side.x, b.y + side.y, b.z + side.z).color(r, gg, bb, al).next();
        g.vertex(m, a.x + side.x, a.y + side.y, a.z + side.z).color(r, gg, bb, al).next();
    }

    private static void tri(VertexConsumer g, Matrix4f m, Vector3f apex, Vector3f a, Vector3f b, float r, float gg, float bb, float aApex, float aBase) {
        g.vertex(m, apex.x, apex.y, apex.z).color(r, gg, bb, aApex).next();
        g.vertex(m, a.x, a.y, a.z).color(r, gg, bb, aBase).next();
        g.vertex(m, b.x, b.y, b.z).color(r, gg, bb, aBase).next();
        g.vertex(m, apex.x, apex.y, apex.z).color(r, gg, bb, aApex).next();
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }

    private static Vector3f dir(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from).normalize();
        return new Vector3f((float) d.x, (float) d.y, (float) d.z);
    }
}
