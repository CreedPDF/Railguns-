package com.creedpdf.orbitalrailgun.client.tablet;

import com.creedpdf.orbitalrailgun.entity.RailgunState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/** Latest railgun snapshot received from the server for the tablet display. */
public final class TabletStatus {
    public static volatile TabletStatus latest = new TabletStatus();

    public boolean received;
    public boolean found;
    public boolean pending;
    public RailgunState state = RailgunState.POD;
    public float charge;
    public boolean hasTarget;
    public BlockPos target = BlockPos.ORIGIN;
    public BlockPos pos = BlockPos.ORIGIN;
    public float yaw, pitch;
    public boolean sameDimension = true;
    public int stateTicks;
    public int loadout;
    public boolean arming;

    // ARES-0
    public boolean a0Found;
    public com.creedpdf.orbitalrailgun.entity.Ares0Entity.State a0State = com.creedpdf.orbitalrailgun.entity.Ares0Entity.State.IDLE;
    public int a0Target = -1, a0Ticks, a0Visible, a0Doomed;
    public BlockPos a0Pos = BlockPos.ORIGIN;

    // starship: 0 none, 1 in orbit, 2 parked, 3 flying
    public int shipState;
    public BlockPos shipPos = BlockPos.ORIGIN;
    public String shipDim = "";

    // Space Command strike satellites: how many, the best charge, whether it is charging (or full), where, busy firing
    public int satCount;
    public float satCharge;
    public boolean satPowered, satBusy;
    public String satDim = "";

    /** Last orbital site survey for the tablet's landing zone. */
    public static volatile Survey survey = null;

    public record Survey(BlockPos ground, boolean ok, String reason, BlockPos obstacle, long receivedAt) {}

    public static TabletStatus read(PacketByteBuf buf) {
        TabletStatus s = new TabletStatus();
        s.received = true;
        s.found = buf.readBoolean();
        s.pending = buf.readBoolean();
        if (s.found) {
            s.state = RailgunState.byId(buf.readVarInt());
            s.charge = buf.readFloat();
            s.hasTarget = buf.readBoolean();
            s.target = buf.readBlockPos();
            s.pos = buf.readBlockPos();
            s.yaw = buf.readFloat();
            s.pitch = buf.readFloat();
            s.sameDimension = buf.readBoolean();
            s.stateTicks = buf.readVarInt();
            s.loadout = buf.readVarInt();
            s.arming = buf.readBoolean();
        }
        if (buf.isReadable()) {
            s.a0Found = buf.readBoolean();
            if (s.a0Found) {
                var states = com.creedpdf.orbitalrailgun.entity.Ares0Entity.State.values();
                int st = buf.readVarInt();
                s.a0State = states[Math.max(0, Math.min(states.length - 1, st))];
                s.a0Target = buf.readVarInt();
                s.a0Ticks = buf.readVarInt();
                s.a0Pos = buf.readBlockPos();
                s.a0Visible = buf.readVarInt();
                s.a0Doomed = buf.readVarInt();
            }
        }
        if (buf.isReadable()) {
            s.shipState = buf.readVarInt();
            s.shipPos = buf.readBlockPos();
            s.shipDim = buf.readString();
        }
        if (buf.isReadable()) {
            s.satCount = buf.readVarInt();
            s.satCharge = buf.readFloat();
            s.satPowered = buf.readBoolean();
            s.satDim = buf.readString();
            s.satBusy = buf.readBoolean();
        }
        return s;
    }
}
