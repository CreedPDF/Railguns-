package com.creedpdf.orbitalrailgun.client.orbital;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Overrides the game camera (see CameraMixin / GameRendererMixin) while the railgun's optical
 * camera or a strike cinematic is running. Screens write the pose each frame.
 */
public final class CinematicCamera {
    private CinematicCamera() {}

    private static boolean active;
    private static Vec3d pos = Vec3d.ZERO;
    private static float yaw, pitch, fov = 70f;
    private static boolean hudWasHidden;

    public static boolean active() {
        return active;
    }

    public static void begin() {
        if (active) return;
        active = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        hudWasHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
    }

    public static void end() {
        if (!active) return;
        active = false;
        MinecraftClient.getInstance().options.hudHidden = hudWasHidden;
    }

    public static void set(Vec3d p, float yawDeg, float pitchDeg, float fovDeg) {
        pos = p;
        yaw = yawDeg;
        pitch = pitchDeg;
        fov = fovDeg;
    }

    /** Points the camera from {@code p} at {@code target}. */
    public static void lookAt(Vec3d p, Vec3d target, float fovDeg) {
        Vec3d d = target.subtract(p);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float y = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pi = (float) Math.toDegrees(-Math.atan2(d.y, h));
        set(p, y, pi, fovDeg);
    }

    public static Vec3d pos() { return pos; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }
    public static float fov() { return fov; }
}
