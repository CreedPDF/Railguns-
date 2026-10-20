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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.creedpdf.orbitalrailgun.client.ui.Term.*;

/** ARES-0 on-site terminal: pick a body above the horizon and collapse its core. */
public class Ares0Screen extends Screen {
    private final Ares0Entity ares;
    private Body selected;
    private int ticks;
    private int armedUntil = -1;
    private static final int W = 380, H = 220;
    private final List<Hit> hits = new ArrayList<>();
    private float ox, oy, sc = 1f;

    public Ares0Screen(Ares0Entity ares) {
        super(Text.literal("ARES-0"));
        this.ares = ares;
        this.selected = ares.getTarget();
    }

    @Override
    protected void init() {
        sc = Math.min(1.6f, Math.min((width - 20) / (float) W, (height - 20) / (float) H));
        ox = (width - W * sc) / 2f;
        oy = (height - H * sc) / 2f;
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
    public void render(DrawContext dc, int mouseX, int mouseY, float pt) {
        dc.fill(0, 0, width, height, 0x90000000);
        float time = ticks + pt;
        float mx = (mouseX - ox) / sc, my = (mouseY - oy) / sc;
        hits.clear();
        dc.getMatrices().push();
        dc.getMatrices().translate(ox, oy, 0);
        dc.getMatrices().scale(sc, sc, 1f);
        backdrop(dc, W, H, time);
        String state = switch (ares.getState()) {
            case IDLE -> selected == null ? "SELECT TARGET" : "READY";
            case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", Math.min(100, ares.getStateTicks() * 100 / Ares0Entity.CHARGE_TICKS));
            case FIRING -> "FIRING";
            case COOLDOWN -> "COOLING";
            case DEPLOYING -> "DEPLOYING";
            default -> ares.getState().name();
        };
        header(dc, textRenderer, W, "~/ares-0/core_collapse", state, ares.getState() == Ares0Entity.State.FIRING
                && (ticks / 4) % 2 == 0 ? RED : ORANGE, time);

        // target table
        int x = 8, y = 30, w = 196;
        section(dc, textRenderer, "TARGETS", x, y, w, ORANGE);
        y += 12;
        for (Body b : targets()) {
            boolean up = Ares0Entity.visible(ares.getWorld(), b);
            float e = ClientPlanets.elapsed(b, pt);
            boolean gone = e >= 0;
            boolean sel = b == selected;
            boolean hover = !gone && mx >= x - 1 && mx < x + w && my >= y - 1 && my < y + 9;
            if (sel) dc.fill(x - 1, y - 1, x + w, y + 9, ORANGE);
            else if (hover) dc.fill(x - 1, y - 1, x + w, y + 9, 0x40FF7A1E);
            int col = sel ? 0xFF050505 : gone ? FAINT : TEXT;
            text(dc, textRenderer, (sel ? "> " : "  ") + b.displayName().toLowerCase(Locale.ROOT).replace(' ', '_') + ".body", x, y, col, 0.8f);
            String st = gone ? (e < Blast.DETONATE ? "COLLAPSING" : "DESTROYED") : up ? "ABOVE HORIZON" : "BELOW HORIZON";
            float sw = width(textRenderer, st, 0.7f);
            text(dc, textRenderer, st, x + w - sw - 2, y + 1, sel ? 0xFF050505 : gone ? RED_DIM : up ? OK : DIM, 0.7f);
            if (!gone) {
                final Body fb = b;
                hits.add(new Hit(x - 1, y - 1, w + 1, 10, () -> {
                    selected = fb;
                    armedUntil = -1;
                    ClientPackets.sendAres0(ares.getId(), 0, fb.ordinal());
                }, true));
            }
            y += 12;
        }

        // sky plot
        int rx = 216, ry = 30, rw = W - rx - 8, rh = 110;
        section(dc, textRenderer, "SKY", rx, ry, rw, ORANGE);
        int cx = rx + rw / 2, cy = ry + rh;
        for (int r = 30; r <= 90; r += 30) arcSemi(dc, cx, cy, r, 0x40FF7A1E);
        hline(dc, rx, rx + rw, cy, RULE_HI);
        float sky = ares.getWorld().getSkyAngle(pt);
        for (Body b : targets()) {
            Vec3d d = b.skyDirection(sky);
            if (d.y <= 0 || ClientPlanets.gone(b)) continue;
            double az = Math.atan2(d.x, d.z), el = Math.asin(d.y);
            int px = MathHelper.clamp(cx + (int) (az / Math.PI * (rw / 2f - 6)), rx + 4, rx + rw - 4);
            int py = MathHelper.clamp(cy - (int) (el / (Math.PI / 2) * (rh - 14)), ry + 12, cy - 2);
            int col = b == selected ? 0xFFFFFFFF : ORANGE;
            dc.fill(px - 1, py - 1, px + 2, py + 2, col);
            if (b == selected) UiKit.brackets(dc, px - 4, py - 4, 9, 9, 2, col);
            text(dc, textRenderer, b.displayName().substring(0, Math.min(3, b.displayName().length())).toLowerCase(Locale.ROOT), px + 4, py - 2, col, 0.6f);
        }

        // commands
        int by = H - 48;
        hline(dc, 8, W - 8, by - 8, RULE);
        boolean canFire = ares.getState() == Ares0Entity.State.IDLE && selected != null && !ClientPlanets.gone(selected)
                && ClientPlanets.elapsed(selected, 0f) < 0 && Ares0Entity.visible(ares.getWorld(), selected);
        boolean armed = ticks < armedUntil;
        hits.add(cmd(dc, textRenderer, armed ? "CONFIRM // COLLAPSE CORE" : "EXECUTE CORE COLLAPSE", 8, by, ORANGE, canFire, mx, my, 1.1f, () -> {
            if (ticks < armedUntil) {
                ClientPackets.sendAres0(ares.getId(), 1, selected.ordinal());
                close();
            } else {
                armedUntil = ticks + 60;
                if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_COUNTDOWN_BEEP, 1.2f));
            }
        }));
        hits.add(cmd(dc, textRenderer, "RETURN TO ORBIT", 230, by + 1, DIM, ares.getState() == Ares0Entity.State.IDLE
                || ares.getState() == Ares0Entity.State.COOLDOWN, mx, my, 0.85f, () -> {
            ClientPackets.sendAres0(ares.getId(), 2, -1);
            close();
        }));
        text(dc, textRenderer, armed ? "> press again. this cannot be undone." : selected == null ? "> select a body" : "> target acquired",
                8, by + 16, armed ? RED : DIM, 0.7f);
        footer(dc, textRenderer, W, H, time, "ESC close");
        corners(dc, 1, 1, W - 2, H - 2, 8, 0xFF6A2A0A);
        dc.getMatrices().pop();
    }

    private static void arcSemi(DrawContext dc, int cx, int cy, int r, int col) {
        for (int i = 0; i <= 48; i++) {
            float a = MathHelper.PI * i / 48f;
            int x = cx + (int) (Math.cos(a) * r), y = cy - (int) (Math.sin(a) * r);
            dc.fill(x, y, x + 1, y + 1, col);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float mx = (float) ((mouseX - ox) / sc), my = (float) ((mouseY - oy) / sc);
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(mx, my)) {
                click(h.enabled() ? 1.1f : 0.6f);
                if (h.enabled()) h.action().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void click(float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_CLICK, pitch));
    }
}
