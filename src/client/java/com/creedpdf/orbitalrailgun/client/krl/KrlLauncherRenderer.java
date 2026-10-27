package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.Krl;
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
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * The KRL-1 launcher. The platform faces the way it was placed; the turret on it slews round to the target when fire
 * control launches (and back to rest a while after), carrying the 60-degree rail. Just before the motor lights the
 * latch straps swing clear and the umbilical drops away; the beacon turns throughout. A loaded missile sits on the rail.
 */
public class KrlLauncherRenderer implements BlockEntityRenderer<KrlLauncherBlockEntity> {
    public KrlLauncherRenderer(BlockEntityRendererFactory.Context ctx) {}

    private static final float[] ZERO = {0, 0, 0};

    @Override
    public void render(KrlLauncherBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.LAUNCHER;
        if (mesh == null) return;
        Direction facing = be.getCachedState().contains(KrlLauncherBlock.FACING) ? be.getCachedState().get(KrlLauncherBlock.FACING) : Direction.SOUTH;
        long now = KrlClient.now();
        float rest = (float) Math.toRadians(-facing.asRotation());
        // time relative to liftoff: from the launch in progress, else from the last one
        KrlClient.CFlight f = KrlClient.padFlight(be.getPos());
        double tp;
        if (f != null && f.t(pt) < KrlFlight.TOTAL + 400) tp = f.t(pt);
        else if (be.launchedAt() >= 0) tp = now - be.launchedAt() + pt;
        else tp = 1e9;
        // the turret's heading: slew from rest to the target over SLEW ticks, hold, then return
        float yaw = rest;
        if (f != null) {
            float aim = (float) Math.atan2(f.path.h.x, f.path.h.z);
            float out = (float) smooth((tp + Krl.COUNTDOWN) / Krl.SLEW);
            float back = (float) smooth((tp - KrlFlight.TOTAL - 300) / 80);
            float d = MathHelper.wrapDegrees((float) Math.toDegrees(aim - rest));
            yaw = rest + (float) Math.toRadians(d) * out * (1 - back);
        }
        boolean burning = f != null && tp < 0;
        // a fresh missile on the rail after an earlier launch: that launch's animation is over
        boolean reloaded = be.loaded() && tp > 10;
        float open = reloaded ? 0 : (float) (smooth((tp + Krl.FLAME + 8) / 6) * (1 - smooth((tp - 60) / 30)));
        float umb = reloaded ? 0 : (float) (smooth((tp + Krl.FLAME + 14) / 10) * (1 - smooth((tp - 70) / 30)));
        float beacon = (float) ((now + pt) * (burning ? 14.0 : 3.0));
        float lamp = burning ? 0.7f + 0.3f * MathHelper.sin((now + pt) * 1.1f) : be.loaded() ? 0.8f : 0.45f;

        float[] pc = pivot(mesh, "clamps"), pu = pivot(mesh, "umbilical");
        Quaternionf qYaw = new Quaternionf().rotationY(yaw);
        Quaternionf qRest = new Quaternionf().rotationY(rest);
        Vec3d rd = KrlFlight.RAIL_D;
        KrlMesh.Pose pose = (part, st) -> {
            switch (part) {
                case "base" -> st.multiply(qRest);
                case "turret" -> st.multiply(qYaw);
                case "clamps" -> {
                    turn(st, pc, qYaw);
                    st.multiply(new Quaternionf().rotationAxis((float) Math.toRadians(-110 * open), (float) rd.x, (float) rd.y, (float) rd.z));
                }
                case "umbilical" -> {
                    turn(st, pu, qYaw);
                    st.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(60 * umb));
                }
                default -> {
                }
            }
            return true;
        };
        ms.push();
        ms.translate(0.5, 0, 0.5);
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.LAUNCHER_TEX)), light, pose, null);
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.LAUNCHER_GLOW)), light, pose, (nx, ny, nz, o) -> {
            o[0] = lamp;
            o[1] = lamp;
            o[2] = lamp;
            o[3] = 1;
        });
        // the missile on the rail, lugs down onto the rail
        if (be.loaded() && (f == null || tp < 0 || reloaded) && KrlModels.SPEAR != null) {
            Vec3d nz = KrlFlight.NOZZLE_LOCAL;
            ms.push();
            ms.multiply(qYaw);
            ms.translate(nz.x, nz.y, nz.z);
            ms.multiply(railBasis());
            int lv = f != null && !reloaded ? f.livery : be.livery();
            KrlModels.SPEAR.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.spearTex(lv))), light, null, null);
            KrlModels.SPEAR.render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.SPEAR_GLOW)), light, null, null);
            ms.pop();
        }
        ms.pop();
    }

    /** The missile model's frame on the rail: its +Y along the rail, its +X (the lugs) toward the rail. */
    private static Quaternionf railBasis() {
        Vec3d y = KrlFlight.RAIL_D, x = KrlFlight.RAIL_N.multiply(-1);
        Vec3d z = x.crossProduct(y);
        Matrix3f m = new Matrix3f((float) x.x, (float) x.y, (float) x.z, (float) y.x, (float) y.y, (float) y.z, (float) z.x, (float) z.y, (float) z.z);
        return new Quaternionf().setFromNormalized(m);
    }

    /** At a part's pivot: turn it with the turret about the launcher's vertical axis. */
    private static void turn(MatrixStack st, float[] p, Quaternionf q) {
        st.translate(-p[0], -p[1], -p[2]);
        st.multiply(q);
        st.translate(p[0], p[1], p[2]);
    }

    private static float[] pivot(KrlMesh mesh, String part) {
        KrlMesh.Part p = mesh.part(part);
        return p == null ? ZERO : p.pivot;
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
