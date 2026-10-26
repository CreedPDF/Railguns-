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

    public static final int GROUND = 0, RISE = 40, LOOK = 75, EARTH = 90, WARP = 140, JUP = 190, GATE = 290, RAIL = 350, WIDE = 410, RIDE = 460,
            FLASH = 516, RELEASE = 524, HOME = 570, ENTRY = 630, SKY = 670, IMPACT = Grs.IMPACT, PLANTED = IMPACT + 50, END = IMPACT + 150;
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

    private static final Vec3d EARTH_DIR = new Vec3d(0.25, 1, 0.35).normalize();

    /** Where the spear is during the release and the flight home. */
    private static Vec3d flight(double t) {
        Vec3d gate = Grs.stationPos();
        Vec3d top = E().add(new Vec3d(0.12, 1, 0.08).normalize().multiply(Body.EARTH.radius + 900));
        if (t < HOME) {
            double u = t - RELEASE;
            return gate.add(tangent(MZ()).multiply(u * 25 + u * u * 4));
        }
        double u = HOME - RELEASE;
        Vec3d a = gate.add(tangent(MZ()).multiply(u * 25 + u * u * 4));
        if (t < ENTRY) {
            double k = k(t, HOME, ENTRY);
            return a.add(top.subtract(a).multiply(s(k) * 0.5 + k * 0.5));
        }
        // burning down through the atmosphere toward the surface
        Vec3d d = top.subtract(a).normalize();
        Vec3d down = d.add(E().subtract(top).normalize().multiply(1.5)).normalize();
        return top.add(down.multiply((t - ENTRY) * 16));
    }

    private static Vec3d flightDir(double t) {
        Vec3d d = flight(t + 0.5).subtract(flight(t - 0.5));
        return d.lengthSquared() < 1e-6 ? tangent(MZ()) : d.normalize();
    }

    private static Vec3d arrive() {
        return J().add(toEarth().multiply(RJ() * 3.9)).add(UP.multiply(RJ() * 1.2)).add(sideJ().multiply(RJ() * 1.5));
    }

    /** {eye, look-at} in the Space dimension (and the ring's cutscene state for this moment). Mirrors the offline previewer. */
    private static Vec3d[] spaceShot(double t) {
        Vec3d J = J(), toE = toEarth(), side = sideJ();
        double R = RJ(), Rr = Grs.ringRadius(), mz = MZ();
        GrsRing.spearAngle = null;
        GrsRing.away = false;
        GrsRing.charge = 0;
        GrsRing.wake = 1.1f;
        GrsRing.orbScale = 1f;
        if (t < WARP) {
            // over the world, pulling back until it and the Moon are small
            double k = k(t, EARTH, WARP);
            double d = Body.EARTH.radius * 1.8 * Math.pow(7.5 / 1.8, s(k));
            Vec3d eye = E().add(EARTH_DIR.multiply(d));
            Vec3d look = E().add(new Vec3d(0.3, 0, -0.2).multiply(Body.EARTH.radius * 0.5 * (1 - s(k))));
            return new Vec3d[]{eye, look};
        }
        if (t < JUP) {
            // turn to Jupiter, then the warp
            Vec3d a = E().add(EARTH_DIR.multiply(Body.EARTH.radius * 7.5));
            double e = s(k(t, WARP + 14, JUP));
            Vec3d eye = a.add(arrive().subtract(a).multiply(e));
            Vec3d look = Kit.lerp(E(), J, s(k(t, WARP, WARP + 16)));
            return new Vec3d[]{eye, look};
        }
        if (t < GATE) {
            // Jupiter inside its ring, swelling slowly; the spear lapping and the ring lighting up
            double e = s(k(t, JUP, GATE));
            Vec3d eye = J.add(toE.multiply(R * (3.9 - 1.15 * e))).add(UP.multiply(R * (1.2 - 0.5 * e))).add(side.multiply(R * (1.5 - 0.9 * e)));
            GrsRing.charge = 0.15f + 0.6f * s(k(t, JUP + 40, GATE - 10));
            GrsRing.wake = 1.1f + 3.0f * s(k(t, JUP + 30, GATE));
            return new Vec3d[]{eye, J.add(UP.multiply(R * 0.15)).add(side.multiply(R * 0.35 * (1 - e)))};
        }
        if (t < RAIL) {
            // the muzzle gate, head on, pushing in slowly
            double e = s(k(t, GATE, RAIL));
            Vec3d g = Grs.stationPos();
            Vec3d eye = g.add(tangent(mz).multiply(2600 - 1700 * e)).add(radial(mz).multiply(420 - 200 * e)).add(UP.multiply(140 - 60 * e));
            GrsRing.charge = 0.5f;
            return new Vec3d[]{eye, g.add(UP.multiply(-30))};
        }
        if (t < WIDE) {
            // beside the rail, looking along the ring to the muzzle; the spear goes by
            double k = k(t, RAIL, WIDE);
            double th = mz - 0.36 + 0.1 * k;
            Vec3d eye = Grs.ringPoint(th, Grs.GATE_OUT + 260, Grs.PATH_H + 110);
            Vec3d look = Grs.ringPoint(th + 0.07, 0, Grs.PATH_H - 40);
            GrsRing.spearAngle = th - 0.35 + 0.9 * s(k(t, RAIL + 10, RAIL + 42));
            GrsRing.wake = 1.4f;
            GrsRing.charge = 0.55f;
            return new Vec3d[]{eye, look};
        }
        if (t < RIDE) {
            // the whole ring lit, the spear a flare going round Jupiter
            double a = 0.85 + 0.35 * k(t, WIDE, RIDE);
            Vec3d dir = toE.multiply(Math.cos(a)).add(side.multiply(Math.sin(a)));
            GrsRing.charge = 1f;
            GrsRing.wake = 4.5f;
            return new Vec3d[]{J.add(dir.multiply(R * 3.6)).add(UP.multiply(R * 1.35)), J};
        }
        if (t < RELEASE) {
            // riding the spear along the rail, through the last gates and out of the muzzle
            double k = k(t, RIDE, FLASH);
            double th = mz - 0.5 + 0.52 * (k * 0.45 + k * k * 0.55);
            GrsRing.spearAngle = th;
            GrsRing.charge = 0.6f;
            GrsRing.wake = 0.3f;
            GrsRing.orbScale = 0f;
            Vec3d eye = Grs.pathPoint(th - 34 / Rr).add(UP.multiply(12)).add(radial(th).multiply(3));
            Vec3d look = Grs.pathPoint(th + 220 / Rr).add(UP.multiply(-8));
            return new Vec3d[]{eye, look};
        }
        GrsRing.away = true;
        GrsRing.charge = (float) Math.max(0, 1 - (t - RELEASE) / 40);
        if (t < HOME) {
            // out of the muzzle, past the camera, Jupiter behind it
            Vec3d g = Grs.stationPos();
            Vec3d eye = g.add(tangent(mz).multiply(1500)).add(radial(mz).multiply(700)).add(UP.multiply(120));
            return new Vec3d[]{eye, flight(t)};
        }
        // behind the spear, all the way home
        Vec3d p = flight(t), d = flightDir(t);
        return new Vec3d[]{p.subtract(d.multiply(260)).add(UP.multiply(45)), p.add(d.multiply(400))};
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
            // low by the target as the designator burns in, tilting up the beam
            double k = s(k(t, GROUND, RISE));
            Vec3d pos = target.add(off.multiply(8 - 2.5 * k)).add(side.multiply(3 - k)).add(0, 0.7 + 2.2 * k, 0);
            Vec3d look = target.add(0, 0.4 + 30 * k, 0);
            return pose(pos, look);
        }
        if (t < LOOK) {
            // straight up, faster and faster, through the clouds
            double k = k(t, RISE, LOOK);
            double y = 3 + 560 * Math.pow(k, 2.2);
            Vec3d pos = target.add(off.multiply(2)).add(0, y, 0);
            float yaw = (float) Math.toRadians(baseYaw + 120 * s(k));
            Vec3d look = pos.add(-Math.sin(yaw) * 0.1, 1, Math.cos(yaw) * 0.1);
            return pose(pos, look);
        }
        if (t < EARTH) {
            // looking back down: the target a red point far below
            double k = k(t, LOOK, EARTH);
            Vec3d pos = target.add(0, 620 + 260 * s(k), 0);
            return pose(pos, pos.add(0.03, -1, 0.06));
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
            Vec3d pos = target.add(off.multiply(72)).add(side.multiply(10)).add(0, 3, 0);
            Vec3d skyAt = target.add(sky.multiply(260));
            Vec3d look = Kit.lerp(skyAt, target.add(0, 12, 0), s(k(t, IMPACT - 14, IMPACT)));
            return pose(pos, look);
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
        if (u >= 0 && u < 50) {
            if (u < 6) m = 3;
            else if (u < 18) m = 0;
            else if (u < 23) m = 2;
            else if (u < 30) m = 1;
            else if (u < 42) m = 0;
            else if (u < 46) m = 2;
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

    /** Called from the space sky's far half (nothing there now: the title is drawn flat on the screen, see hud). */
    public static void backFx(VertexConsumerProvider buf, Matrix4f m, Vec3d view, float time) {
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
        if (t >= WARP && t < JUP) warp = (float) (s(k(t, WARP + 12, WARP + 24)) * (1 - s(k(t, JUP - 16, JUP - 2))));
        if (t >= HOME && t < ENTRY) warp = (float) (s(k(t, HOME + 4, HOME + 14)) * (1 - s(k(t, ENTRY - 12, ENTRY))));
        if (warp > 0.01f) streaks(buf.getBuffer(BeamRenderLayers.textured(WARP_T)), m, F, R, U, t, warp, 260, 1f, 0.95f, 0.9f);
        // light bars streaming past inside the coil tunnel
        if (t >= RIDE && t < FLASH) {
            float k = (float) (s(k(t, RIDE + 4, RIDE + 16))) * 0.7f;
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
            float flare = t < HOME ? 45 : entry ? 40 : 18;
            GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, pp.x, pp.y, pp.z, flare * kk, (float) t * 0.1f, 1f, 0.6f, 0.25f, 1);
            Vector3f tail = place(view, p.subtract(d.multiply(entry ? 900 : 1600)), kk);
            GrsDraw.streak(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.STREAK)), m, tail, pp, new Vector3f(), 24 * kk * glow, 1f, 0.55f, 0.2f, 0.9f);
            if (entry) plasma(buf, m, pp, place(view, p.add(d.multiply(-200)), kk), kk, t);
        }
        // rocks streaming by on the flight home
        if (t >= HOME + 10 && t < ENTRY - 8) rocks(buf.getBuffer(BeamRenderLayers.translucent(ROCK)), m, F, R, U, t);
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
        if (t == WARP + 14) GrsClient.play(ModSounds.GRS_DESCENT, 0.6f, 0.8f);
        if (t == JUP + 24) GrsClient.play(ModSounds.GRS_YIELD, 0.5f, 0.9f);
        if (t == GATE) GrsClient.play(ModSounds.GRS_HUM, 1f, 0.9f);
        if (t == RAIL + 22) GrsClient.play(ModSounds.GRS_WHOOSH, 1f, 1f);
        if (t == WIDE + 10) GrsClient.play(ModSounds.GRS_HUM, 0.85f, 0.7f);
        if (t == RIDE) GrsClient.play(ModSounds.GRS_RELEASE, 1.25f, 1f);
        if (t > RIDE + 30 && t < FLASH && (t - RIDE) % 5 == 0) GrsClient.play(ModSounds.GRS_WHOOSH, 1.1f + (t - RIDE) / 60f, 0.45f);
        if (t == RELEASE) GrsClient.play(ModSounds.GRS_DESCENT, 1f, 1f);
        if (t == HOME + 4) GrsClient.play(ModSounds.GRS_WHOOSH, 0.7f, 1f);
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
        // punching through the cloud layer on the way up
        if (t >= RISE && t < LOOK) {
            float c = (float) Math.exp(-Math.pow((t - (RISE + 22)) / 4.0, 2));
            fade(ctx, w, h, c * 0.85f, 0xF4F2F0);
        }
        // the cuts: soft dips, white where something flares
        fade(ctx, w, h, win(t, EARTH - 8, 6, EARTH, 6), 0xFFFFFF);
        fade(ctx, w, h, dip(t, GATE, 6), 0x000000);
        fade(ctx, w, h, dip(t, RAIL, 5), 0x000000);
        fade(ctx, w, h, dip(t, WIDE, 6), 0x000000);
        fade(ctx, w, h, dip(t, RIDE, 5), 0x000000);
        fade(ctx, w, h, win(t, FLASH - 4, 4, RELEASE, 6), 0xFFFFFF);
        fade(ctx, w, h, dip(t, HOME, 5), 0x000000);
        if (t >= ENTRY && t < SKY) fade(ctx, w, h, (float) (s(k(t, ENTRY + 10, SKY)) * 0.55), 0xFFE8D8);
        fade(ctx, w, h, win(t, SKY - 6, 5, SKY, 6), 0xFFFFFF);
        if (t >= SKY && t < IMPACT) fade(ctx, w, h, (float) (s(k(t, SKY, IMPACT)) * 0.5), 0x000000);
        fade(ctx, w, h, win(t, IMPACT - 2, 2, IMPACT, 3), 0xFFFFFF);
        fade(ctx, w, h, win(t, END - 20, 16, END + 10, 1), 0x000000);

        // GUNGNIR over Jupiter, flat on the screen (always reads the right way round)
        float ta = win(t, JUP + 22, 22, GATE - 26, 18);
        if (ta > 0.02f) title(ctx, mc, w, h, t, ta);
        word(ctx, W_JUPITER, w, h, h * 0.8f, 0.36f, win(t, JUP + 40, 16, GATE - 24, 14));
        word(ctx, W_RELEASE, w, h, h * 0.5f, 0.42f, win(t, RELEASE + 8, 8, HOME - 8, 8));
        if (t >= IMPACT && t < PLANTED + 20) {
            float a = win(t, IMPACT + 4, 3, PLANTED + 6, 10);
            impactOverlay(ctx, w, h, t, a);
            word(ctx, W_IMPACT, w, h, h * 0.42f, 0.36f, a);
        }
        String skip = "[" + GrsClient.key(GrsClient.FIRE) + "] SKIP";
        ctx.drawText(mc.textRenderer, skip, w - 8 - mc.textRenderer.getWidth(skip), h - 14, 0x70FFB080, false);
        RenderSystem.disableBlend();
    }

    /** The title, set just above Jupiter's disc where the camera sees it, settling in as it fades up. */
    private static void title(DrawContext ctx, MinecraftClient mc, int w, int h, double t, float a) {
        Vec3d[] sh = spaceShot(t);
        Vec3d f = sh[1].subtract(sh[0]).normalize();
        Vec3d r = f.crossProduct(UP).normalize(), u = r.crossProduct(f);
        double tf = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2);
        Vec3d top = J().add(UP.multiply(RJ() * 1.08)).subtract(sh[0]);
        double z = top.dotProduct(f);
        float cx = w / 2f, cy = h * 0.2f;
        if (z > 1) {
            cx = (float) ((top.dotProduct(r) / z / tf / ((double) w / h) + 1) * w / 2);
            cy = (float) ((1 - top.dotProduct(u) / z / tf) * h / 2);
        }
        float settle = (float) s(k(t, JUP + 22, JUP + 60));
        float scale = 1.06f - 0.06f * settle;
        // the title texture is 8:1 with the letters in its middle half
        int tw = (int) (w * 0.8f * scale), th = tw / 8;
        int x = (int) (cx - tw / 2f), y = (int) Math.max(4, cy - th * 0.78f);
        RenderSystem.setShaderColor(1, 1, 1, a);
        ctx.drawTexture(TITLE, x, y, tw, th, 0, 0, 4096, 512, 4096, 512);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    private static float dip(double t, double at, double half) {
        double d = Math.abs(t - at);
        return d > half ? 0 : (float) (1 - s(d / half));
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
        if ((u > 12 && u < 18) || (u > 32 && u < 38)) {
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
