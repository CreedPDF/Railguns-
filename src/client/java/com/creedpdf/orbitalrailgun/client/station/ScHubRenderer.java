package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.station.ScHubBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The Command Hub: its comms dish turns slowly; its lights glow while the grid is up and flash red under attack. */
public class ScHubRenderer implements BlockEntityRenderer<ScHubBlockEntity> {
    public ScHubRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(ScHubBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.HUB;
        if (mesh == null || be.getWorld() == null) return;
        float time = be.getWorld().getTime() + pt;
        float k = be.online() ? 0.85f + 0.15f * MathHelper.sin(time * 0.1f) : 0.12f;
        if (be.threats() > 0) k = 0.5f + 0.5f * (((int) (time / 6)) % 2);
        float dish = be.online() ? time * 0.012f : 0f;
        ms.push();
        ms.translate(0.5, 0, 0.5);
        KrlMesh.Pose pose = (part, s) -> {
            if (part.equals("dish")) s.multiply(RotationAxis.POSITIVE_Y.rotation(dish));
            return true;
        };
        ScDraw.draw(mesh, KrlModels.HUB_TEX, KrlModels.HUB_GLOW, ms, vcp, light, KrlCompat.lod(be.getPos()), pose, ScDraw.grey(k));
        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ScHubBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 192;
    }
}
