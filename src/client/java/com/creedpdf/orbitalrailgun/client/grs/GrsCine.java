package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.SpaceSky;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.grs.GungnirStrikeEntity;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * The caller's cutscene when Gungnir is called - one long move from the target to Jupiter and back:
 * <ol>
 *   <li>GROUND - the designator burns orange circuit traces into the ground round the target, a beam stands up off it;</li>
 *   <li>RISE - the camera rockets straight up through the clouds, then looks down on the target, a red point far below;</li>
 *   <li>EARTH - out in space over the world, pulling back until it and the Moon are small;</li>
 *   <li>WARP - the Milky Way, then the stars stretch into lines;</li>
 *   <li>JUPITER - it swells in front of you inside its ring; GUNGNIR is written across the sky behind it;</li>
 *   <li>SKIM / GATE / RAIL - past the planet's limb, the muzzle gate head-on, then racing along the ring beside the rail
 *   through the coil gates;</li>
 *   <li>WIDE - the whole ring lit, the spear a flare going round Jupiter;</li>
 *   <li>RIDE - on the spear through the coil tunnel, light bars streaming past;</li>
 *   <li>RELEASE - out of the muzzle past the planet;</li>
 *   <li>WARP HOME - behind the spear, rocks flashing by, the Moon, then Earth;</li>
 *   <li>ENTRY - burning down through the atmosphere;</li>
 *   <li>SKY - on the ground, a star coming down;</li>
 *   <li>IMPACT - the impact frames;</li>
 *   <li>PLANTED - the spear standing in its crater as the dust rolls out.</li>
 * </ol>
 * Space shots park the real camera far above the build limit and draw the solar system from a virtual eye in the
 * Space dimension. Press the fire key to skip.
 */
public final class GrsCine {
    private GrsCine() {}

    public static final int GROUND = 0, RISE = 22, LOOK = 40, EARTH = 48, WARP = 64, JUP = 82, SKIM = 112, GATE = 120, RAIL = 134, WIDE = 170,
            RIDE = 192, FLASH = 220, RELEASE = 224, HOME = 238, ENTRY = 258, SKY = 270, IMPACT = Grs.IMPACT, PLANTED = IMPACT + 40, END = IMPACT + 92;
    private static final double PARK_Y = 2400;

    public static final Identifier TITLE = RailgunMod.id("textures/grs/cine/title.png"), W_RELEASE = RailgunMod.id("textures/grs/cine/release.png"),
            W_IMPACT = RailgunMod.id("textures/grs/cine/impact.png"), W_JUPITER = RailgunMod.id("textures/grs/cine/jupiter.png"),
            CIRCUIT = RailgunMod.id("textures/grs/fx/circuit.png"), WARP_T = RailgunMod.id("textures/grs/fx/warp.png"),
            ROCK = RailgunMod.id("textures/grs/fx/rock.png");

    private static long start = -1;
    private static Vec3d target, origin;
    private static float baseYaw;
    private static long seed;

