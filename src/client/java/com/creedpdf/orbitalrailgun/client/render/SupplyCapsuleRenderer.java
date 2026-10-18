package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.SupplyCapsuleEntity;
import net.minecraft.client.render.LightmapTextureManager;
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

/** Renders the capsule and, while unloading, the hard-light transfer stream into the railgun. */
public class SupplyCapsuleRenderer extends GeoEntityRenderer<SupplyCapsuleEntity> {
    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    public SupplyCapsuleRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new SupplyCapsuleModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 1.2f;
    }

    @Override
    public void render(SupplyCapsuleEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        super.render(e, yaw, pt, ms, vcp, light);
        if (e.getPhase() != SupplyCapsuleEntity.Phase.TRANSFER) return;
        RailgunEntity rg = e.getRailgun();
        if (rg == null) return;
        Vec3d origin = e.getLerpedPos(pt);
        Vector3f a = local(e.cargoPos(), origin);
        Vector3f b = local(rg.getLerpedPos(pt).add(0, 3.2, 0), origin);
        float time = e.age + pt;
        float fade = Math.min(1f, (e.getPhaseTicks() + pt) / 8f)
                * Math.min(1f, (SupplyCapsuleEntity.Phase.TRANSFER.ticks - e.getPhaseTicks() - pt) / 8f);
        Matrix4f m = ms.peek().getPositionMatrix();
        BeamRenderer.Beam beam = new BeamRenderer.Beam(a, b);
        // Arcing stream: a bezier lifted in the middle, drawn as two twisting ribbons + glow.
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f).add(0, 2.5f, 0);
        int segs = 24;
        for (int k = 0; k < 2; k++) {
            VertexConsumer vc = vcp.getBuffer(BeamRenderLayers.textured(BeamRenderLayers.TRANSFER_STREAM));
            Vector3f prevL = null, prevR = null;
            for (int i = 0; i <= segs; i++) {
                float s = i / (float) segs;
                Vector3f p = bez(a, mid, b, s);
                float ang = s * 12f + time * 0.4f + k * MathHelper.PI;
                float w = 0.22f + 0.12f * MathHelper.sin(s * 9 + time * 0.3f);
                Vector3f off = new Vector3f(beam.u).mul(MathHelper.cos(ang) * 0.35f).add(new Vector3f(beam.v).mul(MathHelper.sin(ang) * 0.35f));
                Vector3f side = new Vector3f(beam.u).mul(MathHelper.cos(ang + 1.57f) * w).add(new Vector3f(beam.v).mul(MathHelper.sin(ang + 1.57f) * w));
                Vector3f l = new Vector3f(p).add(off).sub(side), r = new Vector3f(p).add(off).add(side);
                if (prevL != null) {
                    float v0 = (s - 1f / segs) * 4f - time * 0.12f, v1 = s * 4f - time * 0.12f;
                    v(vc, m, prevL, 0, v0, fade);
                    v(vc, m, prevR, 1, v0, fade);
                    v(vc, m, r, 1, v1, fade);
                    v(vc, m, l, 0, v1, fade);
                }
                prevL = l;
                prevR = r;
            }
        }
        for (int i = 1; i < 6; i++) {
            Vector3f p = bez(a, mid, b, ((time * 0.04f) + i / 6f) % 1f);
            BeamRenderer.glowSphere(vcp.getBuffer(BeamRenderLayers.GLOW), m, p, 0.25f, 0.4f, 1f, 0.8f, 0.8f * fade);
        }
        BeamRenderer.glowSphere(vcp.getBuffer(BeamRenderLayers.GLOW), m, a, 0.9f, 0.3f, 1f, 0.8f, 0.4f * fade);
        BeamRenderer.glowSphere(vcp.getBuffer(BeamRenderLayers.GLOW), m, b, 1.4f, 0.3f, 1f, 0.8f, 0.35f * fade);
    }

    private static Vector3f bez(Vector3f a, Vector3f c, Vector3f b, float s) {
        float u = 1 - s;
        return new Vector3f(a).mul(u * u).add(new Vector3f(c).mul(2 * u * s)).add(new Vector3f(b).mul(s * s));
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vector3f p, float u, float v, float a) {
        vc.vertex(m, p.x, p.y, p.z).color(0.6f, 1f, 0.9f, 0.9f * a).texture(u, v).light(LIGHT).normal(0, 1, 0).next();
    }

    private static Vector3f local(Vec3d w, Vec3d o) {
        return new Vector3f((float) (w.x - o.x), (float) (w.y - o.y), (float) (w.z - o.z));
    }

    @Override
    public boolean shouldRender(SupplyCapsuleEntity e, net.minecraft.client.render.Frustum f, double x, double y, double z) {
        return true;
    }
}
