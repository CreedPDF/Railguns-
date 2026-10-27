package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlMagazineBlock;
import com.creedpdf.orbitalrailgun.krl.KrlMagazineBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/** The KRL-M30 magazine: each loaded tube shows its interceptor's nose (parts r00..r29); empty tubes are dark. */
public class KrlMagazineRenderer implements BlockEntityRenderer<KrlMagazineBlockEntity> {
    public KrlMagazineRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(KrlMagazineBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.MAGAZINE;
        if (mesh == null) return;
        Direction facing = be.getCachedState().contains(KrlMagazineBlock.FACING) ? be.getCachedState().get(KrlMagazineBlock.FACING) : Direction.SOUTH;
        int rounds = be.rounds();
        KrlMesh.Pose pose = (part, st) -> {
            if (part.length() == 3 && part.charAt(0) == 'r') {
                try {
                    return Integer.parseInt(part.substring(1)) < rounds;
                } catch (NumberFormatException e) {
                    return true;
                }
            }
            return true;
        };
        int lod = KrlCompat.lod(be.getPos());
        ms.push();
        ms.translate(0.5, 0, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.MAGAZINE_TEX)), light, pose, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.MAGAZINE_GLOW)), light, pose, null);
        ms.pop();
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }
}
