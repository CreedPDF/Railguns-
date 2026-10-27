package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.station.ScModuleBlock;
import com.creedpdf.orbitalrailgun.station.ScSatelliteBlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The strike satellite in its cradle. It turns slowly on the cradle, its panels feathering toward the light; the
 * emitter's lens rings and crystal brighten with charge and pulse when it is full, and flare for a few seconds after
 * it has fired.
 */
public class ScSatelliteRenderer implements BlockEntityRenderer<ScSatelliteBlockEntity> {
    public ScSatelliteRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(ScSatelliteBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.SAT;
        if (mesh == null || be.getWorld() == null) return;
        float time = be.getWorld().getTime() + pt;
        float since = be.getWorld().getTime() - be.fired() + pt;
        float c = be.charge();
        float emit = c >= 1f ? 0.75f + 0.25f * MathHelper.sin(time * 0.25f) : 0.12f + 0.6f * c;
        if (since < 80) emit = Math.max(emit, 1f - since / 80f);
        float body = be.linked() ? 0.9f : 0.3f;
        float facing = (float) Math.toRadians(-be.getCachedState().get(ScModuleBlock.FACING).asRotation());
        float spin = time * 0.006f;
        float feather = 0.25f * MathHelper.sin(time * 0.01f);
        int lod = KrlCompat.lod(be.getPos());
        ms.push();
        ms.translate(0.5, 0, 0.5);
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(facing));
        ScDraw.draw(mesh, KrlModels.SAT_TEX, KrlModels.SAT_GLOW, ms, vcp, light, lod, ScDraw.only("mount"), ScDraw.grey(body));
        ms.pop();
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(spin));
        String[] cur = {""};
        KrlMesh.Pose pose = (part, s) -> {
            cur[0] = part;
            if (part.equals("mount")) return false;
            if (part.startsWith("panel")) s.multiply(RotationAxis.POSITIVE_X.rotation(feather));
            return true;
        };
        final float fe = emit, fb = body;
        ScDraw.draw(mesh, KrlModels.SAT_TEX, KrlModels.SAT_GLOW, ms, vcp, light, lod, pose, (nx, ny, nz, o) -> {
            float k = cur[0].equals("emitter") ? fe : fb;
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        });
        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ScSatelliteBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 192;
    }
}
