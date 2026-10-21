package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.client.ui.Term;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import com.creedpdf.orbitalrailgun.entity.ShipEntity.Phase;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * The flight film. While you ride the starship the camera becomes a film camera with a shot list
 * per flight phase:
 * <ul>
 *   <li>ignition and VTOL lift-off from a low ground camera, then a close tracking shot as the
 *       nacelles swing aft;</li>
 *   <li>a <b>fly-by</b>: a fixed camera beside the climb-out path, the ship screaming past it;</li>
 *   <li>a chase up through the sky, then the jump: the drive charges, the stars stretch into
 *       streaks, the ship pierces away with a zip and a flash; cut;</li>
 *   <li>deep space: dropping out of the jump, a slow orbit, a front shot looking back, a low side
 *       pass, then the next jump toward the destination (the sky is drawn from a virtual position
 *       gliding there);</li>
 *   <li>arrival: a chase down into the sky, a second fly-by, a wide ground shot as it converts to
 *       VTOL and stops in a hover; an orbiting shot of the landing scan and levelling laser; a low
 *       shot of the gear touching down.</li>
 * </ul>
 * Letterbox bars, hyperspace star streaks, flashes and fades are drawn over the HUD.
 */
public final class ShipCinematic {
    private ShipCinematic() {}

    private static Phase lastPhase;
    private static int lastPhaseTicks, gap = 999;
    private static int whooshTick = -1;
    private static Phase whooshPhase;

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
            if (s.getPhase() != whooshPhase) { whooshPhase = s.getPhase(); whooshTick = -1; }
            lastPhase = s.getPhase();
            lastPhaseTicks = s.getPhaseTicks();
            gap = 0;
            // fly-bys: the roar at the camera as the ship passes it
            int t = s.getPhaseTicks();
            int at = switch (s.getPhase()) {
                case ASCENT -> 134;
                case DESCENT -> (int) (s.duration() * 0.47f);
                default -> -100;
            };
            if (t == at && whooshTick != at) {
                whooshTick = at;
                mc.getSoundManager().play(PositionedSoundInstance.master(ModSounds.SHIP_WHOOSH, 1f, 1.4f));
            }
        } else if (gap < 999) {
            gap++;
        }
    }

    /** Full black between worlds (the ship is handed to the next dimension). */
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

    /** Space-dimension point the sky is drawn from during the cruise. */
    public static Vec3d virtualPos(ShipEntity s, float pt) {
        float f = smoother((s.getPhaseTicks() + pt - ShipEntity.CR_EXIT) / (ShipEntity.CR_JUMP - ShipEntity.CR_EXIT));
        Vec3d a = s.anchor(), b = s.cruiseEnd();
        return a.add(b.subtract(a).multiply(f));
    }

    public static Vec3d spaceView(Vec3d cam) {
        ShipEntity s = riding();
        if (s == null || s.getPhase() != Phase.CRUISE) return cam;
        float pt = MinecraftClient.getInstance().getTickDelta();
        return virtualPos(s, pt).add(cam.subtract(s.anchor()));
    }

    // ---- camera --------------------------------------------------------------------------------

    /** Camera pose {x, y, z, yaw, pitch}, or null when not filming. */
    @Nullable
    public static double[] camera(float pt) {
        ShipEntity s = riding();
        if (s == null || !s.inFlight()) return null;
        float t = s.getPhaseTicks() + pt;
        Vec3d P = s.getLerpedPos(pt).add(0, 3, 0);
        Vec3d H = ShipEntity.heading(s.getFacing());
        Vec3d R = new Vec3d(-H.z, 0, H.x);
        Vec3d UP = new Vec3d(0, 1, 0);
        Vec3d N = s.nose(t);
        Vec3d A = s.anchor().add(0, 3, 0);
        Vec3d cam, look;
        float shake = 0f;
        switch (s.getPhase()) {
            case IGNITION -> {
                cam = A.add(H.multiply(22 - 6 * t / ShipEntity.IGNITION_TICKS)).add(R.multiply(14)).add(0, -1.6, 0);
                look = P.add(0, -0.5, 0);
                shake = s.thrust(t);
            }
            case ASCENT -> {
                if (t < 55) {
                    cam = A.add(H.multiply(18)).add(R.multiply(14)).add(0, -1.8, 0);
                    look = P;
                    shake = s.thrust(t);
                } else if (t < 98) {
                    cam = P.subtract(N.multiply(14)).add(R.multiply(7)).add(0, 1.5, 0);
                    look = P.add(N.multiply(4));
                    shake = 0.4f;
                } else if (t < 152) {
                    Vec3d F = s.pathPos(140).add(0, 3, 0);
                    Vec3d nF = s.nose(140);
                    Vec3d side = new Vec3d(-nF.z, 0, nF.x).normalize();
                    cam = F.add(side.multiply(5.5)).add(0, -1.5, 0).subtract(nF.multiply(2));
                    look = P;
                } else if (t < 200) {
                    cam = P.subtract(N.multiply(24)).add(0, 5, 0).add(R.multiply(3));
                    look = P.add(N.multiply(30));
                } else {
                    Vec3d J = s.pathPos(200).add(0, 3, 0);
                    Vec3d nJ = s.nose(200);
                    cam = J.subtract(nJ.multiply(20)).add(R.multiply(6)).add(0, 3, 0);
                    look = t < ShipEntity.ASC_JUMP ? P : J.add(nJ.multiply(60));
                    shake = s.hyperCharge(t) * 0.8f;
                }
            }
            case CRUISE -> {
                Vec3d side = N.crossProduct(UP);
                side = side.lengthSquared() < 1e-6 ? R : side.normalize();
                Vec3d lift = side.crossProduct(N).normalize();
                if (t < 40) {
                    cam = P.subtract(N.multiply(22)).add(lift.multiply(5));
                    look = P.add(N.multiply(30));
                } else if (t < 150) {
                    float a = 0.4f + (t - 40) * 0.02f;
                    double r = 30 - 5 * MathHelper.sin(t * 0.012f);
                    cam = P.add(side.multiply(Math.cos(a) * r)).add(N.multiply(Math.sin(a) * r)).add(lift.multiply(7 + 4 * MathHelper.sin(t * 0.015f)));
                    look = P;
                } else if (t < 200) {
                    cam = P.add(N.multiply(26)).add(lift.multiply(4)).add(side.multiply(6));
                    look = P;
                } else if (t < ShipEntity.CR_CHARGE) {
                    float k = (t - 200) / 40f;
                    cam = P.add(side.multiply(16)).subtract(lift.multiply(3)).add(N.multiply(12 - 24 * k));
                    look = P;
                } else {
                    Vec3d J = s.anchor().add(0, 3, 0);
                    cam = J.subtract(N.multiply(22)).add(lift.multiply(4)).add(side.multiply(4));
                    look = t < ShipEntity.CR_JUMP ? P : J.add(N.multiply(80));
                    shake = s.hyperCharge(t) * 0.6f;
                }
            }
            case DESCENT -> {
                float sN = t / s.duration();
                Vec3d site = s.site();
                if (sN < 0.25f) {
                    cam = P.subtract(N.multiply(24)).add(0, 6, 0);
                    look = P.add(N.multiply(20));
                } else if (sN < 0.62f) {
                    float sf = 0.47f;
                    Vec3d F = s.pathPos(s.duration() * sf).add(0, 3, 0);
                    Vec3d v = s.velocity(s.duration() * sf);
                    Vec3d side = new Vec3d(-v.z, 0, v.x);
                    side = side.lengthSquared() < 1e-6 ? R : side.normalize();
                    cam = F.add(side.multiply(7)).add(0, -2.5, 0);
                    look = P;
                } else {
                    cam = site.add(R.multiply(30)).add(H.multiply(24)).add(0, 3, 0);
                    look = P;
                }
            }
            case SURVEY -> {
                Vec3d site = s.site();
                float a = 0.6f + t * 0.012f;
                cam = site.add(Math.cos(a) * 28, 7, Math.sin(a) * 28);
                look = site.add(0, ShipEntity.HOVER * 0.45, 0);
            }
            case LANDING -> {
                Vec3d site = s.site();
                cam = site.add(R.multiply(16)).add(H.multiply(12)).add(0, 1.3, 0);
                look = P.add(0, -0.5, 0);
                shake = 0.3f * smooth((t - 60) / 30f);
            }
            case TOUCHDOWN -> {
                Vec3d site = s.site();
                cam = site.add(R.multiply(16 - 3 * t / ShipEntity.TOUCHDOWN_TICKS)).add(H.multiply(12)).add(0, 1.3, 0);
                look = P.add(0, -0.8, 0);
                shake = 0.5f * (1f - smooth(t / 15f));
            }
            default -> {
                return null;
            }
        }
        if (shake > 0.02f) {
            float time = s.age + pt;
            double k = 0.13 * shake;
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

        // hyperspace: stars stretch into streaks as the drive charges, then everything goes white
        float streak = 0f, white = 0f, fade = 0f;
        switch (s.getPhase()) {
            case ASCENT -> {
                streak = s.hyperCharge(t) * 0.6f + (t >= ShipEntity.ASC_JUMP ? 0.4f + 0.6f * smooth((t - ShipEntity.ASC_JUMP) / 6f) : 0f);
                white = smooth((t - (ShipEntity.ASC_JUMP + 3)) / 7f);
                fade = smooth((t - (ShipEntity.ASCENT_TICKS - 8)) / 8f);
            }
            case CRUISE -> {
                if (t < ShipEntity.CR_EXIT) {
                    streak = 1f - smooth(t / ShipEntity.CR_EXIT);
                    white = 1f - smooth(t / 10f);
                } else {
                    streak = s.hyperCharge(t) * 0.6f + (t >= ShipEntity.CR_JUMP ? 0.4f + 0.6f * smooth((t - ShipEntity.CR_JUMP) / 6f) : 0f);
                    white = smooth((t - (ShipEntity.CR_JUMP + 4)) / 8f);
                    fade = smooth((t - (ShipEntity.CRUISE_TICKS - 14)) / 12f);
                }
            }
            case DESCENT -> fade = 1f - smooth(t / 22f);
            default -> {}
        }
        if (streak > 0.01f) streaks(dc, w, h, s.age + pt, streak);
        if (white > 0.003f) dc.fill(0, 0, w, h, ((int) (255 * white) << 24) | 0xFFF4EC);
        if (fade > 0.003f) dc.fill(0, 0, w, h, ((int) (255 * fade) << 24));

        // letterbox
        int bar = (int) (h * 0.11f);
        dc.fill(0, 0, w, bar, 0xFF000000);
        dc.fill(0, h - bar, w, h, 0xFF000000);

        String dest = s.getDestination().displayName();
        String cap = switch (s.getPhase()) {
            case IGNITION -> {
                int count = 3 - (int) (t / 20);
                yield count > 0 ? "VTOL IGNITION // T-" + count : "LIFTOFF";
            }
            case ASCENT -> t >= ShipEntity.ASC_CHARGE ? (t >= ShipEntity.ASC_JUMP ? "JUMP" : String.format(Locale.ROOT, "JUMP DRIVE // %d%%", (int) (s.hyperCharge(t) * 100)))
                    : String.format(Locale.ROOT, "ASCENT // ALT %d M  //  %d M/S", (int) s.getY(), (int) (s.velocity(t).length() * 20));
            case CRUISE -> t >= ShipEntity.CR_CHARGE ? (t >= ShipEntity.CR_JUMP ? "JUMP // " + dest
                    : String.format(Locale.ROOT, "JUMP DRIVE // %d%%", (int) (s.hyperCharge(t) * 100)))
                    : String.format(Locale.ROOT, "DEEP SPACE // EN ROUTE TO %s", dest);
            case DESCENT -> String.format(Locale.ROOT, "ENTRY // %s  //  ALT %d M  //  %d M/S", dest,
                    (int) Math.max(0, s.getY() - s.groundY()), (int) (s.velocity(t).length() * 20));
            case SURVEY -> s.siteFlat() ? "LANDING ZONE SCAN // CLEAR"
                    : t < ShipEntity.SCAN_TICKS ? "LANDING ZONE SCAN // UNEVEN TERRAIN" : "LEVELLING LANDING ZONE";
            case LANDING -> String.format(Locale.ROOT, "VTOL DESCENT // %.1f M", Math.max(0, s.getY() - s.groundY()));
            case TOUCHDOWN -> "TOUCHDOWN // " + dest;
            default -> "";
        };
        float sc = 0.85f;
        float cw = Term.width(mc.textRenderer, cap, sc);
        int ty = h - bar + (bar - 7) / 2;
        Term.text(dc, mc.textRenderer, cap, (w - cw) / 2f, ty, 0xFFCFCCC4, sc);
        dc.fill((int) ((w - cw) / 2f) - 10, ty + 2, (int) ((w - cw) / 2f) - 6, ty + 6, ((int) (t / 8)) % 2 == 0 ? 0xFFE8382E : 0xFF4A1210);
        Term.text(dc, mc.textRenderer, "ARES STARSHIP", 8, (bar - 6) / 2f, 0xFFE8382E, 0.7f);
    }

    /** Radial star streaks from the centre of the screen (additive). */
    private static void streaks(DrawContext dc, int w, int h, float time, float k) {
        Matrix4f m = dc.getMatrices().peek().getPositionMatrix();
        float cx = w / 2f, cy = h / 2f, diag = (float) Math.sqrt(cx * cx + cy * cy);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int n = 260;
        for (int i = 0; i < n; i++) {
            long hsh = i * 0x9E3779B97F4A7C15L;
            float ang = (float) ((hsh >>> 11 & 0xFFFF) / 65535.0 * Math.PI * 2);
            float r0 = (float) ((hsh >>> 27 & 0xFFFF) / 65535.0);
            float speed = 0.004f + 0.03f * k * k;
            float r = (r0 + time * speed) % 1f;
            float inner = diag * (0.04f + 0.96f * r * r);
            float len = diag * (0.004f + 0.55f * k * k * (0.4f + 0.6f * r));
            float outer = inner + len;
            float wd = 0.5f + 1.4f * k * r;
            float ca = MathHelper.cos(ang), sa = MathHelper.sin(ang);
            float px = -sa * wd, py = ca * wd;
            float a = Math.min(1f, k * 1.4f) * (0.35f + 0.65f * r);
            float x0 = cx + ca * inner, y0 = cy + sa * inner, x1 = cx + ca * outer, y1 = cy + sa * outer;
            buf.vertex(m, x0 - px, y0 - py, 0).color(1f, 0.95f, 0.9f, 0f).next();
            buf.vertex(m, x1 - px, y1 - py, 0).color(1f, 0.92f, 0.85f, a).next();
            buf.vertex(m, x1 + px, y1 + py, 0).color(1f, 0.92f, 0.85f, a).next();
            buf.vertex(m, x0 + px, y0 + py, 0).color(1f, 0.95f, 0.9f, 0f).next();
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
