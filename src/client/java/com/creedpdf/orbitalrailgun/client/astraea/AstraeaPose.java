package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import net.minecraft.client.model.ModelPart;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;

/**
 * The bearer's body during ASTRAEA (BipedEntityModelMixin):
 * <ul>
 *   <li><b>Ascension</b>: both arms straight up, the lance held overhead and laid horizontal across them, the head
 *   thrown back toward the rift, the chest opened up to the light; trembling as it pours in.</li>
 *   <li><b>The throw</b>: drawn back overhead like a javelin, then hurled straight up; the head follows it into the
 *   sky and stays there; at the end the arm goes up to catch it.</li>
 * </ul>
 */
public final class AstraeaPose {
    private AstraeaPose() {}

    private static float smooth(double x) {
        float k = (float) MathHelper.clamp(x, 0, 1);
        return k * k * (3 - 2 * k);
    }

    public static void apply(PlayerEntity p, float pt, float age, ModelPart head, ModelPart hat, ModelPart body, ModelPart rightArm, ModelPart leftArm) {
        boolean right = p.getMainArm() == Arm.RIGHT;
        ModelPart arm = right ? rightArm : leftArm, off = right ? leftArm : rightArm;
        float m = right ? 1f : -1f;
        AstraeaState.Ritual r = AstraeaState.RITUALS.get(p.getId());
        if (r != null) {
            double t = AstraeaState.ritualT(p.getId(), pt);
            if (t >= 0 && t < Timeline.R_END + 10) {
                float k = smooth((t - 4) / 24.0) * (1f - smooth((t - (Timeline.R_END - 16)) / 24.0));
                if (k <= 0.001f) return;
                float pour = t > Timeline.R_DESCEND && t < Timeline.R_IMPACT ? 1f : 0f;
                float tremble = pour * 0.035f * MathHelper.sin(age * 2.7f);
                float arch = t > Timeline.R_ABSORB && t < Timeline.R_IMPACT + 10 ? -0.22f : -0.08f;
                arm.pitch = MathHelper.lerp(k, arm.pitch, -2.95f + tremble);
                arm.yaw = MathHelper.lerp(k, arm.yaw, 1.57f * m);
                arm.roll = MathHelper.lerp(k, arm.roll, 0.08f * m);
                off.pitch = MathHelper.lerp(k, off.pitch, -2.95f - tremble);
                off.yaw = MathHelper.lerp(k, off.yaw, -0.25f * m);
                off.roll = MathHelper.lerp(k, off.roll, -0.18f * m);
                body.pitch = MathHelper.lerp(k, body.pitch, arch);
                head.pitch = MathHelper.lerp(k, head.pitch, -0.75f);
                head.yaw = MathHelper.lerp(k, head.yaw, 0f);
                hat.copyTransform(head);
                return;
            }
        }
        AstraeaState.Strike s = AstraeaState.strike;
        if (s == null || s.player() != p.getId()) return;
        double t = AstraeaState.strikeT(pt);
        if (t < 0) return;
        if (t < Timeline.S_RELEASE + 14) {
            // wind-up then the throw, straight up
            float wind = smooth(t / Timeline.S_RELEASE);
            float snap = smooth((t - Timeline.S_RELEASE + 2) / 4.0);
            float k = 1f - smooth((t - Timeline.S_RELEASE - 6) / 8.0);
            float pitch = MathHelper.lerp(snap, -2.35f - 0.45f * wind, -3.1f);
            arm.pitch = MathHelper.lerp(k, arm.pitch, pitch);
            arm.yaw = MathHelper.lerp(k, arm.yaw, 0.15f * m * (1 - snap));
            off.pitch = MathHelper.lerp(k, off.pitch, -1.2f - 0.9f * snap);
            off.yaw = MathHelper.lerp(k, off.yaw, 0.35f * m);
            body.pitch = MathHelper.lerp(k, body.pitch, -0.18f * wind * (1 - snap) + 0.06f * snap);
        }
        // eyes on the sky the whole time it is up there
        float look = smooth((t - Timeline.S_RELEASE) / 10.0) * (1f - smooth((t - Timeline.S_CATCH) / 14.0));
        head.pitch = MathHelper.lerp(look, head.pitch, -0.95f);
        // the catch
        float catchK = smooth((t - (Timeline.S_CATCH - 14)) / 10.0) * (1f - smooth((t - Timeline.S_CATCH - 4) / 12.0));
        if (catchK > 0.001f) {
            arm.pitch = MathHelper.lerp(catchK, arm.pitch, -2.9f);
            arm.yaw = MathHelper.lerp(catchK, arm.yaw, 0.1f * m);
        }
        hat.copyTransform(head);
    }
}
