package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlStationBlock;
import com.creedpdf.orbitalrailgun.krl.KrlStationBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The Missile Integration Station bench, turned to face the way it was placed; its terminal and fuze LED glow. */
public class KrlStationRenderer implements BlockEntityRenderer<KrlStationBlockEntity> {
    public KrlStationRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(KrlStationBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.STATION;
        if (mesh == null) return;
        Direction facing = be.getCachedState().contains(KrlStationBlock.FACING) ? be.getCachedState().get(KrlStationBlock.FACING) : Direction.SOUTH;
        float time = KrlClient.now() + pt;
        float g = 0.85f + 0.15f * MathHelper.sin(time * 0.15f);
        ms.push();
        ms.translate(0.5, 0, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        int lod = KrlCompat.lod(be.getPos());
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.STATION_TEX)), light, null, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.STATION_GLOW)), light, null, (nx, ny, nz, o) -> {
            o[0] = g;
            o[1] = g;
            o[2] = g;
            o[3] = 1;
        });
        ms.pop();
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }
}
