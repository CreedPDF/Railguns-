package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlUi;
import com.creedpdf.orbitalrailgun.station.Sc;
import com.creedpdf.orbitalrailgun.station.ScNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * The Command Hub console: the power grid (supply against demand, each module class and how much of it is live),
 * the strike satellite's charge, the debris radar, and two controls - start / stop a debris drill (a swarm of rocks
 * sent at the station to test its defences) and switch point defence between AUTO and HOLD.
 */
public class ScHubScreen extends Screen {
    record Info(BlockPos hub, boolean online, float supply, float demand, int solar, int solarLive, int railguns, int railgunsLive,
                int sats, float satCharge, int threats, int drill, boolean defenseAuto) {
        static Info read(PacketByteBuf b, BlockPos hub) {
            return new Info(hub, b.readBoolean(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readBoolean());
        }
    }

    private static final int CW = 320, CH = 184;
    private static final int BG = 0xF00C0D0E, PANEL = 0xE0161819, EDGE = 0xFF34373A, TICK = 0xFF6E7378, RED = 0xFFE8382E, AMBER = 0xFFFFB02E,
            GREEN = 0xFF7FD67A, WHITE = 0xFFEEF0EC, GREY = 0xFF9AA09C, DIM = 0xFF5E6462, FAINT = 0xFF33373A;
    static Info info;
    private final BlockPos hub;
    private final KrlUi ui = new KrlUi();
    private float ox, oy, s = 1;
    private int ticks;

    public ScHubScreen(BlockPos hub) {
        super(Text.translatable("block.orbital_railgun.sc_hub"));
        this.hub = hub;
    }

    @Override
    protected void init() {
        s = Math.min(width * 0.92f / CW, height * 0.9f / CH);
        ox = (width - CW * s) / 2;
        oy = (height - CH * s) / 2;
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeBlockPos(hub);
            ClientPlayNetworking.send(ScNet.HUB_POLL, b);
        }
        if (client != null && client.player != null && client.player.squaredDistanceTo(hub.toCenterPos()) > 12 * 12) close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void action(int a) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBlockPos(hub);
        b.writeVarInt(a);
        ClientPlayNetworking.send(ScNet.HUB_ACTION, b);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(ox, oy, 0);
        ms.scale(s, s, 1);
        ui.begin(ctx, (mouseX - ox) / s, (mouseY - oy) / s);
        Info in = info != null && info.hub().equals(hub) ? info : null;
        float time = ticks + delta;

        ui.panel(0, 0, CW, CH, BG, EDGE, TICK);
        ui.fill(0, 0, CW, 14, 0xFF131516);
        ui.fill(0, 14, CW, 14.5f, 0xFF7A2420);
        ui.fill(4, 3, 7, 11, RED);
        ui.head("SPACE COMMAND", 10, 2.4f, WHITE, 0.82f);
        ui.text("COMMAND HUB  //  " + hub.getX() + " " + hub.getY() + " " + hub.getZ(), 10 + ui.hw("SPACE COMMAND", 0.82f) + 6, 5.3f, DIM, 0.42f);
        if (in == null) {
            ui.headC("LINKING...", CW / 2f, CH / 2f - 4, AMBER, 0.8f);
            ms.pop();
            return;
        }
        String st = in.online() ? "GRID ONLINE" : "GRID OFFLINE";
        int sc = in.online() ? GREEN : RED;
        float lw = ui.tw(st, 0.5f) + 10;
        ui.fill(CW - 4 - lw, 3, CW - 4, 11, KrlUi.alpha(sc, 0.16f));
        ui.outline(CW - 4 - lw, 3, CW - 4, 11, 0.5f, sc);
        ui.text(st, CW - 4 - lw + 5, 4.8f, sc, 0.5f);

        // ---- power
        card(6, 20, 160, 96, "POWER GRID");
        float sup = in.supply(), dem = in.demand();
        ui.headC(String.format(Locale.ROOT, "%.0f kW", sup), 46, 36, in.online() ? WHITE : RED, 1.1f);
        ui.textC("SUPPLY", 46, 49, GREY, 0.42f);
        ui.headC(String.format(Locale.ROOT, "%.0f kW", dem), 118, 36, WHITE, 1.1f);
        ui.textC("DEMAND", 118, 49, GREY, 0.42f);
        float ratio = dem <= 0 ? 1 : MathHelper.clamp(sup / dem, 0, 1);
        ui.meter(12, 58, 142, 4, ratio, ratio >= 0.999f ? GREEN : ratio > 0.5f ? AMBER : RED, 0xFF26292B);
        ui.textC(ratio >= 0.999f ? "ALL MODULES POWERED" : in.online() ? "LOAD SHEDDING: SATELLITES FIRST, THEN RAILGUNS" : "ADD SOLAR ARRAYS",
                83, 65, ratio >= 0.999f ? GREEN : AMBER, 0.38f);
        kv("LIFE SUPPORT", String.format(Locale.ROOT, "%.0f kW", Sc.HUB_KW), 12, 76, 142, in.online() ? GREEN : RED);
        kv("GRID REACH", Sc.LINK + " BLOCKS", 12, 84, 142, GREY);

        // ---- modules
        card(166, 20, 314, 96, "MODULES");
        row("SOLAR ARRAYS", in.solarLive() + " / " + in.solar(), 172, 32, in.solar() == 0 ? DIM : in.solarLive() == in.solar() ? GREEN : AMBER, in.solar(), in.solarLive());
        row("STATION RAILGUNS", in.railgunsLive() + " / " + in.railguns(), 172, 50, in.railguns() == 0 ? DIM : in.railgunsLive() == in.railguns() ? GREEN : AMBER,
                in.railguns(), in.railgunsLive());
        ui.text("STRIKE SATELLITE", 172, 68, GREY, 0.45f);
        ui.textR(in.sats() == 0 ? "NONE" : in.satCharge() >= 1f ? "READY" : String.format(Locale.ROOT, "%d%%", (int) (in.satCharge() * 100)), 308, 68,
                in.sats() == 0 ? DIM : in.satCharge() >= 1f ? GREEN : AMBER, 0.45f);
        ui.meter(172, 76, 136, 3, in.satCharge(), in.satCharge() >= 1f ? GREEN : RED, 0xFF26292B);
        ui.text(in.sats() == 0 ? "PLACE ONE WITHIN REACH OF THIS HUB" : "FIRE FROM THE UPLINK TABLET  //  SAT PAGE", 172, 84, DIM, 0.36f);

        // ---- debris radar and controls
        card(6, 102, 160, 178, "DEBRIS RADAR");
        float cx = 46, cy = 141, r = 30;
        ui.circle(cx, cy, r, 0.5f, 0xFF3A3E40, 0);
        ui.circle(cx, cy, r * 0.5f, 0.4f, 0xFF2A2E30, 6);
        float sweep = time * 0.08f;
        ui.line(cx, cy, cx + MathHelper.cos(sweep) * r, cy + MathHelper.sin(sweep) * r, 0.6f, KrlUi.alpha(0x7FD67A, 0.7f), 0);
        for (int i = 0; i < Math.min(12, in.threats()); i++) {
            float a = i * 2.4f + time * 0.01f, rr = r * (0.3f + 0.6f * ((i * 37 % 10) / 10f));
            ui.fill(cx + MathHelper.cos(a) * rr - 1, cy + MathHelper.sin(a) * rr - 1, cx + MathHelper.cos(a) * rr + 1, cy + MathHelper.sin(a) * rr + 1,
                    ((int) (time / 5)) % 2 == 0 ? RED : AMBER);
        }
        ui.fill(cx - 1.2f, cy - 1.2f, cx + 1.2f, cy + 1.2f, WHITE);
        ui.head(in.threats() == 0 ? "CLEAR" : in.threats() + " INBOUND", 84, 118, in.threats() == 0 ? GREEN : RED, 0.8f);
        ui.text("WITHIN 220 BLOCKS", 84, 130, DIM, 0.4f);
        ui.text(in.drill() > 0 ? String.format(Locale.ROOT, "DRILL  %ds LEFT", in.drill() / 20) : "NO DRILL", 84, 140, in.drill() > 0 ? AMBER : DIM, 0.42f);

        card(166, 102, 314, 178, "CONTROLS");
        ui.button(in.drill() > 0 ? "STOP DRILL" : "DEBRIS DRILL", 172, 116, 136, 18, AMBER, 0xFF101112, in.drill() > 0, true, 0.62f, () -> action(0));
        ui.text("SENDS A 45 s SWARM OF ROCKS AT THE STATION", 172, 136, DIM, 0.36f);
        ui.button(in.defenseAuto() ? "DEFENCE: AUTO" : "DEFENCE: HOLD", 172, 146, 136, 18, in.defenseAuto() ? GREEN : RED, 0xFF101112, in.defenseAuto(), true,
                0.62f, () -> action(1));
        ui.text(in.defenseAuto() ? "RAILGUNS FIRE AT WILL" : "RAILGUNS HOLD FIRE", 172, 166, DIM, 0.36f);
        ms.pop();
    }

    private void card(float x0, float y0, float x1, float y1, String title) {
        ui.panel(x0, y0, x1, y1, PANEL, EDGE, TICK);
        ui.fill(x0 + 0.5f, y0 + 0.5f, x1 - 0.5f, y0 + 9, 0xFF1B1E1F);
        ui.fill(x0 + 3, y0 + 3, x0 + 5, y0 + 6.5f, RED);
        ui.head(title, x0 + 8, y0 + 1.6f, WHITE, 0.52f);
    }

    private void kv(String k, String v, float x, float y, float w, int vc) {
        ui.kv(k, v, x, y, w, GREY, vc, FAINT, 0.45f);
    }

    private void row(String label, String val, float x, float y, int col, int total, int live) {
        ui.text(label, x, y, GREY, 0.45f);
        ui.textR(val, 308, y, col, 0.45f);
        int cells = Math.max(1, Math.min(12, total));
        ui.segments(x, y + 8, 136, 4, cells, Math.min(cells, live), col, 0xFF26292B, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ui.click((float) ((mouseX - ox) / s), (float) ((mouseY - oy) / s)) == 1 && client != null)
            client.getSoundManager().play(PositionedSoundInstance.master(Sc.S_LINK, 1.4f));
        return true;
    }
}
