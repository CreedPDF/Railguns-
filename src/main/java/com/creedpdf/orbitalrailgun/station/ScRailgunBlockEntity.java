package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Station railgun (point defence). Powered and with its hub's defence on AUTO, it picks the nearest meteorite within
 * {@link Sc#RAIL_RANGE} blocks that no other turret has claimed, slews onto it, charges its capacitors for
 * {@link Sc#RAIL_CHARGE} ticks and fires: the slug crosses the gap instantly and the rock bursts. Then it recovers for
 * {@link Sc#RAIL_COOLDOWN} ticks. Both sides run the same aiming maths (the client only for the turret's pose).
 */
public class ScRailgunBlockEntity extends ScModuleBlockEntity {
    public static final int IDLE = 0, CHARGE = 1, COOL = 2;
    public static final double PIVOT_Y = 1.05, MUZZLE = 2.22;
    private static final Map<Integer, Long> CLAIMS = new HashMap<>();

    private int target = -1;
    private int phase = IDLE;
    private long phaseAt;
    // aim (radians): yaw about Y from +Z (south), elevation up from level
    public float yaw, pitch, prevYaw, prevPitch;
    private boolean aimInit;

    public ScRailgunBlockEntity(BlockPos pos, BlockState state) {
        super(Sc.RAILGUN_BE, pos, state);
    }

    @Override
    public ScModuleBlock.Kind kind() {
        return ScModuleBlock.Kind.RAILGUN;
    }

    @Override
    public float want() {
        return Sc.RAILGUN_KW;
    }

    public int target() {
        return target;
    }

    public int phase() {
        return phase;
    }

    public long phaseAt() {
        return phaseAt;
    }

    public boolean powered() {
        return power >= Sc.RAILGUN_KW - 0.01f;
    }

    public Vec3d pivot() {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + PIVOT_Y, pos.getZ() + 0.5);
    }

    public static Vec3d dir(float yaw, float pitch) {
        float c = MathHelper.cos(pitch);
        return new Vec3d(MathHelper.sin(yaw) * c, MathHelper.sin(pitch), MathHelper.cos(yaw) * c);
    }

    public Vec3d muzzle(float pt) {
        return pivot().add(dir(MathHelper.lerp(pt, prevYaw, yaw), MathHelper.lerp(pt, prevPitch, pitch)).multiply(MUZZLE));
    }

    @Override
    public void tick() {
        super.tick();
        if (world == null) return;
        if (!aimInit) {
            // start facing the way the block was placed (south = yaw 0)
            yaw = prevYaw = (float) Math.toRadians(-getCachedState().get(ScModuleBlock.FACING).asRotation());
            aimInit = true;
        }
        prevYaw = yaw;
        prevPitch = pitch;
        long now = world.getTime();
        Entity t = target >= 0 ? world.getEntityById(target) : null;
        if (t != null && (!t.isAlive() || t.squaredDistanceTo(pivot()) > Sc.RAIL_RANGE * Sc.RAIL_RANGE * 1.2)) t = null;
        // aim: at the target, or a slow search sweep
        float wantYaw, wantPitch;
        if (t != null) {
            Vec3d d = t.getPos().subtract(pivot());
            wantYaw = (float) MathHelper.atan2(d.x, d.z);
            wantPitch = (float) MathHelper.clamp(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)), -0.35, 1.5);
        } else {
            boolean live = powered();
            wantYaw = live ? (float) (Math.sin(now * 0.012 + pos.asLong() % 7) * 1.4) : yaw;
            wantPitch = live ? 0.25f + 0.1f * MathHelper.sin(now * 0.03f) : -0.2f;
        }
        float dy = MathHelper.wrapDegrees((float) Math.toDegrees(wantYaw - yaw)) * MathHelper.RADIANS_PER_DEGREE;
        float step = t != null ? Sc.RAIL_SLEW : Sc.RAIL_SLEW * 0.15f;
        yaw += MathHelper.clamp(dy, -step, step);
        pitch += MathHelper.clamp(wantPitch - pitch, -step, step);
        if (world.isClient) return;

        ServerWorld sw = (ServerWorld) world;
        ScHubBlockEntity hub = hub == null ? null : sw.getBlockEntity(hub) instanceof ScHubBlockEntity h ? h : null;
        boolean armed = powered() && hub != null && hub.defenseAuto();
        if (!armed) {
            setTarget(-1, IDLE, now);
            return;
        }
        if (t == null) {
            if (phase == CHARGE) setTarget(-1, IDLE, now);
            else if (target >= 0) setTarget(-1, phase, now);
            if (now % 4 == 0) {
                MeteorEntity m = acquire(sw, now);
                if (m != null) setTarget(m.getId(), phase, now);
            }
        } else {
            CLAIMS.put(t.getId(), now);
        }
        switch (phase) {
            case IDLE -> {
                if (t != null && Math.abs(dy) < 0.06f && Math.abs(wantPitch - pitch) < 0.06f) {
                    setTarget(target, CHARGE, now);
                    world.playSound(null, pos, Sc.S_RAIL_CHARGE, SoundCategory.BLOCKS, 2.5f, 1f);
                }
            }
            case CHARGE -> {
                if (t == null) setTarget(-1, IDLE, now);
                else if (now - phaseAt >= Sc.RAIL_CHARGE) fire(sw, t, now);
            }
            case COOL -> {
                if (now - phaseAt >= Sc.RAIL_COOLDOWN) setTarget(target, IDLE, now);
            }
        }
    }

    private MeteorEntity acquire(ServerWorld w, long now) {
        CLAIMS.values().removeIf(at -> now - at > 10);
        MeteorEntity best = null;
        double bd = Double.MAX_VALUE;
        Vec3d p = pivot();
        for (MeteorEntity m : w.getEntitiesByClass(MeteorEntity.class, new Box(p, p).expand(Sc.RAIL_RANGE), MeteorEntity::isAlive)) {
            double d = m.squaredDistanceTo(p);
            if (d > Sc.RAIL_RANGE * Sc.RAIL_RANGE) continue;
            if (CLAIMS.containsKey(m.getId())) d *= 4;   // prefer rocks nobody else is on
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    private void fire(ServerWorld w, Entity t, long now) {
        Vec3d from = muzzle(1f), to = t.getPos();
        ScNet.broadcastRailShot(w, pos, from, to);
        w.playSound(null, pos, Sc.S_RAIL_FIRE, SoundCategory.BLOCKS, 4f, 0.95f + w.random.nextFloat() * 0.1f);
        w.playSound(null, to.x, to.y, to.z, Sc.S_RAIL_HIT, SoundCategory.BLOCKS, 5f, 1f);
        if (t instanceof MeteorEntity m) m.intercept(w);
        else t.discard();
        CLAIMS.remove(t.getId());
        setTarget(-1, COOL, now);
    }

    private void setTarget(int id, int ph, long now) {
        if (id == target && ph == phase) return;
        if (ph != phase) phaseAt = now;
        target = id;
        phase = ph;
        sync();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("Target", target);
        nbt.putInt("Phase", phase);
        nbt.putLong("PhaseAt", phaseAt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        target = nbt.getInt("Target");
        phase = nbt.getInt("Phase");
        phaseAt = nbt.getLong("PhaseAt");
    }
}
