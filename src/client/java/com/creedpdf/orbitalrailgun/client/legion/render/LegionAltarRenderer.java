package com.creedpdf.orbitalrailgun.client.legion.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.legion.block.LegionAltarBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The Legion Altar: its ring of vanes turns slowly, the focus crystal bobs and spins, the runes smoulder. */
public class LegionAltarRenderer implements BlockEntityRenderer<LegionAltarBlockEntity> {
    public LegionAltarRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(LegionAltarBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.ALTAR;
        if (mesh == null || be.getWorld() == null) return;
        float time = be.getWorld().getTime() + pt + be.getPos().hashCode() % 100;
        float k = 0.75f + 0.25f * MathHelper.sin(time * 0.08f);
        ms.push();
        ms.translate(0.5, 0, 0.5);
        KrlMesh.Pose pose = (part, s) -> {
            if (part.equals("vanes")) s.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.02f));
            else if (part.equals("crystal")) {
                s.translate(0, 0.05f * MathHelper.sin(time * 0.06f), 0);
                s.multiply(RotationAxis.POSITIVE_Y.rotation(-time * 0.035f));
            }
            return true;
        };
        int lod = KrlCompat.lod(be.getPos());
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.ALTAR_TEX)), light, pose, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.ALTAR_GLOW)), light, pose, (nx, ny, nz, o) -> {
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        });
        ms.pop();
    }
}
