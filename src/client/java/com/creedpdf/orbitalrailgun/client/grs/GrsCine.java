package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.SpaceSky;
import com.creedpdf.orbitalrailgun.client.ui.Term;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The caller's cutscene when Gungnir is called (it fills the strike's release window, {@link Grs#RELEASE} ticks):
 * <ol>
 *   <li><b>RISE</b> - the camera tears straight up off the player into the sky;</li>
 *   <li><b>FLIGHT</b> - out across the solar system to Jupiter, its name written over it as it swells;</li>
 *   <li><b>STATION</b> - a slow pass round the Gungnir Railway Station on Jupiter's rings, the spear lapping;</li>
 *   <li><b>RIDE</b> - the camera drops onto Gungnir and rides it round the track as it spins faster and faster;</li>
 *   <li><b>SLINGSHOT</b> - cut wide: the spear leaves the station and whips one and three quarter times round Jupiter;</li>
 *   <li><b>FIRE</b> - it breaks for Earth in a flash;</li>
 *   <li><b>STRIKE</b> - back on the ground, a wide shot of the target as it comes down and plants itself.</li>
 * </ol>
 * The world is hidden while the camera is in space (it is parked far above the build limit and the space sky is drawn
 * from a virtual position in the Space dimension). Press the fire key to skip.
 */
public final class GrsCine {
    private GrsCine() {}

    public static final int RISE = 60, FLY = 135, STATION = 180, RIDE = 232, SLING = 280, FIRE = Grs.RELEASE, END = Grs.IMPACT + 58;
    private static final double PARK_Y = 2400;
    private static final double LAPS = 1.75;

    private static long start = -1;
    private static Vec3d target, origin;
    private static float baseYaw;
    private static float rideA0 = Float.NaN;

    public static void start(BlockPos tgt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || tgt == null) return;
        start = GrsClient.now();
        target = Vec3d.ofBottomCenter(tgt).add(0, 1, 0);
        origin = mc.player.getEyePos();
        baseYaw = mc.player.getYaw();
        rideA0 = Float.NaN;
        GrsClient.play(ModSounds.GRS_WHOOSH, 0.55f, 1f);
    }

    public static void skip() {
        start = -1;
        GrsSpace.rideAngle = null;
        GrsSpace.away = false;
    }

    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (start < 0 || mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return false;
        if (GrsClient.now() - start >= END) {
            skip();
            return false;
        }
        return true;
    }

    static float t(float pt) {
        return GrsClient.now() - start + pt;
    }

    public static boolean inSpace(float pt) {
        float t = t(pt);
        return active() && t >= RISE && t < FIRE;
    }

    private static float s(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return (float) (x * x * x * (x * (x * 6 - 15) + 10));
    }

    // ------------------------------------------------------------------------------------------- geometry (space dim)

    private static final Vec3d UP = new Vec3d(0, 1, 0);

    private static Vec3d J() {
        return Body.JUPITER.center;
    }

    private static Vec3d S() {
        return Grs.stationPos();
    }

    private static Vec3d out() {
        return S().subtract(J()).normalize();
    }

    private static Vec3d side() {
        return UP.crossProduct(out()).normalize();
    }

    private static double R() {
        return Body.JUPITER.radius;
    }

    private static float spaceTime(float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0 : mc.world.getTime() + pt;
    }

    private static float rideAngle(float t, float pt) {
        if (Float.isNaN(rideA0)) rideA0 = GrsSpace.lapAngle(spaceTime(pt));
        float u = Math.max(0, t - RIDE);
        return rideA0 + 0.12f * u + 0.0028f * u * u;
    }

    /** The spear on its slingshot round Jupiter, 0..1. */
    private static Vec3d slingPos(double k) {
        double th0 = Math.atan2(out().z, out().x);
        double th = th0 + LAPS * Math.PI * 2 * Math.pow(k, 1.7);
        return J().add(Math.cos(th) * R() * Grs.RING_ORBIT, 0, Math.sin(th) * R() * Grs.RING_ORBIT);
    }

    private static Vec3d toEarth() {
        return Body.EARTH.center.subtract(J()).normalize();
    }

    /** {space-dimension eye, look-at} while the camera is in space. */
    private static Vec3d[] spaceShot(float t, float pt) {
        Vec3d J = J(), S = S(), out = out(), side = side();
        if (t < FLY) {
            double k = (t - RISE) / (FLY - RISE);
            Vec3d a = new Vec3d(0, Body.EARTH.center.y + Body.EARTH.radius + 90, 0);
            Vec3d b = S.add(out.multiply(520)).add(UP.multiply(120)).add(side.multiply(180));
            // most of the distance is covered in the middle; it drifts into Jupiter's system at the end
            Vec3d pos = Kit.lerp(a, b, s(k));
            Vec3d look = Kit.lerp(J, S, s((k - 0.62) / 0.38));
            return new Vec3d[]{pos, look};
        }
        if (t < STATION) {
            double k = (t - FLY) / (STATION - FLY);
            double ang = 0.35 + k * 0.9;
            double rad = 520 - 330 * s(k);
            Vec3d dir = out.multiply(Math.cos(ang)).add(side.multiply(Math.sin(ang)));
            Vec3d pos = S.add(dir.multiply(rad)).add(UP.multiply(120 - 80 * k));
            return new Vec3d[]{pos, S};
        }
        if (t < RIDE) {
            float a = rideAngle(t, pt);
            float time = spaceTime(pt);
            GrsSpace.rideAngle = a;
            Vec3d at = GrsSpace.trackPoint(a - 0.1f, time), ahead = GrsSpace.trackPoint(a + 0.32f, time);
            Vec3d radial = at.subtract(S).multiply(1, 0, 1).normalize();
            return new Vec3d[]{at.add(UP.multiply(2.2)).add(radial.multiply(1.4)), ahead.add(UP.multiply(0.6))};
        }
        GrsSpace.rideAngle = null;
        GrsSpace.away = true;
        Vec3d wide = J.add(out.multiply(R() * 3.4)).add(UP.multiply(R() * 1.25)).add(side.multiply(R() * 1.6));
        double drift = (t - RIDE) / (FIRE - RIDE);
        return new Vec3d[]{wide.add(side.multiply(-R() * 0.3 * drift)), J.add(toEarth().multiply(R() * 0.5 * drift))};
    }

    // ------------------------------------------------------------------------------------------- the camera

    /** Camera pose {x, y, z, yaw, pitch} in the real world, or null when no cutscene. */
    @Nullable
    public static double[] camera(float pt) {
        if (!active()) {
            GrsSpace.rideAngle = null;
            GrsSpace.away = false;
            return null;
        }
        float t = t(pt);
        Vec3d pos, look;
        if (t < RISE) {
            double k = t / RISE;
            double e = k * k * k;
            pos = origin.add(0, 1.5 + 1700 * e, 0);
            float yaw = (float) Math.toRadians(baseYaw + 70 * k);
            look = pos.add(-Math.sin(yaw) * 0.06, -1, Math.cos(yaw) * 0.06);
            return pose(pos, look);
        }
        if (t < FIRE) {
            if (t < RIDE) GrsSpace.away = false;
            Vec3d[] sh = spaceShot(t, pt);
            Vec3d dir = sh[1].subtract(sh[0]);
            Vec3d park = new Vec3d(origin.x, PARK_Y, origin.z);
            return pose(park, park.add(dir.normalize()));
        }
        GrsSpace.rideAngle = null;
        GrsSpace.away = false;
        Vec3d off = origin.subtract(target).multiply(1, 0, 1);
        off = off.lengthSquared() < 4 ? new Vec3d(1, 0, 0) : off.normalize();
        Vec3d sideW = off.crossProduct(UP);
        double k = (t - FIRE) / (END - FIRE);
        pos = target.add(off.multiply(95 - 20 * k)).add(sideW.multiply(30 * k)).add(0, 42 + 10 * k, 0);
        look = target.add(0, 12 + 70 * s((t - FIRE - 20) / 30.0), 0);
        return pose(pos, look);
    }

    /** The space-dimension eye position for the current frame (where the sky is drawn from). */
    private static Vec3d spaceEye(float pt) {
        return spaceShot(t(pt), pt)[0];
    }

    private static double[] pose(Vec3d pos, Vec3d look) {
        Vec3d d = look.subtract(pos).normalize();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = Math.toDegrees(-Math.asin(MathHelper.clamp(d.y, -0.9999, 0.9999)));
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }

    // ------------------------------------------------------------------------------------------- the sky in space

    /** WorldRenderEvents.LAST: while the camera is in space, draw the whole solar system from the virtual eye. */
    public static void world(WorldRenderContext ctx) {
        float pt = ctx.tickDelta();
        if (!inSpace(pt)) return;
        Matrix4f m = new Matrix4f(ctx.matrixStack().peek().getPositionMatrix());
        SpaceSky.render(m, spaceEye(pt), new Quaternionf(), spaceTime(pt), pt, 1f, 1f, null, 1f);
    }

    /** Called from the space sky: the spear on its slingshot and its shot for Earth. */
    public static void spaceFx(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, float time) {
        if (!active()) return;
        float pt = MinecraftClient.getInstance().getTickDelta();
        float t = t(pt);
        if (t < RIDE || t >= FIRE) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        if (t < SLING) {
            double k = (t - RIDE) / (SLING - RIDE);
            VertexConsumer orb = buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB));
            for (int i = 0; i < 70; i++) {
                double kk = k - i * 0.004 * (0.4 + k);
                if (kk < 0) break;
                Vec3d p = slingPos(kk);
                float f = (float) Math.pow(1 - i / 70f, 1.4);
                sprite(orb, m, viewPos, toLocal, p, (i == 0 ? 260 : 190 * (1 - i / 90f)), f, 0.45f * f + (i == 0 ? 0.35f : 0), 0.08f * f + (i == 0 ? 0.25f : 0));
            }
            sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, viewPos, toLocal, slingPos(k), 1100, 1f, 0.5f, 0.12f);
            return;
        }
        // the shot: out of the slingshot along the tangent, straight for Earth
        double k = (t - SLING) / (FIRE - SLING);
        Vec3d from = slingPos(1);
        Vec3d head = from.add(toEarth().multiply(Math.pow(k, 2.2) * 40000));
        Vector3f a = place(viewPos, toLocal, from), b = place(viewPos, toLocal, head);
        Vector3f eye = new Vector3f();
        float w = 0.6f;
        VertexConsumer beam = buf.getBuffer(BeamRenderLayers.textured(GrsDraw.BEAM));
        GrsDraw.beam(beam, m, a, b, eye, w * 2.5f, time * 0.6f, 8f, 1f, 0.4f, 0.06f, 0.8f);
        GrsDraw.beam(beam, m, a, b, eye, w, time, 6f, 1f, 0.9f, 0.75f, 1f);
        float flash = (float) Math.exp(-(t - SLING) / 5.0);
        sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, viewPos, toLocal, from, 900 + 5000 * flash, 1f, 0.55f, 0.15f);
        sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB)), m, viewPos, toLocal, head, 420, 1f, 0.85f, 0.55f);
    }

    /** True when Jupiter's disc stands between the eye and p. */
    private static boolean hidden(Vec3d view, Vec3d p) {
        Vec3d d = p.subtract(view);
        double len = d.length();
        d = d.multiply(1 / len);
        Vec3d oc = J().subtract(view);
        double along = oc.dotProduct(d);
        if (along < 0 || along > len) return false;
        return oc.lengthSquared() - along * along < R() * R() * 0.98;
    }

    private static Vector3f place(Vec3d view, Quaternionf toLocal, Vec3d p) {
        Vec3d rel = p.subtract(view);
        double d = rel.length();
        float k = d < 60 ? 1f : (float) (60 * 0.96 / d);
        return toLocal.transform(new Vector3f((float) rel.x * k, (float) rel.y * k, (float) rel.z * k));
    }

    private static void sprite(VertexConsumer vc, Matrix4f m, Vec3d view, Quaternionf toLocal, Vec3d p, float size, float r, float g, float b) {
        if (hidden(view, p)) return;
        Vec3d rel = p.subtract(view);
        double d = rel.length();
        float k = d < 60 ? 1f : (float) (60 * 0.96 / d);
        Vector3f c = place(view, toLocal, p);
        GrsDraw.sprite(vc, m, c.x, c.y, c.z, Math.max(0.06f, size * k), 0, r, g, b, 1);
    }

    // ------------------------------------------------------------------------------------------- sound cues

    private static int lastTick = -1;

    public static void tick(MinecraftClient mc) {
        if (!active()) {
            lastTick = -1;
            return;
        }
        int t = (int) (GrsClient.now() - start);
        if (t == lastTick) return;
        lastTick = t;
        if (t == RISE) GrsClient.play(ModSounds.GRS_HUM, 0.7f, 1f);
        if (t == RISE + 60) GrsClient.play(ModSounds.GRS_YIELD, 0.55f, 0.7f);
        if (t == FLY) GrsClient.play(ModSounds.GRS_HUM, 1.0f, 0.8f);
        if (t >= STATION && t < RIDE && (t - STATION) % 9 == 0) {
            float k = (t - STATION) / (float) (RIDE - STATION);
            GrsClient.play(ModSounds.GRS_WHOOSH, 0.8f + 0.55f * k, 0.85f);
        }
        if (t == RIDE) GrsClient.play(ModSounds.GRS_RELEASE, 1f, 1f);
        if (t == SLING) GrsClient.play(ModSounds.GRS_DESCENT, 1.15f, 1f);
    }

    // ------------------------------------------------------------------------------------------- the frame

    public static void hud(DrawContext ctx, float pt) {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float t = t(pt);
        RenderSystem.enableBlend();
        int bar = (int) (h * 0.11f);
        ctx.fill(0, 0, w, bar, 0xFF000000);
        ctx.fill(0, h - bar, w, h, 0xFF000000);
        // the cuts
        fade(ctx, w, h, win(t, RISE - 14, 12, RISE, 8), 0xFFF2E0);
        fade(ctx, w, h, cut(t, STATION), 0x000000);
        fade(ctx, w, h, cut(t, RIDE), 0x000000);
        fade(ctx, w, h, win(t, FIRE - 8, 6, FIRE, 6), 0xFFE8C8);
        fade(ctx, w, h, win(t, END - 14, 12, END + 10, 1), 0x000000);
        // Jupiter's name over it as it swells
        if (t >= RISE && t < STATION) {
            Vec3d[] sh = spaceShot(t, pt);
            Vec3d look = sh[1].subtract(sh[0]).normalize();
            Vec3d toJ = J().subtract(sh[0]);
            double dJ = toJ.length();
            double cos = look.dotProduct(toJ.normalize());
            float a = win(t, RISE + 34, 16, STATION - 18, 14) * (float) MathHelper.clamp((cos - 0.9) / 0.08, 0, 1);
            if (a > 0.02f) {
                double ang = Math.asin(MathHelper.clamp(R() / dJ, 0, 1));
                double fov = Math.toRadians(mc.options.getFov().getValue());
                float off = (float) (Math.tan(ang) / Math.tan(fov / 2) * h / 2);
                float y = Math.max(bar + 8, h / 2f - off - 30);
                int col = ((int) (255 * a) << 24) | 0xFFD7A8;
                String name = "J U P I T E R";
                Term.text(ctx, tr, name, w / 2f - Term.width(tr, name, 2.6f) / 2, y, col, 2.6f);
                String sub = "FIFTH PLANET  ·  43 LIGHT-MINUTES FROM EARTH";
                Term.text(ctx, tr, sub, w / 2f - Term.width(tr, sub, 0.8f) / 2, y + 26, ((int) (200 * a) << 24) | 0xFF9A50, 0.8f);
            }
        }
        caption(ctx, tr, w, h, bar, "GUNGNIR RAILWAY STATION", "GRS  ·  JUPITER RING B", win(t, FLY + 6, 10, STATION - 10, 8));
        caption(ctx, tr, w, h, bar, "GUNGNIR", "LAPPING AT THE SPEED OF LIGHT", win(t, STATION + 8, 8, RIDE - 12, 8));
        caption(ctx, tr, w, h, bar, "SLINGSHOT", "ONE AND THREE QUARTER TURNS ROUND JUPITER", win(t, RIDE + 6, 8, SLING - 8, 6));
        caption(ctx, tr, w, h, bar, "TARGET  ·  EARTH", null, win(t, SLING, 4, FIRE - 4, 4));
        String skip = "[" + GrsClient.key(GrsClient.FIRE) + "] SKIP";
        Term.text(ctx, tr, skip, w - 10 - Term.width(tr, skip, 0.7f), h - bar / 2f - 3, 0x80FF9A50, 0.7f);
        RenderSystem.disableBlend();
    }

    private static void caption(DrawContext ctx, TextRenderer tr, int w, int h, int bar, String big, String small, float a) {
        if (a <= 0.02f) return;
        int col = ((int) (255 * a) << 24) | 0xFFC890;
        Term.text(ctx, tr, big, w / 2f - Term.width(tr, big, 1.6f) / 2, h - bar - 34, col, 1.6f);
        if (small != null) Term.text(ctx, tr, small, w / 2f - Term.width(tr, small, 0.75f) / 2, h - bar - 16, ((int) (200 * a) << 24) | 0xFF8A3A, 0.75f);
    }

    private static float win(double t, double a0, double d0, double a1, double d1) {
        return s((t - a0) / d0) * (1 - s((t - a1) / d1));
    }

    private static float cut(double t, double at) {
        double d = Math.abs(t - at);
        return d > 3 ? 0 : (float) (1 - d / 3);
    }

    private static void fade(DrawContext ctx, int w, int h, float k, int rgb) {
        if (k <= 0.01f) return;
        ctx.fill(0, 0, w, h, ((int) (255 * Math.min(1, k)) << 24) | rgb);
    }

    /** Tiny local vector helper. */
    static final class Kit {
        static Vec3d lerp(Vec3d a, Vec3d b, double k) {
            return new Vec3d(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
        }
    }
}
