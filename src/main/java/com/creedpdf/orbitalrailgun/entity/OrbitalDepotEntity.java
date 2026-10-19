package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ARES-9 orbital depot. Called down from orbital storage, it lands, lifts its hood and spins up a
 * four-sided cabinet holding every attachment on its shelves. The owner right-clicks parts to
 * select them (one muzzle, one ammunition, up to four modules), then presses SEND on the console
 * at the front: the parts launch to the orbital railgun (a docking cutscene plays) and the depot
 * closes and returns to orbit.
 *
 * <p>Shelf layout (model px, mirrored from tools/blender/build_models.py depot_slot): face 0 is
 * model -Z (world south, where the console is), then +X, +Z, -X; on each face three tiers top to
 * bottom and two columns; slot = face * 6 + tier * 2 + column, in {@link Loadout.Option} order.</p>
 */
public class OrbitalDepotEntity extends Entity implements GeoEntity {
    public enum DState { FALLING, LANDED, OPENING, OPEN, SENDING, CLOSING, LAUNCHING }

    public static final int FALL_TICKS = 70, SETTLE_TICKS = 16, OPEN_TICKS = 72, SEND_TICKS = 60,
            CLOSE_TICKS = 72, LAUNCH_TICKS = 100, IDLE_TIMEOUT = 6000;
    public static final double DROP_HEIGHT = 240.0;
    /** Cabinet lift once open (px), shelf plate tops (px, top tier first). */
    public static final float CAB_LIFT = 4f;
    public static final float[] TIERS = {62f, 40f, 18f};

