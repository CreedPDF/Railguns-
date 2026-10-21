package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.block.FlightConsoleBlock;
import com.creedpdf.orbitalrailgun.block.RailgunColliderBlock;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.PlanetState;
import com.creedpdf.orbitalrailgun.space.ShipRegistry;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.api.PortalAPI;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ARES starship.
 *
 * <p>Parked, it stands on its tripod gear with the rear hatch down as a ramp; invisible collision
 * blocks are placed around the cabin so you can walk in, and a real Flight Console block stands at
 * the front. Its whole flight is scripted, deterministic motion (the same functions run on the
 * server and the client, so it is perfectly smooth), built from integrated velocity profiles:</p>
 * <ol>
 *   <li>{@link Phase#IGNITION}: hatch closes, nacelles swing nozzle-down, engines light;</li>
 *   <li>{@link Phase#ASCENT}: VTOL lift-off, gear folds, nacelles swing aft, the ship accelerates
 *       and climbs, charges its jump drive and pierces away;</li>
 *   <li>{@link Phase#CRUISE}: deep space (the client glides the sky toward the destination); it
 *       drops out of the jump, then charges and jumps again;</li>
 *   <li>{@link Phase#DESCENT}: a fast curving approach into the destination's sky that bleeds off
 *       speed, converting to VTOL to stop in a hover above the landing zone;</li>
 *   <li>{@link Phase#SURVEY}: a holographic scan of the ground; if it is not flat a wide laser
 *       sweeps it level;</li>
 *   <li>{@link Phase#LANDING} / {@link Phase#TOUCHDOWN}: vertical descent on the thrusters, gear
 *       out, suspension compressing on contact, hatch down;</li>
 *   <li>{@link Phase#DEPARTING}: back to orbit (stowed / recalled): everyone aboard is put down
 *       beside it, then it lifts and flies off.</li>
 * </ol>
 * The model is posed by bones (yaw / pitch on the root, roll on a bank bone, the nacelles), never
 * by entity yaw, which stays 0.
 */
public class ShipEntity extends Entity implements GeoEntity {
    /** Ordinals are saved: only append. */
    public enum Phase { PARKED, IGNITION, ASCENT, CRUISE, DESCENT, TOUCHDOWN, SURVEY, LANDING, DEPARTING }

    public static final int IGNITION_TICKS = 60, ASCENT_TICKS = 228, CRUISE_TICKS = 300, LANDING_TICKS = 90,
            TOUCHDOWN_TICKS = 40, DEPART_TICKS = 150;
    public static final int DESCENT_PILOTED = 170, DESCENT_CALLED = 140;
    /** Ascent: jump-drive charge and the jump itself. */
    public static final int ASC_CHARGE = 195, ASC_JUMP = 215;
    /** Cruise: dropping out of the jump at the start, charging and jumping again at the end. */
    public static final int CR_EXIT = 25, CR_CHARGE = 240, CR_JUMP = 262;
    /** Survey: scan time, then one footprint row flattened every {@link #ROW_TICKS}. */
    public static final int SCAN_TICKS = 45, ROW_TICKS = 3, ROW_MIN = -10, ROW_MAX = 10, HALF_WIDTH = 6;
    public static final double HOVER = 22;

    private static final TrackedData<Integer> PHASE = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TICKS = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FACING = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> DEST = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> DUR = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> AX = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> AY = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> AZ = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> GROUND = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> SX = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> SZ = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> GLIDE = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> FLAT = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private UUID pilot, owner;
    @Nullable private BlockPos home;          // overworld pad
    private boolean placed;
    @Nullable private ChunkPos forced;
    private int level;                        // survey: target ground block height
    private int clientTicks;
    private int lastPhase = -1;

    public ShipEntity(EntityType<? extends ShipEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.ignoreCameraFrustum = true;
    }

    // =============================================================================================
    // Tracked state
    // =============================================================================================

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(PHASE, Phase.PARKED.ordinal());
        dataTracker.startTracking(TICKS, 0);
        dataTracker.startTracking(FACING, 0);
        dataTracker.startTracking(DEST, Body.MOON.ordinal());
        dataTracker.startTracking(DUR, 0);
        dataTracker.startTracking(AX, 0f);
        dataTracker.startTracking(AY, 0f);
        dataTracker.startTracking(AZ, 0f);
        dataTracker.startTracking(GROUND, 0f);
        dataTracker.startTracking(SX, 0f);
        dataTracker.startTracking(SZ, 0f);
        dataTracker.startTracking(GLIDE, false);
        dataTracker.startTracking(FLAT, true);
    }

    public Phase getPhase() {
        return Phase.values()[MathHelper.clamp(dataTracker.get(PHASE), 0, Phase.values().length - 1)];
    }

    public int getPhaseTicks() {
        return getWorld().isClient ? clientTicks : dataTracker.get(TICKS);
    }

    public int getFacing() {
        return dataTracker.get(FACING) & 3;
    }

    public void setFacing(int f) {
        dataTracker.set(FACING, f & 3);
    }

    public Body getDestination() {
        return Body.ALL[MathHelper.clamp(dataTracker.get(DEST), 0, Body.ALL.length - 1)];
    }

    public Vec3d anchor() {
        return new Vec3d(dataTracker.get(AX), dataTracker.get(AY), dataTracker.get(AZ));
    }

    public float groundY() {
        return dataTracker.get(GROUND);
    }

    /** Landing-zone centre (x, z). */
    public Vec3d site() {
        return new Vec3d(dataTracker.get(SX), groundY(), dataTracker.get(SZ));
    }

    public boolean gliding() {
        return dataTracker.get(GLIDE);
    }

    public boolean siteFlat() {
        return dataTracker.get(FLAT);
    }

    /** Length of the current variable-length phase (descent, survey). */
    public int duration() {
        return switch (getPhase()) {
            case IGNITION -> IGNITION_TICKS;
            case ASCENT -> ASCENT_TICKS;
            case CRUISE -> CRUISE_TICKS;
            case LANDING -> LANDING_TICKS;
            case TOUCHDOWN -> TOUCHDOWN_TICKS;
            case DEPARTING -> DEPART_TICKS;
            case DESCENT, SURVEY -> Math.max(1, dataTracker.get(DUR));
            default -> 0;
        };
    }

    public boolean inFlight() {
        return getPhase() != Phase.PARKED;
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    private void setPhase(Phase p) {
        dataTracker.set(PHASE, p.ordinal());
        dataTracker.set(TICKS, 0);
        setAnchor(getPos());
    }

    private void setAnchor(Vec3d a) {
        dataTracker.set(AX, (float) a.x);
        dataTracker.set(AY, (float) a.y);
        dataTracker.set(AZ, (float) a.z);
    }

    /** Horizontal nose heading for a cardinal facing: 0 north, 1 east, 2 south, 3 west. */
    public static Vec3d heading(int f) {
        return switch (f & 3) {
            case 1 -> new Vec3d(1, 0, 0);
            case 2 -> new Vec3d(0, 0, 1);
            case 3 -> new Vec3d(-1, 0, 0);
            default -> new Vec3d(0, 0, -1);
        };
    }

    public static int facingOf(Direction d) {
        return switch (d) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    // =============================================================================================
    // Flight model
    // =============================================================================================

    static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    static float smoother(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * f * (f * (f * 6 - 15) + 10);
    }

    /** Integrated (up, forward) displacement tables, one entry per tick. */
    private record Profile(double[] up, double[] fwd) {
        double up(float t) { return lerp(up, t); }
        double fwd(float t) { return lerp(fwd, t); }

        private static double lerp(double[] a, float t) {
            if (t <= 0) return 0;
            int i = (int) t;
            if (i >= a.length - 1) return a[a.length - 1] + (a[a.length - 1] - a[a.length - 2]) * (t - (a.length - 1));
            return a[i] + (a[i + 1] - a[i]) * (t - i);
        }
    }

    private interface Vel { double at(float t); }

    private static Profile integrate(int ticks, Vel vy, Vel vf) {
        double[] up = new double[ticks + 2], fwd = new double[ticks + 2];
        double u = 0, f = 0;
        for (int i = 1; i < up.length; i++) {
            for (int k = 0; k < 4; k++) {
                float t = i - 1 + (k + 0.5f) / 4f;
                u += vy.at(t) * 0.25;
                f += vf.at(t) * 0.25;
            }
            up[i] = u;
            fwd[i] = f;
        }
        return new Profile(up, fwd);
    }

    /** Take-off: VTOL hover climb, transition, acceleration and climb-out, then the jump. */
    private static final Profile ASCENT = integrate(ASCENT_TICKS + 40,
            t -> (t < 60 ? 22.0 / 60 * Math.PI / 2 * Math.sin(Math.PI * t / 60) : 0)
                    + 1.6 * smoother((t - 70) / 60f) + (t >= ASC_JUMP ? 3.0 * smooth((t - ASC_JUMP) / 5f) : 0),
            t -> 2.6 * smoother((t - 55) / 80f) + (t >= ASC_JUMP ? 22.0 * smooth((t - ASC_JUMP) / 5f) : 0));

    /** Departure to orbit: hover up off the pad, then a fast climbing exit. */
    private static final Profile DEPART = integrate(DEPART_TICKS + 20,
            t -> (t < 50 ? 18.0 / 50 * Math.PI / 2 * Math.sin(Math.PI * t / 50) : 0) + 1.3 * smoother((t - 60) / 45f),
            t -> 2.4 * smoother((t - 50) / 50f));

    /** Space: direction of travel toward the destination (and where the cruise ends). */
    public Vec3d cruiseEnd() {
        Body d = getDestination();
        Vec3d out = anchor().subtract(d.center).normalize();
        return d.center.add(out.multiply(d.radius + d.atmosphere + 900));
    }

    public Vec3d cruiseDir() {
        Vec3d d = cruiseEnd().subtract(anchor());
        return d.lengthSquared() > 1e-6 ? d.normalize() : heading(getFacing());
    }

    /** Hover point above the landing zone, the curved approach start and its tangent. */
    private Vec3d[] approach() {
        Vec3d H = heading(getFacing()), R = new Vec3d(-H.z, 0, H.x);
        Vec3d E = site().add(0, HOVER, 0);
        boolean far = gliding();
        Vec3d S = E.add(0, far ? 120 : 80, 0).subtract(H.multiply(far ? 230 : 110)).add(R.multiply(far ? 70 : 30));
        Vec3d chord = E.subtract(S);
        Vec3d dir = chord.normalize().add(H.multiply(0.35)).add(0, -0.2, 0).normalize();
        Vec3d vS = dir.multiply(1.15 * chord.length() / duration());
        return new Vec3d[]{S, vS, E};
    }

    /** Where the ship is {@code t} ticks into its phase. */
    public Vec3d pathPos(float t) {
        Vec3d a = anchor();
        Vec3d H = heading(getFacing());
        return switch (getPhase()) {
            case PARKED -> a;
            case IGNITION -> a.add(0, 1.5 * smooth((t - 35) / 25f), 0);
            case ASCENT -> a.add(H.multiply(ASCENT.fwd(t))).add(0, ASCENT.up(t), 0);
            case CRUISE -> t < CR_JUMP ? a : a.add(cruiseDir().multiply(30 * (t - CR_JUMP)));
            case DESCENT -> {
                Vec3d[] ap = approach();
                float s = MathHelper.clamp(t / duration(), 0f, 1f);
                float h00 = 2 * s * s * s - 3 * s * s + 1, h10 = s * s * s - 2 * s * s + s, h01 = -2 * s * s * s + 3 * s * s;
                yield ap[0].multiply(h00).add(ap[1].multiply(h10 * duration())).add(ap[2].multiply(h01));
            }
            case SURVEY -> a.add(0, 0.3 * MathHelper.sin(t * 0.09f), 0);
            case LANDING -> new Vec3d(a.x, groundY() + (a.y - groundY()) * (1 - smoother(t / LANDING_TICKS)), a.z);
            case TOUCHDOWN -> a.add(0, -0.22 * Math.sin(Math.min(Math.PI, t / 8.0 * Math.PI)) * Math.exp(-t / 14.0), 0);
            case DEPARTING -> a.add(H.multiply(DEPART.fwd(t))).add(0, DEPART.up(t), 0);
        };
    }

    public Vec3d velocity(float t) {
        return pathPos(t + 0.5f).subtract(pathPos(t - 0.5f));
    }

    /** 1 = nacelles nozzle-down (hover), 0 = aft (forward flight). */
    public float vtol(float t) {
        return switch (getPhase()) {
            case IGNITION -> smooth(t / 30f);
            case ASCENT -> 1f - smooth((t - 55) / 40f);
            case DESCENT -> smooth((t / duration() - 0.55f) / 0.25f);
            case SURVEY, LANDING -> 1f;
            case TOUCHDOWN -> 1f - smooth((t - 15) / 25f);
            case DEPARTING -> Math.min(smooth(t / 25f), 1f - smooth((t - 45) / 30f));
            default -> 0f;
        };
    }

    /** 0 = legs down, 1 = folded. */
    public float legFold(float t) {
        return switch (getPhase()) {
            case ASCENT -> smooth((t - 20) / 35f);
            case CRUISE, DESCENT, SURVEY -> 1f;
            case LANDING -> 1f - smooth((t - 10) / 45f);
            case DEPARTING -> smooth((t - 40) / 30f);
            default -> 0f;
        };
    }

    /** 1 = hatch down as a ramp, 0 = closed. */
    public float rampOpen(float t) {
        return switch (getPhase()) {
            case PARKED -> 1f;
            case IGNITION -> 1f - smooth(t / 25f);
            case TOUCHDOWN -> smooth((t - 10) / 30f);
            case DEPARTING -> 1f - smooth(t / 20f);
            default -> 0f;
        };
    }

    /** Engine output 0..1 (flame size, shake). */
    public float thrust(float t) {
        return switch (getPhase()) {
            case IGNITION -> 0.65f * smooth((t - 15) / 30f);
            case ASCENT -> 0.75f + 0.25f * smooth((t - 60) / 60f);
            case CRUISE -> 0.55f;
            case DESCENT -> 0.45f + 0.35f * smooth((t / duration() - 0.6f) / 0.3f);
            case SURVEY -> 0.55f;
            case LANDING -> 0.55f + 0.25f * smooth((t - 50) / 40f);
            case TOUCHDOWN -> 0.6f * (1f - smooth(t / 20f));
            case DEPARTING -> t < 15 ? 0.3f * smooth(t / 15f) : 0.6f + 0.4f * smooth((t - 50) / 40f);
            default -> 0f;
        };
    }

    /** Jump-drive charge 0..1 (glow, sound, the client's star streaks). */
    public float hyperCharge(float t) {
        return switch (getPhase()) {
            case ASCENT -> smooth((t - ASC_CHARGE) / (ASC_JUMP - ASC_CHARGE));
            case CRUISE -> t < CR_EXIT ? 1f - smooth(t / CR_EXIT) : smooth((t - CR_CHARGE) / (CR_JUMP - CR_CHARGE));
            default -> 0f;
        };
    }

    /** Pose: {yaw, pitch (nose up), bank} in radians. */
    public float[] pose(float t) {
        Vec3d H = heading(getFacing());
        Phase ph = getPhase();
        if (ph == Phase.CRUISE) {
            Vec3d n = cruiseDir();
            double horiz = Math.sqrt(n.x * n.x + n.z * n.z);
            return new float[]{(float) Math.atan2(-n.x, -n.z), (float) Math.atan2(n.y, horiz), 0f};
        }
        float yaw = (float) Math.atan2(-H.x, -H.z), pitch = 0f, bank = 0f;
        if (ph == Phase.ASCENT || ph == Phase.DESCENT || ph == Phase.DEPARTING) {
            Vec3d v = velocity(t);
            double vh = Math.sqrt(v.x * v.x + v.z * v.z);
            float w = smooth((float) vh / 1.2f);
            Vec3d hd = vh > 1e-4 ? new Vec3d(v.x / vh, 0, v.z / vh) : H;
            Vec3d dir = H.multiply(1 - w).add(hd.multiply(w));
            if (dir.lengthSquared() > 1e-6) yaw = (float) Math.atan2(-dir.x, -dir.z);
            float vt = vtol(t);
            float flight = (float) MathHelper.clamp(Math.atan2(v.y, Math.max(vh, 0.05)), Math.toRadians(-28), Math.toRadians(40));
            pitch = flight * (1f - vt) * w;
            if (ph == Phase.DESCENT) {
                float s = t / duration();
                pitch += (float) Math.toRadians(12) * smooth((s - 0.5f) / 0.12f) * (1f - smooth((s - 0.72f) / 0.2f));
            }
            // bank into the turn: lateral acceleration against the right vector
            Vec3d acc = pathPos(t + 1).subtract(pathPos(t).multiply(2)).add(pathPos(t - 1));
            Vec3d right = new Vec3d(-dir.z, 0, dir.x);
            bank = (float) MathHelper.clamp(-acc.dotProduct(right) * 9.0, -0.5, 0.5) * (1f - vt);
        } else if (ph == Phase.SURVEY) {
            bank = 0.03f * MathHelper.sin(t * 0.05f);
        }
        return new float[]{yaw, pitch, bank};
    }

    /** Unit vector the nose points along. */
    public Vec3d nose(float t) {
        float[] p = pose(t);
        return new Vec3d(-Math.sin(p[0]) * Math.cos(p[1]), Math.sin(p[1]), -Math.cos(p[0]) * Math.cos(p[1]));
    }

    /** Pivot of a VTOL nacelle (model px). */
    public static final double NAC_X = 100, NAC_Y = 50, NAC_Z = -20;

    /**
     * Model point (px) to world space for the current pose, exactly as the model is drawn:
     * nacelle tilt (if {@code nacelle} != 0, its side), bank, pitch, yaw.
     */
    public Vec3d modelToWorld(double px, double py, double pz, int nacelle, float pt) {
        float t = getPhaseTicks() + pt;
        double x = px / 16.0, y = (py - 48) / 16.0, z = -pz / 16.0;
        if (nacelle != 0) {
            double a = -Math.PI / 2 * vtol(t);
            double cy = (NAC_Y - 48) / 16.0, cz = -NAC_Z / 16.0;
            double ry = y - cy, rz = z - cz;
            y = cy + ry * Math.cos(a) + rz * Math.sin(a);
            z = cz - ry * Math.sin(a) + rz * Math.cos(a);
        }
        float[] p = pose(t);
        double c = p[2];
        double x1 = x * Math.cos(c) + y * Math.sin(c), y1 = -x * Math.sin(c) + y * Math.cos(c);
        double pi = p[1];
        double y2 = y1 * Math.cos(pi) - z * Math.sin(pi), z2 = y1 * Math.sin(pi) + z * Math.cos(pi);
        double b = p[0];
        double x3 = x1 * Math.cos(b) + z2 * Math.sin(b), z3 = -x1 * Math.sin(b) + z2 * Math.cos(b);
        Vec3d base = getWorld().isClient ? getLerpedPos(pt) : getPos();
        return base.add(x3, 3.0 + y2, z3);
    }

    // =============================================================================================
    // Ticking
    // =============================================================================================

    @Override
    public void tick() {
        super.tick();
        setYaw(0f);
        prevYaw = 0f;
        Phase ph = getPhase();
        if (getWorld().isClient) {
            if (ph.ordinal() != lastPhase) {
                lastPhase = ph.ordinal();
                clientTicks = dataTracker.get(TICKS);
            } else {
                clientTicks++;
                int server = dataTracker.get(TICKS);
                if (Math.abs(server - clientTicks) > 4) clientTicks = server;
            }
            clientFx(ph, clientTicks);
            setPosition(pathPos(clientTicks));
            return;
        }
        ServerWorld world = (ServerWorld) getWorld();
        int t = dataTracker.get(TICKS) + 1;
        dataTracker.set(TICKS, t);
        if (owner != null && age % 20 == 0 && !ShipRegistry.get(world.getServer()).isCurrent(owner, getUuid())) {
            discard();
            return;
        }
        mountPilot(world);
        if (ph != Phase.PARKED) followChunk(world);
        switch (ph) {
            case PARKED -> {
                if (!placed || t % 100 == 0) placeStructure(world);
            }
            case IGNITION -> {
                if (t == 1) sound(world, ModSounds.SHIP_IGNITE, 8f, 1f);
                if (t >= IGNITION_TICKS) next(Phase.ASCENT, t);
            }
            case ASCENT -> {
                if (t == 1) sound(world, ModSounds.SHIP_LAUNCH, 20f, 1f);
                if (t % 50 == 0 && t < ASC_CHARGE) sound(world, ModSounds.SHIP_ENGINE, 12f, 1f);
                if (t == ASC_CHARGE) sound(world, ModSounds.SHIP_HYPER_CHARGE, 16f, 1f);
                if (t == ASC_JUMP) {
                    sound(world, ModSounds.SHIP_HYPER_JUMP, 24f, 1f);
                    RailgunFx.burst(world, ModParticles.FLASH, getPos().add(0, 3, 0), 2, 1, 1, 0);
                }
                if (t >= ASCENT_TICKS) toSpace(world);
            }
            case CRUISE -> {
                if (t == 2) sound(world, ModSounds.SHIP_HYPER_JUMP, 10f, 1.25f);
                if (t % 80 == 30) sound(world, ModSounds.SHIP_ENGINE, 4f, 0.7f);
                if (t == CR_CHARGE) sound(world, ModSounds.SHIP_HYPER_CHARGE, 12f, 1f);
                if (t == CR_JUMP) sound(world, ModSounds.SHIP_HYPER_JUMP, 20f, 1f);
                if (t >= CRUISE_TICKS) toDestination(world);
            }
            case DESCENT -> {
                if (t % 45 == 1) sound(world, ModSounds.SHIP_ENGINE, 12f, 0.9f);
                if (t >= duration()) startSurvey(world, t);
            }
            case SURVEY -> {
                if (t == 1) sound(world, ModSounds.SHIP_SCAN, 8f, 1f);
                if (!siteFlat()) {
                    int ft = t - SCAN_TICKS;
                    if (ft >= 0 && ft % 40 == 0 && ft < (ROW_MAX - ROW_MIN + 1) * ROW_TICKS) sound(world, ModSounds.SHIP_LASER, 10f, 1f);
                    if (ft >= 0 && ft % ROW_TICKS == 0) {
                        int row = ROW_MIN + ft / ROW_TICKS;
                        if (row <= ROW_MAX) flattenRow(world, row);
                    }
                }
                if (t >= duration()) next(Phase.LANDING, t);
            }
            case LANDING -> {
                if (t % 40 == 1) sound(world, ModSounds.SHIP_ENGINE, 8f, 0.8f);
                if (t >= LANDING_TICKS) {
                    setPosition(pathPos(LANDING_TICKS));
                    setPhase(Phase.TOUCHDOWN);
                    setAnchor(new Vec3d(site().x, groundY(), site().z));
                    sound(world, ModSounds.SHIP_LAND, 10f, 1f);
                    RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 200, 7, 0.3, 0.3);
                }
            }
            case TOUCHDOWN -> {
                if (t >= TOUCHDOWN_TICKS) park(world);
            }
            case DEPARTING -> {
                if (t == 25) sound(world, ModSounds.SHIP_ENGINE, 12f, 1f);
                if (t == 60) sound(world, ModSounds.SHIP_LAUNCH, 16f, 1.1f);
                if (t >= DEPART_TICKS) {
                    if (owner != null) ShipRegistry.get(world.getServer()).setOrbit(owner);
                    unfollow(world);
                    discard();
                    return;
                }
            }
        }
        if (getPhase() != Phase.PARKED && !isRemoved()) setPosition(pathPos(dataTracker.get(TICKS)));
    }

    private void next(Phase p, int t) {
        setPosition(pathPos(t));
        setPhase(p);
    }

    private void sound(ServerWorld w, net.minecraft.sound.SoundEvent s, float vol, float pitch) {
        w.playSound(null, getX(), getY() + 3, getZ(), s, SoundCategory.BLOCKS, vol, pitch);
    }

    /** Client: ground dust kicked up by the thrusters (the flames themselves are 3D, see ShipRenderer). */
    private void clientFx(Phase ph, int t) {
        World w = getWorld();
        float th = thrust(t) * Math.max(vtol(t), ph == Phase.IGNITION ? 1f : 0f);
        if (th <= 0.05f) return;
        double ground = groundBelow();
        if (ground > 28) return;
        float k = (float) (1 - ground / 28) * th;
        double gy = getY() - ground + 0.2;
        for (int i = 0; i < 8 * k + 1; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = 3 + random.nextDouble() * 11;
            w.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, getX() + Math.cos(a) * r, gy, getZ() + Math.sin(a) * r,
                    Math.cos(a) * 0.3 * k, 0.015, Math.sin(a) * 0.3 * k);
        }
    }

    private double groundBelow() {
        BlockPos p = getBlockPos();
        for (int i = 0; i < 40; i++) {
            BlockState s = getWorld().getBlockState(p.down(i));
            if (!s.isAir() && !s.isOf(RailgunMod.RAILGUN_COLLIDER)) return i;
        }
        return 40;
    }

    // ---- chunk loading (the ship keeps its own chunk loaded in flight) -----------------------

    private void followChunk(ServerWorld w) {
        ChunkPos c = new ChunkPos(getBlockPos());
        if (c.equals(forced)) return;
        unfollow(w);
        w.setChunkForced(c.x, c.z, true);
        forced = c;
    }

    private void unfollow(ServerWorld w) {
        if (forced != null) {
            w.setChunkForced(forced.x, forced.z, false);
            forced = null;
        }
    }

    // =============================================================================================
    // Pilot
    // =============================================================================================

    @Nullable
    private ServerPlayerEntity pilotPlayer(MinecraftServer server) {
        return pilot == null ? null : server.getPlayerManager().getPlayer(pilot);
    }

    private void mountPilot(ServerWorld world) {
        if (pilot == null || getPhase() == Phase.PARKED || getPhase() == Phase.DEPARTING) return;
        ServerPlayerEntity p = pilotPlayer(world.getServer());
        if (p == null || p.getWorld() != world || p.getVehicle() == this) return;
        if (p.squaredDistanceTo(this) > 320 * 320) return;
        p.stopRiding();
        p.startRiding(this, true);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengerList().isEmpty() && passenger instanceof PlayerEntity;
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        if (!hasPassenger(passenger)) return;
        Vec3d seat = modelToWorld(0, 34, 0, 0, 1f);   // mid-cabin: inside the hull whatever the pose
        updater.accept(passenger, seat.x, seat.y - 0.6, seat.z);
    }

    @Override
    public boolean shouldRender(double distance) {
        return true;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    // =============================================================================================
    // Launch, travel, arrival
    // =============================================================================================

    public static List<Body> destinations(World world) {
        List<Body> out = new ArrayList<>();
        Body here = world.getRegistryKey() == World.OVERWORLD ? Body.EARTH : SpaceDims.planetOf(world.getRegistryKey());
        for (Body b : Body.ALL) {
            if (b.landable() && b != here) out.add(b);
        }
        return out;
    }

    public boolean launch(ServerPlayerEntity player, Body dest) {
        if (getPhase() != Phase.PARKED) return false;
        if (!destinations(getWorld()).contains(dest)) return false;
        if (dest != Body.EARTH && PlanetState.get(player.getServer()).doomed(dest)) {
            player.sendMessage(Text.translatable("message.orbital_railgun.ship.destroyed").formatted(Formatting.RED), true);
            return false;
        }
        ServerWorld world = (ServerWorld) getWorld();
        if (world.getRegistryKey() == World.OVERWORLD) home = getBlockPos();
        removeStructure(world);
        dataTracker.set(DEST, dest.ordinal());
        pilot = player.getUuid();
        player.stopRiding();
        player.startRiding(this, true);
        setPhase(Phase.IGNITION);
        return true;
    }

    private void toSpace(ServerWorld world) {
        MinecraftServer server = world.getServer();
        ServerWorld space = server.getWorld(SpaceDims.SPACE);
        if (space == null) { arriveAt(world, getBlockPos()); return; }
        Body from = world.getRegistryKey() == World.OVERWORLD ? Body.EARTH : SpaceDims.planetOf(world.getRegistryKey());
        if (from == null) from = Body.EARTH;
        Vec3d pos = from.surfaceToSpace(anchor().x, anchor().z, from.atmosphere + 500);
        transfer(world, space, s -> {
            s.setPosition(pos);
            s.setPhase(Phase.CRUISE);
        });
    }

    private void toDestination(ServerWorld world) {
        MinecraftServer server = world.getServer();
        Body dest = getDestination();
        RegistryKey<World> key = dest.landDim();
        ServerWorld target = key == null ? null : server.getWorld(key);
        if (target == null) target = server.getOverworld();
        BlockPos site = dest == Body.EARTH ? (home != null ? home : target.getSpawnPos())
                : new BlockPos((int) (random.nextGaussian() * 40), 0, (int) (random.nextGaussian() * 40));
        int ground = groundAt(target, site);
        boolean piloted = pilot != null;
        transfer(world, target, s -> s.beginDescent(new BlockPos(site.getX(), ground, site.getZ()), piloted));
    }

    /** Ground (first free y) at the middle of the landing zone. */
    public static int groundAt(ServerWorld w, BlockPos site) {
        w.getChunk(site.getX() >> 4, site.getZ() >> 4);
        return w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, site.getX(), site.getZ());
    }

    private void beginDescent(BlockPos ground, boolean piloted) {
        dataTracker.set(SX, ground.getX() + 0.5f);
        dataTracker.set(SZ, ground.getZ() + 0.5f);
        dataTracker.set(GROUND, (float) ground.getY());
        dataTracker.set(GLIDE, piloted);
        dataTracker.set(DUR, piloted ? DESCENT_PILOTED : DESCENT_CALLED);
        dataTracker.set(PHASE, Phase.DESCENT.ordinal());
        dataTracker.set(TICKS, 0);
        Vec3d start = pathPos(0);
        setPosition(start);
        setAnchor(start);
    }

    /** Recreates the ship in another world (with its pilot); {@code setup} positions the copy. */
    private void transfer(ServerWorld from, ServerWorld to, java.util.function.Consumer<ShipEntity> setup) {
        ServerPlayerEntity p = pilotPlayer(from.getServer());
        if (p != null && p.getVehicle() == this) p.stopRiding();
        unfollow(from);
        ShipEntity n = new ShipEntity(RailgunMod.SHIP, to);
        n.pilot = pilot;
        n.owner = owner;
        n.home = home;
        n.setFacing(getFacing());
        n.dataTracker.set(DEST, dataTracker.get(DEST));
        setup.accept(n);
        n.followChunk(to);
        to.spawnEntity(n);
        if (owner != null) ShipRegistry.get(from.getServer()).setDeployed(owner, n.getUuid(), to.getRegistryKey().getValue().toString(), n.getBlockPos());
        discard();
        if (p != null) {
            PortalAPI.teleportEntity(p, to, n.getPos().add(0, 2, 0));
            p.fallDistance = 0;
        }
    }

    /** Fallback: land straight down where we are. */
    private void arriveAt(ServerWorld world, BlockPos at) {
        beginDescent(new BlockPos(at.getX(), groundAt(world, at), at.getZ()), pilot != null);
    }

    /** Called from the uplink tablet: the ship flies in from orbit to land at {@code ground}. */
    public static ShipEntity call(ServerWorld world, ServerPlayerEntity player, BlockPos ground, int facing) {
        ShipEntity s = new ShipEntity(RailgunMod.SHIP, world);
        s.owner = player.getUuid();
        s.setFacing(facing);
        s.beginDescent(ground, false);
        if (world.getRegistryKey() == World.OVERWORLD) s.home = ground;
        s.followChunk(world);
        world.spawnEntity(s);
        ShipRegistry.get(world.getServer()).setDeployed(player.getUuid(), s.getUuid(), world.getRegistryKey().getValue().toString(), ground);
        return s;
    }

    // ---- survey and flattening ------------------------------------------------------------------

    private BlockPos siteOrigin(int y) {
        return BlockPos.ofFloored(dataTracker.get(SX), y, dataTracker.get(SZ));
    }

    /** Hovering: decide whether the zone is flat; if not, plan the laser levelling. */
    private void startSurvey(ServerWorld w, int t) {
        setPosition(pathPos(t));
        int f = getFacing();
        BlockPos o = siteOrigin(0);
        int[] tops = new int[(ROW_MAX - ROW_MIN + 1) * (2 * HALF_WIDTH + 1)];
        int[] obst = new int[tops.length];
        int i = 0;
        for (int lz = ROW_MIN; lz <= ROW_MAX; lz++) {
            for (int lx = -HALF_WIDTH; lx <= HALF_WIDTH; lx++) {
                BlockPos c = cellPos(o, f, lx, 0, lz);
                w.getChunk(c.getX() >> 4, c.getZ() >> 4);
                tops[i] = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1;
                obst[i] = w.getTopY(Heightmap.Type.MOTION_BLOCKING, c.getX(), c.getZ()) - 1;
                i++;
            }
        }
        int[] sorted = tops.clone();
        Arrays.sort(sorted);
        level = sorted[sorted.length / 2];
        boolean flat = true;
        for (int k = 0; k < tops.length; k++) {
            if (Math.abs(tops[k] - level) > 1 || obst[k] > level + 2) { flat = false; break; }
        }
        dataTracker.set(FLAT, flat);
        dataTracker.set(GROUND, (float) (level + 1));
        dataTracker.set(DUR, SCAN_TICKS + (flat ? 0 : (ROW_MAX - ROW_MIN + 1) * ROW_TICKS + 10));
        setPhase(Phase.SURVEY);
    }

    private void flattenRow(ServerWorld w, int lz) {
        int f = getFacing();
        BlockPos o = siteOrigin(0);
        BlockState top = surfaceBlock(w, o), fill = fillBlock(top);
        for (int lx = -HALF_WIDTH; lx <= HALF_WIDTH; lx++) {
            BlockPos c = cellPos(o, f, lx, 0, lz);
            // cut everything above the level
            for (int y = level + 1; y <= level + 26; y++) {
                BlockPos p = new BlockPos(c.getX(), y, c.getZ());
                BlockState s = w.getBlockState(p);
                if (s.isAir() || s.getHardness(w, p) < 0 || s.isOf(RailgunMod.RAILGUN_COLLIDER) || s.isOf(RailgunMod.FLIGHT_CONSOLE)) continue;
                w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
            // fill holes below it
            BlockPos lv = new BlockPos(c.getX(), level, c.getZ());
            if (soft(w.getBlockState(lv))) w.setBlockState(lv, top, Block.NOTIFY_LISTENERS);
            for (int d = 1; d <= 6; d++) {
                BlockPos p = lv.down(d);
                if (!soft(w.getBlockState(p))) break;
                w.setBlockState(p, fill, Block.NOTIFY_LISTENERS);
            }
            if (lx % 3 == 0) {
                w.spawnParticles(ParticleTypes.LAVA, c.getX() + 0.5, level + 1.1, c.getZ() + 0.5, 1, 0.3, 0.1, 0.3, 0);
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.getX() + 0.5, level + 1.2, c.getZ() + 0.5, 2, 0.4, 0.2, 0.4, 0.01);
            }
        }
    }

    private static boolean soft(BlockState s) {
        return s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty();
    }

    /** Most common solid surface block across the zone (grass, sand, regolith...). */
    private BlockState surfaceBlock(ServerWorld w, BlockPos o) {
        Map<BlockState, Integer> count = new HashMap<>();
        int f = getFacing();
        for (int lz = ROW_MIN; lz <= ROW_MAX; lz += 4) {
            for (int lx = -HALF_WIDTH; lx <= HALF_WIDTH; lx += 3) {
                BlockPos c = cellPos(o, f, lx, 0, lz);
                BlockPos p = new BlockPos(c.getX(), w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1, c.getZ());
                BlockState s = w.getBlockState(p);
                if (s.isFullCube(w, p) && s.getFluidState().isEmpty()) count.merge(s, 1, Integer::sum);
            }
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(w.getRegistryKey() == World.OVERWORLD ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState());
    }

    private static BlockState fillBlock(BlockState top) {
        if (top.isOf(Blocks.GRASS_BLOCK) || top.isOf(Blocks.MYCELIUM) || top.isOf(Blocks.PODZOL) || top.isOf(Blocks.DIRT_PATH)) {
            return Blocks.DIRT.getDefaultState();
        }
        return top;
    }

    // ---- parking, stowing ----------------------------------------------------------------------

    private void park(ServerWorld world) {
        BlockPos b = BlockPos.ofFloored(site().x, Math.round(groundY()), site().z);
        setPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
        setPhase(Phase.PARKED);
        unfollow(world);
        placeStructure(world);
        if (owner != null) ShipRegistry.get(world.getServer()).setDeployed(owner, getUuid(), world.getRegistryKey().getValue().toString(), b);
        ServerPlayerEntity p = pilotPlayer(world.getServer());
        if (p != null && p.getVehicle() == this) {
            p.stopRiding();
            Vec3d stand = cell(0, 2, -3);
            p.requestTeleport(stand.x, stand.y, stand.z);
            p.fallDistance = 0;
        }
        pilot = null;
    }

    /**
     * Back to orbit: everyone aboard is put down on the ground beside it in a flash, then it lifts
     * off and flies away. Ownerless ships (from older versions) hand the stower their item back.
     */
    public boolean depart(@Nullable ServerPlayerEntity by) {
        if (getPhase() != Phase.PARKED || !(getWorld() instanceof ServerWorld w)) return false;
        Vec3d out = cell(9, 0, 0);
        BlockPos ob = BlockPos.ofFloored(out);
        int gy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ob.getX(), ob.getZ());
        Box cabin = new Box(getPos().add(-8, -1, -10), getPos().add(8, 8, 10));
        for (PlayerEntity pl : w.getEntitiesByClass(PlayerEntity.class, cabin, e -> true)) {
            if (pl instanceof ServerPlayerEntity sp) {
                w.spawnParticles(ModParticles.FLASH, sp.getX(), sp.getY() + 1, sp.getZ(), 1, 0, 0, 0, 0);
                sp.requestTeleport(out.x, gy, out.z);
                w.spawnParticles(ModParticles.FLASH, out.x, gy + 1, out.z, 1, 0, 0, 0, 0);
                w.playSound(null, out.x, gy, out.z, ModSounds.RIFT_ZAP, SoundCategory.PLAYERS, 1.5f, 1.4f);
            }
        }
        removeStructure(w);
        if (owner == null && by != null && !by.isCreative()) by.getInventory().offerOrDrop(new ItemStack(RailgunMod.STARSHIP));
        pilot = null;
        setPhase(Phase.DEPARTING);
        sound(w, ModSounds.SHIP_IGNITE, 8f, 1.1f);
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && getWorld() instanceof ServerWorld w) {
            removeStructure(w);
            unfollow(w);
        }
        super.remove(reason);
    }

    // =============================================================================================
    // Walk-in structure
    // =============================================================================================

    public Vec3d cell(int lx, int ly, int lz) {
        BlockPos c = cellPos(origin(), getFacing(), lx, ly, lz);
        return new Vec3d(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    public static BlockPos cellPos(BlockPos origin, int facing, int lx, int ly, int lz) {
        int x, z;
        switch (facing & 3) {
            case 1 -> { x = -lz; z = lx; }
            case 2 -> { x = -lx; z = -lz; }
            case 3 -> { x = lz; z = -lx; }
            default -> { x = lx; z = lz; }
        }
        return origin.add(x, ly, z);
    }

    private static List<int[]> layout() {
        List<int[]> out = new ArrayList<>();
        for (int lz = -6; lz <= 5; lz++) for (int lx = -3; lx <= 3; lx++) out.add(new int[]{lx, 1, lz, 16});
        for (int lz = -6; lz <= 5; lz++) for (int lx = -3; lx <= 3; lx++) out.add(new int[]{lx, 5, lz, 16});
        for (int ly = 2; ly <= 4; ly++) {
            for (int lz = -6; lz <= 5; lz++) {
                out.add(new int[]{-3, ly, lz, 16});
                out.add(new int[]{3, ly, lz, 16});
            }
            for (int lx = -2; lx <= 2; lx++) out.add(new int[]{lx, ly, -6, 16});
            out.add(new int[]{-2, ly, 5, 16});
            out.add(new int[]{2, ly, 5, 16});
        }
        for (int lx = -1; lx <= 1; lx++) {
            out.add(new int[]{lx, 4, 5, 16});
            out.add(new int[]{lx, 1, 6, 8});
            out.add(new int[]{lx, 0, 7, 16});
            out.add(new int[]{lx, 0, 8, 8});
        }
        return out;
    }

    private BlockPos origin() {
        return BlockPos.ofFloored(getX(), Math.round(getY()), getZ());
    }

    private void placeStructure(ServerWorld w) {
        BlockPos o = origin();
        int f = getFacing();
        for (int[] c : layout()) {
            BlockPos p = cellPos(o, f, c[0], c[1], c[2]);
            BlockState s = w.getBlockState(p);
            if (!s.isAir() && !s.isReplaceable() && !s.isOf(RailgunMod.RAILGUN_COLLIDER)) continue;
            w.setBlockState(p, RailgunMod.RAILGUN_COLLIDER.getDefaultState().with(RailgunColliderBlock.HEIGHT, c[3]), 3);
        }
        BlockPos console = cellPos(o, f, 0, 2, -5);
        BlockState cs = w.getBlockState(console);
        if (cs.isAir() || cs.isReplaceable() || cs.isOf(RailgunMod.FLIGHT_CONSOLE)) {
            Direction toPilot = switch (f) {
                case 1 -> Direction.WEST;
                case 2 -> Direction.NORTH;
                case 3 -> Direction.EAST;
                default -> Direction.SOUTH;
            };
            w.setBlockState(console, RailgunMod.FLIGHT_CONSOLE.getDefaultState().with(FlightConsoleBlock.FACING, toPilot), 3);
        }
        placed = true;
    }

    private void removeStructure(ServerWorld w) {
        if (!placed && getPhase() != Phase.PARKED) return;
        BlockPos o = origin();
        int f = getFacing();
        for (int[] c : layout()) {
            BlockPos p = cellPos(o, f, c[0], c[1], c[2]);
            if (w.getBlockState(p).isOf(RailgunMod.RAILGUN_COLLIDER)) w.removeBlock(p, false);
        }
        BlockPos console = cellPos(o, f, 0, 2, -5);
        if (w.getBlockState(console).isOf(RailgunMod.FLIGHT_CONSOLE)) w.removeBlock(console, false);
        placed = false;
    }

    @Nullable
    public static ShipEntity near(World w, BlockPos pos) {
        ShipEntity best = null;
        double bd = 30 * 30;
        for (ShipEntity s : w.getEntitiesByClass(ShipEntity.class, new Box(pos).expand(24), e -> true)) {
            double d = s.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    // =============================================================================================
    // Persistence
    // =============================================================================================

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(PHASE, nbt.getInt("Phase"));
        dataTracker.set(TICKS, nbt.getInt("PhaseTicks"));
        dataTracker.set(FACING, nbt.getInt("Facing"));
        dataTracker.set(DEST, nbt.getInt("Dest"));
        dataTracker.set(DUR, nbt.getInt("Dur"));
        dataTracker.set(AX, nbt.getFloat("AX"));
        dataTracker.set(AY, nbt.getFloat("AY"));
        dataTracker.set(AZ, nbt.getFloat("AZ"));
        dataTracker.set(GROUND, nbt.getFloat("Ground"));
        dataTracker.set(SX, nbt.getFloat("SX"));
        dataTracker.set(SZ, nbt.getFloat("SZ"));
        dataTracker.set(GLIDE, nbt.getBoolean("Glide"));
        dataTracker.set(FLAT, !nbt.contains("Flat") || nbt.getBoolean("Flat"));
        placed = nbt.getBoolean("Placed");
        level = nbt.getInt("Level");
        if (nbt.containsUuid("Pilot")) pilot = nbt.getUuid("Pilot");
        if (nbt.containsUuid("Owner")) owner = nbt.getUuid("Owner");
        if (nbt.contains("Home")) home = BlockPos.fromLong(nbt.getLong("Home"));
        if (nbt.contains("ForcedChunk")) forced = new ChunkPos(nbt.getLong("ForcedChunk"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Phase", dataTracker.get(PHASE));
        nbt.putInt("PhaseTicks", dataTracker.get(TICKS));
        nbt.putInt("Facing", getFacing());
        nbt.putInt("Dest", dataTracker.get(DEST));
        nbt.putInt("Dur", dataTracker.get(DUR));
        nbt.putFloat("AX", dataTracker.get(AX));
        nbt.putFloat("AY", dataTracker.get(AY));
        nbt.putFloat("AZ", dataTracker.get(AZ));
        nbt.putFloat("Ground", dataTracker.get(GROUND));
        nbt.putFloat("SX", dataTracker.get(SX));
        nbt.putFloat("SZ", dataTracker.get(SZ));
        nbt.putBoolean("Glide", dataTracker.get(GLIDE));
        nbt.putBoolean("Flat", dataTracker.get(FLAT));
        nbt.putBoolean("Placed", placed);
        nbt.putInt("Level", level);
        if (pilot != null) nbt.putUuid("Pilot", pilot);
        if (owner != null) nbt.putUuid("Owner", owner);
        if (home != null) nbt.putLong("Home", home.asLong());
        if (forced != null) nbt.putLong("ForcedChunk", forced.toLong());
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
