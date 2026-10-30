package com.creedpdf.orbitalrailgun.astraea;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The server half of ASTRAEA: the Ascension ritual (charges the lance with one throw) and the strike (the throw that
 * splits the Earth), each a fixed timeline ({@link Timeline}) driven from the overworld's tick; the permanent split
 * ({@link SplitState}) and the chasms it tears ({@link ChasmCarver}).
 *
 * <p>While either plays out the bearer is rooted and cannot be hurt. During a strike the lance has left their hand
 * (it is kept here and given back when it falls from the sky, or at once if they leave or the server stops).</p>
 */
public final class AstraeaServer {
    private AstraeaServer() {}

    static final class Ritual {
        final UUID player;
        final long start;
        final Vec3d anchor;

        Ritual(UUID player, long start, Vec3d anchor) {
            this.player = player;
            this.start = start;
            this.anchor = anchor;
        }
    }

    static final class Strike {
        final UUID player;
        final long start;
        final Vec3d anchor;
        final Vec3d normal;
        final double chasmAngle;
        final int slot;
        ItemStack lance;

        Strike(UUID player, long start, Vec3d anchor, Vec3d normal, double chasmAngle, int slot, ItemStack lance) {
            this.player = player;
            this.start = start;
            this.anchor = anchor;
            this.normal = normal;
            this.chasmAngle = chasmAngle;
            this.slot = slot;
            this.lance = lance;
        }
    }

    private static final Map<UUID, Ritual> RITUALS = new HashMap<>();
    private static final Map<UUID, Strike> STRIKES = new HashMap<>();

    public static boolean busy(ServerPlayerEntity p) {
        return RITUALS.containsKey(p.getUuid()) || STRIKES.containsKey(p.getUuid());
    }

    // ------------------------------------------------------------------------------------------- the key

    /** The ability key: Ascension if the lance is uncharged, the strike if it is. */
    public static void press(ServerPlayerEntity p) {
        ItemStack stack = p.getMainHandStack();
        if (!stack.isOf(Astraea.LANCE) || busy(p) || !p.isAlive() || p.isSpectator()) return;
        ServerWorld w = p.getServerWorld();
        if (!AstraeaItem.charged(stack)) {
            startRitual(p, w);
            return;
        }
        if (w.getRegistryKey() != World.OVERWORLD) {
            p.sendMessage(KrlText.t("ASTRAEA  //  THE LANCE CAN ONLY STRIKE EARTH FROM ITS SURFACE", KrlText.GREY), true);
            return;
        }
        if (!STRIKES.isEmpty()) {
            p.sendMessage(KrlText.t("ASTRAEA  //  THE SKY IS ALREADY TORN", KrlText.GREY), true);
            return;
        }
        startStrike(p, w);
    }

