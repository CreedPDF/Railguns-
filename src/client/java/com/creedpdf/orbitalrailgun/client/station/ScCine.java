package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.station.ScStrike;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The strike cutscene, for the player who ordered it:
 * <ol>
 *   <li>ORBIT - a slow arc round the satellite as it turns onto the target and its emitter charges;</li>
 *   <li>FIRE - beside the satellite looking straight down as it fires and the beam falls away toward the ground;</li>
 *   <li>GROUND - from the ground a safe way off as the beam hits: the flash, the shockwaves, the column boring in;</li>
 *   <li>back to the player as the beam cuts out.</li>
 * </ol>
 * Letterboxed, hand and HUD hidden, pure maths on the strike's age. Sneak to leave early.
 */
public final class ScCine {
    private ScCine() {}

    public static final int ORBIT_END = 44, GROUND_FROM = 60, END = 150;
    private static int strikeId = -1;
    private static boolean skipped, wasSneak;

    static void start(int id) {
        strikeId = id;
        skipped = false;
    }

    @Nullable
    private static ScFx.Strike strike() {
        if (strikeId < 0 || skipped) return null;
        for (ScFx.Strike s : ScFx.STRIKES) if (s.id == strikeId) return s;
        return null;
    }

    public static boolean active() {
        ScFx.Strike s = strike();
        return s != null && s.age(0) < END;
    }

    static void tick(MinecraftClient mc) {
        boolean sn = mc.options.sneakKey.isPressed();
        if (sn && !wasSneak && active()) skipped = true;
        wasSneak = sn;
        if (!active()) strikeId = skipped ? strikeId : -1;
    }

    private static double sm(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static double ground(double x, double z, double fallback) {
        var w = MinecraftClient.getInstance().world;
        if (w == null) return fallback;
        int ix = MathHelper.floor(x), iz = MathHelper.floor(z);
        if (!w.isChunkLoaded(ix >> 4, iz >> 4)) return fallback;
        return w.getTopY(Heightmap.Type.MOTION_BLOCKING, ix, iz);
    }

    /** {x, y, z, yaw, pitch} or null. */
    public static double[] camera(float pt) {
        ScFx.Strike s = strike();
        if (s == null) return null;
        double a = s.age(pt);
        if (a >= END) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d t = s.target, sat = s.sat();
        Vec3d eye, look;
        if (a < ORBIT_END) {
            double k = a / ORBIT_END;
            double ang = s.id * 1.7 + k * 1.3;
            double r = 15 - 5 * sm(k);
            eye = sat.add(Math.cos(ang) * r, 3.5 - 4 * sm(k), Math.sin(ang) * r);
            look = sat.add(0, -1.5, 0);
        } else if (a < GROUND_FROM) {
            double k = (a - ORBIT_END) / (GROUND_FROM - ORBIT_END);
            double ang = s.id * 1.7 + 1.3;
            eye = sat.add(Math.cos(ang) * 7, 2 + 4 * k, Math.sin(ang) * 7);
            // tip from the satellite down to the ground far below
            Vec3d down = t.add(0, (sat.y - t.y) * (1 - sm(k * 1.4)) * 0.6, 0);
            look = sat.add(0, -3, 0).lerp(down, sm(k * 1.6));
        } else {
            // a ground spot between the player and the target, 48 blocks out, a little raised
            Vec3d from = mc.player != null ? mc.player.getPos() : t.add(48, 0, 0);
            Vec3d dir = new Vec3d(from.x - t.x, 0, from.z - t.z);
            if (dir.lengthSquared() < 1) dir = new Vec3d(1, 0, 0.3);
            dir = dir.normalize();
            double k = (a - GROUND_FROM) / (END - GROUND_FROM);
            double dist = 52 - 10 * sm(k);
            double ex = t.x + dir.x * dist, ez = t.z + dir.z * dist;
            double ey = Math.max(ground(ex, ez, t.y), t.y) + 9 + 5 * (1 - sm(k * 2));
            eye = new Vec3d(ex, ey, ez);
            look = t.add(0, a < ScStrike.IMPACT ? 30 : 9, 0);
        }
        Vec3d d = look.subtract(eye);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{eye.x, eye.y, eye.z, yaw, pitch};
    }

    /** Letterbox and caption. */
    public static void hud(DrawContext ctx, float pt) {
        ScFx.Strike s = strike();
        if (s == null) return;
        float a = s.age(pt);
        if (a >= END) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float in = Math.min(1f, a / 8f) * Math.min(1f, (END - a) / 8f);
        int bar = (int) (h * 0.11f * in);
        ctx.fill(0, 0, w, bar, 0xFF000000);
        ctx.fill(0, h - bar, w, h, 0xFF000000);
        String line;
        int col;
        if (a < ScStrike.FIRE) {
            line = String.format(Locale.ROOT, "STRIKE SATELLITE  //  EMITTER CHARGE %d%%", (int) Math.min(100, a / ScStrike.FIRE * 100));
            col = KrlText.AMBER;
        } else if (a < ScStrike.IMPACT) {
            line = "FIRING  //  BEAM DESCENDING";
            col = KrlText.RED;
        } else if (a < ScStrike.BEAM_END) {
            line = "IMPACT CONFIRMED";
            col = KrlText.RED;
        } else {
            line = "BEAM TERMINATED";
            col = KrlText.GREY;
        }
        if (bar > 6) {
            ctx.drawText(mc.textRenderer, KrlText.t(line, col), 12, h - bar + (bar - 8) / 2, 0xFFFFFFFF, false);
            String coords = String.format(Locale.ROOT, "TGT %d %d %d    SNEAK TO SKIP", (int) s.target.x, (int) s.target.y, (int) s.target.z);
            ctx.drawText(mc.textRenderer, KrlText.t(coords, KrlText.GREY), w - 12 - mc.textRenderer.getWidth(KrlText.t(coords)), (bar - 8) / 2, 0xFFFFFFFF, false);
        }
    }
}
