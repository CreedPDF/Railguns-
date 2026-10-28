package com.creedpdf.orbitalrailgun.legion.entity;

import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.SwarmFormation;
import com.creedpdf.orbitalrailgun.legion.SwarmManager;
import com.creedpdf.orbitalrailgun.legion.SwarmPaths;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityGroup;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.potion.PotionUtil;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * One weapon of a player's swarm, flying on its own. It carries a copy of the bound {@link ItemStack} (its looks,
 * damage, enchantments, potion effects and on-hit behaviour all come from that stack) and runs a state machine:
 * <ul>
 *   <li>{@link SwarmState#ORBIT} - holds its place in the owner's formation ({@link SwarmPaths#idle}). Both sides
 *   evaluate the same function of time; the client evaluates it every frame from the owner's interpolated position
 *   and ignores position packets meanwhile, so the swarm stays glued to a walking or sprinting player. After a strike
 *   it glides back into place over {@link #SETTLE} ticks instead of snapping.</li>
 *   <li>{@link SwarmState#STRIKE} - dashes at {@link #STRIKE_SPEED} blocks per tick at a target, passes right through
 *   its bounding box (dealing the weapon's damage once on the way through), overshoots a few ticks and returns. A
 *   strike can be queued with a delay (focus volleys launch one blade after another).</li>
 *   <li>{@link SwarmState#DEFEND} - (shields and heavy weapons) flies into the path of a projectile aimed at its owner
 *   and knocks it away: arrows are batted back, fireballs and other shots are destroyed.</li>
 *   <li>{@link SwarmState#STORM} - a Blade Storm: whips round the owner in a widening vortex ({@link SwarmPaths#storm})
 *   cutting everything it passes, every {@link #STORM_HIT_EVERY} ticks.</li>
 * </ul>
 * Hits feed the owner's Fury; a kill is an Execution (a bigger burst, more Fury). The weapon is not stored in the
 * world: {@code SwarmManager} creates and removes these entities from the owner's {@link IWeaponSwarm} and writes wear
 * back to it.
 */
public class AutonomousWeaponEntity extends Entity {
    public static final double STRIKE_SPEED = 2.1, DEFEND_SPEED = 2.6;
    public static final int SETTLE = 10;
    private static final int PIERCE_TICKS = 4, STRIKE_TIMEOUT = 34, DEFEND_TIMEOUT = 24, STORM_HIT_EVERY = 3;
    private static final double STORM_REACH = 1.5;

    private static final TrackedData<ItemStack> STACK = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.ITEM_STACK);
    private static final TrackedData<Integer> STATE = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ORDER = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> SIZE = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FORMATION = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> STORM_T0 = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    // ---- both sides: the glide back into the formation after a strike
    private Vec3d settleFrom = null;
    private long settleAt;

    // ---- server only
    private int slot = -1;
    private int stateTicks;
    @Nullable private Entity target;
    @Nullable private Entity queued;
    private int queuedDelay;
    private Vec3d dash = Vec3d.ZERO;
    private boolean hit;
    private int pierce;
    private long readyAt;
    private int stormHits;
    @Nullable private PlayerEntity ownerCache;

    // ---- client only: the blade direction drawn last frame (smoothed)
    public Vec3d renderDir = new Vec3d(0, 1, 0);

    public AutonomousWeaponEntity(EntityType<? extends AutonomousWeaponEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
    }

    /** Bind this entity to a swarm slot (server). */
    public void setup(PlayerEntity owner, int slot, ItemStack stack) {
        this.slot = slot;
        this.ownerCache = owner;
        dataTracker.set(OWNER, Optional.of(owner.getUuid()));
        setStack(stack);
        setPosition(owner.getX(), owner.getEyeY(), owner.getZ());
        settleFrom = getPos();
        settleAt = owner.getWorld().getTime();
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(STACK, ItemStack.EMPTY);
        dataTracker.startTracking(STATE, SwarmState.ORBIT.ordinal());
        dataTracker.startTracking(ORDER, 0);
        dataTracker.startTracking(SIZE, 1);
        dataTracker.startTracking(FORMATION, 0);
        dataTracker.startTracking(STORM_T0, 0);
        dataTracker.startTracking(OWNER, Optional.empty());
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        // a new state on the client: whatever path comes next starts gliding from here
        if (STATE.equals(data) && getWorld() != null && getWorld().isClient) {
            settleFrom = getPos();
            settleAt = getWorld().getTime();
        }
    }

    // ------------------------------------------------------------------------------------------- accessors

    public ItemStack getStack() {
        return dataTracker.get(STACK);
    }

    public void setStack(ItemStack s) {
        dataTracker.set(STACK, s.copy());
    }

    public SwarmState state() {
        return SwarmState.byId(dataTracker.get(STATE));
    }

    private void setState(SwarmState s) {
        settleFrom = getPos();
        settleAt = getWorld().getTime();
        dataTracker.set(STATE, s.ordinal());
        stateTicks = 0;
    }

    public int slot() {
        return slot;
    }

    public int order() {
        return dataTracker.get(ORDER);
    }

    public int size() {
        return dataTracker.get(SIZE);
    }

    public SwarmFormation formation() {
        return SwarmFormation.byId(dataTracker.get(FORMATION));
    }

    public int stormStart() {
        return dataTracker.get(STORM_T0);
    }

    /** Its place in the formation, the number of weapons flying and the formation. */
    public void setOrder(int order, int size, SwarmFormation f) {
        if (dataTracker.get(ORDER) != order) dataTracker.set(ORDER, order);
        if (dataTracker.get(SIZE) != size) dataTracker.set(SIZE, size);
        if (dataTracker.get(FORMATION) != f.ordinal()) {
            settleFrom = getPos();
            settleAt = getWorld().getTime();
            dataTracker.set(FORMATION, f.ordinal());
        }
    }

    @Nullable
    public UUID ownerUuid() {
        return dataTracker.get(OWNER).orElse(null);
    }

    @Nullable
    public PlayerEntity owner() {
        UUID u = ownerUuid();
        if (u == null) return null;
        if (ownerCache != null && !ownerCache.isRemoved() && ownerCache.getUuid().equals(u) && ownerCache.getWorld() == getWorld()) return ownerCache;
        ownerCache = getWorld().getPlayerByUuid(u);
        return ownerCache;
    }

    /** Ready to be sent out: in formation, nothing queued, recovered from its last strike or parry. */
    public boolean ready() {
        return state() == SwarmState.ORBIT && queued == null && getWorld().getTime() >= readyAt;
    }

    // ------------------------------------------------------------------------------------------- the paths

    /**
     * Where this weapon belongs at time {@code time} (fractional ticks on the client), with the owner at
     * {@code feet} facing {@code bodyYaw} / {@code headYaw}: the formation or storm point, blended in from where it
     * last left a straight-line path so changes are glides, never jumps.
     */
    public SwarmPaths.Pose pathPose(PlayerEntity owner, Vec3d feet, float bodyYaw, float headYaw, double time) {
        SwarmPaths.Pose p = state() == SwarmState.STORM
                ? SwarmPaths.storm(feet, owner.getHeight(), order(), size(), time - stormStart(), getId())
                : SwarmPaths.idle(formation(), feet, owner.getHeight(), bodyYaw, headYaw, order(), size(), time, getId());
        if (settleFrom == null) return p;
        double k = MathHelper.clamp((time - settleAt) / SETTLE, 0, 1);
        if (k >= 1) {
            settleFrom = null;
            return p;
        }
        k = k * k * (3 - 2 * k);
        return new SwarmPaths.Pose(settleFrom.lerp(p.pos(), k), p.aim());
    }

    // ------------------------------------------------------------------------------------------- orders (server)

    /** Send this weapon through {@code t} after {@code delay} ticks. Returns false if it is busy. */
    public boolean strike(Entity t, int delay) {
        if (!ready() || !SwarmCapability.canStrike(getStack())) return false;
        if (delay > 0) {
            queued = t;
            queuedDelay = delay;
            return true;
        }
        launch(t);
        return true;
    }

    private void launch(Entity t) {
        target = t;
        hit = false;
        pierce = 0;
        dash = t.getBoundingBox().getCenter().subtract(getPos()).normalize();
        setState(SwarmState.STRIKE);
        getWorld().playSound(null, getX(), getY(), getZ(), ArsenalLegion.WEAPON_LAUNCH, SoundCategory.PLAYERS, 1.1f, 0.9f + random.nextFloat() * 0.2f);
    }

    /** Send this weapon into the path of a projectile. Returns false if it is busy. */
    public boolean defend(Entity projectile) {
        if (!ready() || !SwarmCapability.canDefend(getStack())) return false;
        target = projectile;
        setState(SwarmState.DEFEND);
        getWorld().playSound(null, getX(), getY(), getZ(), ArsenalLegion.WEAPON_LAUNCH, SoundCategory.PLAYERS, 0.8f, 1.3f);
        return true;
    }

    /** Join a Blade Storm that started at world time {@code t0}. */
    public void storm(int t0) {
        queued = null;
        target = null;
        stormHits = 0;
        dataTracker.set(STORM_T0, t0);
        setState(SwarmState.STORM);
    }

    private void backToOrbit(int recover) {
        target = null;
        readyAt = getWorld().getTime() + recover;
        setState(SwarmState.ORBIT);
    }

    // ------------------------------------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        World w = getWorld();
        PlayerEntity owner = owner();
        if (w.isClient) {
            if (state().analytic() && owner != null) {
                Vec3d p = pathPose(owner, owner.getPos(), owner.getBodyYaw(), owner.getHeadYaw(), w.getTime()).pos();
                setVelocity(p.subtract(getPos()));
                setPosition(p);
            }
            com.creedpdf.orbitalrailgun.RailgunMod.client().legionTrail(this);
            return;
        }
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.squaredDistanceTo(this) > 64 * 64) {
            discard();
            return;
        }
        stateTicks++;
        switch (state()) {
            case ORBIT -> {
                follow(owner);
                if (queued != null && --queuedDelay <= 0) {
                    Entity q = queued;
                    queued = null;
                    if (q.isAlive() && q.getWorld() == w) launch(q);
                }
            }
            case STRIKE -> tickStrike((ServerWorld) w, owner);
            case DEFEND -> tickDefend((ServerWorld) w, owner);
            case STORM -> tickStorm((ServerWorld) w, owner);
        }
    }

    private void follow(PlayerEntity owner) {
        Vec3d p = pathPose(owner, owner.getPos(), owner.getBodyYaw(), owner.getHeadYaw(), getWorld().getTime()).pos();
        setVelocity(p.subtract(getPos()));
        setPosition(p);
    }

    /**
     * Client: positions from the server are only for the straight-line states; formation and storm paths are
     * computed here every frame, so a late packet must not tug the blade off its path.
     */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps, boolean interpolate) {
        if (getWorld().isClient && state().analytic()) return;
        super.updateTrackedPositionAndAngles(x, y, z, yaw, pitch, steps, interpolate);
    }

    private void tickStrike(ServerWorld w, PlayerEntity owner) {
        if (!hit) {
            if (target == null || !target.isAlive() || target.getWorld() != w || stateTicks > STRIKE_TIMEOUT) {
                backToOrbit(10);
                return;
            }
            dash = target.getBoundingBox().getCenter().subtract(getPos()).normalize();
            Vec3d step = dash.multiply(STRIKE_SPEED);
            Box tb = target.getBoundingBox().expand(0.35);
            if (tb.contains(getPos()) || tb.raycast(getPos(), getPos().add(step)).isPresent()) {
                pierce(w, owner, target, 1f, true);
                hit = true;
                pierce = PIERCE_TICKS;
            }
            setVelocity(step);
            setPosition(getPos().add(step));
        } else {
            Vec3d step = dash.multiply(STRIKE_SPEED * 0.8);
            setVelocity(step);
            setPosition(getPos().add(step));
            if (--pierce <= 0) backToOrbit(14);
        }
    }

    private void tickDefend(ServerWorld w, PlayerEntity owner) {
        if (target == null || !target.isAlive() || target.getWorld() != w || stateTicks > DEFEND_TIMEOUT) {
            backToOrbit(8);
            return;
        }
        Vec3d aim = target.getPos().add(target.getVelocity());
        Vec3d d = aim.subtract(getPos());
        if (d.length() < 1.4 || getBoundingBox().expand(0.5).intersects(target.getBoundingBox())) {
            parry(w, owner, target);
            backToOrbit(SwarmCapability.isShield(getStack()) ? 6 : 12);
            return;
        }
        Vec3d step = d.length() > DEFEND_SPEED ? d.normalize().multiply(DEFEND_SPEED) : d;
        setVelocity(step);
        setPosition(getPos().add(step));
    }

    private void tickStorm(ServerWorld w, PlayerEntity owner) {
        double t = w.getTime() - stormStart();
        if (t >= SwarmPaths.STORM_TICKS) {
            backToOrbit(20);
            return;
        }
        Vec3d before = getPos();
        follow(owner);
        dash = getPos().subtract(before);
        if (dash.lengthSquared() > 1e-6) dash = dash.normalize();
        if (stateTicks % STORM_HIT_EVERY != 0 || !SwarmCapability.canStrike(getStack())) return;
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(STORM_REACH), LivingEntity::isAlive)) {
            if (e == owner || (e instanceof TameableEntity tame && owner.getUuid().equals(tame.getOwnerUuid()))) continue;
            if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) continue;
            pierce(w, owner, e, 0.6f, ++stormHits % 4 == 0);
        }
    }

    // ------------------------------------------------------------------------------------------- combat

    /**
     * The weapon passes through {@code t}: its damage (times {@code scale}), enchantments, potion effects and on-hit
     * behaviour apply. Feeds Fury; a kill is an Execution.
     */
    private void pierce(ServerWorld w, PlayerEntity owner, Entity t, float scale, boolean wears) {
        ItemStack stack = getStack();
        EntityGroup group = t instanceof LivingEntity le ? le.getGroup() : EntityGroup.DEFAULT;
        float damage = (SwarmCapability.baseDamage(stack) + EnchantmentHelper.getAttackDamage(stack, group)) * scale;
        DamageSource src = w.getDamageSources().playerAttack(owner);
        t.timeUntilRegen = 0;   // a swarm hits from many blades at once: one blade's i-frames must not swallow the next
        if (!t.damage(src, damage)) return;
        Vec3d at = t.getBoundingBox().getCenter();
        w.spawnParticles(ArsenalLegion.KINETIC_GAZE, getX(), getY(), getZ(), 0, dash.x, dash.y, dash.z, 2.5);
        w.playSound(null, t.getX(), t.getBodyY(0.5), t.getZ(), ArsenalLegion.WEAPON_PIERCE, SoundCategory.PLAYERS, 1f, 0.9f + random.nextFloat() * 0.2f);
        boolean killed = false;
        if (t instanceof LivingEntity le) {
            int fire = EnchantmentHelper.getLevel(Enchantments.FIRE_ASPECT, stack);
            if (fire > 0) le.setOnFireFor(4 * fire);
            int kb = EnchantmentHelper.getLevel(Enchantments.KNOCKBACK, stack);
            le.takeKnockback(0.35 + kb * 0.5, -dash.x, -dash.z);
            for (StatusEffectInstance e : PotionUtil.getPotionEffects(stack)) le.addStatusEffect(new StatusEffectInstance(e), owner);
            if (!(stack.getItem() instanceof SwordItem || stack.getItem() instanceof MiningToolItem || stack.getItem() instanceof TridentItem))
                stack.getItem().postHit(stack.copy(), le, owner);
            killed = !le.isAlive();
        }
        if (killed) {
            w.playSound(null, at.x, at.y, at.z, ArsenalLegion.EXECUTION, SoundCategory.PLAYERS, 1.4f, 0.9f + random.nextFloat() * 0.2f);
            LegionPackets.fx(w, LegionPackets.FX_EXECUTE, at, dash, getId());
            SwarmManager.addFury(owner, 12);
        } else {
            LegionPackets.fx(w, LegionPackets.FX_PIERCE, at, dash, getId());
            SwarmManager.addFury(owner, state() == SwarmState.STORM ? 1 : 5);
        }
        if (wears) wear(owner, stack.getItem() instanceof AxeItem ? 2 : 1);
    }

    /** Knock a projectile aside: arrows are batted back toward where they came from, everything else is destroyed. */
    private void parry(ServerWorld w, PlayerEntity owner, Entity proj) {
        Vec3d p = proj.getPos();
        if (proj instanceof PersistentProjectileEntity arrow) {
            arrow.setVelocity(arrow.getVelocity().multiply(-0.45).add(0, 0.12, 0));
            arrow.setOwner(owner);
        } else {
            if (proj instanceof ExplosiveProjectileEntity) w.spawnParticles(ArsenalLegion.KINETIC_GAZE, p.x, p.y, p.z, 12, 0.4, 0.4, 0.4, 0.4);
            proj.discard();
        }
        w.playSound(null, p.x, p.y, p.z, ArsenalLegion.WEAPON_PARRY, SoundCategory.PLAYERS, 1.2f, 0.95f + random.nextFloat() * 0.1f);
        LegionPackets.fx(w, LegionPackets.FX_PARRY, p, proj.getVelocity().normalize(), getId());
        SwarmManager.addFury(owner, 6);
        wear(owner, SwarmCapability.isShield(getStack()) ? 1 : 2);
    }

    /** Wear the bound weapon (unbreaking applies); a weapon that breaks leaves the swarm for good. */
    private void wear(PlayerEntity owner, int amount) {
        ItemStack stack = getStack().copy();
        if (!stack.isDamageable()) return;
        boolean broke = stack.damage(amount, random, owner instanceof ServerPlayerEntity sp ? sp : null);
        IWeaponSwarm swarm = SwarmCapability.of(owner);
        if (broke) {
            swarm.set(slot, ItemStack.EMPTY);
            getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
            discard();
        } else {
            swarm.set(slot, stack);
            setStack(stack);
        }
    }

    // ------------------------------------------------------------------------------------------- entity plumbing

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 96 * 96;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }
}
