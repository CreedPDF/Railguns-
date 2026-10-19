package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.DimensionalChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server rules of space and the planets:
 * <ul>
 *   <li>zero gravity in space (entities float, keep their momentum);</li>
 *   <li>vacuum everywhere off Earth: anything without a sealed EVA suit freezes and suffocates in seconds;</li>
 *   <li>flying into a planet's atmosphere carries you down to its surface (and into Earth's, home), with
 *       no loading screen; climbing above a planet's sky carries you back up into space;</li>
 *   <li>the gas giants have no surface: their crushing atmosphere throws you back out.</li>
 * </ul>
 */
public final class SpaceServer {
    private SpaceServer() {}

    public static final Identifier REENTRY = RailgunMod.id("reentry");
    public static final RegistryKey<DamageType> VACUUM = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, RailgunMod.id("vacuum"));
    public static final RegistryKey<DamageType> CRUSHED = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, RailgunMod.id("crushed"));
    private static final String ZERO_G = "orbital_railgun.zero_g";

    /** Above this height on a planet you are back in space. */
    public static final double PLANET_CEILING = 420;
    /** Height you arrive at when coming down into a planet / Earth. */
    public static final double PLANET_ARRIVAL = 340, EARTH_ARRIVAL = 430;

    private record Preload(Body body, ChunkLoader loader) {}

    private static final Map<UUID, Preload> PRELOAD = new HashMap<>();

    public static void tick(ServerWorld world) {
        boolean space = SpaceDims.isSpace(world);
        Body planet = SpaceDims.planetOf(world);
        boolean airless = space || planet != null;
        MinecraftServer server = world.getServer();
        PlanetState planets = PlanetState.get(server);
        long now = world.getTime();

        // non-player living things: zero-g in space, and the vacuum
        if (airless) {
            for (Entity e : world.iterateEntities()) {
                if (e instanceof PlayerEntity) continue;
                if (space && !e.hasNoGravity() && !(e instanceof RiftPortal)) e.setNoGravity(true);
                if (e instanceof LivingEntity le && le.isAlive() && now % 10 == 0 && !EvaSuit.full(le)) {
                    le.damage(vacuum(world), 4f);
                    le.setFrozenTicks(Math.min(le.getFrozenTicks() + 40, 300));
                }
            }
        }

        List<ServerPlayerEntity> players = new ArrayList<>(world.getPlayers());
        for (ServerPlayerEntity p : players) {
            // ---- gravity
            if (space) {
                if (!p.hasNoGravity()) {
                    p.setNoGravity(true);
                    p.addCommandTag(ZERO_G);
                }
            } else if (p.getCommandTags().contains(ZERO_G)) {
                p.setNoGravity(false);
                p.removeScoreboardTag(ZERO_G);
            }
            // floating in space / riding the jetpack is not "flying" for the anti-cheat
            if (space || EvaSuit.jetpack(p)) p.networkHandler.floatingTicks = 0;

            // ---- life support
            if (airless && !p.isCreative() && !p.isSpectator()) {
                if (EvaSuit.full(p)) {
                    p.setAir(p.getMaxAir());
                } else {
                    p.setAir(Math.max(-20, p.getAir() - 12));
                    p.setFrozenTicks(Math.min(p.getFrozenTicks() + 6, 280));
                    if (now % 10 == 0) {
                        p.damage(vacuum(world), 3f);
                        p.sendMessage(Text.translatable("message.orbital_railgun.vacuum").formatted(Formatting.RED), true);
                    }
                }
            }

            if (space) tickInSpace(server, world, p, planets, now);
            else if (planet != null) tickOnPlanet(server, world, p, planet, planets, now);
            else if (world.getRegistryKey() == World.OVERWORLD) dropPreload(p);
        }
    }

    /** Once per server tick: timed events of planets ARES-0 has hit. */
    public static void tickBlasts(MinecraftServer server) {
        PlanetState ps = PlanetState.get(server);
        long now = server.getOverworld().getTime();
        for (Body b : Body.ALL) {
            if (!ps.doomed(b)) continue;
            long e = now - ps.blastStart(b);
            if (e == Blast.EJECT) evacuate(server, b);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void tickInSpace(MinecraftServer server, ServerWorld world, ServerPlayerEntity p, PlanetState planets, long now) {
        Vec3d pos = p.getPos();
        Body nearest = null;
        double nearestGap = Double.MAX_VALUE;
        for (Body b : Body.ALL) {
            if (planets.destroyed(b, now)) continue;
            double gap = pos.distanceTo(b.center) - b.radius - b.atmosphere;
            if (gap < nearestGap) {
                nearestGap = gap;
                nearest = b;
            }
        }
        if (nearest == null) return;

        if (nearest.landable() && nearestGap < 700) preload(server, p, nearest, pos);
        else dropPreload(p);

        if (nearestGap > 0) return;
        if (nearest.gasGiant()) {
            // crushing pressure: hurled back out
            Vec3d out = pos.subtract(nearest.center).normalize();
            p.setVelocity(out.multiply(1.6));
            p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
            if (!p.isCreative()) p.damage(new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(CRUSHED)), 6f);
            p.sendMessage(Text.translatable("message.orbital_railgun.gas_giant", nearest.displayName()).formatted(Formatting.GOLD), true);
            return;
        }
        if (planets.doomed(nearest)) {
            p.sendMessage(Text.translatable("message.orbital_railgun.doomed", nearest.displayName()).formatted(Formatting.RED), true);
            Vec3d out = pos.subtract(nearest.center).normalize();
            p.setVelocity(out.multiply(1.2));
            p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
            return;
        }
        descend(server, p, nearest);
    }

    /** Space -> the surface below (seamless). */
    private static void descend(MinecraftServer server, ServerPlayerEntity p, Body body) {
        RegistryKey<World> dimKey = body.landDim();
        ServerWorld dest = dimKey == null ? null : server.getWorld(dimKey);
        if (dest == null) return;
        double[] xz = body.spaceToSurface(p.getPos());
        double y = body == Body.EARTH ? EARTH_ARRIVAL : PLANET_ARRIVAL;
        Vec3d v = p.getVelocity();
        double down = Math.max(1.2, Math.min(3.0, v.length()));
        PortalAPI.teleportEntity(p, dest, new Vec3d(xz[0], y, xz[1]));
        p.fallDistance = 0;
        p.setVelocity(0, -down, 0);
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 80, 1, true, false));
        sendReentry(p, body, true);
        dest.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.REENTRY_BURN, SoundCategory.PLAYERS, 2.5f, 1f);
        if (body != Body.EARTH) {
            p.sendMessage(Text.translatable("message.orbital_railgun.landing", body.displayName()).formatted(Formatting.AQUA), true);
        }
    }

    private static void tickOnPlanet(MinecraftServer server, ServerWorld world, ServerPlayerEntity p, Body planet, PlanetState planets, long now) {
        dropPreload(p);
        if (p.getY() > PLANET_CEILING) ascend(server, p, planet);
    }

    /** A planet's sky -> space (seamless). Earth has no way up except the rift. */
    private static void ascend(MinecraftServer server, ServerPlayerEntity p, Body planet) {
        ServerWorld space = server.getWorld(SpaceDims.SPACE);
        if (space == null) return;
        Vec3d out = planet.surfaceNormal(p.getX(), p.getZ());
        Vec3d pos = planet.surfaceToSpace(p.getX(), p.getZ(), planet.atmosphere + 30);
        double speed = Math.max(0.6, p.getVelocity().y);
        PortalAPI.teleportEntity(p, space, pos);
        p.setVelocity(out.multiply(speed));
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        sendReentry(p, planet, false);
    }

    /** Eject everyone standing on a planet that is about to explode. */
    public static void evacuate(MinecraftServer server, Body planet) {
        RegistryKey<World> key = planet.landDim();
        ServerWorld w = key == null ? null : server.getWorld(key);
        ServerWorld space = server.getWorld(SpaceDims.SPACE);
        if (w == null || space == null || planet == Body.EARTH) return;
        for (ServerPlayerEntity p : new ArrayList<>(w.getPlayers())) {
            Vec3d out = planet.surfaceNormal(p.getX(), p.getZ());
            Vec3d pos = planet.center.add(out.multiply(planet.radius * 2.4));
            PortalAPI.teleportEntity(p, space, pos);
            p.setVelocity(out.multiply(2.2));
            p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
            sendReentry(p, planet, false);
            p.sendMessage(Text.translatable("message.orbital_railgun.ejected", planet.displayName()).formatted(Formatting.RED), false);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void preload(MinecraftServer server, ServerPlayerEntity p, Body body, Vec3d pos) {
        Preload cur = PRELOAD.get(p.getUuid());
        double[] xz = body.spaceToSurface(pos);
        ChunkPos cp = new ChunkPos((int) Math.floor(xz[0]) >> 4, (int) Math.floor(xz[1]) >> 4);
        if (cur != null && cur.body == body) {
            DimensionalChunkPos c = cur.loader.center;
            if (Math.abs(c.x - cp.x) < 4 && Math.abs(c.z - cp.z) < 4) return;
        }
        dropPreload(p);
        ChunkLoader loader = new ChunkLoader(new DimensionalChunkPos(body.landDim(), cp), 5);
        PortalAPI.addChunkLoaderForPlayer(p, loader);
        PRELOAD.put(p.getUuid(), new Preload(body, loader));
    }

    private static void dropPreload(ServerPlayerEntity p) {
        Preload cur = PRELOAD.remove(p.getUuid());
        if (cur != null) PortalAPI.removeChunkLoaderForPlayer(p, cur.loader);
    }

    public static void forget(ServerPlayerEntity p) {
        PRELOAD.remove(p.getUuid());
    }

    public static void sendReentry(ServerPlayerEntity p, Body body, boolean down) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(body.ordinal());
        buf.writeBoolean(down);
        ServerPlayNetworking.send(p, REENTRY, buf);
    }

    public static DamageSource vacuum(World world) {
        return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(VACUUM));
    }
}
