package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.creedpdf.orbitalrailgun.client.ui.Term.*;

/** Starship navigation terminal: pick a destination, launch, or stow the ship. */
public class ShipScreen extends Screen {
    private final ShipEntity ship;
    private Body selected;
    private int ticks, armedUntil = -1, stowArmed = -1;
    private static final int W = 360, H = 210;
    private final List<Hit> hits = new ArrayList<>();
    private float ox, oy, sc = 1f;

    public ShipScreen(ShipEntity ship) {
        super(Text.literal("ARES Starship"));
        this.ship = ship;
    }

    @Override
    protected void init() {
        sc = Math.min(1.6f, Math.min((width - 20) / (float) W, (height - 20) / (float) H));
        ox = (width - W * sc) / 2f;
        oy = (height - H * sc) / 2f;
    }

    @Override
    public void tick() {
        ticks++;
        if (ship.isRemoved() || ship.inFlight() || client == null || client.player == null || client.player.squaredDistanceTo(ship) > 24 * 24) close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private String here() {
        if (client == null || client.world == null) return "?";
        if (client.world.getRegistryKey() == World.OVERWORLD) return "EARTH";
        Body b = com.creedpdf.orbitalrailgun.space.SpaceDims.planetOf(client.world.getRegistryKey());
        return b == null ? "?" : b.displayName();
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
        header(dc, textRenderer, W, "~/starship/navigation", "DOCKED // " + here(), OK, time);

        int x = 8, y = 30, w = 200;
        section(dc, textRenderer, "DESTINATIONS", x, y, w, RED);
        y += 12;
        for (Body b : ShipEntity.destinations(ship.getWorld())) {
            boolean gone = b != Body.EARTH && ClientPlanets.elapsed(b, 0f) >= 0;
            boolean sel = b == selected;
            boolean hover = !gone && mx >= x - 1 && mx < x + w && my >= y - 1 && my < y + 9;
            if (sel) dc.fill(x - 1, y - 1, x + w, y + 9, RED);
            else if (hover) dc.fill(x - 1, y - 1, x + w, y + 9, 0x40E8382E);
            int col = sel ? 0xFF050505 : gone ? FAINT : TEXT;
            text(dc, textRenderer, (sel ? "> " : "  ") + b.displayName().toLowerCase(Locale.ROOT).replace(' ', '_') + ".nav", x, y, col, 0.8f);
            String st = gone ? "DESTROYED" : String.format(Locale.ROOT, "%.2f g", b.gravity);
            float sw = width(textRenderer, st, 0.7f);
            text(dc, textRenderer, st, x + w - sw - 2, y + 1, sel ? 0xFF050505 : gone ? RED_DIM : DIM, 0.7f);
            if (!gone) {
                final Body fb = b;
                hits.add(new Hit(x - 1, y - 1, w + 1, 10, () -> { selected = fb; armedUntil = -1; }, true));
            }
            y += 12;
        }

        int rx = 222, rw = W - rx - 8, ry = 30;
        section(dc, textRenderer, "FLIGHT PLAN", rx, ry, rw, RED);
        ry += 14;
        kv(dc, textRenderer, "ORIGIN", here(), rx, ry, rw, TEXT, 0.7f);
        ry += 10;
        kv(dc, textRenderer, "TARGET", selected == null ? "--" : selected.displayName(), rx, ry, rw, selected == null ? DIM : RED, 0.7f);
        ry += 10;
        kv(dc, textRenderer, "FLIGHT TIME", String.format(Locale.ROOT, "%ds", (ShipEntity.IGNITION_TICKS + ShipEntity.ASCENT_TICKS
                + ShipEntity.CRUISE_TICKS + ShipEntity.DESCENT_PILOTED + ShipEntity.LANDING_TICKS) / 20), rx, ry, rw, TEXT, 0.7f);
        ry += 10;
        kv(dc, textRenderer, "CABIN", "PRESSURISED", rx, ry, rw, OK, 0.7f);
        ry += 14;
        para(dc, textRenderer, "Bring an EVA suit: only Earth has air outside the ship.", rx, ry, rw, DIM, 0.65f);

        int by = H - 46;
        hline(dc, 8, W - 8, by - 8, RULE);
        boolean armed = ticks < armedUntil;
        hits.add(cmd(dc, textRenderer, armed ? "CONFIRM // LAUNCH" : "LAUNCH", 8, by, RED, selected != null, mx, my, 1.1f, () -> {
            if (ticks < armedUntil) {
                ClientPackets.sendShip(ship.getId(), 0, selected.ordinal());
                close();
            } else {
                armedUntil = ticks + 60;
                beep();
            }
        }));
        boolean sArmed = ticks < stowArmed;
        hits.add(cmd(dc, textRenderer, sArmed ? "CONFIRM // RETURN TO ORBIT" : "RETURN TO ORBIT", 200, by + 1, DIM, true, mx, my, 0.85f, () -> {
            if (ticks < stowArmed) {
                ClientPackets.sendShip(ship.getId(), 1, -1);
                close();
            } else {
                stowArmed = ticks + 60;
                beep();
            }
        }));
        text(dc, textRenderer, armed ? "> press again. strap in." : selected == null ? "> select a destination" : "> flight plan locked",
                8, by + 16, armed ? RED : DIM, 0.7f);
        footer(dc, textRenderer, W, H, time, "ESC close");
        corners(dc, 1, 1, W - 2, H - 2, 8, RED_DIM);
        dc.getMatrices().pop();
    }

    private void beep() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_COUNTDOWN_BEEP, 1.2f));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float mx = (float) ((mouseX - ox) / sc), my = (float) ((mouseY - oy) / sc);
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(mx, my)) {
                if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_CLICK, h.enabled() ? 1.1f : 0.6f));
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
}