    public static void start(BlockPos tgt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || tgt == null) return;
        start = GrsClient.now();
        target = Vec3d.ofBottomCenter(tgt).add(0, 1, 0);
        origin = mc.player.getEyePos();
        baseYaw = mc.player.getYaw();
        seed = tgt.asLong();
        lastTick = -1;
    }

    public static void skip() {
        start = -1;
        reset();
    }

    private static void reset() {
        GrsRing.spearAngle = null;
        GrsRing.away = false;
        GrsRing.charge = 0;
        GrsRing.wake = 1.1f;
        GrsRing.orbScale = 1f;
        GrsImpactFx.mode = -1;
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
        return active() && t >= EARTH && t < SKY;
    }

    static float s(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return (float) (x * x * x * (x * (x * 6 - 15) + 10));
    }

    private static double k(double t, double a, double b) {
        return MathHelper.clamp((t - a) / (b - a), 0, 1);
    }

    // ------------------------------------------------------------------------------------------- space geometry

    private static final Vec3d UP = new Vec3d(0, 1, 0);

    private static Vec3d J() {
        return Body.JUPITER.center;
    }

    private static double RJ() {
        return Body.JUPITER.radius;
    }

    private static Vec3d E() {
        return Body.EARTH.center;
    }

    private static Vec3d toEarth() {
        return E().subtract(J()).multiply(1, 0, 1).normalize();
    }

    private static Vec3d sideJ() {
        return UP.crossProduct(toEarth()).normalize();
    }

    private static double MZ() {
        return Grs.muzzleAngle();
    }

    private static Vec3d radial(double th) {
        return new Vec3d(Math.cos(th), 0, Math.sin(th));
    }

    private static Vec3d tangent(double th) {
        return new Vec3d(-Math.sin(th), 0, Math.cos(th));
    }

    /** Where the spear is during the release and the flight home. */
    private static Vec3d flight(double t) {
        Vec3d from = Grs.stationPos();
        Vec3d to = E().add(0, Body.EARTH.radius + 60, 0);
        if (t < HOME) {
            double u = t - RELEASE;
            return from.add(tangent(MZ()).multiply(u * 18 + u * u * 6));
        }
        Vec3d a = from.add(tangent(MZ()).multiply(14 * 18 + 14 * 14 * 6));
        double k = k(t, HOME, ENTRY + 6);
        return a.add(to.subtract(a).multiply(s(k) * 0.35 + k * 0.65));
    }

    private static Vec3d flightDir(double t) {
        Vec3d a = flight(t), b = flight(t + 0.5);
        Vec3d d = b.subtract(a);
        return d.lengthSquared() < 1e-6 ? tangent(MZ()) : d.normalize();
    }

    /** {eye, look-at} in the Space dimension. */
    private static Vec3d[] spaceShot(double t) {
        Vec3d J = J(), toE = toEarth(), side = sideJ();
        double R = RJ(), Rr = Grs.ringRadius(), mz = MZ();
        GrsRing.spearAngle = null;
        GrsRing.away = false;
        GrsRing.charge = 0;
        GrsRing.wake = 1.1f;
        GrsRing.orbScale = 1f;
        if (t < WARP) {
            // over the world, pulling back fast
            double k = k(t, EARTH, WARP);
            double d = Body.EARTH.radius * (1.25 * Math.pow(18, Math.pow(k, 1.3)));
            Vec3d dir = new Vec3d(0.25, 1, 0.35).normalize();
            Vec3d eye = E().add(dir.multiply(d));
            Vec3d look = E().add(new Vec3d(0.3, 0, -0.2).multiply(Body.EARTH.radius * 0.4 * (1 - k)));
            return new Vec3d[]{eye, look};
        }
        Vec3d arrive = J.add(toE.multiply(R * 6.0)).add(UP.multiply(R * 1.05)).add(side.multiply(R * 0.6));
        if (t < JUP) {
            // turn away to the Milky Way, then the warp to Jupiter
            double k = k(t, WARP, JUP);
            Vec3d a = E().add(new Vec3d(0.25, 1, 0.35).normalize().multiply(Body.EARTH.radius * 22.5));
            double e = k < 0.3 ? 0 : s((k - 0.3) / 0.7);
            Vec3d eye = a.add(arrive.subtract(a).multiply(e));
            Vec3d milky = eye.add(new Vec3d(0.2, 0.75, -0.6));
            Vec3d look = Kit.lerp(milky, J, s(k * 2.2 - 0.3));
            return new Vec3d[]{eye, look};
        }
        if (t < SKIM) {
            // Jupiter in its ring, swelling; the spear lapping, the ring lighting up
            double k = k(t, JUP, SKIM);
            Vec3d eye = J.add(toE.multiply(R * (6.0 - 1.6 * s(k)))).add(UP.multiply(R * (1.05 - 0.2 * k))).add(side.multiply(R * 0.6 * (1 - k)));
            GrsRing.charge = (float) (0.15 + 0.55 * s((k - 0.35) / 0.65));
            GrsRing.wake = (float) (1.1 + 3.5 * s((k - 0.3) / 0.7));
            return new Vec3d[]{eye, J.add(UP.multiply(R * 0.12))};
        }
        if (t < GATE) {
            // skimming Jupiter's limb as the spear's wake sweeps over it
            double k = k(t, SKIM, GATE);
            double th = mz - 0.75 + k * 0.12;
            Vec3d eye = J.add(radial(th).multiply(R * 1.13)).add(UP.multiply(R * 0.12));
            Vec3d look = Grs.pathPoint(th + 0.55).add(UP.multiply(-R * 0.08));
            GrsRing.spearAngle = mz - 0.95 + k * 1.2;
            GrsRing.wake = 2.5f;
            GrsRing.charge = 0.4f;
            return new Vec3d[]{eye, look};
        }
        if (t < RAIL) {
            // the muzzle gate, head on, pushing in
            double k = k(t, GATE, RAIL);
            double back = 1400 - 700 * s(k);
            Vec3d gate = Grs.stationPos();
            Vec3d eye = gate.add(tangent(mz).multiply(back)).add(UP.multiply(90 - 40 * k)).add(radial(mz).multiply(160));
            GrsRing.charge = 0.35f + 0.5f * (float) k;
            return new Vec3d[]{eye, gate.add(UP.multiply(-40))};
        }
        if (t < WIDE) {
            // racing along the ring beside the rail, through the coil gates
            double k = k(t, RAIL, WIDE);
            double th = mz - 0.42 + 0.36 * (k * 0.7 + s(k) * 0.3);
            Vec3d eye = Grs.ringPoint(th, Grs.GATE_OUT + 140, Grs.PATH_H + 90);
            Vec3d look = Grs.ringPoint(th + 0.06, 30, Grs.PATH_H - 40);
            GrsRing.spearAngle = mz - 0.42 + (t - RAIL) * 0.05;
            GrsRing.wake = 1.6f;
            GrsRing.charge = 0.55f;
            return new Vec3d[]{eye, look};
        }
        if (t < RIDE) {
            // the whole ring lit, the spear a flare going round
            double k = k(t, WIDE, RIDE);
            Vec3d dir = toE.multiply(Math.cos(0.9 + k * 0.25)).add(side.multiply(Math.sin(0.9 + k * 0.25))).multiply(R * 5.2).add(UP.multiply(R * 0.7));
            GrsRing.charge = 0.9f;
            GrsRing.wake = 4.5f;
            return new Vec3d[]{J.add(dir), J};
        }
        if (t < RELEASE) {
            // riding the spear through the coil tunnel into the muzzle
            double k = k(t, RIDE, FLASH);
            double th = mz - 0.68 + 0.68 * (k * 0.6 + k * k * 0.4);
            GrsRing.spearAngle = th;
            GrsRing.orbScale = 0.12f;
            GrsRing.charge = 1f;
            GrsRing.wake = 0.25f;
            Vec3d eye = Grs.pathPoint(th - 26 / Rr).add(UP.multiply(9)).add(radial(th).multiply(4));
            Vec3d look = Grs.pathPoint(th + 160 / Rr).add(UP.multiply(-6));
            return new Vec3d[]{eye, look};
        }
        GrsRing.away = true;
        GrsRing.charge = (float) Math.max(0, 1 - (t - RELEASE) / 30);
        if (t < HOME) {
            // out of the muzzle past the planet: the camera holds, the spear goes by
            Vec3d gate = Grs.stationPos();
            Vec3d eye = gate.add(tangent(mz).multiply(2600)).add(radial(mz).multiply(1800)).add(UP.multiply(300));
            return new Vec3d[]{eye, flight(t)};
        }
        // behind the spear, all the way home
        Vec3d p = flight(t), d = flightDir(t);
        Vec3d eye = p.subtract(d.multiply(t < ENTRY ? 110 : 80)).add(UP.multiply(t < ENTRY ? 26 : 18));
        Vec3d look = p.add(d.multiply(400));
        return new Vec3d[]{eye, look};
    }

    // ------------------------------------------------------------------------------------------- the camera

    /** Camera pose {x, y, z, yaw, pitch} in the real world, or null when no cutscene. */
    @Nullable
    public static double[] camera(float pt) {
        if (!active()) {
            reset();
            return null;
        }
        double t = t(pt);
        updateFx(t);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d off = origin.subtract(target).multiply(1, 0, 1);
        off = off.lengthSquared() < 4 ? new Vec3d(1, 0, 0) : off.normalize();
        Vec3d side = off.crossProduct(up);
        if (t < RISE) {
            double k = k(t, GROUND, RISE);
            Vec3d pos = target.add(off.multiply(7 - 2 * k)).add(side.multiply(2.5)).add(0, 0.6 + 1.6 * k, 0);
            Vec3d look = target.add(0, 0.5 + 26 * s(k), 0);
            return pose(pos, look);
        }
        if (t < LOOK) {
            double k = k(t, RISE, LOOK);
            double y = 3 + 520 * Math.pow(k, 2.3);
            Vec3d pos = target.add(off.multiply(1.5)).add(0, y, 0);
            float yaw = (float) Math.toRadians(baseYaw + 140 * k);
            Vec3d look = pos.add(-Math.sin(yaw) * 0.08, 1, Math.cos(yaw) * 0.08);
            return pose(pos, look);
        }
        if (t < EARTH) {
            double k = k(t, LOOK, EARTH);
            Vec3d pos = target.add(0, 620 + 120 * k, 0);
            return pose(pos, pos.add(0.02, -1, 0.05));
        }
        if (t < SKY) {
            Vec3d[] sh = spaceShot(t);
            Vec3d park = new Vec3d(origin.x, PARK_Y, origin.z);
            return pose(park, park.add(sh[1].subtract(sh[0]).normalize()));
        }
        reset();
        updateFx(t);
        if (t < IMPACT) {
            // on the ground: a star coming down
            Vec3d sky = skyDir();
            Vec3d pos = target.add(off.multiply(34)).add(0, 2.5, 0);
            return pose(pos, pos.add(sky.multiply(100)).add(target.subtract(pos).multiply(0.15 * k(t, SKY, IMPACT))));
        }
        if (t < PLANTED) {
            double k = k(t, IMPACT, PLANTED);
            Vec3d pos = target.add(off.multiply(78)).add(side.multiply(18)).add(0, 34, 0);
            return pose(pos, target.add(0, 10 + 20 * k, 0));
        }
        double k = k(t, PLANTED, END);
        Vec3d pos = target.add(off.multiply(88 + 30 * k)).add(side.multiply(18 + 20 * k)).add(0, 30 + 12 * k, 0);
        return pose(pos, target.add(0, 30 + 70 * s(k * 1.3), 0));
    }

    private static Vec3d skyDir() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof GungnirStrikeEntity g && g.getPos().squaredDistanceTo(target) < 16) return g.skyDir();
            }
        }
        return new Vec3d(0.3, 0.9, 0.25).normalize();
    }

    private static double[] pose(Vec3d pos, Vec3d look) {
        Vec3d d = look.subtract(pos).normalize();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = Math.toDegrees(-Math.asin(MathHelper.clamp(d.y, -0.9999, 0.9999)));
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }

    /** The impact frames: which post effect is on this frame. */
    private static void updateFx(double t) {
        int m = -1;
        double u = t - IMPACT;
        if (u >= 0 && u < 34) {
            if (u < 5) m = 3;
            else if (u < 13) m = 0;
            else if (u < 17) m = 2;
            else if (u < 21) m = 1;
            else if (u < 28) m = 0;
            else if (u < 31) m = 2;
            else m = 3;
        }
        GrsImpactFx.mode = m;
    }

    // ------------------------------------------------------------------------------------------- the sky in space

    /** WorldRenderEvents.LAST: while the camera is in space, draw the solar system from the virtual eye. */
    public static void world(WorldRenderContext ctx) {
        float pt = ctx.tickDelta();
        if (!inSpace(pt)) return;
        Matrix4f m = new Matrix4f(ctx.matrixStack().peek().getPositionMatrix());
        double t = t(pt);
        Vec3d eye = spaceShot(t)[0];
        MinecraftClient mc = MinecraftClient.getInstance();
        SpaceSky.render(m, eye, new Quaternionf(), mc.world.getTime() + pt, pt, 1f, 1f, null, 1f);
    }

    /** Impostor scale for the scene round the eye: the nearer of Jupiter and Earth sets it (matching the sky's bodies). */
    private static float scale(Vec3d view) {
        double dj = view.distanceTo(J()), de = view.distanceTo(E());
        double d = Math.min(dj, de);
        return d < 60 ? 1f : (float) (60 / d);
    }

    private static Vector3f place(Vec3d view, Vec3d p, float k) {
        Vec3d r = p.subtract(view);
        return new Vector3f((float) (r.x * k), (float) (r.y * k), (float) (r.z * k));
    }

    /** Called from the space sky's far half, before Jupiter: the GUNGNIR title written across the sky behind it. */
    public static void backFx(VertexConsumerProvider buf, Matrix4f m, Vec3d view, float time) {
        if (!active()) return;
        double t = t(MinecraftClient.getInstance().getTickDelta());
        if (t < JUP || t >= SKIM) return;
        double k = k(t, JUP, SKIM);
        // the letters glitch in, hold, then fade
        float a = (float) (s((k - 0.12) / 0.12) * (1 - s((k - 0.85) / 0.15)));
        if (k < 0.3) a *= (MathHelper.sin((float) (t * 9.7)) > -0.3 ? 1 : 0.15f);
        if (a < 0.02f) return;
        float kk = scale(view);
        Vec3d c = J().subtract(toEarth().multiply(RJ() * 2.0)).add(UP.multiply(RJ() * 1.25));
        Vector3f p = place(view, c, kk);
        float w = (float) (RJ() * 2.6 * kk), h = w * 0.25f;
        float jit = k < 0.3 ? (float) (Math.sin(t * 31) * 0.02 * w) : 0;
        quad(buf.getBuffer(BeamRenderLayers.translucent(TITLE)), m, p.x + jit, p.y, p.z, w, h, 0, 0, 0, 1, 1, 1, 1, 1, a);
    }

    /** Called from the space sky after every layer (normal depth): warp, light bars, the spear in flight, rocks, plasma. */
    public static void spaceFx(VertexConsumerProvider buf, Matrix4f m, Vec3d view, Quaternionf toLocal, float time) {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        double t = t(mc.getTickDelta());
        GrsDraw.camera(mc.gameRenderer.getCamera());
        Vector3f F = new Vector3f(GrsDraw.F), R = new Vector3f(GrsDraw.R), U = new Vector3f(GrsDraw.U);
        // the warps
        float warp = 0;
        if (t >= WARP && t < JUP) warp = (float) (s(k(t, WARP + 4, WARP + 9)) * (1 - s(k(t, JUP - 5, JUP))));
        if (t >= HOME && t < ENTRY) warp = (float) (s(k(t, HOME, HOME + 4)) * (1 - s(k(t, ENTRY - 4, ENTRY))));
        if (warp > 0.01f) streaks(buf.getBuffer(BeamRenderLayers.textured(WARP_T)), m, F, R, U, t, warp, 260, 1f, 0.95f, 0.9f);
        // light bars streaming past inside the coil tunnel
        if (t >= RIDE && t < FLASH) {
            float k = (float) (s(k(t, RIDE, RIDE + 3)));
            bars(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.BEAM)), m, F, R, U, t, k);
        }
        // the spear itself in flight
        if (t >= RELEASE && t < SKY) {
            float kk = scale(view);
            Vec3d p = flight(t), d = flightDir(t);
            boolean entry = t >= ENTRY;
            spearModel(buf, m, view, kk, p, d, entry ? 1.6f : 0.6f, time);
            Vector3f pp = place(view, p, kk);
            float glow = entry ? 2.4f : 1f;
            GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, pp.x, pp.y, pp.z, 60 * kk * glow, (float) t * 0.1f, 1f, 0.6f, 0.25f, 1);
            Vector3f tail = place(view, p.subtract(d.multiply(entry ? 900 : 1600)), kk);
            GrsDraw.streak(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.STREAK)), m, tail, pp, new Vector3f(), 24 * kk * glow, 1f, 0.55f, 0.2f, 0.9f);
            if (entry) plasma(buf, m, pp, place(view, p.add(d.multiply(-200)), kk), kk, t);
        }
        // rocks streaming by on the flight home
        if (t >= HOME + 2 && t < ENTRY - 2) rocks(buf.getBuffer(BeamRenderLayers.translucent(ROCK)), m, F, R, U, t);
    }

    private static void streaks(VertexConsumer vc, Matrix4f m, Vector3f F, Vector3f R, Vector3f U, double t, float k, int n, float r, float g, float b) {
        Random rnd = Random.create(seed ^ 0x5EED);
        for (int i = 0; i < n; i++) {
            float ang = rnd.nextFloat() * MathHelper.TAU;
            float rad = 0.6f + rnd.nextFloat() * 9f;
            float ph = (float) ((rnd.nextFloat() + t * (0.09 + rnd.nextFloat() * 0.06)) % 1.0);
            float z0 = 40 * (1 - ph) + 1, len = (4 + 18 * ph) * k;
            Vector3f off = new Vector3f(R).mul(MathHelper.cos(ang) * rad).add(new Vector3f(U).mul(MathHelper.sin(ang) * rad));
            Vector3f head = new Vector3f(F).mul(Math.max(0.6f, z0 - len)).add(new Vector3f(off).mul((z0 - len) / 20f));
            Vector3f tail = new Vector3f(F).mul(z0).add(new Vector3f(off).mul(z0 / 20f));
            float a = k * (0.3f + 0.7f * ph);
            GrsDraw.streak(vc, m, tail, head, new Vector3f(), 0.03f + 0.03f * ph, r * a, g * a, b * a, 1);
        }
    }

    private static void bars(VertexConsumer vc, Matrix4f m, Vector3f F, Vector3f R, Vector3f U, double t, float k) {
        for (int i = 0; i < 24; i++) {
            float ang = i / 24f * MathHelper.TAU;
            float rad = 3.2f + (i % 3) * 0.4f;
            Vector3f off = new Vector3f(R).mul(MathHelper.cos(ang) * rad).add(new Vector3f(U).mul(MathHelper.sin(ang) * rad * 0.8f));
            float ph = (float) ((i * 0.37 + t * 0.55) % 1.0);
            float z0 = 30 * (1 - ph), len = 10 + 14 * ph;
            Vector3f a = new Vector3f(F).mul(z0 + len).add(off), b = new Vector3f(F).mul(Math.max(0.4f, z0)).add(off);
            float al = k * (0.4f + 0.6f * ph);
            boolean white = i % 4 != 0;
            GrsDraw.beam(vc, m, a, b, new Vector3f(), 0.22f, (float) t, 4f, 1f, white ? 0.92f : 0.5f, white ? 0.8f : 0.15f, al);
        }
    }

    private static void rocks(VertexConsumer vc, Matrix4f m, Vector3f F, Vector3f R, Vector3f U, double t) {
        Random rnd = Random.create(seed ^ 0xA57E);
        for (int i = 0; i < 90; i++) {
            float ang = rnd.nextFloat() * MathHelper.TAU;
            float rad = 2.5f + rnd.nextFloat() * 14f;
            float ph = (float) ((rnd.nextFloat() + t * 0.06) % 1.0);
            float z = 50 * (1 - ph) - 2;
            if (z < 0.8f) continue;
            Vector3f p = new Vector3f(F).mul(z).add(new Vector3f(R).mul(MathHelper.cos(ang) * rad)).add(new Vector3f(U).mul(MathHelper.sin(ang) * rad));
            float sz = 0.15f + rnd.nextFloat() * 0.9f;
            int tile = rnd.nextInt(4);
            float u0 = (tile % 2) * 0.5f, v0 = (tile / 2) * 0.5f;
            float a = Math.min(1, ph * 3);
            quad(vc, m, p.x, p.y, p.z, sz, sz, (float) (t * 0.1 * (i % 5 - 2)), u0, v0, u0 + 0.5f, v0 + 0.5f, 0.75f, 0.7f, 0.66f, a);
        }
    }

    private static void plasma(VertexConsumerProvider buf, Matrix4f m, Vector3f nose, Vector3f back, float kk, double t) {
        VertexConsumer vc = buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB));
        for (int i = 0; i < 14; i++) {
            float f = i / 14f;
            Vector3f p = new Vector3f(nose).lerp(back, f * 1.8f);
            float s = (70 - 40 * f) * kk * (0.85f + 0.15f * MathHelper.sin((float) (t * 3 + i)));
            GrsDraw.sprite(vc, m, p.x, p.y, p.z, s, 0, 1f * (1 - f), 0.7f * (1 - f), 0.45f * (1 - f), 1);
        }
    }

    private static void spearModel(VertexConsumerProvider buf, Matrix4f m, Vec3d view, float kk, Vec3d pos, Vec3d dir, float glow, float time) {
        if (GrsModels.SPEAR == null) return;
        Vector3f p = place(view, pos, kk);
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(m);
        ms.translate(p.x, p.y, p.z);
        ms.scale(kk, kk, kk);
        // tip first: the model's tip is at y = 0, the pommel up +Y
        ms.multiply(new Quaternionf().rotationTo(new Vector3f(0, -1, 0), dir.toVector3f()).rotateY(time * 0.2f));
        ms.translate(0, -20, 0);
        GrsModels.SPEAR.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.solid(GrsModels.SPEAR_TEX)), GrsDraw.FULL, null, (nx, ny, nz, o) -> {
            float d = Math.max(0, nx * 0.55f + ny * 0.3f - nz * 0.78f);
            float k = 0.2f + 0.8f * d;
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1;
        });
        GrsModels.SPEAR.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.textured(GrsModels.SPEAR_GLOW)), GrsDraw.FULL, null, (nx, ny, nz, o) -> {
            o[0] = glow;
            o[1] = glow;
            o[2] = glow;
            o[3] = 1;
        });
    }

    /** A camera-facing quad with its own size and uv rect. */
    static void quad(VertexConsumer vc, Matrix4f m, float x, float y, float z, float hw, float hh, float ang, float u0, float v0, float u1, float v1, float r,
                     float g, float b, float a) {
        float c = MathHelper.cos(ang), sn = MathHelper.sin(ang);
        Vector3f R = GrsDraw.R, U = GrsDraw.U;
        float rx = (R.x * c + U.x * sn) * hw, ry = (R.y * c + U.y * sn) * hw, rz = (R.z * c + U.z * sn) * hw;
        float ux = (-R.x * sn + U.x * c) * hh, uy = (-R.y * sn + U.y * c) * hh, uz = (-R.z * sn + U.z * c) * hh;
        GrsDraw.v(vc, m, x - rx - ux, y - ry - uy, z - rz - uz, u0, v1, r, g, b, a);
        GrsDraw.v(vc, m, x + rx - ux, y + ry - uy, z + rz - uz, u1, v1, r, g, b, a);
        GrsDraw.v(vc, m, x + rx + ux, y + ry + uy, z + rz + uz, u1, v0, r, g, b, a);
        GrsDraw.v(vc, m, x - rx + ux, y - ry + uy, z - rz + uz, u0, v0, r, g, b, a);
    }

    // ------------------------------------------------------------------------------------------- on the ground

    /** WorldRenderEvents.AFTER_TRANSLUCENT: the designator's circuit traces and beam at the target. */
    public static void ground(WorldRenderContext ctx, VertexConsumerProvider.Immediate buf) {
        if (!active()) return;
        double t = t(ctx.tickDelta());
        if (t >= EARTH) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        GrsDraw.camera(ctx.camera());
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ms.peek().getPositionMatrix();
        float reveal = (float) (2 + 13 * s(k(t, 0, 16)));
        int bx = MathHelper.floor(target.x), bz = MathHelper.floor(target.z);
        VertexConsumer vc = buf.getBuffer(BeamRenderLayers.textured(CIRCUIT));
        float flick = 0.8f + 0.2f * MathHelper.sin((float) t * 2.3f);
        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                float d = MathHelper.sqrt(dx * dx + dz * dz);
                if (d > reveal) continue;
                int x = bx + dx, z = bz + dz;
                int y = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                float a = (1 - d / 15.5f) * flick * Math.min(1, (reveal - d) * 0.6f);
                float u0 = (x & 7) / 8f, v0 = (z & 7) / 8f;
                GrsDraw.v(vc, m, x, y + 0.02f, z, u0, v0, a, 0.42f * a, 0.08f * a, 1);
                GrsDraw.v(vc, m, x, y + 0.02f, z + 1, u0, v0 + 0.125f, a, 0.42f * a, 0.08f * a, 1);
                GrsDraw.v(vc, m, x + 1, y + 0.02f, z + 1, u0 + 0.125f, v0 + 0.125f, a, 0.42f * a, 0.08f * a, 1);
                GrsDraw.v(vc, m, x + 1, y + 0.02f, z, u0 + 0.125f, v0, a, 0.42f * a, 0.08f * a, 1);
            }
        }
        buf.draw();
        // the beam standing off the target, and its spark
        Vector3f base = new Vector3f((float) target.x, (float) target.y, (float) target.z);
        Vector3f top = new Vector3f(base).add(0, 400, 0);
        Vector3f eye = new Vector3f((float) cam.x, (float) cam.y, (float) cam.z);
        float bk = (float) s(k(t, 2, 8));
        GrsDraw.beam(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.BEAM)), m, base, top, eye, 0.5f * bk, (float) t * 0.3f, 10f, 1f, 0.45f, 0.1f, 0.9f * bk);
        buf.draw();
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, base.x, base.y + 0.3f, base.z, 2.5f * flick, (float) t * 0.1f, 1f, 0.7f, 0.4f,
                1);
        buf.draw();
        // seen from high above, the target is a red point
        if (t >= RISE + 8) {
            float a = (float) s(k(t, RISE + 8, LOOK));
            GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.xray(GrsDraw.ORB)), m, base.x, base.y + 1, base.z, 10f * a, 0, a, 0.15f * a, 0.08f * a, 1);
            buf.draw();
        }
        ms.pop();
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
        if (t == 1) GrsClient.play(ModSounds.GRS_LOCK, 0.7f, 1f);
        if (t == RISE) GrsClient.play(ModSounds.GRS_WHOOSH, 0.55f, 1f);
        if (t == EARTH) GrsClient.play(ModSounds.GRS_HUM, 0.7f, 1f);
        if (t == WARP + 6) GrsClient.play(ModSounds.GRS_DESCENT, 0.6f, 0.8f);
        if (t == JUP + 10) GrsClient.play(ModSounds.GRS_YIELD, 0.5f, 0.9f);
        if (t == GATE) GrsClient.play(ModSounds.GRS_HUM, 1f, 0.9f);
        if (t > RAIL && t < WIDE && (t - RAIL) % 9 == 0) GrsClient.play(ModSounds.GRS_WHOOSH, 0.9f + (t - RAIL) / 90f, 0.6f);
        if (t == RIDE) GrsClient.play(ModSounds.GRS_RELEASE, 1.25f, 1f);
        if (t > RIDE && t < FLASH && (t - RIDE) % 4 == 0) GrsClient.play(ModSounds.GRS_WHOOSH, 1.2f + (t - RIDE) / 40f, 0.4f);
        if (t == RELEASE) GrsClient.play(ModSounds.GRS_DESCENT, 1f, 1f);
        if (t == HOME) GrsClient.play(ModSounds.GRS_WHOOSH, 0.7f, 1f);
        if (t == ENTRY) GrsClient.play(ModSounds.GRS_DESCENT, 1.35f, 0.9f);
        if (t == IMPACT) RailgunHud.shake(4f);
    }

    // ------------------------------------------------------------------------------------------- the frame

    public static void hud(DrawContext ctx, float pt) {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        double t = t(pt);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // punching through the cloud layer
        if (t >= RISE && t < LOOK) {
            float c = (float) Math.exp(-Math.pow((t - (RISE + 9)) / 2.6, 2));
            fade(ctx, w, h, c * 0.9f, 0xF4F2F0);
        }
        fade(ctx, w, h, win(t, LOOK + 5, 3, EARTH, 2), 0xFFFFFF);
        fade(ctx, w, h, cut(t, WARP), 0x000000);
        fade(ctx, w, h, cut(t, SKIM), 0x000000);
        fade(ctx, w, h, win(t, RAIL - 3, 2, RAIL, 3), 0xFFFFFF);
        fade(ctx, w, h, cut(t, WIDE), 0x000000);
        fade(ctx, w, h, cut(t, RIDE), 0x000000);
        fade(ctx, w, h, win(t, FLASH - 2, 2, RELEASE, 3), 0xFFFFFF);
        fade(ctx, w, h, cut(t, HOME), 0x000000);
        // burning in: white haze, then the flash to the ground
        if (t >= ENTRY && t < SKY) fade(ctx, w, h, (float) (k(t, ENTRY, SKY) * 0.6), 0xFFE8D8);
        fade(ctx, w, h, win(t, SKY - 3, 2, SKY, 3), 0xFFFFFF);
        // night falls round the star coming down
        if (t >= SKY && t < IMPACT) fade(ctx, w, h, (float) (k(t, SKY, IMPACT) * 0.55), 0x000000);
        fade(ctx, w, h, win(t, IMPACT - 1, 1, IMPACT, 2), 0xFFFFFF);
        fade(ctx, w, h, win(t, END - 12, 10, END + 10, 1), 0x000000);

        // the words
        word(ctx, W_JUPITER, w, h, h * 0.74f, 0.42f, win(t, JUP + 16, 6, SKIM - 6, 4));
        word(ctx, W_RELEASE, w, h, h * 0.5f, 0.42f, win(t, RELEASE + 2, 3, HOME - 3, 3));
        if (t >= IMPACT && t < PLANTED + 10) {
            float a = win(t, IMPACT + 3, 2, PLANTED + 4, 6);
            impactOverlay(ctx, w, h, t, a);
            word(ctx, W_IMPACT, w, h, h * 0.42f, 0.36f, a);
        }
        String skip = "[" + GrsClient.key(GrsClient.FIRE) + "] SKIP";
        ctx.drawText(mc.textRenderer, skip, w - 8 - mc.textRenderer.getWidth(skip), h - 14, 0x70FFB080, false);
        RenderSystem.disableBlend();
    }

    private static void word(DrawContext ctx, Identifier tex, int w, int h, float cy, float scale, float a) {
        if (a <= 0.02f) return;
        int ww = (int) (w * scale), hh = ww / 4;
        int x = (w - ww) / 2, y = (int) (cy - hh / 2f);
        // a dark band behind the word, like a lower third
        ctx.fill(x + ww / 6, y + hh / 5, x + ww * 5 / 6, y + hh * 4 / 5, ((int) (110 * a) << 24));
        RenderSystem.setShaderColor(1, 1, 1, a);
        ctx.drawTexture(tex, x, y, ww, hh, 0, 0, 1024, 256, 1024, 256);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Over the impact frames: a perspective grid, corner brackets, a range readout and a lightning crack. */
    private static void impactOverlay(DrawContext ctx, int w, int h, double t, float a) {
        int col = ((int) (200 * a) << 24) | (GrsImpactFx.mode == 1 ? 0x102040 : 0xFF6A2A);
        // a ceiling grid converging on the horizon
        float hz = h * 0.4f;
        for (int i = -10; i <= 10; i++) line(ctx, w / 2f + i * w * 0.02f, hz, w / 2f + i * w * 0.12f, 0, col, 1);
        for (int j = 1; j < 9; j++) {
            float f = (float) Math.pow(j / 9.0, 1.7);
            float y = hz * (1 - f);
            float half = w * (0.2f + 1.0f * f);
            line(ctx, w / 2f - half, y, w / 2f + half, y, col, 1);
        }
        int b = (int) (Math.min(w, h) * 0.08);
        int bc = ((int) (230 * a) << 24) | (GrsImpactFx.mode == 1 ? 0x081830 : 0xFFE0D0);
        bracket(ctx, (int) (w * 0.24), (int) (h * 0.2), b, 1, 1, bc);
        bracket(ctx, (int) (w * 0.76), (int) (h * 0.2), b, -1, 1, bc);
        bracket(ctx, (int) (w * 0.24), (int) (h * 0.62), b, 1, -1, bc);
        bracket(ctx, (int) (w * 0.76), (int) (h * 0.62), b, -1, -1, bc);
        String rng = String.format(java.util.Locale.ROOT, "%04d", (int) (Math.abs(Math.sin(t * 1.7)) * 9999));
        ctx.drawText(MinecraftClient.getInstance().textRenderer, rng, (int) (w * 0.76) - 30, (int) (h * 0.2) - 12, bc, false);
        // the crack: a jagged bolt of white across the frame, re-struck every few frames
        double u = t - IMPACT;
        if ((u > 9 && u < 14) || (u > 22 && u < 27)) {
            Random r = Random.create(seed + (long) (u / 2));
            float x = w * (0.35f + r.nextFloat() * 0.3f), y = 0;
            float dx = (r.nextFloat() - 0.5f) * w * 0.05f;
            while (y < h) {
                float nx = x + dx + (r.nextFloat() - 0.5f) * w * 0.06f, ny = y + h * (0.04f + r.nextFloat() * 0.05f);
                line(ctx, x, y, nx, ny, 0x60FFFFFF, 5);
                line(ctx, x, y, nx, ny, 0xFFFFFFFF, 2);
                x = nx;
                y = ny;
            }
        }
    }

    private static void bracket(DrawContext ctx, int x, int y, int len, int sx, int sy, int col) {
        ctx.fill(Math.min(x, x + sx * len), y, Math.max(x, x + sx * len), y + 2, col);
        ctx.fill(x, Math.min(y, y + sy * len), x + 2, Math.max(y, y + sy * len), col);
    }

    private static void line(DrawContext ctx, float x0, float y0, float x1, float y1, int col, int width) {
        float dx = x1 - x0, dy = y1 - y0;
        int n = (int) Math.max(Math.abs(dx), Math.abs(dy));
        if (n <= 0) return;
        int hw = Math.max(1, width / 2);
        for (int i = 0; i <= n; i += 2) {
            int x = (int) (x0 + dx * i / n), y = (int) (y0 + dy * i / n);
            ctx.fill(x - hw / 2, y - hw / 2, x - hw / 2 + Math.max(1, width), y - hw / 2 + Math.max(1, width), col);
        }
    }

    private static float win(double t, double a0, double d0, double a1, double d1) {
        return s((t - a0) / d0) * (1 - s((t - a1) / d1));
    }

    private static float cut(double t, double at) {
        double d = Math.abs(t - at);
        return d > 2.5 ? 0 : (float) (1 - d / 2.5);
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

    @SuppressWarnings("unused")
    private static final int GL = GL11.GL_ONE;
}
