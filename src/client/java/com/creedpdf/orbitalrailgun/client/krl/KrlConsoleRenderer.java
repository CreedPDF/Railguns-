package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlConsoleBlock;
import com.creedpdf.orbitalrailgun.krl.KrlConsoleBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The fire-control console: the desk, the launch button's flip cover (raised while fire control is open or a motor is
 * burning) and the annunciator strip, which steadies when idle and flashes through a countdown.
 */
public class KrlConsoleRenderer implements BlockEntityRenderer<KrlConsoleBlockEntity> {
    public KrlConsoleRenderer(BlockEntityRendererFactory.Context ctx) {}

    /** Per console: {cover 0..1, last time}. */
    private final java.util.Map<BlockPos, float[]> covers = new java.util.HashMap<>();

    @Override
    public void render(KrlConsoleBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.CONSOLE;
        if (mesh == null) return;
        Direction facing = be.getCachedState().contains(KrlConsoleBlock.FACING) ? be.getCachedState().get(KrlConsoleBlock.FACING) : Direction.SOUTH;
        long now = KrlClient.now();
        boolean counting = false;
        for (BlockPos lp : be.links()) {
            KrlClient.CFlight f = KrlClient.padFlight(lp);
            if (f != null && f.t(pt) < 0) counting = true;
        }
        boolean open = counting || KrlScreen.openFor(be.getPos());
        float target = open ? 1 : 0;
        float time = now + pt;
        // the cover swings at its own pace, whatever the frame rate
        float[] cs = covers.computeIfAbsent(be.getPos().toImmutable(), k -> new float[]{target, time});
        float dt = MathHelper.clamp(time - cs[1], 0, 2);
        cs[1] = time;
        cs[0] = MathHelper.clamp(cs[0] + Math.signum(target - cs[0]) * dt * 0.12f, 0, 1);
        if (covers.size() > 64) covers.clear();
        float c0 = cs[0];
        float cover = c0 * c0 * (3 - 2 * c0);
        float ann = counting ? ((int) (time / 4) % 2 == 0 ? 1f : 0.35f) : 0.8f + 0.05f * MathHelper.sin(time * 0.05f);

        ms.push();
        ms.translate(0.5, 0, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        KrlMesh.Pose pose = (part, st) -> {
            if (part.equals("cover")) st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-105 * cover));
            return true;
        };
        int lod = KrlCompat.lod(be.getPos());
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.CONSOLE_TEX)), light, pose, null);
        if (KrlCompat.shadowPass()) {
            ms.pop();
            return;
        }
        KrlMesh glow = mesh.glow(lod);
        glow.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.CONSOLE_GLOW)), light, (part, st) -> {
            if (part.equals("cover")) st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-105 * cover));
            return true;
        }, null);
        // the annunciators again, modulated
        glow.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.CONSOLE_GLOW)), light, (part, st) -> part.equals("annunciators"), (nx, ny, nz, o) -> {
            o[0] = ann * 0.6f;
            o[1] = ann * 0.6f;
            o[2] = ann * 0.6f;
            o[3] = 1;
        });
        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(KrlConsoleBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }
}
