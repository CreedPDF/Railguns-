package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import com.creedpdf.orbitalrailgun.station.ScSolarBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The solar array. After placement the mast swings round and the blankets roll out from their drums (each wing is
 * scaled out along its length while its drum rides to the tip); then the mast lays the boom north-south and the wings
 * tilt to follow the sun across the sky (in orbit they rock gently).
 */
public class ScSolarRenderer implements BlockEntityRenderer<ScSolarBlockEntity> {
    private static final float WING_LEN = 4.6f;

    public ScSolarRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(ScSolarBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.SOLAR;
        if (mesh == null || be.getWorld() == null) return;
        float time = be.getWorld().getTime() + pt;
        float ext = Math.max(0.03f, be.extension(pt));
        float swing = MathHelper.clamp(ext * 1.6f, 0f, 1f);
        float tilt;
        if (SpaceDims.isSpace(be.getWorld())) tilt = 0.22f * MathHelper.sin(time * 0.004f + be.getPos().getX());
        else {
            float a = be.getWorld().getSkyAngle(pt) * MathHelper.TAU;
            // the boom lies north-south, so tilting about it follows the sun from east to west (sky angle 0 = noon)
            tilt = -MathHelper.clamp(MathHelper.wrapDegrees((float) Math.toDegrees(a)) * MathHelper.RADIANS_PER_DEGREE, -1.2f, 1.2f);
        }
        tilt *= swing;
        float yaw = MathHelper.HALF_PI * swing;
        float k = be.linked() ? 0.9f : 0.3f;
        int lod = KrlCompat.lod(be.getPos());
        ms.push();
        ms.translate(0.5, 0, 0.5);
        ScDraw.draw(mesh, KrlModels.SOLAR_TEX, KrlModels.SOLAR_GLOW, ms, vcp, light, lod, ScDraw.only("base"), ScDraw.grey(k));
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
        final float t = tilt, e = ext;
        KrlMesh.Pose pose = (part, s) -> {
            switch (part) {
                case "base" -> {
                    return false;
                }
                case "wing_l", "wing_r" -> {
                    s.multiply(RotationAxis.POSITIVE_X.rotation(t));
                    s.scale(e, 1f, 1f);
                }
                case "roll_l", "roll_r" -> {
                    s.multiply(RotationAxis.POSITIVE_X.rotation(t));
                    s.translate((part.endsWith("l") ? -1 : 1) * WING_LEN * (e - 1f), 0, 0);
                }
                default -> {
                }
            }
            return true;
        };
        ScDraw.draw(mesh, KrlModels.SOLAR_TEX, KrlModels.SOLAR_GLOW, ms, vcp, light, lod, pose, ScDraw.grey(k));
        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ScSolarBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 192;
    }
}
