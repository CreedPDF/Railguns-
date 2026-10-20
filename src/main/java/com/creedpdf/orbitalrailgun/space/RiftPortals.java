package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.GeometryPortalShape;

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
        // the tear's own jagged outline (same seed the client uses for the glowing rim)
        float[][] pts = RiftShape.outline(seed(rg), (float) radius);
        float[] ext = RiftShape.extent(pts);
        double w = ext[0] * 2.02, h = ext[1] * 2.02;
        // axisW x axisH = -Y: the portal faces down, so it is seen and entered from below
        front.setOrientationAndSize(new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), w, h);
        front.setDestinationDimension(SpaceDims.SPACE);
        front.setDestination(exit);
        GeometryPortalShape shape = new GeometryPortalShape();
        shape.triangles = new java.util.ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            float[] a = pts[i], b = pts[(i + 1) % pts.length];
            shape.triangles.add(new GeometryPortalShape.TriangleInPlane(0, 0, a[0], a[1], b[0], b[1]));
        }
        front.specialShape = shape;
        front.specialShape.normalize(front.width, front.height);
        front.expireAt = world.getTime() + lifetime;

        RiftPortal back = PortalAPI.createReversePortal(front);
        back.expireAt = front.expireAt;

        PortalAPI.spawnServerEntity(front);
        PortalAPI.spawnServerEntity(back);
        OPEN.put(rg.getUuid(), new Pair(front.getUuid(), back.getUuid()));
    }

    /** Seed shared with the client so the rim drawn around the portal matches its outline. */
    public static long seed(RailgunEntity rg) {
        return rg.getUuid().getMostSignificantBits() ^ 0x5EED_0F_A215L;
    }

    /** @param scale fraction of the full size (the shape's proportions never change) */
    public static void resize(ServerWorld world, RailgunEntity rg, double scale) {
        Pair p = OPEN.get(rg.getUuid());
        if (p == null) return;
        ServerWorld space = world.getServer().getWorld(SpaceDims.SPACE);
        for (Entity e : new Entity[]{world.getEntity(p.front), space == null ? null : space.getEntity(p.back)}) {
            if (e instanceof RiftPortal portal) {
                if (portal.baseWidth <= 0) {
                    portal.baseWidth = portal.width;
                    portal.baseHeight = portal.height;
                }
                double k = Math.max(0.02, scale);
                portal.setWidth(portal.baseWidth * k);
                portal.setHeight(portal.baseHeight * k);
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
