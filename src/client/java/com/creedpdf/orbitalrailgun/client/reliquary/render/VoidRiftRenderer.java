package com.creedpdf.orbitalrailgun.client.reliquary.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.reliquary.CosmicReliquary;
import com.creedpdf.orbitalrailgun.reliquary.entity.VoidRiftEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The rift: a jagged vertical tear in space, a white-hot seam inside a violet glow, flickering, opening and closing. */
public class VoidRiftRenderer extends EntityRenderer<VoidRiftEntity> {
    public VoidRiftRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 0;
    }

    @Override
    public Identifier getTexture(VoidRiftEntity e) {
        return CosmicReliquary.id("textures/fx/blank.png");
    }

    @Override
    public void render(VoidRiftEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        if (KrlCompat.shadowPass()) return;
        float a = e.age + pt;
        float open = Math.min(1f, a / 6f) * Math.min(1f, (VoidRiftEntity.LIFE - a) / 12f);
        if (open <= 0) return;
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        Random r = Random.create(e.getId() * 31L + (long) (a / 3));   // the seam re-tears every few ticks
        int n = 10;
        float h = 2.3f * open;
        Vector3f prev = new Vector3f(0, 0.05f, 0);
        float flick = 0.8f + 0.2f * MathHelper.sin(a * 2.3f);
        for (int i = 1; i <= n; i++) {
            float y = 0.05f + h * i / n;
            float off = i == n ? 0 : (r.nextFloat() - 0.5f) * 0.45f * open;
            Vector3f cur = new Vector3f(off, y, (r.nextFloat() - 0.5f) * 0.2f);
            float mid = MathHelper.sin(MathHelper.PI * i / n);
            ReliquaryFx.strip(g, m, prev, cur, 0.9f * mid * open + 0.1f, ReliquaryFx.VR, ReliquaryFx.VG, ReliquaryFx.VB, 0.35f * flick);
            ReliquaryFx.strip(g, m, prev, cur, 0.12f * mid + 0.03f, 1f, 0.9f, 1f, flick);
            prev = cur;
        }
        super.render(e, yaw, pt, ms, vcp, light);
    }
}
