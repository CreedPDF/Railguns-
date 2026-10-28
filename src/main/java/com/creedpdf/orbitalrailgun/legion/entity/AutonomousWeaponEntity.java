package com.creedpdf.orbitalrailgun.legion.entity;

import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
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
 * One weapon of a player's swarm, flying on its own. It carries a copy of the bound {@link ItemStack} (its looks, its
 * damage, enchantments, potion effects and on-hit behaviour all come from that stack) and runs a three-state machine:
 * <ul>
 *   <li>{@link SwarmState#ORBIT} - circles its owner on a trigonometric path: angle = time x speed + its share of the
 *   circle (so the swarm spreads evenly), a radius that grows with the swarm, a bob whose phase comes from the entity
 *   id (so weapons never move in lockstep), and exponential damping toward that point (so a weapon coming back from a
 *   strike eases into its place instead of snapping). The client runs the same maths itself, so orbits are perfectly
 *   smooth whatever the network does.</li>
 *   <li>{@link SwarmState#STRIKE} - dashes at {@link #STRIKE_SPEED} blocks per tick at a target, passes right through
 *   its bounding box (dealing the weapon's damage once on the way through), carries on for a few ticks and returns.</li>
 *   <li>{@link SwarmState#DEFEND} - (shields and heavy weapons only) flies into the path of a projectile aimed at its
 *   owner and knocks it away: arrows are batted back, fireballs and other shots are destroyed.</li>
 * </ul>
 * The weapon is not stored in the world: {@code SwarmManager} creates and removes these entities from the owner's
 * {@link IWeaponSwarm} and writes wear back to it.
 */
public class AutonomousWeaponEntity extends Entity {
    public static final double STRIKE_SPEED = 1.9, DEFEND_SPEED = 2.4;
    /** How far a weapon keeps flying after it passes through its target, in ticks. */
    private static final int PIERCE_TICKS = 4, STRIKE_TIMEOUT = 34, DEFEND_TIMEOUT = 24;

    private static final TrackedData<ItemStack> STACK = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.ITEM_STACK);
    private static final TrackedData<Integer> STATE = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ORDER = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> SIZE = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(AutonomousWeaponEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    // ---- server only
    private int slot = -1;
    private int stateTicks;
    @Nullable private Entity target;
    private Vec3d dash = Vec3d.ZERO;
    private boolean hit;
    private int pierce;
    private long readyAt;
    @Nullable private PlayerEntity ownerCache;

    // ---- client only: last flight direction (for the renderer while nearly still)
    public Vec3d lastDir = new Vec3d(0, 1, 0);

    public AutonomousWeaponEntity(EntityType<? extends AutonomousWeaponEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.ignoreCameraFrustum = false;
    }

    /** Bind this entity to a swarm slot (server). */
    public void setup(PlayerEntity owner, int slot, ItemStack stack) {
        this.slot = slot;
        this.ownerCache = owner;
        dataTracker.set(OWNER, Optional.of(owner.getUuid()));
        setStack(stack);
        setPosition(owner.getX(), owner.getEyeY(), owner.getZ());
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(STACK, ItemStack.EMPTY);
        dataTracker.startTracking(STATE, SwarmState.ORBIT.ordinal());
        dataTracker.startTracking(ORDER, 0);
        dataTracker.startTracking(SIZE, 1);
        dataTracker.startTracking(OWNER, Optional.empty());
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
        dataTracker.set(STATE, s.ordinal());
        stateTicks = 0;
    }

    public int slot() {
        return slot;
    }

    /** Its place in the circle and the number of weapons flying. */
    public void setOrder(int order, int size) {
        if (dataTracker.get(ORDER) != order) dataTracker.set(ORDER, order);
        if (dataTracker.get(SIZE) != size) dataTracker.set(SIZE, size);
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

    /** Ready to be sent out: orbiting, and recovered from its last strike or parry. */
    public boolean ready() {
        return state() == SwarmState.ORBIT && getWorld().getTime() >= readyAt;
    }

    // ------------------------------------------------------------------------------------------- orders (server)

    /** Send this weapon through {@code t}. Returns false if it is busy. */
    public boolean strike(Entity t) {
        if (!ready() || !SwarmCapability.canStrike(getStack())) return false;
        target = t;
        hit = false;
        pierce = 0;
        dash = t.getBoundingBox().getCenter().subtract(getPos()).normalize();
        setState(SwarmState.STRIKE);
        getWorld().playSound(null, getX(), getY(), getZ(), ArsenalLegion.WEAPON_LAUNCH, SoundCategory.PLAYERS, 1.1f, 0.9f + random.nextFloat() * 0.2f);
        return true;
    }

    /** Send this weapon into the path of a projectile. Returns false if it is busy. */
    public boolean defend(Entity projectile) {
        if (!ready() || !SwarmCapability.canDefend(getStack())) return false;
        target = projectile;
        setState(SwarmState.DEFEND);
        getWorld().playSound(null, getX(), getY(), getZ(), ArsenalLegion.WEAPON_LAUNCH, SoundCategory.PLAYERS, 0.8f, 1.3f);
        return true;
    }

    private void backToOrbit(int recover) {
        target = null;
        readyAt = getWorld().getTime() + recover;
        setState(SwarmState.ORBIT);
    }

    // ------------------------------------------------------------------------------------------- the orbit

    /**
     * Where a weapon of the swarm belongs at this moment: on a circle round the owner's chest. Pure function of time,
     * place in the circle and entity id, shared by server and client.
     */
    public static Vec3d orbitPoint(PlayerEntity owner, int order, int size, double time, int id) {
        size = Math.max(1, size);
        double ang = time * 0.055 + order * MathHelper.TAU / size;
        double r = 1.5 + 0.17 * size;
        double bob = 0.22 * Math.sin(time * 0.09 + id * 1.7) + 0.08 * Math.sin(time * 0.031 + id);
        return owner.getPos().add(Math.cos(ang) * r, owner.getHeight() * 0.62 + bob, Math.sin(ang) * r);
    }

    /** Ease toward the orbit point: gentle when close, quicker from far away (a weapon returning from a strike). */
    private void flyOrbit(PlayerEntity owner) {
        Vec3d want = orbitPoint(owner, dataTracker.get(ORDER), dataTracker.get(SIZE), getWorld().getTime(), getId());
        Vec3d d = want.subtract(getPos());
        double dist = d.length();
        double k = dist > 6 ? 0.4 : dist > 2 ? 0.3 : 0.24;
        Vec3d step = d.multiply(k);
        if (step.length() > 2.6) step = step.normalize().multiply(2.6);
        setVelocity(step);
        setPosition(getPos().add(step));
    }

    // ------------------------------------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        World w = getWorld();
        PlayerEntity owner = owner();
        if (w.isClient) {
            // the orbit is computed here too (smooth at any ping); strikes and parries come from the server
            if (state() == SwarmState.ORBIT && owner != null) flyOrbit(owner);
            Vec3d m = getPos().subtract(prevX, prevY, prevZ);
            if (m.lengthSquared() > 1e-4) lastDir = m.normalize();
            com.creedpdf.orbitalrailgun.RailgunMod.client().legionTrail(this);
            return;
        }
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.squaredDistanceTo(this) > 64 * 64) {
            discard();
            return;
        }
        stateTicks++;
        switch (state()) {
            case ORBIT -> flyOrbit(owner);
            case STRIKE -> tickStrike((ServerWorld) w, owner);
            case DEFEND -> tickDefend((ServerWorld) w, owner);
        }
    }

    private void tickStrike(ServerWorld w, PlayerEntity owner) {
        if (!hit) {
            if (target == null || !target.isAlive() || target.getWorld() != w || stateTicks > STRIKE_TIMEOUT) {
                backToOrbit(10);
                return;
            }
            // home on the target's centre, then pass straight through it
            dash = target.getBoundingBox().getCenter().subtract(getPos()).normalize();
            Vec3d step = dash.multiply(STRIKE_SPEED);
            Box tb = target.getBoundingBox().expand(0.35);
            if (tb.contains(getPos()) || tb.raycast(getPos(), getPos().add(step)).isPresent()) {
                pierce(w, owner, target);
                hit = true;
                pierce = PIERCE_TICKS;
            }
            setVelocity(step);
            setPosition(getPos().add(step));
        } else {
            Vec3d step = dash.multiply(STRIKE_SPEED * 0.8);
            setVelocity(step);
            setPosition(getPos().add(step));
            if (--pierce <= 0) backToOrbit(16);
        }
    }

    private void tickDefend(ServerWorld w, PlayerEntity owner) {
        if (target == null || !target.isAlive() || target.getWorld() != w || stateTicks > DEFEND_TIMEOUT) {
            backToOrbit(8);
            return;
        }
        // lead the shot by one tick and meet it
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

    // ------------------------------------------------------------------------------------------- combat

    /** The weapon passes through {@code t}: its damage, enchantments, potion effects and on-hit behaviour apply. */
    private void pierce(ServerWorld w, PlayerEntity owner, Entity t) {
        ItemStack stack = getStack();
        EntityGroup group = t instanceof LivingEntity le ? le.getGroup() : EntityGroup.DEFAULT;
        float damage = SwarmCapability.baseDamage(stack) + EnchantmentHelper.getAttackDamage(stack, group);
        DamageSource src = w.getDamageSources().playerAttack(owner);
        t.timeUntilRegen = 0;   // a swarm hits from many blades at once: don't let one blade's i-frames swallow the next
        if (!t.damage(src, damage)) return;
        Vec3d p = getPos();
        w.spawnParticles(ArsenalLegion.KINETIC_GAZE, p.x, p.y, p.z, 0, dash.x, dash.y, dash.z, 2.5);
        w.playSound(null, t.getX(), t.getBodyY(0.5), t.getZ(), ArsenalLegion.WEAPON_PIERCE, SoundCategory.PLAYERS, 1f, 0.9f + random.nextFloat() * 0.2f);
        if (t instanceof LivingEntity le) {
            int fire = EnchantmentHelper.getLevel(Enchantments.FIRE_ASPECT, stack);
            if (fire > 0) le.setOnFireFor(4 * fire);
            int kb = EnchantmentHelper.getLevel(Enchantments.KNOCKBACK, stack);
            le.takeKnockback(0.35 + kb * 0.5, -dash.x, -dash.z);
            for (StatusEffectInstance e : PotionUtil.getPotionEffects(stack)) le.addStatusEffect(new StatusEffectInstance(e), owner);
            // custom on-hit behaviour of non-vanilla weapons (vanilla ones only wear themselves out, done below)
            if (!(stack.getItem() instanceof SwordItem || stack.getItem() instanceof MiningToolItem || stack.getItem() instanceof TridentItem))
                stack.getItem().postHit(stack.copy(), le, owner);
        }
        wear(owner, stack.getItem() instanceof AxeItem ? 2 : 1);
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
        w.spawnParticles(ArsenalLegion.KINETIC_GAZE, p.x, p.y, p.z, 0, 0, 1, 0, 1.5);
        w.playSound(null, p.x, p.y, p.z, ArsenalLegion.WEAPON_PARRY, SoundCategory.PLAYERS, 1.2f, 0.95f + random.nextFloat() * 0.1f);
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
