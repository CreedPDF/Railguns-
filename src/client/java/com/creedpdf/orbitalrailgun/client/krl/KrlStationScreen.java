package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.Krl;
import com.creedpdf.orbitalrailgun.krl.KrlLoadout;
import com.creedpdf.orbitalrailgun.krl.KrlNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;

/**
 * The Missile Integration Station: refit the kinetic missile in your main hand. Pick the warhead, pack its explosive
 * charge, choose the motor and the paint; the read-outs show what the missile will do and the bill of materials, and
 * INTEGRATE applies it (the server checks and takes the materials).
 */
public class KrlStationScreen extends Screen {
    private static final float VW = 380, VH = 230;
    private static final int BG = 0xF2141715, PANEL = 0xE01C201D, EDGE = 0xFF464E48, TICK = 0xFF8F9A90, AMBER = 0xFFFFB02E, WHITE = 0xFFEEF0E8,
            GREY = 0xFF939A92, DIM = 0xFF5E665F, GREEN = 0xFF7FD67A, RED = 0xFFFF5040;
    private static final int[] WARHEAD_COLOUR = {0xFFB8BCB4, 0xFFFFC230, 0xFFFF7A1E, 0xFFFF4A2A, 0xFFA0A6A0, 0xFFE0D060};

    private final BlockPos station;
    private final KrlUi ui = new KrlUi();
    private int warhead, charge, motor, livery;
    private boolean picked;
    private float s, ox, oy;
    private int ticks;

    public KrlStationScreen(BlockPos station) {
        super(Text.literal("KRL Missile Integration"));
        this.station = station;
    }

