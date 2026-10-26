package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlLauncherBlock;
import com.creedpdf.orbitalrailgun.krl.KrlLauncherBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * The KRL-1 pad: the 3 x 3 pad and its rail tower, the clamp arms that swing clear a moment before liftoff and close
 * again afterwards, the umbilical that drops away, the beacon that turns while the motor burns, and the spear on the
 * rail while one is loaded.
 */
public class KrlLauncherRenderer implements BlockEntityRenderer<KrlLauncherBlockEntity> {
    public KrlLauncherRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(KrlLauncherBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.LAUNCHER;
        if (mesh == null) return;
        Direction facing = be.getCachedState().contains(KrlLauncherBlock.FACING) ? be.getCachedState().get(KrlLauncherBlock.FACING) : Direction.SOUTH;
        long now = KrlClient.now();
        // time relative to liftoff: from the countdown in progress, else from the last launch
        KrlClient.CFlight f = KrlClient.padFlight(be.getPos());
        double tp;
        if (f != null && f.t(pt) < KrlFlight.TOTAL + 400) tp = f.t(pt);
        else if (be.launchedAt() >= 0) tp = now - be.launchedAt() + pt;
        else tp = 1e9;
        boolean burning = f != null && tp < 0;
        float open = (float) (smooth((tp + 14) / 10) * (1 - smooth((tp - 220) / 50)));
        float umb = (float) (smooth((tp + 70) / 22) * (1 - smooth((tp - 230) / 50)));
        float beacon = (float) ((now + pt) * (burning ? 9.0 : 1.5));
        float blink = burning ? 0.65f + 0.35f * MathHelper.sin((now + pt) * 0.9f) : be.loaded() ? 0.75f : 0.35f;

        ms.push();
        ms.translate(0.5, 0, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        KrlMesh.Pose pose = (part, st) -> {
            switch (part) {
                case "clamps" -> st.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(78 * open));
                case "umbilical" -> st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(70 * umb));
                case "beacon" -> st.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(beacon));
                default -> {
                }
            }
            return true;
        };
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.LAUNCHER_TEX)), light, pose, null);
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.LAUNCHER_GLOW)), light, pose, (nx, ny, nz, o) -> {
            o[0] = blink;
            o[1] = blink;
            o[2] = blink;
            o[3] = 1;
        });
        // the spear on the rail
        if (be.loaded() && (f == null || tp < 0) && KrlModels.SPEAR != null) {
            Vec3d rp = be.railPoint().subtract(Vec3d.ofBottomCenter(be.getPos()));
            ms.push();
            ms.translate(0, rp.y, 0);
            KrlModels.SPEAR.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.SPEAR_TEX)), light, null, null);
            KrlModels.SPEAR.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.SPEAR_GLOW)), light, null, null);
            ms.pop();
        }
        ms.pop();
    }

    private static double smooth(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    @Override
    public boolean rendersOutsideBoundingBox(KrlLauncherBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 192;
    }
}
