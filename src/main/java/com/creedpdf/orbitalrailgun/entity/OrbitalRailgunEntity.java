package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.loadout.PartDrop;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * The ARES-9 in its orbital configuration. Never spawned in a world: the orbital uplink screen
 * creates a client-side instance and renders it in its own space scene. The scene writes the
 * pose it wants each frame into the public fields below; the client animator reads them.
 */
public class OrbitalRailgunEntity extends Entity implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Animation to play ("stowed", "deploy", "stow", "idle", "charge", "fire") and its time in seconds. */
    public String anim = "stowed";
    public float animTime;
    /** Seconds for looping layers (idle, charge). */
    public float clock;
    /** Installed loadout (raw). */
    public int loadout;
    /** Per-part docking override (attach cutscene): bone -> pose. Null = docked normally. */
    public final Map<String, PartDrop.Pose> partPoses = new HashMap<>();
    /** When true, parts without an override are hidden (they are still on their way up). */
    public boolean hideUnposedParts;

    public OrbitalRailgunEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = true;
    }

    @Override
    protected void initDataTracker() {}

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}

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
