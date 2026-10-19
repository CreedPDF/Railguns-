package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.PortalManipulation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Opens, resizes and closes the portal pair of each railgun's rift. */
public final class RiftPortals {
    private RiftPortals() {}

    /** How far above the top of the atmosphere the rift comes out in space. */
    public static final double EXIT_ALTITUDE = 340;

    private record Pair(UUID front, UUID back) {}

    private static final Map<UUID, Pair> OPEN = new HashMap<>();

    /** Where a rift at {@code riftPos} in {@code world} comes out in space, or null if this world has no space above it. */
    @Nullable
    public static Vec3d spaceExit(ServerWorld world, Vec3d riftPos) {
        Body body = Body.byLandDim(world.getRegistryKey());
        if (body == null) return null;
        return body.surfaceToSpace(riftPos.x, riftPos.z, body.atmosphere + EXIT_ALTITUDE);
    }

    public static void open(ServerWorld world, RailgunEntity rg, double radius, long lifetime) {
        close(world, rg);
        Vec3d c = rg.getRiftPos(1f);
        Vec3d exit = spaceExit(world, c);
        ServerWorld space = world.getServer().getWorld(SpaceDims.SPACE);
        if (exit == null || space == null) return;
        space.getChunk((int) Math.floor(exit.x) >> 4, (int) Math.floor(exit.z) >> 4);

        RiftPortal front = RiftPortal.TYPE.create(world);
        if (front == null) return;
        front.setOriginPos(c);
        // axisW x axisH = -Y: the portal faces down, so it is seen and entered from below
        front.setOrientationAndSize(new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), radius * 2, radius * 2);
        front.setDestinationDimension(SpaceDims.SPACE);
        front.setDestination(exit);
        PortalManipulation.makePortalRound(front, 64);
        front.specialShape.normalize(front.width, front.height);
        front.expireAt = world.getTime() + lifetime;

        RiftPortal back = PortalAPI.createReversePortal(front);
        back.expireAt = front.expireAt;

        PortalAPI.spawnServerEntity(front);
        PortalAPI.spawnServerEntity(back);
        OPEN.put(rg.getUuid(), new Pair(front.getUuid(), back.getUuid()));
    }

    public static void resize(ServerWorld world, RailgunEntity rg, double radius) {
        Pair p = OPEN.get(rg.getUuid());
        if (p == null) return;
        ServerWorld space = world.getServer().getWorld(SpaceDims.SPACE);
        for (Entity e : new Entity[]{world.getEntity(p.front), space == null ? null : space.getEntity(p.back)}) {
            if (e instanceof RiftPortal portal) {
                double d = Math.max(0.5, radius * 2);
                portal.setWidth(d);
                portal.setHeight(d);
                portal.reloadAndSyncToClient();
            }
        }
    }

    public static void close(ServerWorld world, RailgunEntity rg) {
        Pair p = OPEN.remove(rg.getUuid());
        if (p == null) return;
        ServerWorld space = world.getServer().getWorld(SpaceDims.SPACE);
        Entity a = world.getEntity(p.front);
        if (a != null) a.discard();
        if (space != null) {
            Entity b = space.getEntity(p.back);
            if (b != null) b.discard();
        }
    }

    public static boolean isOpen(RailgunEntity rg) {
        return OPEN.containsKey(rg.getUuid());
    }

    public static void clear() {
        OPEN.clear();
    }
}