    private static void startRitual(ServerPlayerEntity p, ServerWorld w) {
        Ritual r = new Ritual(p.getUuid(), w.getTime(), p.getPos());
        RITUALS.put(p.getUuid(), r);
        AstraeaNet.sendRitual(w, p, r.start, r.anchor);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), Astraea.S_STANCE, SoundCategory.PLAYERS, 1.6f, 1f);
    }

    private static void startStrike(ServerPlayerEntity p, ServerWorld w) {
        // the cut runs through the bearer's spot on the globe, along a direction they did not choose
        double angle = w.random.nextDouble() * Math.PI;
        Vec3d n = splitNormal(p.getX(), p.getZ(), angle);
        int slot = p.getInventory().selectedSlot;
        ItemStack lance = p.getMainHandStack().copy();
        Strike s = new Strike(p.getUuid(), w.getTime(), p.getPos(), n, angle, slot, lance);
        STRIKES.put(p.getUuid(), s);
        AstraeaNet.sendStrike(w, p, s.start, s.anchor, n, angle);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), Astraea.S_STANCE, SoundCategory.PLAYERS, 1.2f, 1.25f);
    }

    /**
     * The split plane in the Space dimension's frame: it passes through the Earth's centre and the bearer's point on
     * the globe, and meets the ground along the overworld direction {@code angle} (so the chasm on the ground lies
     * exactly in the cut).
     */
    public static Vec3d splitNormal(double x, double z, double angle) {
        Vec3d up = Body.EARTH.surfaceNormal(x, z);
        Quaternionf toSpace = new Quaternionf().rotationTo(0, 1, 0, (float) up.x, (float) up.y, (float) up.z);
        Vector3f t = toSpace.transform(new Vector3f((float) Math.cos(angle), 0, (float) Math.sin(angle)));
        Vec3d tv = new Vec3d(t.x, t.y, t.z);
        Vec3d n = tv.crossProduct(up);
        return n.lengthSquared() < 1e-8 ? new Vec3d(0, 0, 1) : n.normalize();
    }

    // ------------------------------------------------------------------------------------------- ticking

    public static void tick(MinecraftServer server) {
        ServerWorld ow = server.getOverworld();
        tickRituals(server);
        tickStrikes(server, ow);
        SplitState st = SplitState.get(server);
        if (!st.chasms.isEmpty()) ChasmCarver.tick(ow, st);
    }

    private static void tickRituals(MinecraftServer server) {
        for (Iterator<Ritual> it = RITUALS.values().iterator(); it.hasNext(); ) {
            Ritual r = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(r.player);
            if (p == null) {
                it.remove();
                continue;
            }
            ServerWorld w = p.getServerWorld();
            long t = w.getTime() - r.start;
            root(p, r.anchor);
            Vec3d rift = r.anchor.add(0, 34, 0);
            if (t == Timeline.R_RIFT) sound(w, rift, Astraea.S_RIFT, 12f, 1f);
            if (t >= Timeline.R_STAR && t < Timeline.R_DESCEND && (t - Timeline.R_STAR) % 22 == 0) {
                float k = (t - Timeline.R_STAR) / (float) (Timeline.R_DESCEND - Timeline.R_STAR);
                sound(w, rift, Astraea.S_PULSE, 3f + 6f * k, 0.9f + 0.25f * k);
            }
            if (t == Timeline.R_DESCEND) sound(w, r.anchor, Astraea.S_DESCEND, 6f, 1f);
            if (t == Timeline.R_ABSORB) sound(w, r.anchor, Astraea.S_ABSORB, 5f, 1f);
            if (t == Timeline.R_IMPACT) {
                sound(w, r.anchor, Astraea.S_ASCENSION, 6f, 1f);
                ItemStack lance = findLance(p);
                if (lance != null) AstraeaItem.setCharged(lance, true);
                w.spawnParticles(ParticleTypes.END_ROD, r.anchor.x, r.anchor.y + 1, r.anchor.z, 160, 0.4, 1.0, 0.4, 0.6);
                w.spawnParticles(ParticleTypes.FLASH, r.anchor.x, r.anchor.y + 1, r.anchor.z, 2, 0, 0, 0, 0);
            }
            if (t >= Timeline.R_END) {
                it.remove();
                AstraeaNet.sendRitualEnd(w, p);
            }
        }
    }

    private static void tickStrikes(MinecraftServer server, ServerWorld ow) {
        for (Iterator<Strike> it = STRIKES.values().iterator(); it.hasNext(); ) {
            Strike s = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player);
            long t = ow.getTime() - s.start;
            if (p != null && p.getServerWorld() == ow && t < Timeline.S_RELEASE + 6) root(p, s.anchor);
            if (t == Timeline.S_RELEASE) {
                sound(ow, s.anchor, Astraea.S_THROW, 5f, 1f);
                if (p != null) takeLance(p, s);
            }
            if (t == Timeline.S_SPLIT) split(server, ow, s);
            if (t == Timeline.S_CATCH) {
                sound(ow, s.anchor, Astraea.S_RETURN, 2f, 1f);
                if (p != null) giveBack(p, s);
            }
            if (t >= Timeline.S_END) {
                it.remove();
                if (p != null && s.lance != null) giveBack(p, s);
            }
        }
    }

    /** The moment: Earth splits. Permanent. */
    private static void split(MinecraftServer server, ServerWorld ow, Strike s) {
        SplitState st = SplitState.get(server);
        if (!st.split()) {
            st.splitTime = ow.getTime();
            st.normal = s.normal;
        }
        st.strikes++;
        SplitState.Chasm c = new SplitState.Chasm();
        // the chasm opens a little to one side of the thrower, so they stand on its edge rather than over it
        Vec3d d = new Vec3d(Math.cos(s.chasmAngle), 0, Math.sin(s.chasmAngle));
        Vec3d side = new Vec3d(-d.z, 0, d.x).multiply(30 + ow.random.nextInt(14));
        c.x = s.anchor.x + side.x;
        c.z = s.anchor.z + side.z;
        c.angle = s.chasmAngle;
        c.half = ChasmCarver.HALF;
        c.seed = ow.random.nextLong();
        st.chasms.add(c);
        st.markDirty();
        st.sync(server);
        AstraeaNet.sendSplitMoment(server, ow.getTime());
    }

    // ------------------------------------------------------------------------------------------- the lance

    @Nullable
    private static ItemStack findLance(ServerPlayerEntity p) {
        if (p.getMainHandStack().isOf(Astraea.LANCE)) return p.getMainHandStack();
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(Astraea.LANCE)) return s;
        }
        return null;
    }

    private static void takeLance(ServerPlayerEntity p, Strike s) {
        ItemStack held = p.getInventory().getStack(s.slot);
        if (held.isOf(Astraea.LANCE)) {
            s.lance = held.copy();
            p.getInventory().setStack(s.slot, ItemStack.EMPTY);
        } else {
            ItemStack l = findLance(p);
            if (l != null) {
                s.lance = l.copy();
                l.setCount(0);
            }
        }
    }

    private static void giveBack(ServerPlayerEntity p, Strike s) {
        if (s.lance == null) return;
        ItemStack l = s.lance;
        s.lance = null;
        AstraeaItem.setCharged(l, false);
        if (p.getInventory().getStack(s.slot).isEmpty()) p.getInventory().setStack(s.slot, l);
        else if (!p.getInventory().insertStack(l)) p.dropItem(l, false);
    }

    /** Leaving mid-strike: the lance goes straight back to them. */
    public static void forget(ServerPlayerEntity p) {
        RITUALS.remove(p.getUuid());
        Strike s = STRIKES.get(p.getUuid());
        if (s != null) giveBack(p, s);
    }

    public static void stopping(MinecraftServer server) {
        for (Strike s : STRIKES.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player);
            if (p != null) giveBack(p, s);
        }
        STRIKES.clear();
        RITUALS.clear();
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static void root(ServerPlayerEntity p, Vec3d anchor) {
        p.setVelocity(Vec3d.ZERO);
        if (p.getPos().squaredDistanceTo(anchor) > 0.04) p.requestTeleport(anchor.x, anchor.y, anchor.z);
        p.fallDistance = 0;
    }

    private static void sound(ServerWorld w, Vec3d at, net.minecraft.sound.SoundEvent s, float vol, float pitch) {
        w.playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, vol, pitch);
    }

    public static boolean allowDamage(ServerPlayerEntity p, DamageSource source) {
        if (source.isOf(DamageTypes.OUT_OF_WORLD) || source.isOf(DamageTypes.GENERIC_KILL)) return true;
        Strike s = STRIKES.get(p.getUuid());
        if (s != null) return p.getWorld().getTime() - s.start > Timeline.S_SPLIT + 60;
        return !RITUALS.containsKey(p.getUuid());
    }

    public static int clampTicks(long t) {
        return (int) MathHelper.clamp(t, 0, Integer.MAX_VALUE);
    }
}
