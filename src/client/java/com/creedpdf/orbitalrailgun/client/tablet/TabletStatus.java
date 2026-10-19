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
        return s;
    }
}
