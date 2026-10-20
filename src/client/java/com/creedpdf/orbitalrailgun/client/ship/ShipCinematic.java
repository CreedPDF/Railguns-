package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.client.ui.Term;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import com.creedpdf.orbitalrailgun.entity.ShipEntity.Phase;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The flight cinematic. While you ride the starship the camera leaves your head and becomes a
 * film camera: a low ground shot of ignition and lift-off, a chase up through the sky, a slow
 * orbit around the ship in deep space while the destination swells ahead (the sky is drawn from
 * a virtual position gliding from the launch world to the destination), then a ground shot of the
 * ship gliding in, flaring and settling on its tripod gear. Letterbox bars, fades on the world
 * changes and a terse terminal caption.
 */
public final class ShipCinematic {
    private ShipCinematic() {}

    private static Phase lastPhase;
    private static int lastPhaseTicks, gap = 999;

    @Nullable
    public static ShipEntity riding() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof ShipEntity s ? s : null;
    }

    public static boolean active() {
        ShipEntity s = riding();
        return (s != null && s.inFlight()) || blackout() > 0f;
    }

    public static void tick(MinecraftClient mc) {
        ShipEntity s = riding();
        if (s != null && s.inFlight()) {
            lastPhase = s.getPhase();
            lastPhaseTicks = s.getPhaseTicks();
            gap = 0;
        } else if (gap < 999) {
            gap++;
        }
    }

    /** Full black between worlds (the ship is handed over to the next dimension). */
    private static float blackout() {
        if (riding() != null || lastPhase == null) return 0f;
        boolean edge = (lastPhase == Phase.ASCENT && lastPhaseTicks > ShipEntity.ASCENT_TICKS - 30)
                || (lastPhase == Phase.CRUISE && lastPhaseTicks > ShipEntity.CRUISE_TICKS - 30);
        if (!edge) return 0f;
        return gap < 60 ? 1f : gap < 75 ? 1f - (gap - 60) / 15f : 0f;
    }

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    private static float smoother(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * f * (f * (f * 6 - 15) + 10);
    }

    // ---- cruise geometry ---------------------------------------------------------------------

    public static Vec3d cruiseEnd(ShipEntity s) {
        Body d = s.getDestination();
        Vec3d start = s.anchor();
        Vec3d out = start.subtract(d.center).normalize();
        return d.center.add(out.multiply(d.radius + d.atmosphere + 900));
    }

    /** Space-dimension point the sky is drawn from during the cruise. */
    public static Vec3d virtualPos(ShipEntity s, float pt) {
        float f = smoother((s.getPhaseTicks() + pt) / ShipEntity.CRUISE_TICKS);
        Vec3d a = s.anchor(), b = cruiseEnd(s);
        return a.add(b.subtract(a).multiply(f));
    }

    /** Unit vector the nose points along. */
    public static Vec3d noseDirection(ShipEntity s, float pt) {
        if (s.getPhase() == Phase.CRUISE) {
            Vec3d d = cruiseEnd(s).subtract(s.anchor());
            return d.lengthSquared() > 1e-6 ? d.normalize() : ShipEntity.heading(s.getFacing());
        }
        Vec3d h = ShipEntity.heading(s.getFacing());
        double p = Math.toRadians(s.pitchDeg(s.getPhaseTicks() + pt));
        return new Vec3d(h.x * Math.cos(p), Math.sin(p), h.z * Math.cos(p));
    }

    /** Model point (px) of the ship in world space, posed exactly like the model. */
    public static Vec3d shipPoint(ShipEntity s, double px, double py, double pz, float pt) {
        Vec3d n = noseDirection(s, pt);
        double horiz = Math.sqrt(n.x * n.x + n.z * n.z);
        double pitch = Math.atan2(n.y, horiz);
        double yaw = Math.atan2(-n.x, -n.z);
        double lx = px / 16.0, ly = (py - 48) / 16.0, lz = -pz / 16.0;
        double y1 = ly * Math.cos(pitch) - lz * Math.sin(pitch);
        double z1 = ly * Math.sin(pitch) + lz * Math.cos(pitch);
        double x2 = lx * Math.cos(yaw) + z1 * Math.sin(yaw);
        double z2 = -lx * Math.sin(yaw) + z1 * Math.cos(yaw);
        return s.getLerpedPos(pt).add(x2, 3.0 + y1, z2);
    }

    /** Sky viewpoint in the space dimension: virtual while cruising. */
    public static Vec3d spaceView(Vec3d cam) {
        ShipEntity s = riding();
        if (s == null || s.getPhase() != Phase.CRUISE) return cam;
        float pt = MinecraftClient.getInstance().getTickDelta();
        return virtualPos(s, pt).add(cam.subtract(s.getLerpedPos(pt)));
    }

    // ---- camera --------------------------------------------------------------------------------

    /** Camera pose: {x, y, z, yaw, pitch}, or null when not filming. */
    @Nullable
    public static double[] camera(float pt) {
        ShipEntity s = riding();
        if (s == null || !s.inFlight()) return null;
        float t = s.getPhaseTicks() + pt;
        Vec3d P = s.getLerpedPos(pt).add(0, 3, 0);
        Vec3d H = ShipEntity.heading(s.getFacing());
        Vec3d R = new Vec3d(-H.z, 0, H.x);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d cam, look;
        switch (s.getPhase()) {
            case IGNITION -> {
                Vec3d a = s.anchor().add(0, 3, 0);
                cam = a.add(H.multiply(24 - 5 * t / ShipEntity.IGNITION_TICKS)).add(R.multiply(15)).add(0, -1.2, 0);
                look = P.add(0, -0.5, 0);
            }
            case ASCENT -> {
                Vec3d a = s.anchor().add(0, 3, 0);
                if (t < 75) {
                    cam = a.add(H.multiply(20)).add(R.multiply(15)).add(0, -1.2, 0);
                    look = P;
                } else if (t < 155) {
                    cam = P.subtract(H.multiply(30)).add(R.multiply(8)).add(0, -9, 0);
                    look = P.add(H.multiply(6));
                } else {
                    cam = P.add(R.multiply(26)).add(0, 12, 0).subtract(H.multiply(12));
                    look = P.add(H.multiply(4));
                }
            }
            case CRUISE -> {
                Vec3d N = noseDirection(s, pt);
                Vec3d side = N.crossProduct(up);
                side = side.lengthSquared() < 1e-6 ? R : side.normalize();
                Vec3d lift = side.crossProduct(N).normalize();
                if (t < 300) {
                    float a = 0.4f + t * 0.014f;
                    double r = 30 - 5 * MathHelper.sin(t * 0.01f);
                    cam = P.add(side.multiply(Math.cos(a) * r)).add(N.multiply(Math.sin(a) * r)).add(lift.multiply(7 + 4 * MathHelper.sin(t * 0.013f)));
                    look = P;
                } else {
                    // behind the ship, looking where it is going: the destination fills the screen
                    float k = smooth((t - 300) / 60f);
                    cam = P.subtract(N.multiply(26 + 6 * k)).add(lift.multiply(5)).add(side.multiply(3));
                    look = P.add(N.multiply(40));
                }
            }
            case DESCENT -> {
                Vec3d site = new Vec3d(s.anchor().x, s.groundY(), s.anchor().z);
                float e = t / ShipEntity.DESCENT_TICKS;
                if (e < 0.45f) {
                    cam = site.add(R.multiply(34)).add(H.multiply(26)).add(0, 3, 0);
                    look = P;
                } else if (e < 0.82f) {
                    cam = P.add(R.multiply(22)).add(0, 1.5, 0).subtract(H.multiply(6));
                    look = P.add(0, -1, 0);
                } else {
                    cam = site.add(R.multiply(17)).add(H.multiply(12)).add(0, 1.4, 0);
                    look = P.add(0, -0.5, 0);
                }
            }
            case TOUCHDOWN -> {
                Vec3d site = new Vec3d(s.anchor().x, s.groundY(), s.anchor().z);
                cam = site.add(R.multiply(17 - 3 * t / ShipEntity.TOUCHDOWN_TICKS)).add(H.multiply(12)).add(0, 1.4, 0);
                look = P.add(0, -0.8, 0);
            }
            default -> {
                return null;
            }
        }
        // shake from the engines near the ground
        float th = s.thrust(t);
        boolean low = s.getPhase() == Phase.IGNITION || (s.getPhase() == Phase.ASCENT && t < 110) || s.getPhase() == Phase.TOUCHDOWN;
        if (low && th > 0.05f) {
            float time = s.age + pt;
            double k = 0.12 * th;
            cam = cam.add(k * MathHelper.sin(time * 3.7f), k * MathHelper.sin(time * 4.3f + 1f), k * MathHelper.sin(time * 3.1f + 2f));
        }
        Vec3d d = look.subtract(cam);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{cam.x, cam.y, cam.z, yaw, pitch};
    }

    // ---- overlay -------------------------------------------------------------------------------

    public static void hud(DrawContext dc, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = dc.getScaledWindowWidth(), h = dc.getScaledWindowHeight();
        float black = blackout();
        ShipEntity s = riding();
        if (s == null || !s.inFlight()) {
            if (black > 0f) dc.fill(0, 0, w, h, ((int) (255 * black) << 24));
            return;
        }
        float t = s.getPhaseTicks() + pt;
        // letterbox
        int bar = (int) (h * 0.11f);
        dc.fill(0, 0, w, bar, 0xFF000000);
        dc.fill(0, h - bar, w, h, 0xFF000000);
        // fades around the dimension changes
        float fade = 0f;
        switch (s.getPhase()) {
            case ASCENT -> fade = smooth((t - (ShipEntity.ASCENT_TICKS - 22)) / 20f);
            case CRUISE -> fade = Math.max(1f - smooth(t / 25f), smooth((t - (ShipEntity.CRUISE_TICKS - 22)) / 20f));
            case DESCENT -> fade = 1f - smooth(t / 25f);
            default -> {}
        }
        if (fade > 0.003f) dc.fill(0, 0, w, h, ((int) (255 * fade) << 24));

        // caption in the lower bar
        String dest = s.getDestination().displayName();
        String cap = switch (s.getPhase()) {
            case IGNITION -> {
                int count = 4 - (int) (t / 16);
                yield count > 0 ? "IGNITION SEQUENCE // T-" + count : "LIFTOFF";
            }
            case ASCENT -> String.format(Locale.ROOT, "ASCENT // ALT %d M  //  VEL %d M/S", (int) s.getY(), (int) (Math.max(0, t) * 0.0248 * 20));
            case CRUISE -> String.format(Locale.ROOT, "EN ROUTE // %s  //  ETA %ds", dest, Math.max(0, (int) ((ShipEntity.CRUISE_TICKS - t) / 20)));
            case DESCENT -> String.format(Locale.ROOT, "ENTRY // %s  //  ALT %d M", dest, (int) Math.max(0, s.getY() - s.groundY()));
            case TOUCHDOWN -> "TOUCHDOWN // " + dest;
            default -> "";
        };
        float sc = 0.85f;
        float cw = Term.width(mc.textRenderer, cap, sc);
        int ty = h - bar + (bar - 7) / 2;
        Term.text(dc, mc.textRenderer, cap, (w - cw) / 2f, ty, 0xFFCFCCC4, sc);
        dc.fill((int) ((w - cw) / 2f) - 10, ty + 2, (int) ((w - cw) / 2f) - 6, ty + 6, ((int) (t / 8)) % 2 == 0 ? 0xFFE8382E : 0xFF4A1210);
        String tag = "ARES STARSHIP";
        Term.text(dc, mc.textRenderer, tag, 8, (bar - 6) / 2f, 0xFFE8382E, 0.7f);
    }
}
