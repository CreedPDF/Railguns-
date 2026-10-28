package com.creedpdf.orbitalrailgun.reliquary.entity;

import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.logic.VoidMark;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The tear a Void-Carver swing leaves in space: it floats where the blade passed for {@link #LIFE} ticks and, every
 * {@link #PERIOD} ticks, throws an arc into every entity still carrying its {@link VoidMark} (within
 * {@link #REACH} blocks). Each arc subtracts {@link #ARC_DAMAGE} health with a {@code void_arc} hit, which ignores
 * invulnerability frames, so arcs from several rifts all land. The arcs themselves are drawn by the clients.
 */
public class VoidRiftEntity extends Entity {
    public static final int LIFE = 100, PERIOD = 10;
    public static final double REACH = 32;
    public static final float ARC_DAMAGE = 3f;

    private final List<Entity> marked = new ArrayList<>();
    @Nullable private UUID owner;
    /** Blade direction of the swing that opened it (radians), for the renderer's crack orientation. */
    public float cutYaw;

    public VoidRiftEntity(EntityType<? extends VoidRiftEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    public void setup(PlayerEntity owner, List<Entity> marks) {
        this.owner = owner.getUuid();
        this.cutYaw = owner.getYaw();
        setYaw(owner.getYaw());
        long until = owner.getWorld().getTime() + LIFE + 2;
        for (Entity e : marks) {
            marked.add(e);
            VoidMark.put(e, new VoidMark(getId(), until));
        }
    }

    @Override
    protected void initDataTracker() {
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
        if (age == 0 || age % PERIOD != 0) return;
        PlayerEntity attacker = owner == null ? null : w.getPlayerByUuid(owner);
        List<Entity> hit = new ArrayList<>();
        for (Entity e : marked) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.getWorld() != w || le.squaredDistanceTo(this) > REACH * REACH) continue;
            if (!VoidMark.marked(le, getId(), w.getTime())) continue;
            ModDamageTypes.hurt(w, le, ModDamageTypes.VOID_ARC, this, attacker, ARC_DAMAGE);
            hit.add(le);
        }
        if (!hit.isEmpty()) {
            w.playSound(null, getX(), getY(), getZ(), ModSounds.VOID_ARC, SoundCategory.PLAYERS, 1.2f, 0.85f + w.random.nextFloat() * 0.3f);
            ReliquaryNet.broadcastArcs(w, this, hit);
        }
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 128 * 128;
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
}
