package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hooks the Gungnir ring ({@link GrsRing}) into the skies: the space sky draws its far half before Jupiter and its near
 * half last with real depth; Earth's night sky draws it round the little Jupiter there. Also the pass-by roar: every
 * time the spear goes by you near the ring, you hear it.
 */
public final class GrsSpace {
    private GrsSpace() {}

    /** Phase of the spear's lap, radians (for the goggles' feed). */
    public static float lapAngle(float time) {
        return (float) (time / Grs.LAP_TICKS * Math.PI * 2);
    }

    static boolean alive(float pt) {
        return ClientPlanets.elapsed(Body.JUPITER, pt) < Blast.DETONATE;
    }

    private static Vector3f jc, sunL;
    private static float jr;
    private static Quaternionf toL;

    /** Space sky, far half (called around Jupiter's own draw; {@code front} is ignored for the near half, see stationFront). */
    public static void pass(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, Vector3f c, float r, Vector3f sun,
                            float time, float pt, float alpha, boolean front) {
        if (!alive(pt)) return;
        jc = new Vector3f(c);
        jr = r;
        sunL = new Vector3f(sun);
        toL = new Quaternionf(toLocal);
        if (front) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        GrsRing.draw(buf, m, viewPos, toLocal, c, r, sun, time, alpha, false, true);
        GrsCine.backFx(buf, m, viewPos, time);
    }

    /** Space sky, after every layer: the near half of the ring with the normal depth range so it sorts properly. */
    public static void stationFront(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, Vector3f sun, float time, float pt,
                                    float alpha) {
        if (!alive(pt) || jc == null) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        GrsRing.draw(buf, m, viewPos, toL, jc, jr, sunL, time, alpha, true, true);
        jc = null;
    }

    /** Earth's sky: the ring round the small Jupiter there, both halves, in world orientation. */
    public static void overworld(VertexConsumerProvider.Immediate buf, Matrix4f world, Quaternionf skyRot, Vector3f cLocal, float r, Vector3f sunLocal,
                                 float time, float pt, float alpha, boolean front) {
        if (!alive(pt) || alpha < 0.02f) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        Vector3f c = skyRot.transform(new Vector3f(cLocal));
        Vector3f sun = skyRot.transform(new Vector3f(sunLocal));
        Vec3d far = Body.JUPITER.center.add(0, 0, 1e7);
        GrsRing.draw(buf, world, far, new Quaternionf(skyRot).rotateX(0.35f), c, r, sun, time, alpha, front, false);
    }

    // ------------------------------------------------------------------------------------------- the pass-by roar

    private static double lastRel = Double.NaN;

    /** Client tick: when the spear on its lap goes past you near the ring, play the zoom of it going by. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || !SpaceDims.isSpace(mc.world) || !alive(0) || GrsRing.spearAngle != null) {
            lastRel = Double.NaN;
            return;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d j = Body.JUPITER.center;
        double myAng = Math.atan2(eye.z - j.z, eye.x - j.x);
        Vec3d near = Grs.pathPoint(myAng);
        double dist = eye.distanceTo(near);
        if (dist > 2500) {
            lastRel = Double.NaN;
            return;
        }
        float time = mc.world.getTime();
        double rel = MathHelper.wrapDegrees(Math.toDegrees(GrsRing.lapAngle(time) - myAng));
        if (!Double.isNaN(lastRel) && lastRel < 0 && rel >= 0 && Math.abs(rel - lastRel) < 180) {
            float vol = (float) MathHelper.clamp(2.2 - dist / 700.0, 0.2, 2.2);
            float pitch = (float) MathHelper.clamp(1.2 - dist / 3000.0, 0.7, 1.2);
            mc.world.playSound(near.x, near.y, near.z, ModSounds.GRS_WHOOSH, SoundCategory.AMBIENT, vol, pitch, false);
        }
        lastRel = rel;
    }
}
