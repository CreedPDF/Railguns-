package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.block.FlightConsoleBlock;
import com.creedpdf.orbitalrailgun.block.RailgunColliderBlock;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.PlanetState;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.BlockState;
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
import java.util.List;
import java.util.UUID;

/**
 * The ARES starship. Parked, it stands on its tripod gear with the rear hatch down as a ramp; the
 * mod places invisible collision blocks around the cabin so you can walk in, and a real Flight
 * Console block at the front. Using the console picks a destination and launches:
 *
 * <ol>
 *   <li>{@link Phase#IGNITION}: hatch closes, engines light, the ship lifts on its thrusters;</li>
 *   <li>{@link Phase#ASCENT}: it climbs, retracts its legs, pitches up and accelerates out of the sky;</li>
 *   <li>{@link Phase#CRUISE}: in space, the client flies a cinematic camera around the ship while
 *       the solar system slides past toward the destination;</li>
 *   <li>{@link Phase#DESCENT}: it drops into the destination's sky, glides in, flares, deploys its legs;</li>
 *   <li>{@link Phase#TOUCHDOWN}: settles on its gear, hatch drops, the cabin is walkable again.</li>
 * </ol>
 *
 * The model is posed by bones (yaw / pitch on the root), never by entity yaw, which stays 0.
 */
public class ShipEntity extends Entity implements GeoEntity {
    public enum Phase { PARKED, IGNITION, ASCENT, CRUISE, DESCENT, TOUCHDOWN }

    public static final int IGNITION_TICKS = 80, ASCENT_TICKS = 220, CRUISE_TICKS = 400, DESCENT_TICKS = 260, TOUCHDOWN_TICKS = 50;
    /** Descent start height above the landing ground. */
    public static final double DESCENT_HEIGHT = 190, DROP_HEIGHT = 150;
    /** Glide-in distance behind the landing point (piloted landings). */
    public static final double APPROACH = 110;

    private static final TrackedData<Integer> PHASE = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TICKS = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FACING = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> DEST = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> AX = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> AY = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> AZ = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> GROUND = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> GLIDE = DataTracker.registerData(ShipEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private UUID pilot;
    @Nullable private BlockPos home;          // overworld landing pad
    private boolean placed;                   // collision + console are in the world
    @Nullable private BlockPos forced;        // force-loaded chunk while flying unattended
    private int clientTicks;
    private int lastPhase = -1;

    public ShipEntity(EntityType<? extends ShipEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.ignoreCameraFrustum = true;
    }

    // ---- tracked state ------------------------------------------------------------------------

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(PHASE, Phase.PARKED.ordinal());
        dataTracker.startTracking(TICKS, 0);
        dataTracker.startTracking(FACING, 0);
        dataTracker.startTracking(DEST, Body.MOON.ordinal());
        dataTracker.startTracking(AX, 0f);
        dataTracker.startTracking(AY, 0f);
        dataTracker.startTracking(AZ, 0f);
        dataTracker.startTracking(GROUND, 0f);
        dataTracker.startTracking(GLIDE, false);
    }

    public Phase getPhase() {
        return Phase.values()[MathHelper.clamp(dataTracker.get(PHASE), 0, Phase.values().length - 1)];
    }

    /** Ticks into the current phase (client: counted locally, re-synced from the server). */
    public int getPhaseTicks() {
        return getWorld().isClient ? clientTicks : dataTracker.get(TICKS);
    }

    public int getFacing() {
        return dataTracker.get(FACING) & 3;
    }

    public Body getDestination() {
        int d = dataTracker.get(DEST);
        return Body.ALL[MathHelper.clamp(d, 0, Body.ALL.length - 1)];
    }

    public Vec3d anchor() {
        return new Vec3d(dataTracker.get(AX), dataTracker.get(AY), dataTracker.get(AZ));
    }

    /** Landing ground height (descent phases). */
    public float groundY() {
        return dataTracker.get(GROUND);
    }

    public boolean gliding() {
        return dataTracker.get(GLIDE);
    }

