package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.station.Sc;
import com.creedpdf.orbitalrailgun.station.ScModuleBlock;
import com.creedpdf.orbitalrailgun.station.ScRailgunBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The station railgun: pedestal facing the way it was placed, housing and barrel aimed by the block entity's own
 * tracking (yaw about the vertical, elevation about the trunnion). The capacitor coils and rails glow dim while armed,
 * build up through the charge, and flare for a moment as it fires.
 */
public class ScRailgunRenderer implements BlockEntityRenderer<ScRailgunBlockEntity> {
    public ScRailgunRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(ScRailgunBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.TURRET;
        if (mesh == null || be.getWorld() == null) return;
        long now = be.getWorld().getTime();
        float since = now - be.phaseAt() + pt;
        float k;
        if (!be.powered()) k = 0.06f;
        else if (be.phase() == ScRailgunBlockEntity.CHARGE) k = 0.35f + 0.65f * Math.min(1f, since / Sc.RAIL_CHARGE);
        else if (be.phase() == ScRailgunBlockEntity.COOL) k = Math.max(0.35f, 1f - since / 10f);
        else k = 0.35f + 0.05f * MathHelper.sin((now + pt) * 0.2f);
        float yaw = MathHelper.lerp(pt, be.prevYaw, be.yaw), pitch = MathHelper.lerp(pt, be.prevPitch, be.pitch);
        float facing = (float) Math.toRadians(-be.getCachedState().get(ScModuleBlock.FACING).asRotation());
        ms.push();
        ms.translate(0.5, 0, 0.5);
        final float fk = k;
        KrlMesh.Pose pose = (part, s) -> {
            switch (part) {
                case "base" -> s.multiply(RotationAxis.POSITIVE_Y.rotation(facing));
                case "yaw" -> s.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
                case "barrel" -> {
                    s.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
                    s.multiply(RotationAxis.POSITIVE_X.rotation(-pitch));
                }
                default -> {
                }
            }
            return true;
        };
        ScDraw.draw(mesh, KrlModels.TURRET_TEX, KrlModels.TURRET_GLOW, ms, vcp, light, KrlCompat.lod(be.getPos()), pose, ScDraw.grey(fk));
        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ScRailgunBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 192;
    }
}
