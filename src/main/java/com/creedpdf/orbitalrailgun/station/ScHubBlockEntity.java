package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The station's core and power grid. Modules report in once a second; every second the hub shares the solar supply
 * out: its own life support first ({@link Sc#HUB_KW}), then the railguns (nearest first, {@link Sc#RAILGUN_KW} each),
 * then whatever is left to the satellites (up to {@link Sc#SAT_KW} each). It also watches for incoming debris, sounds
 * the alarm, and runs debris drills.
 */
public class ScHubBlockEntity extends BlockEntity {
    private record Report(ScModuleBlock.Kind kind, float supply, float want, long at) {}

    private final Map<Long, Report> reports = new HashMap<>();
    private final Map<Long, Float> alloc = new HashMap<>();
    private boolean registered;
    private long lastAlarm = -1000;

    // synced state (the console and the renderer)
    float supply, demand;
    int solar, solarLive, railguns, railgunsLive, sats, threats;
    boolean online;
    int drill;
    boolean defenseAuto = true;

    public ScHubBlockEntity(BlockPos pos, BlockState state) {
        super(Sc.HUB_BE, pos, state);
    }

    public boolean online() {
        return online;
    }

    public int drill() {
        return drill;
    }

    public boolean defenseAuto() {
        return defenseAuto;
    }

    public int threats() {
        return threats;
    }

    void report(ScModuleBlockEntity m) {
        if (world == null) return;
        reports.put(m.getPos().asLong(), new Report(m.kind(), m.supply(), m.want(), world.getTime()));
    }

    float allocation(BlockPos module) {
        return alloc.getOrDefault(module.asLong(), 0f);
    }

    public void tick() {
        if (world == null || world.isClient) return;
        if (!registered) {
            ScGrid.add(world, pos);
            registered = true;
        }
        if (drill > 0) {
            drill--;
            if (drill == 0) sync();
        }
        if ((world.getTime() + pos.asLong()) % 20 != 0) return;
        long now = world.getTime();
        reports.values().removeIf(r -> now - r.at() > 50);
        reports.entrySet().removeIf(e -> !(world.getBlockEntity(BlockPos.fromLong(e.getKey())) instanceof ScModuleBlockEntity));

        float sup = 0, dem = Sc.HUB_KW;
        int so = 0, sl = 0, rg = 0, st = 0;
        List<Long> guns = new ArrayList<>(), satList = new ArrayList<>();
        for (Map.Entry<Long, Report> e : reports.entrySet()) {
            Report r = e.getValue();
            dem += r.want();
            switch (r.kind()) {
                case SOLAR -> {
                    so++;
                    if (r.supply() > 0) sl++;
                    sup += r.supply();
                }
                case RAILGUN -> {
                    rg++;
                    guns.add(e.getKey());
                }
                case SATELLITE -> {
                    st++;
                    if (r.want() > 0) satList.add(e.getKey());
                }
            }
        }
        alloc.clear();
        float avail = sup - Sc.HUB_KW;
        int live = 0;
        if (avail >= 0) {
            guns.sort(Comparator.comparingDouble(k -> BlockPos.fromLong(k).getSquaredDistance(pos)));
            for (long g : guns) {
                if (avail >= Sc.RAILGUN_KW) {
                    alloc.put(g, Sc.RAILGUN_KW);
                    avail -= Sc.RAILGUN_KW;
                    live++;
                }
            }
            if (!satList.isEmpty()) {
                float each = Math.min(Sc.SAT_KW, avail / satList.size());
                for (long s : satList) alloc.put(s, Math.max(0f, each));
            }
        }
        boolean on = sup >= Sc.HUB_KW;
        // debris watch
        int th = world.getEntitiesByClass(MeteorEntity.class, new Box(pos).expand(220), e -> e.isAlive()).size();
        if (th > 0 && now - lastAlarm > 80) {
            lastAlarm = now;
            world.playSound(null, pos.up(2), Sc.S_ALARM, SoundCategory.BLOCKS, 1.6f, 1f);
        }
        boolean changed = on != online || so != solar || sl != solarLive || rg != railguns || live != railgunsLive || st != sats
                || th != threats || Math.abs(sup - supply) > 0.05f || Math.abs(dem - demand) > 0.05f;
        supply = sup;
        demand = dem;
        solar = so;
        solarLive = sl;
        railguns = rg;
        railgunsLive = live;
        sats = st;
        threats = th;
        if (on && !online) world.playSound(null, pos.up(2), Sc.S_HUM, SoundCategory.BLOCKS, 1f, 1f);
        online = on;
        if (changed) sync();
    }

    void toggleDrill() {
        drill = drill > 0 ? 0 : Sc.DRILL_TICKS;
        sync();
    }

    void toggleDefense() {
        defenseAuto = !defenseAuto;
        sync();
    }

    void sync() {
        markDirty();
        if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world != null && !world.isClient) ScGrid.remove(world, pos);
        registered = false;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("Drill", drill);
        nbt.putBoolean("Defense", defenseAuto);
        nbt.putBoolean("Online", online);
        nbt.putFloat("Supply", supply);
        nbt.putFloat("Demand", demand);
        nbt.putIntArray("Counts", new int[]{solar, solarLive, railguns, railgunsLive, sats, threats});
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        drill = nbt.getInt("Drill");
        defenseAuto = !nbt.contains("Defense") || nbt.getBoolean("Defense");
        online = nbt.getBoolean("Online");
        supply = nbt.getFloat("Supply");
        demand = nbt.getFloat("Demand");
        int[] c = nbt.getIntArray("Counts");
        if (c.length == 6) {
            solar = c[0];
            solarLive = c[1];
            railguns = c[2];
            railgunsLive = c[3];
            sats = c[4];
            threats = c[5];
        }
    }
}
