package com.creedpdf.orbitalrailgun.rift;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The dimension on the other side of the rift: deep space with drifting asteroids. Players lifted
 * up the railgun's beam into an open rift arrive on a floating asteroid island; low gravity keeps
 * them floaty. Standing on the glowing return pad (or falling off into the void) sends them home.
 */
public final class RiftDimension {
    private RiftDimension() {}

    public static final RegistryKey<World> KEY = RegistryKey.of(RegistryKeys.WORLD, RailgunMod.id("rift"));
    public static final Identifier ENTER_PACKET = RailgunMod.id("rift_enter");
    /** Top of the return pad on the arrival island. */
    public static final int PAD_Y = 100;
    private static final int RETURN_TICKS = 40;

    private record Home(RegistryKey<World> world, Vec3d pos, float yaw, float pitch) {}

    private static final Map<UUID, Home> HOMES = new HashMap<>();
    private static final Map<UUID, Integer> ON_PAD = new HashMap<>();

    public static boolean isRift(World w) {
        return w.getRegistryKey() == KEY;
    }

    /** Through the rift: remember where they came from, build the island if needed, send them over. */
    public static void enter(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        ServerWorld rift = server.getWorld(KEY);
        if (rift == null) {
            p.sendMessage(Text.literal("The rift dimension is not available in this world.").formatted(Formatting.RED), true);
            return;
        }
        HOMES.put(p.getUuid(), new Home(p.getWorld().getRegistryKey(), p.getPos(), p.getYaw(), p.getPitch()));
        ensureIsland(rift);
        p.fallDistance = 0;
        p.setVelocity(Vec3d.ZERO);
        p.teleport(rift, 0.5, PAD_Y + 1.2, 6.5, 180f, -10f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 120, 0, true, false));
        rift.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.RIFT_ENTER, SoundCategory.PLAYERS, 2f, 1f);
        ServerPlayNetworking.send(p, ENTER_PACKET, PacketByteBufs.create());
        p.sendMessage(Text.translatable("message.orbital_railgun.rift.arrived").formatted(Formatting.LIGHT_PURPLE), true);
    }

    public static void goHome(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        Home h = HOMES.remove(p.getUuid());
        ServerWorld w = h == null ? null : server.getWorld(h.world);
        p.fallDistance = 0;
        p.setVelocity(Vec3d.ZERO);
        if (w == null) {
            w = server.getOverworld();
            BlockPos s = w.getSpawnPos();
            p.teleport(w, s.getX() + 0.5, s.getY() + 1, s.getZ() + 0.5, p.getYaw(), p.getPitch());
        } else {
            p.teleport(w, h.pos.x, h.pos.y, h.pos.z, h.yaw, h.pitch);
        }
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, true, false));
        w.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.RIFT_ENTER, SoundCategory.PLAYERS, 2f, 0.7f);
        ServerPlayNetworking.send(p, ENTER_PACKET, PacketByteBufs.create());
    }

    /** Low gravity inside, the return pad, and the void below. */
    public static void tick(ServerWorld world) {
        if (!isRift(world)) return;
        for (ServerPlayerEntity p : new java.util.ArrayList<>(world.getPlayers())) {
            if (p.age % 30 == 0) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0, true, false));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 60, 2, true, false));
            }
            if (p.getY() < 8) {
                goHome(p);
                continue;
            }
            boolean onPad = Math.abs(p.getX() - 0.5) < 1.6 && Math.abs(p.getZ() - 0.5) < 1.6
                    && p.getY() >= PAD_Y + 0.9 && p.getY() < PAD_Y + 3;
            int n = onPad ? ON_PAD.getOrDefault(p.getUuid(), 0) + 1 : 0;
            if (n == 0) ON_PAD.remove(p.getUuid()); else ON_PAD.put(p.getUuid(), n);
            if (n > 0 && n % 5 == 0) {
                int bars = Math.min(10, n * 10 / RETURN_TICKS);
                p.sendMessage(Text.literal("RETURNING  " + "▮".repeat(bars) + "▯".repeat(10 - bars)).formatted(Formatting.LIGHT_PURPLE), true);
                RailgunFx.burst(world, ModParticles.RIFT_SPARK, p.getPos(), 12, 0.6, 0.2, 0.15);
            }
            if (n >= RETURN_TICKS) {
                ON_PAD.remove(p.getUuid());
                goHome(p);
            }
        }
    }

    public static void forget(ServerPlayerEntity p) {
        if (isRift(p.getWorld())) goHome(p);
        HOMES.remove(p.getUuid());
        ON_PAD.remove(p.getUuid());
    }

    // ---- terrain: the arrival island and drifting asteroids -----------------------------------------

    public static void ensureIsland(ServerWorld w) {
        BlockPos pad = new BlockPos(0, PAD_Y, 0);
        w.getChunk(pad);
        if (!w.getBlockState(pad).isAir()) return;
        Random r = Random.create(0x5EEDL);
        asteroid(w, new BlockPos(0, PAD_Y - 7, 0), 14, r, true);
        for (int i = 0; i < 26; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 28 + r.nextDouble() * 80;
            int y = 60 + r.nextInt(100);
            asteroid(w, BlockPos.ofFloored(Math.cos(a) * d, y, Math.sin(a) * d), 2 + r.nextInt(6), r, false);
        }
        // the return pad
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockState st = Math.abs(x) <= 1 && Math.abs(z) <= 1 ? Blocks.CRYING_OBSIDIAN.getDefaultState()
                        : Blocks.POLISHED_BLACKSTONE.getDefaultState();
                w.setBlockState(pad.add(x, 0, z), st, Block.NOTIFY_LISTENERS);
                for (int y = 1; y <= 6; y++) w.setBlockState(pad.add(x, y, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            w.setBlockState(pad.add(c[0], 1, c[1]), Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState(), Block.NOTIFY_LISTENERS);
            w.setBlockState(pad.add(c[0], 2, c[1]), Blocks.END_ROD.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        w.setBlockState(pad.down(), Blocks.SEA_LANTERN.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    private static void asteroid(ServerWorld w, BlockPos c, int radius, Random r, boolean main) {
        double ox = r.nextDouble() * 100, oy = r.nextDouble() * 100, oz = r.nextDouble() * 100;
        double flat = main ? 0.55 : 0.8 + r.nextDouble() * 0.4;
        for (int x = -radius - 2; x <= radius + 2; x++) {
            for (int y = -radius - 2; y <= radius + 2; y++) {
                for (int z = -radius - 2; z <= radius + 2; z++) {
                    double yy = y / (y > 0 ? (main ? 0.45 : flat) : flat);
                    double d = Math.sqrt(x * x + yy * yy + z * z);
                    double n = Math.sin((x + ox) * 0.45) * Math.sin((y + oy) * 0.5) * Math.sin((z + oz) * 0.4) * 2.2
                            + Math.sin((x + oz) * 0.9 + (z + ox) * 0.7) * 0.9;
                    if (d > radius + n) continue;
                    BlockPos p = c.add(x, y, z);
                    if (p.getY() < 2 || p.getY() > 250) continue;
                    double surf = radius + n - d;
                    BlockState st;
                    double v = Math.sin(x * 1.3 + oy) * Math.cos(z * 1.1 + ox) + Math.sin(y * 1.7 + oz);
                    if (surf < 1.2 && y > 0) st = v > 1.1 ? Blocks.AMETHYST_BLOCK.getDefaultState() : v > 0.4 ? Blocks.CALCITE.getDefaultState() : Blocks.TUFF.getDefaultState();
                    else if (v > 1.55) st = Blocks.CRYING_OBSIDIAN.getDefaultState();
                    else if (v > 1.35) st = Blocks.SEA_LANTERN.getDefaultState();
                    else st = (v > 0 ? Blocks.BLACKSTONE : Blocks.DEEPSLATE).getDefaultState();
                    w.getChunk(p);
                    w.setBlockState(p, st, Block.NOTIFY_LISTENERS);
                }
            }
        }
        // crystals on top
        for (int i = 0; i < radius * 2; i++) {
            int x = r.nextInt(radius * 2 + 1) - radius, z = r.nextInt(radius * 2 + 1) - radius;
            for (int y = radius + 3; y > -radius; y--) {
                BlockPos p = c.add(x, y, z);
                if (!w.getBlockState(p).isAir() && w.getBlockState(p.up()).isAir()) {
                    if (main && Math.abs(x) <= 3 && Math.abs(z) <= 3) break;
                    w.setBlockState(p.up(), (r.nextBoolean() ? Blocks.AMETHYST_CLUSTER : Blocks.LARGE_AMETHYST_BUD).getDefaultState(), Block.NOTIFY_LISTENERS);
                    break;
                }
            }
        }
    }
}