    private static final TrackedData<Integer> STATE = DataTracker.registerData(OrbitalDepotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> DRAFT = DataTracker.registerData(OrbitalDepotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> SENT = DataTracker.registerData(OrbitalDepotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(OrbitalDepotEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int stateTicks;
    private double landY = Double.NaN;
    /** Server: called once the parts have left (sends the attach cutscene / updates the rig). */
    @Nullable private Consumer<OrbitalDepotEntity> onSent;

    public OrbitalDepotEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = true;
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(STATE, DState.FALLING.ordinal());
        dataTracker.startTracking(DRAFT, 0);
        dataTracker.startTracking(SENT, 0);
        dataTracker.startTracking(OWNER, Optional.empty());
    }

    // ---- accessors ----------------------------------------------------------------------------------

    public DState getDState() {
        int i = dataTracker.get(STATE);
        return DState.values()[MathHelper.clamp(i, 0, DState.values().length - 1)];
    }

    public void setDState(DState s) {
        dataTracker.set(STATE, s.ordinal());
        stateTicks = 0;
    }

    public int getStateTicks() { return stateTicks; }
    public Loadout getDraft() { return Loadout.of(dataTracker.get(DRAFT)); }
    public void setDraft(Loadout l) { dataTracker.set(DRAFT, l.raw()); }
    public Loadout getSent() { return Loadout.of(dataTracker.get(SENT)); }
    @Nullable public UUID getOwnerUuid() { return dataTracker.get(OWNER).orElse(null); }
    public void setOwnerUuid(UUID id) { dataTracker.set(OWNER, Optional.ofNullable(id)); }
    public void setLandY(double y) { this.landY = y; }

    public boolean isOwner(PlayerEntity p) {
        UUID o = getOwnerUuid();
        return o == null || o.equals(p.getUuid());
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STATE.equals(data) && getWorld().isClient) stateTicks = 0;
    }

    public static OrbitalDepotEntity findOwned(MinecraftServer server, UUID owner) {
        for (ServerWorld w : server.getWorlds()) {
            List<? extends OrbitalDepotEntity> l = w.getEntitiesByType(RailgunMod.ORBITAL_DEPOT, e -> e.isAlive() && owner.equals(e.getOwnerUuid()));
            if (!l.isEmpty()) return l.get(0);
        }
        return null;
    }

    // ---- tick -------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        int t = stateTicks++;
        DState s = getDState();
        if (getWorld().isClient) {
            clientTick(s, t);
            return;
        }
        ServerWorld w = (ServerWorld) getWorld();
        switch (s) {
            case FALLING -> {
                if (Double.isNaN(landY)) landY = getY() - DROP_HEIGHT;
                double f = Math.min(1.0, t / (double) FALL_TICKS);
                double y = landY + DROP_HEIGHT * Math.pow(1 - f, 2.3);
                setPosition(getX(), y, getZ());
                if (t == 0) w.playSound(null, getX(), landY + 40, getZ(), ModSounds.DRONE_DESCENT, SoundCategory.BLOCKS, 8f, 0.8f);
                if (t == FALL_TICKS - 22) w.playSound(null, getX(), landY + 10, getZ(), ModSounds.DRONE_RETRO, SoundCategory.BLOCKS, 5f, 0.85f);
                if (t >= FALL_TICKS) land(w);
            }
            case LANDED -> { if (t >= SETTLE_TICKS) { setDState(DState.OPENING); w.playSound(null, getX(), getY() + 2, getZ(), ModSounds.DEPOT_OPEN, SoundCategory.BLOCKS, 3f, 1f); } }
            case OPENING -> { if (t >= OPEN_TICKS) setDState(DState.OPEN); }
            case OPEN -> { if (t >= IDLE_TIMEOUT) close(w); }
            case SENDING -> {
                if (t == 4) w.playSound(null, getX(), getY() + 3, getZ(), ModSounds.DEPOT_SEND, SoundCategory.BLOCKS, 4f, 1f);
                if (t == 30 && onSent != null) { onSent.accept(this); onSent = null; }
                if (t >= SEND_TICKS) close(w);
            }
            case CLOSING -> {
                if (t >= CLOSE_TICKS) {
                    setDState(DState.LAUNCHING);
                    w.playSound(null, getX(), getY(), getZ(), ModSounds.DEPOT_LAUNCH, SoundCategory.BLOCKS, 8f, 1f);
                }
            }
            case LAUNCHING -> {
                double f = t / (double) LAUNCH_TICKS;
                if (t > 12) setPosition(getX(), landY + DROP_HEIGHT * 1.4 * Math.pow((t - 12) / (double) (LAUNCH_TICKS - 12), 2.4), getZ());
                if (t == 2) RailgunFx.burst(w, ModParticles.THRUSTER_SHOCK, getPos(), 3, 0.2, 0.1, 0);
                if (f >= 1) discard();
            }
        }
    }

    private void close(ServerWorld w) {
        setDState(DState.CLOSING);
        w.playSound(null, getX(), getY() + 2, getZ(), ModSounds.DEPOT_OPEN, SoundCategory.BLOCKS, 3f, 0.85f);
    }

    private void land(ServerWorld w) {
        setPosition(getX(), landY, getZ());
        // clear the footprint (3x3, 6 high) of anything breakable
        BlockPos base = BlockPos.ofFloored(getX(), landY, getZ());
        for (BlockPos p : BlockPos.iterate(base.add(-1, 0, -1), base.add(1, 6, 1))) {
            BlockState st = w.getBlockState(p);
            if (!st.isAir() && st.getHardness(w, p) >= 0 && st.getHardness(w, p) < 50) {
                w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
            }
        }
        w.playSound(null, getX(), landY, getZ(), ModSounds.DEPOT_LAND, SoundCategory.BLOCKS, 6f, 1f);
        RailgunFx.burst(w, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 60, 2.5, 0.3, 0.25);
        RailgunFx.burst(w, ModParticles.DEBRIS, getPos().add(0, 0.5, 0), 30, 1.5, 0.3, 0.3);
        RailgunFx.burst(w, ModParticles.THRUSTER_SHOCK, getPos().add(0, 0.1, 0), 2, 0.1, 0.05, 0);
        setDState(DState.LANDED);
    }

    private void clientTick(DState s, int t) {
        World w = getWorld();
        var r = this.random;
        if (s == DState.FALLING) {
            double f = Math.min(1.0, t / (double) FALL_TICKS);
            for (int i = 0; i < 4; i++) {
                w.addParticle(ModParticles.DRONE_TRAIL, getX() + (r.nextDouble() - 0.5), getY() + 7 + r.nextDouble() * 6,
                        getZ() + (r.nextDouble() - 0.5), 0, 0.2, 0);
            }
            if (f > 0.62) {
                for (int k = 0; k < 4; k++) {
                    double sx = (k & 1) == 0 ? 0.7 : -0.7, sz = (k & 2) == 0 ? 0.7 : -0.7;
                    w.addParticle(ModParticles.DRONE_JET, getX() + sx, getY() - 0.2, getZ() + sz,
                            (r.nextDouble() - 0.5) * 0.1, -1.2 - r.nextDouble() * 0.6, (r.nextDouble() - 0.5) * 0.1);
                }
            }
        } else if (s == DState.LAUNCHING && t > 4) {
            for (int k = 0; k < 4; k++) {
                double sx = (k & 1) == 0 ? 0.7 : -0.7, sz = (k & 2) == 0 ? 0.7 : -0.7;
                w.addParticle(ModParticles.DRONE_JET, getX() + sx, getY() - 0.2, getZ() + sz,
                        (r.nextDouble() - 0.5) * 0.1, -1.4 - r.nextDouble() * 0.6, (r.nextDouble() - 0.5) * 0.1);
            }
            if (t > 20) w.addParticle(ModParticles.DRONE_TRAIL, getX(), getY() - 2, getZ(), 0, -0.1, 0);
        } else if (s == DState.SENDING) {
            Loadout sent = getSent();
            for (Loadout.Option o : Loadout.Option.values()) {
                if (!sent.has(o)) continue;
                float lt = t - 6;
                if (lt < 0 || lt > 50) continue;
                Vec3d p = slotWorld(o.ordinal(), 0, lift(lt), 0);
                w.addParticle(ModParticles.DRONE_JET, p.x, p.y - 0.3, p.z, (r.nextDouble() - 0.5) * 0.05, -0.5, (r.nextDouble() - 0.5) * 0.05);
            }
        }
    }

    /** Height (px) a sent part has risen at local time lt (0..50 ticks). */
    public static float lift(float lt) {
        return lt <= 0 ? 0 : 0.7f * lt * lt;
    }

    // ---- shelves ------------------------------------------------------------------------------------

    /** Slot centre in model px (cabinet rotation is 0 once open). */
    public static float[] slotCenter(int i) {
        int face = i / 6, rem = i % 6, tier = rem / 2, col = rem % 2;
        float u = col == 0 ? -9f : 9f;
        float y = TIERS[tier] + 9f;
        float d = 13f;
        return switch (face) {
            case 0 -> new float[]{u, y, -d};
            case 1 -> new float[]{d, y, u};
            case 2 -> new float[]{-u, y, d};
            default -> new float[]{-d, y, -u};
        };
    }

    /** Outward unit vector of a slot's face (model px axes). */
    public static float[] slotOut(int i) {
        return switch (i / 6) {
            case 0 -> new float[]{0, 0, -1};
            case 1 -> new float[]{1, 0, 0};
            case 2 -> new float[]{0, 0, 1};
            default -> new float[]{-1, 0, 0};
        };
    }

    public Vec3d slotWorld(int i, float ox, float oy, float oz) {
        float[] c = slotCenter(i);
        return getPos().add((c[0] + ox) / 16.0, (c[1] + CAB_LIFT + oy) / 16.0, -(c[2] + oz) / 16.0);
    }

    /** Which shelf slot (or -1) a hit point (relative to the entity, world axes) lands on. */
    public static int slotAt(Vec3d hit) {
        double mx = hit.x * 16, my = hit.y * 16 - CAB_LIFT, mz = -hit.z * 16;
        int face;
        double u;
        if (Math.abs(mz) >= Math.abs(mx)) { face = mz < 0 ? 0 : 2; u = mz < 0 ? mx : -mx; }
        else { face = mx > 0 ? 1 : 3; u = mx > 0 ? mz : -mz; }
        int tier = -1;
        for (int k = 0; k < TIERS.length; k++) if (my >= TIERS[k] - 1 && my < TIERS[k] + 20) tier = k;
        if (tier < 0) return -1;
        int col = u < 0 ? 0 : 1;
        int slot = face * 6 + tier * 2 + col;
        return slot < Loadout.Option.values().length ? slot : -1;
    }

    /** The front console (model -Z face, plinth). */
    public static boolean consoleAt(Vec3d hit) {
        double mx = hit.x * 16, my = hit.y * 16, mz = -hit.z * 16;
        return my < 16 && mz < -14 && Math.abs(mx) < 16;
    }

    @Override
    public ActionResult interactAt(PlayerEntity player, Vec3d hitPos, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (getDState() != DState.OPEN) return ActionResult.PASS;
        if (!isOwner(player)) {
            if (!getWorld().isClient) player.sendMessage(Text.translatable("message.orbital_railgun.depot.not_yours").formatted(Formatting.RED), true);
            return ActionResult.SUCCESS;
        }
        if (consoleAt(hitPos)) {
            if (getWorld().isClient) RailgunMod.client().openDepotPanel(this);
            return ActionResult.SUCCESS;
        }
        int slot = slotAt(hitPos);
        if (slot < 0) return ActionResult.PASS;
        if (!getWorld().isClient) {
            Loadout.Option o = Loadout.Option.values()[slot];
            setDraft(getDraft().toggle(o));
            getWorld().playSound(null, getX(), getY() + 3, getZ(), ModSounds.DEPOT_SELECT, SoundCategory.BLOCKS, 1.2f,
                    getDraft().has(o) ? 1.15f : 0.85f);
            player.sendMessage(Text.literal((getDraft().has(o) ? "+ " : "- ") + o.title).formatted(getDraft().has(o) ? Formatting.AQUA : Formatting.GRAY), true);
        }
        return ActionResult.SUCCESS;
    }

    /** Server: SEND pressed on the console. */
    public boolean send(PlayerEntity player, Consumer<OrbitalDepotEntity> whenLaunched) {
        if (getDState() != DState.OPEN || !isOwner(player)) return false;
        if (player.squaredDistanceTo(this) > 12 * 12) return false;
        dataTracker.set(SENT, getDraft().raw());
        this.onSent = whenLaunched;
        setDState(DState.SENDING);
        return true;
    }

    /** Server: recall from the tablet. */
    public void recall() {
        DState s = getDState();
        if (s == DState.OPEN || s == DState.OPENING) close((ServerWorld) getWorld());
    }

    @Override
    public boolean isCollidable() { return getDState() != DState.FALLING && getDState() != DState.LAUNCHING; }

    @Override
    public boolean canHit() { return !isRemoved(); }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean shouldRender(double distance) { return distance < 512 * 512; }

    @Override
    public Box getVisibilityBoundingBox() {
        return getBoundingBox().expand(2, 12, 2).stretch(0, getDState() == DState.SENDING ? 20 : 0, 0);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(STATE, nbt.getInt("State"));
        stateTicks = nbt.getInt("StateTicks");
        dataTracker.set(DRAFT, nbt.getInt("Draft"));
        landY = nbt.contains("LandY") ? nbt.getDouble("LandY") : Double.NaN;
        if (nbt.containsUuid("Owner")) setOwnerUuid(nbt.getUuid("Owner"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("State", dataTracker.get(STATE));
        nbt.putInt("StateTicks", stateTicks);
        nbt.putInt("Draft", dataTracker.get(DRAFT));
        if (!Double.isNaN(landY)) nbt.putDouble("LandY", landY);
        UUID o = getOwnerUuid();
        if (o != null) nbt.putUuid("Owner", o);
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
