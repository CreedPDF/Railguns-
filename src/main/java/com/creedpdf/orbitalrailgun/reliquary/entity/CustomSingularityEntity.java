package com.creedpdf.orbitalrailgun.reliquary.entity;

import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.logic.CustomDisintegrationEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The Star-Eater's collapse: a singularity that lives {@link #LIFE} ticks. Every tick (server) it queries a box of
 * radius {@link #RADIUS} for living things - not its maker, nor spectators or creative players - and drags each toward
 * its centre: their velocity is set directly to their momentum plus an inward pull that grows on an accelerating curve
 * with the singularity's age and with how close they already are. Whatever reaches the centre
 * ({@link #CORE} blocks) is erased by a {@link CustomDisintegrationEvent}. In its last ticks it collapses and vanishes.
 */
public class CustomSingularityEntity extends Entity {
    public static final int LIFE = 140, GROW = 20, COLLAPSE = 15;
    public static final double RADIUS = 10, CORE = 1.1;
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(CustomSingularityEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    public CustomSingularityEntity(EntityType<? extends CustomSingularityEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    public void setOwner(PlayerEntity p) {
        dataTracker.set(OWNER, Optional.of(p.getUuid()));
    }

    @Nullable
    public PlayerEntity owner() {
        return dataTracker.get(OWNER).map(u -> getWorld().getPlayerByUuid(u)).orElse(null);
    }

    /** 0..1 how strong it is now: grows in, holds, collapses out (shared by server and renderer). */
    public float strength(float pt) {
        float a = age + pt;
        if (a < GROW) {
            float k = a / GROW;
            return k * k;
        }
        if (a > LIFE - COLLAPSE) return Math.max(0f, (LIFE - a) / COLLAPSE);
        return 1f;
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(OWNER, Optional.empty());
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        ServerWorld w = (ServerWorld) getWorld();
        if (age >= LIFE) {
            discard();
            return;
        }
        if (age == 1) w.playSound(null, getX(), getY(), getZ(), ModSounds.SINGULARITY_FORM, SoundCategory.PLAYERS, 4f, 1f);
        PlayerEntity owner = owner();
        Vec3d c = getPos();
        float life = age / (float) LIFE;
        float str = strength(0);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(RADIUS), LivingEntity::isAlive)) {
            if (e == owner || e.isSpectator() || (e instanceof PlayerEntity p && p.getAbilities().creativeMode)) continue;
            Vec3d at = e.getPos().add(0, e.getHeight() * 0.5, 0);
            Vec3d to = c.subtract(at);
            double d = to.length();
            if (d > RADIUS) continue;
            if (d < CORE) {
                CustomDisintegrationEvent.fire(w, this, e, owner);
                continue;
            }
            // accelerating curve: stronger with age (squared) and with closeness (squared), always toward the centre
            double near = 1 - d / RADIUS;
            double pull = (0.05 + 0.35 * life * life) * (0.35 + 1.6 * near * near) * str;
            Vec3d v = e.getVelocity().multiply(0.82).add(to.multiply(1 / d).multiply(pull));
            double max = Math.min(d, 1.6);
            if (v.length() > max) v = v.normalize().multiply(max);
            e.setVelocity(v);
            e.velocityModified = true;
            e.fallDistance = 0;
        }
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 160 * 160;
    }

    @Override
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        return false;
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

    /** For the renderer's spin. */
    public float spin(float pt) {
        return (age + pt) * 0.08f + MathHelper.sin((age + pt) * 0.013f) * 0.5f;
    }
}