    private ItemStack held() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
    }

    private KrlLoadout have() {
        return KrlLoadout.of(held());
    }

    private KrlLoadout want() {
        return new KrlLoadout(warhead, charge, motor, livery);
    }

    @Override
    protected void init() {
        if (!picked) {
            KrlLoadout h = have();
            warhead = h.warhead();
            charge = h.charge();
            motor = h.motor();
            livery = h.livery();
            picked = true;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        ticks++;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !held().isOf(Krl.SPEAR) || mc.player.squaredDistanceTo(Vec3d.ofCenter(station)) > 64) close();
    }

    @Override
    public void renderBackground(DrawContext context) {
    }

    private int count(ItemStack need) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? 0 : mc.player.getInventory().count(need.getItem());
    }

    private boolean creative() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getAbilities().creativeMode;
    }

    @Override
    public void render(DrawContext dc, int mouseX, int mouseY, float delta) {
        s = Math.min(width / VW, height / VH);
        ox = (width - VW * s) / 2;
        oy = (height - VH * s) / 2;
        dc.fill(0, 0, width, height, 0xB0000000);
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(ox, oy, 0);
        ms.scale(s, s, 1);
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;
        ui.begin(dc, mx, my);
        float time = ticks + delta;

        ui.panel(0, 0, VW, VH, BG, EDGE, TICK);
        ui.fill(1, 1, VW - 1, 15, 0xFF101311);
        ui.head("KRL MISSILE INTEGRATION STATION", 6, 3, AMBER, 0.85f);
        ui.textR(String.format(Locale.ROOT, "BENCH %d %d %d   //   WARHEAD INTEGRATION", station.getX(), station.getY(), station.getZ()), VW - 6, 5.5f,
                GREY, 0.5f);
        for (float y = 17; y < VH - 2; y += 3) ui.fill(1, y, VW - 1, y + 0.35f, 0x07B0FFB0);

        KrlLoadout have = have(), want = want();
        drawPreview(want, time);
        drawOptions(want);
        drawStats(have, want);
        drawCost(have, want);
        ms.pop();
    }

    // ---- the missile, big, in the chosen paint, with its warhead section marked

    private void drawPreview(KrlLoadout w, float time) {
        ui.panel(5, 19, 120, 225, PANEL, EDGE, TICK);
        ui.head("ASSEMBLY", 9, 21, AMBER, 0.6f);
        ItemStack show = w.applyTo(new ItemStack(Krl.SPEAR));
        var ms = ui.dc.getMatrices();
        ms.push();
        // the GUI item view draws the missile corner to corner; scale it up to fill the panel
        ms.translate(18, 34, 100);
        ms.scale(5.6f, 5.6f, 1);
        ui.dc.drawItem(show, 0, 0);
        ms.pop();
        // tags along the missile
        int wc = WARHEAD_COLOUR[w.warhead()];
        ui.fill(9, 132, 116, 132.5f, EDGE);
        ui.text("WARHEAD", 9, 135, DIM, 0.45f);
        ui.head(KrlLoadout.WARHEADS[w.warhead()], 9, 140, wc, 0.62f);
        ui.text("CHARGE", 9, 151, DIM, 0.45f);
        ui.text(w.warhead() == KrlLoadout.KINETIC ? "NONE - INERT" : w.charge() + " / " + KrlLoadout.MAX_CHARGE + " BLOCKS TNT", 9, 156, WHITE, 0.5f);
        ui.text("MOTOR", 9, 164, DIM, 0.45f);
        ui.text(KrlLoadout.MOTORS[w.motor()] + "  //  HTPB / AP", 9, 169, WHITE, 0.5f);
        ui.text("LIVERY", 9, 177, DIM, 0.45f);
        ui.text(Krl.LIVERY_NAMES[w.livery()].toUpperCase(Locale.ROOT), 9, 182, WHITE, 0.5f);
        ui.fill(9, 190, 116, 190.5f, EDGE);
        ui.para(KrlLoadout.WARHEAD_INFO[w.warhead()], 9, 193, 105, GREY, 0.45f, 5f);
        // a slow scan line over the assembly view
        float sy = 34 + ((time * 0.8f) % 92);
        ui.fill(8, sy, 117, sy + 0.4f, 0x3090FFA0);
    }

    // ---- the choices

    private void drawOptions(KrlLoadout w) {
        float x0 = 125, x1 = 255;
        ui.panel(x0, 19, x1, 225, PANEL, EDGE, TICK);
        float y = 21;
        ui.head("WARHEAD", x0 + 4, y, AMBER, 0.6f);
        y += 10;
        for (int i = 0; i < KrlLoadout.WARHEADS.length; i++) {
            int col = i % 2, row = i / 2;
            float bx = x0 + 4 + col * 62, by = y + row * 14;
            final int id = i;
            ui.button(KrlLoadout.WARHEADS[i], bx, by, 60, 12, WARHEAD_COLOUR[i], 0xFF101311, warhead == i, true, 0.5f, () -> {
                warhead = id;
                if (id == KrlLoadout.KINETIC) charge = 0;
                else if (charge == 0) charge = 1;
                click(1.2f);
            });
        }
        y += 46;
        ui.head("EXPLOSIVE CHARGE", x0 + 4, y, AMBER, 0.6f);
        boolean kin = warhead == KrlLoadout.KINETIC;
        ui.textR(kin ? "INERT" : charge + " / " + KrlLoadout.MAX_CHARGE, x1 - 4, y + 1.5f, kin ? DIM : WHITE, 0.5f);
        y += 10;
        int hv = ui.segments(x0 + 4, y, x1 - x0 - 8, 9, KrlLoadout.MAX_CHARGE, kin ? 0 : charge, kin ? DIM : AMBER, 0xFF262B27, !kin);
        for (int i = 0; i < KrlLoadout.MAX_CHARGE; i++) {
            float gap = 1.2f, cw = (x1 - x0 - 8 - gap * (KrlLoadout.MAX_CHARGE - 1)) / KrlLoadout.MAX_CHARGE;
            final int lvl = i + 1;
            ui.hits.add(new KrlUi.Hit(x0 + 4 + i * (cw + gap), y, cw, 9, () -> {
                charge = charge == lvl ? lvl - 1 : lvl;
                if (charge == 0) charge = 1;
                click(1f + lvl * 0.08f);
            }, !kin));
        }
        if (hv >= 0) ui.text("SET CHARGE " + (hv + 1), x0 + 4, y + 11, GREY, 0.42f);
        y += 20;
        ui.head("MOTOR", x0 + 4, y, AMBER, 0.6f);
        y += 10;
        for (int i = 0; i < KrlLoadout.MOTORS.length; i++) {
            final int id = i;
            float bw = (x1 - x0 - 8 - 4) / 3f;
            ui.button(KrlLoadout.MOTORS[i], x0 + 4 + i * (bw + 2), y, bw, 12, 0xFFF0D060, 0xFF101311, motor == i, true, 0.5f, () -> {
                motor = id;
                click(1.1f);
            });
        }
        y += 16;
        ui.text(String.format(Locale.ROOT, "RANGE %,.0f m   MACH %.0f AT IMPACT", KrlLoadout.RANGE[motor], KrlLoadout.MACH[motor]), x0 + 4, y, GREY, 0.45f);
        y += 10;
        ui.head("LIVERY", x0 + 4, y, AMBER, 0.6f);
        y += 10;
        int[] swatch = {0xFF9A9C9A, 0xFF41452A, 0xFFD9D9D4, 0xFFA1875E, 0xFF333536, 0xFFEEEEEA};
        for (int i = 0; i < Krl.LIVERIES.length; i++) {
            float bx = x0 + 4 + i * 20.5f;
            boolean sel = livery == i, hvv = ui.hover(bx, y, 18, 12);
            ui.fill(bx, y, bx + 18, y + 12, swatch[i]);
            if (i == 5) {
                ui.fill(bx, y, bx + 9, y + 6, 0xFF101010);
                ui.fill(bx + 9, y + 6, bx + 18, y + 12, 0xFF101010);
            }
            ui.outline(bx - 0.8f, y - 0.8f, bx + 18.8f, y + 12.8f, sel ? 1f : 0.5f, sel ? AMBER : hvv ? WHITE : EDGE);
            final int id = i;
            ui.hits.add(new KrlUi.Hit(bx, y, 18, 12, () -> {
                livery = id;
                click(1.3f);
            }, true));
        }
        y += 15;
        ui.text(Krl.LIVERY_NAMES[livery].toUpperCase(Locale.ROOT), x0 + 4, y, WHITE, 0.48f);
    }

    // ---- what it will do

    private void drawStats(KrlLoadout have, KrlLoadout w) {
        float x0 = 260, x1 = 375;
        ui.panel(x0, 19, x1, 112, PANEL, EDGE, TICK);
        ui.head("PERFORMANCE", x0 + 4, 21, AMBER, 0.6f);
        float y = 31;
        float rad = w.power() * 2f;
        row("YIELD", String.format(Locale.ROOT, "%.1f TNT-EQ", w.power() / 4f), x0, x1, y, cmp(w.power(), have.power()));
        y += 6;
        row("BLAST RADIUS", String.format(Locale.ROOT, "~%.0f m", rad), x0, x1, y, cmp(w.power(), have.power()));
        y += 6;
        String eff = switch (w.warhead()) {
            case KrlLoadout.THERMOBARIC -> "FIREBALL";
            case KrlLoadout.INCENDIARY -> String.format(Locale.ROOT, "FIRES %d m", 10 + 2 * w.charge());
            case KrlLoadout.BUNKER -> String.format(Locale.ROOT, "DEPTH %d m", 8 + 3 * w.charge());
            case KrlLoadout.CLUSTER -> String.format(Locale.ROOT, "%d BOMBLETS", 6 + w.charge());
            case KrlLoadout.HE -> "BLAST";
            default -> "IMPACT ONLY";
        };
        row("EFFECT", eff, x0, x1, y, WHITE);
        y += 6;
        row("RANGE", String.format(Locale.ROOT, "%,.0f m", w.range()), x0, x1, y, cmp((float) w.range(), (float) have.range()));
        y += 6;
        row("IMPACT SPEED", String.format(Locale.ROOT, "MACH %.0f", w.mach()), x0, x1, y, cmp((float) w.mach(), (float) have.mach()));
        y += 6;
        double v = w.mach() * 340.3;
        row("KINETIC ENERGY", String.format(Locale.ROOT, "%,.0f MJ", 0.5 * 1500 * v * v / 1e6), x0, x1, y, WHITE);
        y += 6;
        row("APOGEE FACTOR", String.format(Locale.ROOT, "x%.2f", w.heightMul()), x0, x1, y, WHITE);
        y += 9;
        // yield bar
        ui.text("YIELD", x0 + 4, y, DIM, 0.42f);
        ui.meter(x0 + 26, y + 0.5f, x1 - x0 - 30, 3, w.power() / 15.5f, WARHEAD_COLOUR[w.warhead()], 0xFF262B27);
        y += 6;
        ui.text("RANGE", x0 + 4, y, DIM, 0.42f);
        ui.meter(x0 + 26, y + 0.5f, x1 - x0 - 30, 3, (float) (w.range() / KrlLoadout.MAX_RANGE), 0xFFF0D060, 0xFF262B27);
    }

    private int cmp(float now, float was) {
        return now > was + 1e-3 ? GREEN : now < was - 1e-3 ? RED : WHITE;
    }

    private void row(String k, String v, float x0, float x1, float y, int vc) {
        ui.kv(k, v, x0 + 4, y, x1 - x0 - 8, GREY, vc, 0xFF3A403B, 0.46f);
    }

    // ---- what it costs, and the button

    private void drawCost(KrlLoadout have, KrlLoadout w) {
        float x0 = 260, x1 = 375;
        ui.panel(x0, 116, x1, 225, PANEL, EDGE, TICK);
        ui.head("BILL OF MATERIALS", x0 + 4, 118, AMBER, 0.6f);
        List<ItemStack> cost = have.costTo(w);
        boolean cr = creative();
        boolean can = true;
        float y = 129;
        if (cost.isEmpty()) {
            ui.text(have.equals(w) ? "NO CHANGES" : "NO MATERIALS NEEDED", x0 + 4, y, GREY, 0.48f);
            y += 8;
        }
        for (ItemStack c : cost) {
            int got = count(c);
            boolean ok = cr || got >= c.getCount();
            if (!ok) can = false;
            var ms = ui.dc.getMatrices();
            ms.push();
            ms.translate(x0 + 4, y - 1, 0);
            ms.scale(0.6f, 0.6f, 1);
            ui.dc.drawItem(c, 0, 0);
            ms.pop();
            ui.text(c.getName().getString().toUpperCase(Locale.ROOT), x0 + 16, y + 1, WHITE, 0.45f);
            ui.textR(cr ? c.getCount() + "  (CREATIVE)" : got + " / " + c.getCount(), x1 - 4, y + 1, ok ? GREEN : RED, 0.45f);
            y += 11;
        }
        if (cr && !cost.isEmpty()) ui.text("CREATIVE: NOTHING IS TAKEN", x0 + 4, y, DIM, 0.42f);
        boolean changed = !have.equals(w);
        boolean go = changed && can;
        ui.button(changed ? (can ? "INTEGRATE" : "MISSING MATERIALS") : "UP TO DATE", x0 + 4, 205, x1 - x0 - 8, 16, can ? 0xFF7FD67A : RED,
                0xFF101311, false, go, 0.75f, this::apply);
        if (held().getCount() > 1) ui.text("REFITS ONE MISSILE OF THE STACK", x0 + 4, 199, DIM, 0.4f);
    }

    private void apply() {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBlockPos(station);
        want().write(b);
        ClientPlayNetworking.send(KrlNet.STATION_APPLY, b);
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(Krl.S_ARM, 1f, 0.8f));
    }

    private void click(float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(Krl.S_CLICK, pitch, 0.55f));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int r = ui.click((float) ((mouseX - ox) / s), (float) ((mouseY - oy) / s));
            if (r == 0) click(0.6f);
            if (r >= 0) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @SuppressWarnings("unused")
    private static float clamp(float v) {
        return MathHelper.clamp(v, 0, 1);
    }
}