    public boolean inFlight() {
        return getPhase() != Phase.PARKED;
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

    public void setFacing(int f) {
        dataTracker.set(FACING, f & 3);
    }

    /** Heading of the nose (unit, horizontal) for a cardinal facing: 0 north, 1 east, 2 south, 3 west. */
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

    // ---- path (shared by server and client so the motion is smooth) -----------------------------

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    /** Where the ship is {@code t} ticks into its phase. */
    public Vec3d pathPos(float t) {
        Vec3d a = anchor();
        Vec3d h = heading(getFacing());
        return switch (getPhase()) {
            case PARKED, CRUISE -> a;
            case IGNITION -> a.add(0, 1.2 * smooth((t - 45f) / 35f), 0);
            case ASCENT -> {
                double up = 0.5 * 0.0248 * t * t;
                double fwd = t > 50 ? 0.5 * 0.012 * (t - 50) * (t - 50) : 0;
                yield a.add(h.multiply(fwd)).add(0, up, 0);
            }
            case DESCENT -> {
                float e = MathHelper.clamp(t / DESCENT_TICKS, 0f, 1f);
                double u = (1 - e) * (1 - e);
                double g = dataTracker.get(GROUND);
                double back = dataTracker.get(GLIDE) ? APPROACH * Math.pow(1 - e, 2.4) : 0;
                double height = (a.y - g) * Math.pow(1 - e, 2.0);
                yield new Vec3d(a.x, g + height, a.z).subtract(h.multiply(back)).add(0, 0 * u, 0);
            }
            case TOUCHDOWN -> {
                double bounce = 0.12 * Math.exp(-t / 10.0) * Math.sin(t * 0.6);
                yield a.add(0, -Math.abs(bounce), 0);
            }
        };
    }

    /** Nose-up pitch (degrees) for the current moment. */
    public float pitchDeg(float t) {
        return switch (getPhase()) {
            case ASCENT -> 34f * smooth((t - 40f) / 70f);
            case DESCENT -> {
                float e = t / DESCENT_TICKS;
                yield dataTracker.get(GLIDE) ? 14f * smooth(e / 0.3f) * (1f - smooth((e - 0.7f) / 0.3f)) : 0f;
            }
            default -> 0f;
        };
    }

    /** 0 = legs down, 1 = folded. */
    public float legFold(float t) {
        return switch (getPhase()) {
            case PARKED, TOUCHDOWN -> 0f;
            case IGNITION -> 0f;
            case ASCENT -> smooth((t - 25f) / 40f);
            case CRUISE -> 1f;
            case DESCENT -> 1f - smooth((t / DESCENT_TICKS - 0.62f) / 0.22f);
        };
    }

    /** 1 = hatch down as a ramp, 0 = closed. */
    public float rampOpen(float t) {
        return switch (getPhase()) {
            case PARKED -> 1f;
            case IGNITION -> 1f - smooth(t / 30f);
            case TOUCHDOWN -> smooth((t - 15f) / 30f);
            default -> 0f;
        };
    }

    /** Engine output 0..1 (for plumes and shake). */
    public float thrust(float t) {
        return switch (getPhase()) {
            case IGNITION -> smooth((t - 25f) / 40f) * 0.7f;
            case ASCENT -> 0.7f + 0.3f * smooth(t / 60f);
            case CRUISE -> 1f;
            case DESCENT -> 0.35f + 0.65f * smooth((t / DESCENT_TICKS - 0.55f) / 0.3f);
            case TOUCHDOWN -> 0.5f * (1f - smooth(t / 25f));
            default -> 0f;
        };
    }

    /**
     * Ship-local point (model px, relative to the model origin) to world space for the current
     * pose: world = anchor-path + R_y(yaw) R_x(pitch) (p - pivot).
     */
    public Vec3d localToWorld(double px, double py, double pz, float pt) {
        float t = getPhaseTicks() + pt;
        Vec3d base = getLerpedPos(pt);
        double lx = px / 16.0, ly = (py - 48) / 16.0, lz = -pz / 16.0;
        double p = Math.toRadians(pitchDeg(t));
        double y1 = ly * Math.cos(p) - lz * Math.sin(p);
        double z1 = ly * Math.sin(p) + lz * Math.cos(p);
        Vec3d h = heading(getFacing());
        double b = Math.atan2(-h.x, -h.z);
        double x2 = lx * Math.cos(b) + z1 * Math.sin(b);
        double z2 = -lx * Math.sin(b) + z1 * Math.cos(b);
        return base.add(x2, 3.0 + y1, z2);
    }

    // ---- ticking -------------------------------------------------------------------------------

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
            Vec3d p = pathPos(clientTicks);
            setPosition(p);
            return;
        }
        ServerWorld world = (ServerWorld) getWorld();
        int t = dataTracker.get(TICKS) + 1;
        dataTracker.set(TICKS, t);
        mountPilot(world);
        switch (ph) {
            case PARKED -> {
                if (!placed) placeStructure(world);
                if (t % 40 == 0 && !placed) placeStructure(world);
            }
            case IGNITION -> {
                if (t == 1) {
                    world.playSound(null, getX(), getY() + 3, getZ(), ModSounds.SHIP_IGNITE, SoundCategory.BLOCKS, 8f, 1f);
                }
                if (t >= IGNITION_TICKS) {
                    setPosition(pathPos(t));
                    setPhase(Phase.ASCENT);
                    world.playSound(null, getX(), getY() + 3, getZ(), ModSounds.SHIP_LAUNCH, SoundCategory.BLOCKS, 20f, 1f);
                }
            }
            case ASCENT -> {
                if (t % 50 == 0) world.playSound(null, getX(), getY(), getZ(), ModSounds.SHIP_ENGINE, SoundCategory.BLOCKS, 12f, 1f);
                if (t >= ASCENT_TICKS) toSpace(world);
            }
            case CRUISE -> {
                if (t % 80 == 1) world.playSound(null, getX(), getY(), getZ(), ModSounds.SHIP_ENGINE, SoundCategory.BLOCKS, 4f, 0.7f);
                if (t >= CRUISE_TICKS) toDestination(world);
            }
            case DESCENT -> {
                if (t % 50 == 0) world.playSound(null, getX(), getY(), getZ(), ModSounds.SHIP_ENGINE, SoundCategory.BLOCKS, 10f, 0.85f);
                if (t >= DESCENT_TICKS) {
                    setPosition(pathPos(DESCENT_TICKS));
                    setPhase(Phase.TOUCHDOWN);
                    world.playSound(null, getX(), getY(), getZ(), ModSounds.SHIP_LAND, SoundCategory.BLOCKS, 10f, 1f);
                    RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 160, 6, 0.3, 0.25);
                }
            }
            case TOUCHDOWN -> {
                if (t >= TOUCHDOWN_TICKS) park(world);
            }
        }
        if (getPhase() != Phase.PARKED || t == 1) setPosition(pathPos(dataTracker.get(TICKS)));
    }

    private void clientFx(Phase ph, int t) {
        World w = getWorld();
        float th = thrust(t);
        if (th <= 0.02f) return;
        // engine exhaust and, near the ground, dust kicked up by the thrusters
        for (int s = -1; s <= 1; s += 2) {
            Vec3d noz = localToWorld(s * 78, 58, -162, 0f);
            Vec3d back = localToWorld(s * 78, 58, -200, 0f).subtract(noz).normalize();
            for (int i = 0; i < 2; i++) {
                w.addParticle(ModParticles.THRUSTER_FLAME, true, noz.x, noz.y, noz.z,
                        back.x * 0.6 + (random.nextDouble() - 0.5) * 0.1, back.y * 0.6, back.z * 0.6 + (random.nextDouble() - 0.5) * 0.1);
            }
        }
        double ground = getY() - groundBelow();
        if ((ph == Phase.IGNITION || ph == Phase.ASCENT || ph == Phase.DESCENT || ph == Phase.TOUCHDOWN) && ground < 30) {
            float k = (float) (1 - ground / 30) * th;
            for (int i = 0; i < 6 * k + 1; i++) {
                double a = random.nextDouble() * Math.PI * 2, r = 3 + random.nextDouble() * 9;
                double gy = getY() - ground + 0.2;
                w.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, getX() + Math.cos(a) * r, gy, getZ() + Math.sin(a) * r,
                        Math.cos(a) * 0.25 * k, 0.02, Math.sin(a) * 0.25 * k);
            }
        }
    }

    private double groundBelow() {
        BlockPos p = getBlockPos();
        for (int i = 0; i < 40; i++) {
            if (!getWorld().getBlockState(p.down(i)).isAir()) return i;
        }
        return 40;
    }

    // ---- pilot -----------------------------------------------------------------------------------

    @Nullable
    private ServerPlayerEntity pilotPlayer(MinecraftServer server) {
        return pilot == null ? null : server.getPlayerManager().getPlayer(pilot);
    }

    private void mountPilot(ServerWorld world) {
        if (pilot == null || getPhase() == Phase.PARKED) return;
        ServerPlayerEntity p = pilotPlayer(world.getServer());
        if (p == null || p.getWorld() != world || p.getVehicle() == this) return;
        if (p.squaredDistanceTo(this) > 256 * 256) return;
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
        Vec3d seat = localToWorld(0, 34, 0, 1f);   // mid-cabin: inside the hull whatever the pose
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

    // ---- launch / travel -------------------------------------------------------------------------

    /** Destinations reachable from the ship's current world. */
    public static List<Body> destinations(World world) {
        List<Body> out = new ArrayList<>();
        Body here = world.getRegistryKey() == World.OVERWORLD ? Body.EARTH : SpaceDims.planetOf(world.getRegistryKey());
        for (Body b : Body.ALL) {
            if (!b.landable() || b == here) continue;
            out.add(b);
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
        if (getWorld().getRegistryKey() == World.OVERWORLD) home = getBlockPos();
        removeStructure(world);
        dataTracker.set(DEST, dest.ordinal());
        pilot = player.getUuid();
        player.stopRiding();
        player.startRiding(this, true);
        setPhase(Phase.IGNITION);
        return true;
    }

    /** Top of the climb: hand over to the space dimension, above the world we left. */
    private void toSpace(ServerWorld world) {
        MinecraftServer server = world.getServer();
        ServerWorld space = server.getWorld(SpaceDims.SPACE);
        if (space == null) { park(world); return; }
        Body from = world.getRegistryKey() == World.OVERWORLD ? Body.EARTH : SpaceDims.planetOf(world.getRegistryKey());
        if (from == null) from = Body.EARTH;
        Vec3d pos = from.surfaceToSpace(getX(), getZ(), from.atmosphere + 500);
        transfer(world, space, pos, Phase.CRUISE, 0, false);
    }

    /** End of the cruise: drop into the destination's sky above the landing site. */
    private void toDestination(ServerWorld world) {
        MinecraftServer server = world.getServer();
        Body dest = getDestination();
        RegistryKey<World> key = dest.landDim();
        ServerWorld target = key == null ? null : server.getWorld(key);
        if (target == null) target = server.getOverworld();
        BlockPos site;
        if (dest == Body.EARTH) {
            site = home != null ? home : target.getSpawnPos();
        } else {
            site = new BlockPos((int) (random.nextGaussian() * 40), 0, (int) (random.nextGaussian() * 40));
        }
        target.getChunk(site.getX() >> 4, site.getZ() >> 4);
        int ground = groundAt(target, site);
        Vec3d pos = new Vec3d(site.getX() + 0.5, Math.min(target.getTopY() - 10, ground + DESCENT_HEIGHT), site.getZ() + 0.5);
        transfer(world, target, pos, Phase.DESCENT, ground, pilot != null);
    }

    /** Highest motion-blocking ground under the ship's footprint around {@code site}. */
    public static int groundAt(ServerWorld w, BlockPos site) {
        int g = w.getBottomY() + 1;
        for (int dx = -7; dx <= 7; dx += 7) {
            for (int dz = -7; dz <= 7; dz += 7) {
                int x = site.getX() + dx, z = site.getZ() + dz;
                w.getChunk(x >> 4, z >> 4);
                g = Math.max(g, w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        return g;
    }

    /** Moves the ship (and its pilot) to another world: the ship is re-created there. */
    private void transfer(ServerWorld from, ServerWorld to, Vec3d pos, Phase phase, int ground, boolean glide) {
        ServerPlayerEntity p = pilotPlayer(from.getServer());
        if (p != null && p.getVehicle() == this) p.stopRiding();
        unforce(from);
        ShipEntity n = new ShipEntity(RailgunMod.SHIP, to);
        n.setPosition(pos);
        n.pilot = pilot;
        n.home = home;
        n.setFacing(getFacing());
        n.dataTracker.set(DEST, dataTracker.get(DEST));
        n.dataTracker.set(GROUND, (float) ground);
        n.dataTracker.set(GLIDE, glide);
        n.setPhase(phase);
        Vec3d start = n.pathPos(0);
        n.setPosition(start);
        n.forceChunk(to);
        to.spawnEntity(n);
        discard();
        if (p != null) {
            PortalAPI.teleportEntity(p, to, start.add(0, 2, 0));
            p.fallDistance = 0;
        }
    }

    private void forceChunk(ServerWorld w) {
        BlockPos b = BlockPos.ofFloored(getPos());
        w.setChunkForced(b.getX() >> 4, b.getZ() >> 4, true);
        forced = b;
    }

    private void unforce(ServerWorld w) {
        if (forced != null) {
            w.setChunkForced(forced.getX() >> 4, forced.getZ() >> 4, false);
            forced = null;
        }
    }

    /** Settled: drop the pilot inside the cabin, place collision and the console. */
    private void park(ServerWorld world) {
        BlockPos b = BlockPos.ofFloored(getX(), Math.round(getY()), getZ());
        setPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
        setPhase(Phase.PARKED);
        unforce(world);
        placeStructure(world);
        ServerPlayerEntity p = pilotPlayer(world.getServer());
        if (p != null && p.getVehicle() == this) {
            p.stopRiding();
            Vec3d stand = cell(0, 2, -3);
            p.requestTeleport(stand.x, stand.y, stand.z);
            p.fallDistance = 0;
        }
        pilot = null;
    }

    /** Called by the starship item: the ship comes down from orbit to land at {@code ground}. */
    public void dropIn(ServerWorld world, BlockPos ground, int facing) {
        setFacing(facing);
        setPosition(ground.getX() + 0.5, ground.getY() + DROP_HEIGHT, ground.getZ() + 0.5);
        dataTracker.set(GROUND, (float) ground.getY());
        dataTracker.set(GLIDE, false);
        if (world.getRegistryKey() == World.OVERWORLD) home = ground;
        setPhase(Phase.DESCENT);
        forceChunk(world);
        world.playSound(null, ground.getX(), ground.getY() + 20, ground.getZ(), ModSounds.SHIP_ENGINE, SoundCategory.BLOCKS, 16f, 0.8f);
    }

    /** Stows the parked ship back into its item. */
    public boolean stow(ServerPlayerEntity player) {
        if (getPhase() != Phase.PARKED) return false;
        removeStructure((ServerWorld) getWorld());
        if (!player.isCreative()) player.getInventory().offerOrDrop(new ItemStack(RailgunMod.STARSHIP));
        discard();
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && getWorld() instanceof ServerWorld w) {
            removeStructure(w);
            unforce(w);
        }
        super.remove(reason);
    }

    // ---- walk-in structure -------------------------------------------------------------------

    /** World centre of the ship-local block cell (lx right, ly up, lz back). */
    public Vec3d cell(int lx, int ly, int lz) {
        BlockPos o = BlockPos.ofFloored(getX(), Math.round(getY()), getZ());
        BlockPos c = cellPos(o, getFacing(), lx, ly, lz);
        return new Vec3d(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    public static BlockPos cellPos(BlockPos origin, int facing, int lx, int ly, int lz) {
        int x, z;
        switch (facing & 3) {
            case 1 -> { x = -lz; z = lx; }      // nose east: right = south, back = west
            case 2 -> { x = -lx; z = -lz; }     // nose south
            case 3 -> { x = lz; z = -lx; }      // nose west
            default -> { x = lx; z = lz; }      // nose north: right = east, back = south
        }
        return origin.add(x, ly, z);
    }

    /** (lx, ly, lz, height px) collision cells of the parked ship. */
    private static List<int[]> layout() {
        List<int[]> out = new ArrayList<>();
        for (int lz = -6; lz <= 5; lz++) for (int lx = -3; lx <= 3; lx++) out.add(new int[]{lx, 1, lz, 16});   // floor
        for (int lz = -6; lz <= 5; lz++) for (int lx = -3; lx <= 3; lx++) out.add(new int[]{lx, 5, lz, 16});   // roof
        for (int ly = 2; ly <= 4; ly++) {
            for (int lz = -6; lz <= 5; lz++) {
                out.add(new int[]{-3, ly, lz, 16});
                out.add(new int[]{3, ly, lz, 16});
            }
            for (int lx = -2; lx <= 2; lx++) out.add(new int[]{lx, ly, -6, 16});        // front wall
            out.add(new int[]{-2, ly, 5, 16});                                          // back wall beside the door
            out.add(new int[]{2, ly, 5, 16});
        }
        for (int lx = -1; lx <= 1; lx++) {
            out.add(new int[]{lx, 4, 5, 16});        // lintel over the 3 x 2 door
            out.add(new int[]{lx, 1, 6, 8});         // ramp: 1.5
            out.add(new int[]{lx, 0, 7, 16});        //       1.0
            out.add(new int[]{lx, 0, 8, 8});         //       0.5
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

    /** The parked ship that owns a console at {@code pos}, if any. */
    @Nullable
    public static ShipEntity near(World w, BlockPos pos) {
        ShipEntity best = null;
        double bd = 30 * 30;
        for (ShipEntity s : w.getEntitiesByClass(ShipEntity.class, new net.minecraft.util.math.Box(pos).expand(24), e -> true)) {
            double d = s.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    // ---- persistence -------------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(PHASE, nbt.getInt("Phase"));
        dataTracker.set(TICKS, nbt.getInt("PhaseTicks"));
        dataTracker.set(FACING, nbt.getInt("Facing"));
        dataTracker.set(DEST, nbt.getInt("Dest"));
        dataTracker.set(AX, nbt.getFloat("AX"));
        dataTracker.set(AY, nbt.getFloat("AY"));
        dataTracker.set(AZ, nbt.getFloat("AZ"));
        dataTracker.set(GROUND, nbt.getFloat("Ground"));
        dataTracker.set(GLIDE, nbt.getBoolean("Glide"));
        placed = nbt.getBoolean("Placed");
        if (nbt.containsUuid("Pilot")) pilot = nbt.getUuid("Pilot");
        if (nbt.contains("Home")) home = BlockPos.fromLong(nbt.getLong("Home"));
        if (nbt.contains("Forced")) forced = BlockPos.fromLong(nbt.getLong("Forced"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Phase", dataTracker.get(PHASE));
        nbt.putInt("PhaseTicks", dataTracker.get(TICKS));
        nbt.putInt("Facing", getFacing());
        nbt.putInt("Dest", dataTracker.get(DEST));
        nbt.putFloat("AX", dataTracker.get(AX));
        nbt.putFloat("AY", dataTracker.get(AY));
        nbt.putFloat("AZ", dataTracker.get(AZ));
        nbt.putFloat("Ground", dataTracker.get(GROUND));
        nbt.putBoolean("Glide", dataTracker.get(GLIDE));
        nbt.putBoolean("Placed", placed);
        if (pilot != null) nbt.putUuid("Pilot", pilot);
        if (home != null) nbt.putLong("Home", home.asLong());
        if (forced != null) nbt.putLong("Forced", forced.asLong());
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
