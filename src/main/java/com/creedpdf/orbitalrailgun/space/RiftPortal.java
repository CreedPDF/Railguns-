package com.creedpdf.orbitalrailgun.space;

import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The see-through, walk-through opening of an ARES-9 rift (Immersive Portals). One sits in the
 * sky above the railgun facing down; its reverse twin floats in space above the planet facing
 * up. Both carry an expiry time so a rift can never be left open, even if the railgun that
 * holds it is unloaded or destroyed.
 */
public class RiftPortal extends Portal {
    public static final EntityType<RiftPortal> TYPE = Portal.createPortalEntityType(RiftPortal::new);

    public long expireAt = Long.MAX_VALUE;

    public RiftPortal(EntityType<?> type, World world) {
        super(type, world);
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && getWorld().getTime() > expireAt) discard();
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        expireAt = nbt.contains("riftExpire") ? nbt.getLong("riftExpire") : Long.MAX_VALUE;
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putLong("riftExpire", expireAt);
    }
}
