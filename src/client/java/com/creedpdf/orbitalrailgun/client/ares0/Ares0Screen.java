package com.creedpdf.orbitalrailgun.client.ares0;

import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.client.ui.UiKit;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ARES-0 fire-control console: pick a planet above the horizon and fire. */
public class Ares0Screen extends Screen {
    private final Ares0Entity ares;
    private Body selected;
    private int ticks;
    private static final int W = 360, H = 220;

    public Ares0Screen(Ares0Entity ares) {
        super(Text.literal("ARES-0"));
        this.ares = ares;
        this.selected = ares.getTarget();
    }

    private List<Body> targets() {
        List<Body> out = new ArrayList<>();
        for (Body b : Body.ALL) if (b.destroyable()) out.add(b);
        return out;
    }

    @Override
    public void tick() {
        ticks++;
        if (ares.isRemoved() || client == null || client.player == null || client.player.squaredDistanceTo(ares) > 96 * 96) close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext dc, int mx, int my, float pt) {
        renderBackground(dc);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        float time = ticks + pt;
        UiKit.chamfer(dc, x0, y0, W, H, 12, UiKit.BG, 0xFF5070FF);
        UiKit.scanlines(dc, x0, y0, W, H, time);
        UiKit.spaced(dc, textRenderer, "ARES-0", x0 + 12, y0 + 10, 0xFFB0C8FF, 1.6f, 1.6f, false);
        dc.drawText(textRenderer, "PLANETARY CORE-COLLAPSE ARRAY", x0 + 92, y0 + 15, 0xFF7088C0, false);
        UiKit.glowLine(dc, x0 + 10, y0 + 30, x0 + W - 10, 0xFF5070FF);

        // target list
        int lx = x0 + 12, ly = y0 + 40;
        dc.drawText(textRenderer, "TARGETS", lx, ly, 0xFF7088C0, false);
        ly += 12;
        for (Body b : targets()) {
            boolean up = Ares0Entity.visible(ares.getWorld(), b);
            float e = ClientPlanets.elapsed(b, pt);
            boolean gone = e >= 0;
            boolean hover = mx >= lx && mx < lx + 170 && my >= ly && my < ly + 22;
            int edge = b == selected ? 0xFFB0C8FF : hover ? 0xFF6080D0 : 0xFF2A3A60;
            UiKit.chamfer(dc, lx, ly, 170, 22, 5, b == selected ? 0xE0182850 : UiKit.PANEL, edge);
            dc.drawText(textRenderer, b.displayName(), lx + 8, ly + 7, gone ? 0xFF706060 : 0xFFE0E8FF, false);
            String st = gone ? (e < Blast.DETONATE ? "COLLAPSING" : "DESTROYED") : up ? "ABOVE HORIZON" : "BELOW HORIZON";
            int col = gone ? 0xFFFF6050 : up ? 0xFF60FFA0 : 0xFF808898;
            dc.drawText(textRenderer, st, lx + 162 - textRenderer.getWidth(st), ly + 7, col, false);
            ly += 25;
        }

        // status + sky plot
        int rx = x0 + 196, ry = y0 + 40;
        UiKit.chamfer(dc, rx, ry, 152, 112, 8, UiKit.PANEL, 0xFF2A3A60);
        int cx = rx + 76, cy = ry + 104;
        for (int r = 30; r <= 90; r += 30) arcSemi(dc, cx, cy, r, 0x40607090);
        dc.fill(rx + 4, cy, rx + 148, cy + 1, 0x80607090);
        float sky = ares.getWorld().getSkyAngle(pt);
        for (Body b : targets()) {
            Vec3d d = b.skyDirection(sky);
            if (d.y <= 0 || ClientPlanets.gone(b)) continue;
            double az = Math.atan2(d.x, d.z), el = Math.asin(d.y);
            int px = cx + (int) (az / Math.PI * 70), py = cy - (int) (el / (Math.PI / 2) * 95);
            px = MathHelper.clamp(px, rx + 6, rx + 146);
            py = MathHelper.clamp(py, ry + 6, cy - 2);
            int col = b == selected ? 0xFFFFFFFF : 0xFF9FB8FF;
            dc.fill(px - 2, py - 2, px + 3, py + 3, col);
            dc.drawText(textRenderer, b.displayName().substring(0, Math.min(3, b.displayName().length())), px + 4, py - 3, col, false);
        }
        String state = switch (ares.getState()) {
            case IDLE -> selected == null ? "SELECT A TARGET" : "READY";
            case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", Math.min(100, ares.getStateTicks() * 100 / Ares0Entity.CHARGE_TICKS));
            case FIRING -> "FIRING";
            case COOLDOWN -> "COOLING";
            case DEPLOYING -> "DEPLOYING";
            default -> ares.getState().name();
        };
        dc.drawText(textRenderer, state, rx + 8, ry + 8, ares.getState() == Ares0Entity.State.FIRING && (ticks / 4) % 2 == 0 ? 0xFFFF6050 : 0xFFE0E8FF, false);

        // buttons
        boolean canFire = ares.getState() == Ares0Entity.State.IDLE && selected != null && !ClientPlanets.gone(selected)
                && ClientPlanets.elapsed(selected, 0f) < 0 && Ares0Entity.visible(ares.getWorld(), selected);
        button(dc, rx, y0 + 160, 152, 30, "FIRE", canFire, mx, my, 0xFFFF4A40);
        button(dc, rx, y0 + 194, 152, 18, "RETURN TO ORBIT", ares.getState() == Ares0Entity.State.IDLE
                || ares.getState() == Ares0Entity.State.COOLDOWN, mx, my, 0xFF7088C0);
        super.render(dc, mx, my, pt);
    }

    private void button(DrawContext dc, int x, int y, int w, int h, String label, boolean on, int mx, int my, int col) {
        boolean hover = on && mx >= x && mx < x + w && my >= y && my < y + h;
        UiKit.chamfer(dc, x, y, w, h, 6, hover ? UiKit.alpha(col, 0.35f) : UiKit.PANEL, on ? col : 0xFF303848);
        int tw = textRenderer.getWidth(label);
        dc.drawText(textRenderer, label, x + (w - tw) / 2, y + (h - 8) / 2, on ? 0xFFFFFFFF : 0xFF505868, false);
    }

    private static void arcSemi(DrawContext dc, int cx, int cy, int r, int col) {
        for (int i = 0; i <= 48; i++) {
            float a = MathHelper.PI * i / 48f;
            int x = cx + (int) (Math.cos(a) * r), y = cy - (int) (Math.sin(a) * r);
            dc.fill(x, y, x + 1, y + 1, col);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        int lx = x0 + 12, ly = y0 + 52;
        for (Body b : targets()) {
            if (mx >= lx && mx < lx + 170 && my >= ly && my < ly + 22) {
                selected = b;
                ClientPackets.sendAres0(ares.getId(), 0, b.ordinal());
                click(1.2f);
                return true;
            }
            ly += 25;
        }
        int rx = x0 + 196;
        if (mx >= rx && mx < rx + 152 && my >= y0 + 160 && my < y0 + 190 && selected != null) {
            ClientPackets.sendAres0(ares.getId(), 1, selected.ordinal());
            click(0.7f);
            close();
            return true;
        }
        if (mx >= rx && mx < rx + 152 && my >= y0 + 194 && my < y0 + 212) {
            ClientPackets.sendAres0(ares.getId(), 2, -1);
            click(1f);
            close();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void click(float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_CLICK, pitch));
    }
}
