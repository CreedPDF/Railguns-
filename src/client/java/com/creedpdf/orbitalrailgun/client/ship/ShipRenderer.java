package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.PlanetBlastFx;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** The starship model plus its blue engine plumes (two nacelles, flaring with thrust). */
public class ShipRenderer extends GeoEntityRenderer<ShipEntity> {
    public ShipRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new ShipModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 0f;
    }

    @Override
    public boolean shouldRender(ShipEntity e, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(ShipEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        super.render(e, yaw, pt, ms, vcp, light);
        float t = e.getPhaseTicks() + pt;
        float th = e.thrust(t);
        if (th <= 0.02f) return;
        Vec3d cam = dispatcher.camera.getPos();
        Vec3d pos = e.getLerpedPos(pt);
        ms.push();
        ms.translate(cam.x - pos.x, cam.y - pos.y, cam.z - pos.z);   // camera-relative world space
        Matrix4f m = ms.peek().getPositionMatrix();
        float time = e.age + pt;
        float flick = 0.88f + 0.12f * MathHelper.sin(time * 3.1f) * MathHelper.sin(time * 1.7f);
        VertexConsumer st = vcp.getBuffer(BeamRenderLayers.textured(PlanetBlastFx.STREAK));
        for (int s = -1; s <= 1; s += 2) {
            Vec3d noz = ShipCinematic.shipPoint(e, s * 78, 58, -161, pt);
            Vec3d aft = ShipCinematic.shipPoint(e, s * 78, 58, -261, pt);
            Vec3d dir = aft.subtract(noz).normalize();
            Vector3f n = rel(noz, cam), d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
            float len = (6f + 16f * th) * flick;
            // plume drawn tail -> head, so start at the far end and point back at the nozzle
            Vector3f tail = new Vector3f(d).mul(len).add(n);
            Vector3f back = new Vector3f(d).negate();
            PlanetBlastFx.streak(st, m, tail, back, len, 1.3f * (0.6f + 0.4f * th), 0.15f, 0.35f, 1f, 0.55f * th);
            Vector3f tail2 = new Vector3f(d).mul(len * 0.55f).add(n);
            PlanetBlastFx.streak(st, m, tail2, back, len * 0.55f, 0.75f, 0.45f, 0.75f, 1f, 0.85f * th);
            Vector3f tail3 = new Vector3f(d).mul(len * 0.22f).add(n);
            PlanetBlastFx.streak(st, m, tail3, back, len * 0.22f, 0.45f, 0.9f, 0.97f, 1f, th);
        }
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        for (int s = -1; s <= 1; s += 2) {
            Vector3f n = rel(ShipCinematic.shipPoint(e, s * 78, 58, -163, pt), cam);
            PlanetBlastFx.glowBall(g, m, n, 1.6f + 0.6f * th, 0.35f, 0.6f, 1f, 0.55f * th * flick);
        }
        ms.pop();
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }
}
