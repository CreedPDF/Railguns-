package com.creedpdf.orbitalrailgun.client.panel;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.ui.UiKit;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.Loadout.Category;
import com.creedpdf.orbitalrailgun.loadout.Loadout.Option;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.creedpdf.orbitalrailgun.client.ui.UiKit.*;

/**
 * The railgun's physical console, zoomed into.
 *
 * <p>Right-clicking the console in the core's hatch flies the camera up to it
 * ({@code CameraMixin} reads {@link #active()}); when it arrives the console boots. Three pages:</p>
 * <ul>
 *   <li><b>STATUS</b>: state, live schematic, progress and the main action (DEPLOY, RETRACT +
 *       confirm, RETURN TO ORBIT).</li>
 *   <li><b>LOADOUT</b>: configure muzzle device, ammunition and up to four modules, then transmit
 *       the loadout; the parts drop from orbital storage and dock on deployment. Only on a landed pod.</li>
 *   <li><b>SYSTEMS</b>: the firing solution the current loadout produces.</li>
 * </ul>
 */
public class PanelScreen extends Screen {
    private static final Identifier DISPLAY = RailgunMod.id("textures/gui/panel_display.png");
    public static final int ZOOM_TICKS = 12;
    private static final float DX0 = 30f / 512, DY0 = 24f / 288, DX1 = 482f / 512, DY1 = 264f / 288;
    private static final int CW = 480, CH = 256;

    @Nullable private static PanelScreen open;

    private final RailgunEntity railgun;
    private final Vec3d camPos;
    private final float camYaw, camPitch;
    private int ticks;
    private int closeTicks = -1;
    private int armedTicks;
    private int bootTicks = -1;
    private float ox, oy, s = 1f;
    private Page page = Page.STATUS;
    private Loadout draft;
    @Nullable private Option hovered;
    private final List<Hit> hits = new ArrayList<>();

    private enum Page { STATUS, LOADOUT, SYSTEMS }

    private record Hit(int x, int y, int w, int h, Runnable action, boolean enabled) {
        boolean in(float mx, float my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    public PanelScreen(RailgunEntity railgun) {
        super(Text.literal("ARES-9 Field Console"));
        this.railgun = railgun;
        camPos = railgun.getPanelPos().add(0, 0.02, -1.02);
        camYaw = 0f;
        camPitch = 1.0f;
        draft = railgun.getLoadout();
    }

    // ---- Camera hook (CameraMixin) -------------------------------------------------------------

    public static float zoom(float tickDelta) {
        PanelScreen p = open;
        if (p == null) return 0f;
        float t = p.closeTicks >= 0 ? 1f - (p.closeTicks + tickDelta) / ZOOM_TICKS : (p.ticks + tickDelta) / ZOOM_TICKS;
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    @Nullable
    public static PanelScreen active() {
        return open;
    }

    public Vec3d camPos() { return camPos; }
    public float camYaw() { return camYaw; }
    public float camPitch() { return camPitch; }

    // ---- Lifecycle -----------------------------------------------------------------------------

    @Override
    protected void init() {
        open = this;
        float dw = (DX1 - DX0) * width, dh = (DY1 - DY0) * height;
        s = Math.min(dw / CW, dh / CH);
        ox = DX0 * width + (dw - CW * s) / 2f;
        oy = DY0 * height + (dh - CH * s) / 2f;
        if (ticks == 0) play(ModSounds.UI_TABLET_OPEN, 0.8f);
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks == ZOOM_TICKS) bootTicks = 0;
        if (bootTicks >= 0) bootTicks++;
        if (armedTicks > 0) armedTicks--;
        if (closeTicks >= 0) {
            if (++closeTicks >= ZOOM_TICKS) {
                open = null;
                MinecraftClient.getInstance().setScreen(null);
            }
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (railgun.isRemoved() || mc.player == null
                || mc.player.getEyePos().distanceTo(railgun.getPanelPos()) > RailgunConfig.PANEL_REACH + 3) {
            beginClose();
        }
        if (page == Page.LOADOUT && !canConfigure() && railgun.getState() != RailgunState.ARMING) page = Page.STATUS;
    }

    private void beginClose() {
        if (closeTicks < 0) {
            closeTicks = Math.max(0, ZOOM_TICKS - Math.min(ticks, ZOOM_TICKS));
            play(ModSounds.UI_TABLET_CLOSE, 0.8f);
        }
    }

    @Override public void removed() { if (open == this) open = null; }
    @Override public boolean shouldPause() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    private boolean live() {
        return closeTicks < 0 && ticks >= ZOOM_TICKS;
    }

    private boolean canConfigure() {
        return railgun.getState() == RailgunState.POD;
    }

    // ---- Actions ---------------------------------------------------------------------------------

    private void send(RailgunEntity.PanelAction a) {
        ClientPackets.sendPanelAction(railgun.getId(), a);
        beginClose();
    }

    private void transmit() {
        ClientPackets.sendLoadoutOrder(railgun.getId(), draft.raw());
        play(ModSounds.UI_LOADOUT_ORDER, 1f);
        page = Page.STATUS;
    }

    // ---- Input -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!live() || button != 0) return true;
        float mx = (float) ((mouseX - ox) / s), my = (float) ((mouseY - oy) / s);
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(mx, my)) {
                if (h.enabled()) h.action().run();
                else play(ModSounds.UI_DENIED, 1.2f);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || (client != null && client.options.inventoryKey.matchesKey(keyCode, scanCode))) {
            beginClose();
        }
        return true;
    }

    // ---- Rendering -------------------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext context) {
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float z = zoom(delta);
        float alpha = z >= 0.999f ? 1f : MathHelper.clamp((z - 0.85f) / 0.15f, 0f, 1f);
        if (alpha <= 0f) return;
        RenderSystem.enableBlend();
        ctx.setShaderColor(1f, 1f, 1f, alpha);
        ctx.drawTexture(DISPLAY, 0, 0, width, height, 0, 0, 512, 288, 512, 288);
        ctx.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        if (alpha < 0.98f || closeTicks >= 0) return;

        float time = ticks + delta;
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;
        hits.clear();
        hovered = null;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(ox, oy, 0);
        ctx.getMatrices().scale(s, s, 1f);

        ctx.fill(0, 0, CW, CH, BG);
        hexGrid(ctx, 0, 0, CW, CH, time, 0x1833E0FF);
        drawHeader(ctx, time);
        drawNav(ctx, mx, my, time);
        switch (page) {
            case STATUS -> drawStatus(ctx, mx, my, time);
            case LOADOUT -> drawLoadout(ctx, mx, my, time);
            case SYSTEMS -> drawSystems(ctx, time);
        }
        scanlines(ctx, 0, 0, CW, CH, time);
        brackets(ctx, 1, 1, CW - 2, CH - 2, 14, CYAN);

        // Boot sequence: typed diagnostics, then a sweep reveal.
        if (bootTicks >= 0 && bootTicks < 16) {
            float b = (bootTicks + delta) / 16f;
            int y = (int) (b * CH);
            ctx.fill(0, y, CW, CH, 0xF0030608);
            ctx.fill(0, y, CW, y + 2, CYAN);
            String[] boot = {"ARES-9 FIELD CONSOLE", "LINK ......... OK", "REACTOR ...... OK", "ORBITAL STORAGE . LINKED"};
            for (int i = 0; i < boot.length; i++) {
                int shown = Math.min(boot[i].length(), (int) ((bootTicks + delta) * 3) - i * 6);
                if (shown > 0) ctx.drawText(textRenderer, boot[i].substring(0, shown), 30, CH - 60 + i * 11, CYAN, false);
            }
        }
        ctx.getMatrices().pop();
    }

    private void drawHeader(DrawContext ctx, float time) {
        chamfer(ctx, 0, 0, CW, 22, 8, 0xE0081420, CYAN_DIM);
        spaced(ctx, textRenderer, "ARES-9", 12, 5, CYAN, 2f, 1.4f, true);
        ctx.drawText(textRenderer, "// FIELD CONSOLE", 82, 8, MUTED, false);
        RailgunState st = railgun.getState();
        String state = Text.translatable(st.translationKey()).getString();
        int tw = textRenderer.getWidth(state) + 8;
        tag(ctx, textRenderer, state, CW - tw - 70, 6, stateColor(st, time));
        String unit = String.format(Locale.ROOT, "UNIT %04d", Math.abs(railgun.getUuid().hashCode()) % 10000);
        ctx.drawText(textRenderer, unit, CW - 64, 8, MUTED, false);
        glowLine(ctx, 8, 22, CW - 8, CYAN);
    }

    private void drawNav(DrawContext ctx, float mx, float my, float time) {
        Page[] pages = Page.values();
        String[] names = {"STATUS", "LOADOUT", "SYSTEMS"};
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            int x = 8, y = 32 + i * 30, w = 74, h = 24;
            boolean en = p != Page.LOADOUT || canConfigure() || railgun.getState() == RailgunState.ARMING;
            boolean sel = page == p;
            boolean hov = en && mx >= x && mx < x + w && my >= y && my < y + h;
            chamfer(ctx, x, y, w, h, 6, sel ? 0xE0123A4A : hov ? 0xC0102430 : PANEL, sel ? CYAN : en ? CYAN_DIM : 0xFF2A333C);
            if (sel) ctx.fill(x + 2, y + 4, x + 4, y + h - 4, CYAN);
            spaced(ctx, textRenderer, names[i], x + 12, y + 8, en ? (sel ? TEXT : 0xFFA8C4D4) : 0xFF3A4652, 1f, 1f, false);
            final Page target = p;
            hits.add(new Hit(x, y, w, h, () -> { page = target; play(ModSounds.UI_CLICK, 1.1f); }, en));
        }
        // Loadout summary under the nav
        Loadout l = railgun.getLoadout();
        ctx.drawText(textRenderer, "INSTALLED", 10, 130, MUTED, false);
        int y = 142;
        List<Option> list = new ArrayList<>();
        if (l.muzzle() != null) list.add(l.muzzle());
        if (l.ammo() != null) list.add(l.ammo());
        list.addAll(l.modules());
        if (list.isEmpty()) ctx.drawText(textRenderer, "STANDARD", 10, y, 0xFF8FA6B6, false);
        for (int i = 0; i < list.size(); i++) {
            icon(ctx, list.get(i), 10 + (i % 3) * 24, y + (i / 3) * 24, 20, false, time);
        }
    }

    // ---- STATUS ----------------------------------------------------------------------------------

    private void drawStatus(DrawContext ctx, float mx, float my, float time) {
        RailgunState st = railgun.getState();
        int x = 92, y = 30, w = CW - 100;
        chamfer(ctx, x, y, w, 112, 10, PANEL, CYAN_DIM);
        // schematic
        drawSchematic(ctx, x + 64, y + 56, st, time);
        Text title = Text.translatable(st.translationKey());
        spaced(ctx, textRenderer, title.getString(), x + 136, y + 12, stateColor(st, time), 1.5f, 2f, true);
        String[] lines = switch (st) {
            case POD -> railgun.getLoadout().isStandard()
                    ? new String[]{"Pod landed and anchored.", "Confirm attachments on LOADOUT,", "then deploy the emplacement."}
                    : new String[]{"Loadout confirmed with orbital storage.", "Parts launch and dock themselves", "as soon as the emplacement deploys."};
            case ARMING -> new String[]{"Orbital storage drop in progress.", "Attachments vectoring in to dock.", "Stand clear of the hardpoints."};
            case PANEL_OPEN, TRANSFORMING -> new String[]{"Deployment sequence running.", "Stand clear of the clamps."};
            case IDLE -> new String[]{"Emplacement online.", "Fire control: ARES-9 uplink tablet."};
            case COOLDOWN -> new String[]{"Barrel cooling."};
            case CHARGING, FIRING -> new String[]{"WEAPON HOT", "Console locked until the shot completes."};
            case PACKING -> new String[]{"Retracting into pod.", "Stand clear."};
            case POD_READY -> new String[]{"Sealed for orbit.", "RETURN launches the pod home."};
            default -> new String[]{};
        };
        for (int i = 0; i < lines.length; i++) ctx.drawText(textRenderer, lines[i], x + 136, y + 38 + i * 12, TEXT, false);

        float progress = switch (st) {
            case PANEL_OPEN -> 0.08f * railgun.getStateTicks() / RailgunConfig.PANEL_TICKS;
            case TRANSFORMING -> 0.08f + 0.92f * railgun.getStateTicks() / RailgunConfig.TRANSFORM_TICKS;
            case PACKING -> (float) railgun.getStateTicks() / RailgunConfig.PACK_TICKS;
            case ARMING -> (float) railgun.getStateTicks() / Math.max(1, com.creedpdf.orbitalrailgun.loadout.PartDrop.armingTicks(railgun.getLoadout()));
            default -> -1f;
        };
        if (progress >= 0) {
            segBar(ctx, x + 136, y + 86, w - 150, 10, progress, 28, 0xFF1A8FB0, CYAN, 0xFF0C1A22);
            ctx.drawText(textRenderer, String.format(Locale.ROOT, "%3d%%", (int) (MathHelper.clamp(progress, 0, 1) * 100)), x + w - 34, y + 74, TEXT, false);
        }
        if (st == RailgunState.ARMING) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.4f);
            chamfer(ctx, x + 136, y + 98, w - 150, 12, 4, alpha(0xFF0F3A2E, 0.6f + 0.4f * pulse), 0xFF3CFFC8);
            ctx.drawText(textRenderer, "ORBITAL STORAGE // PARTS DOCKING", x + 142, y + 100, 0xFF8CFFE0, false);
        }

        // Main action
        int bx = 92, by = 152, bw = CW - 100, bh = 52;
        String label = null;
        int color = RED;
        Runnable act = null;
        switch (st) {
            case POD -> {
                label = "DEPLOY RAILGUN"; act = () -> send(RailgunEntity.PanelAction.DEPLOY);
            }
            case IDLE, COOLDOWN -> {
                if (armedTicks > 0) { label = "CONFIRM RETRACT"; act = () -> send(RailgunEntity.PanelAction.RETRACT); }
                else { label = "RETRACT RAILGUN"; color = AMBER; act = () -> { armedTicks = 60; play(ModSounds.UI_COUNTDOWN_BEEP, 1f); }; }
            }
            case POD_READY -> { label = "RETURN TO ORBIT"; act = () -> send(RailgunEntity.PanelAction.RETURN); }
            default -> { }
        }
        bigButton(ctx, bx, by, bw, bh, label, color, act, mx, my, time);
        ctx.drawText(textRenderer, "ESC  STEP BACK", 10, CH - 14, MUTED, false);
        if (st == RailgunState.POD && canConfigure()) {
            ctx.drawText(textRenderer, "TIP  confirm attachments on LOADOUT before deploying", 92, 214, 0xFF7FD8EE, false);
        }
    }

    private void drawSchematic(DrawContext ctx, int cx, int cy, RailgunState st, float time) {
        boolean deployed = st.isOperational() || st == RailgunState.TRANSFORMING || st == RailgunState.PACKING;
        float f = switch (st) {
            case TRANSFORMING -> MathHelper.clamp(railgun.getStateTicks() / (float) RailgunConfig.TRANSFORM_TICKS, 0, 1);
            case PACKING -> 1f - MathHelper.clamp(railgun.getStateTicks() / (float) RailgunConfig.PACK_TICKS, 0, 1);
            default -> deployed ? 1f : 0f;
        };
        int dim = 0x6633E0FF;
        ring(ctx, cx, cy, 48, 0x2233E0FF, 96);
        octagon(ctx, cx, cy, 12, 0, CYAN);
        for (int k = 0; k < 4; k++) {
            float a = MathHelper.PI / 4 + k * MathHelper.PI / 2;
            float r = 14 + 26 * f;
            float px = cx + MathHelper.cos(a) * r, py = cy + MathHelper.sin(a) * r;
            octagon(ctx, px, py, 6, a, f > 0.5f ? CYAN : dim);
            if (f > 0.05f) line(ctx, cx + MathHelper.cos(a) * 12, cy + MathHelper.sin(a) * 12, px - MathHelper.cos(a) * 6, py - MathHelper.sin(a) * 6, dim);
        }
        float yaw = railgun.getAimYawRad(0);
        if (deployed) line(ctx, cx, cy, cx + MathHelper.sin(yaw) * 44 * f, cy - MathHelper.cos(yaw) * 44 * f, RED);
        float sweep = time * 0.08f;
        for (int i = 0; i < 16; i++) {
            float a = sweep - i * 0.05f;
            int px = cx + (int) (MathHelper.cos(a) * 46), py = cy + (int) (MathHelper.sin(a) * 46);
            ctx.fill(px, py, px + 2, py + 2, alpha(CYAN, 1f - i / 16f));
        }
    }

    private void bigButton(DrawContext ctx, int x, int y, int w, int h, @Nullable String label, int color, @Nullable Runnable act,
                           float mx, float my, float time) {
        boolean on = act != null;
        boolean hov = on && mx >= x && mx < x + w && my >= y && my < y + h;
        float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
        chamfer(ctx, x, y, w, h, 12, on ? alpha(color, hov ? 0.55f : 0.22f + 0.12f * pulse) : 0x40202830, on ? color : 0xFF2A333C);
        for (int sx = x + 14; sx < x + w - 14; sx += 10) {
            ctx.fill(sx, y + 3, sx + 5, y + 5, on ? alpha(0xFFD69614, 0.9f) : 0xFF2A2A2A);
            ctx.fill(sx + 3, y + h - 5, sx + 8, y + h - 3, on ? alpha(0xFFD69614, 0.9f) : 0xFF2A2A2A);
        }
        String text = label == null ? "NO ACTION AVAILABLE" : label;
        float sc = label == null ? 1.2f : 2.0f;
        float tw = spacedWidth(textRenderer, text, 2f, sc);
        spaced(ctx, textRenderer, text, x + (w - tw) / 2f, y + h / 2f - 4 * sc, on ? 0xFFFFFFFF : 0xFF56626E, 2f, sc, on);
        if (on) hits.add(new Hit(x, y, w, h, () -> { play(ModSounds.UI_CLICK, 1f); act.run(); }, true));
    }

    // ---- LOADOUT ---------------------------------------------------------------------------------

    private void drawLoadout(DrawContext ctx, float mx, float my, float time) {
        boolean editable = canConfigure();
        int x = 92;
        drawCategory(ctx, "MUZZLE DEVICE", Category.MUZZLE, x, 30, mx, my, time, editable);
        drawCategory(ctx, "AMMUNITION", Category.AMMO, x, 82, mx, my, time, editable);
        drawCategory(ctx, "SUPPORT MODULES  " + draft.modules().size() + "/" + Loadout.MAX_MODULES, Category.MODULE, x, 134, mx, my, time, editable);

        // Details pane
        int dx = 372, dy = 30, dw = CW - dx - 6, dh = 186;
        chamfer(ctx, dx, dy, dw, dh, 8, PANEL_HI, CYAN_DIM);
        Option o = hovered;
        if (o == null) {
            ctx.drawText(textRenderer, "HOVER AN OPTION", dx + 8, dy + 10, MUTED, false);
            ctx.drawText(textRenderer, "for specifications.", dx + 8, dy + 22, MUTED, false);
            int y = dy + 48;
            ctx.drawText(textRenderer, "ORDER MANIFEST", dx + 8, y, CYAN, false);
            y += 12;
            List<Option> sel = selected(draft);
            if (sel.isEmpty()) ctx.drawText(textRenderer, "standard config", dx + 8, y, 0xFF8FA6B6, false);
            for (Option so : sel) {
                ctx.fill(dx + 8, y + 2, dx + 11, y + 5, so.color);
                ctx.drawText(textRenderer, so.title, dx + 14, y, TEXT, false);
                y += 10;
                if (y > dy + dh - 12) break;
            }
        } else {
            icon(ctx, o, dx + (dw - 48) / 2, dy + 8, 48, true, time);
            List<String> title = wrap(textRenderer, o.title, dw - 12);
            int y = dy + 60;
            for (String t : title) { ctx.drawText(textRenderer, t, dx + 6, y, o.color, true); y += 10; }
            y += 2;
            ctx.getMatrices().push();
            ctx.getMatrices().scale(0.75f, 0.75f, 1f);
            for (String t : wrap(textRenderer, o.description, (int) ((dw - 12) / 0.75f))) {
                ctx.drawText(textRenderer, t, (int) ((dx + 6) / 0.75f), (int) (y / 0.75f), 0xFFB8CCDA, false);
                y += 8;
            }
            ctx.getMatrices().pop();
            y += 4;
            for (String stat : o.stats) {
                ctx.fill(dx + 6, y + 3, dx + 9, y + 6, CYAN);
                ctx.drawText(textRenderer, stat, dx + 12, y, TEXT, false);
                y += 10;
            }
        }

        // Transmit / reset
        boolean changed = !draft.equals(railgun.getLoadout());
        int by = 222;
        if (railgun.getState() == RailgunState.ARMING) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.4f);
            chamfer(ctx, x, by, 276, 26, 8, alpha(0xFF0F3A2E, 0.6f + 0.4f * pulse), 0xFF3CFFC8);
            spaced(ctx, textRenderer, "PARTS DOCKING", x + 60, by + 9, 0xFF8CFFE0, 2f, 1f, false);
        } else {
            Runnable act = editable && changed ? this::transmit : null;
            boolean hov = act != null && mx >= x && mx < x + 276 && my >= by && my < by + 26;
            chamfer(ctx, x, by, 276, 26, 8, act != null ? alpha(0xFF14A0C8, hov ? 0.6f : 0.3f) : 0x40202830, act != null ? CYAN : 0xFF2A333C);
            String label = changed ? "CONFIRM LOADOUT" : "LOADOUT CONFIRMED";
            float tw = spacedWidth(textRenderer, label, 1.5f, 1f);
            spaced(ctx, textRenderer, label, x + (276 - tw) / 2f, by + 9, act != null ? 0xFFFFFFFF : 0xFF56626E, 1.5f, 1f, act != null);
            if (act != null) hits.add(new Hit(x, by, 276, 26, act, true));
            int rx = x + 284;
            boolean rh = mx >= rx && mx < rx + 64 && my >= by && my < by + 26;
            chamfer(ctx, rx, by, 64, 26, 6, rh ? 0x80402020 : PANEL, AMBER);
            ctx.drawText(textRenderer, "RESET", rx + 18, by + 9, AMBER, false);
            hits.add(new Hit(rx, by, 64, 26, () -> { draft = railgun.getLoadout(); play(ModSounds.UI_CLICK, 0.8f); }, true));
        }
    }

    private void drawCategory(DrawContext ctx, String name, Category cat, int x, int y, float mx, float my, float time, boolean editable) {
        ctx.fill(x, y + 3, x + 3, y + 8, CYAN);
        spaced(ctx, textRenderer, name, x + 6, y + 1, TEXT, 1f, 1f, false);
        List<Option> opts = Option.of(cat);
        int tile = 26, gap = 2;
        int ty = y + 13;
        // the "standard" tile for muzzle / ammo
        int i = 0;
        if (cat != Category.MODULE) {
            boolean sel = (cat == Category.MUZZLE ? draft.muzzle() : draft.ammo()) == null;
            int tx = x;
            boolean hov = mx >= tx && mx < tx + tile && my >= ty && my < ty + tile;
            chamfer(ctx, tx, ty, tile, tile, 5, sel ? 0xE0123A4A : PANEL, sel ? CYAN : hov ? CYAN_DIM : 0xFF1E2A34);
            ctx.drawText(textRenderer, "STD", tx + 5, ty + 9, sel ? TEXT : MUTED, false);
            if (editable) hits.add(new Hit(tx, ty, tile, tile, () -> {
                Option cur = cat == Category.MUZZLE ? draft.muzzle() : draft.ammo();
                if (cur != null) draft = draft.toggle(cur);
                play(ModSounds.UI_LOADOUT_SELECT, 0.9f);
            }, true));
            i = 1;
        }
        for (Option o : opts) {
            int tx = x + i * (tile + gap);
            i++;
            boolean sel = draft.has(o);
            boolean inst = railgun.getLoadout().has(o);
            boolean hov = mx >= tx && mx < tx + tile && my >= ty && my < ty + tile;
            if (hov) hovered = o;
            float pulse = sel ? 0.75f + 0.25f * MathHelper.sin(time * 0.25f) : 1f;
            chamfer(ctx, tx, ty, tile, tile, 5, sel ? alpha(o.color, 0.28f * pulse) : hov ? 0xC0142430 : PANEL,
                    sel ? o.color : hov ? CYAN_DIM : 0xFF1E2A34);
            icon(ctx, o, tx + 2, ty + 2, tile - 4, false, time);
            if (inst) ctx.fill(tx + tile - 6, ty + 2, tx + tile - 2, ty + 6, GREEN);
            boolean full = cat == Category.MODULE && !sel && draft.modules().size() >= Loadout.MAX_MODULES;
            if (full) ctx.fill(tx + 1, ty + 1, tx + tile - 1, ty + tile - 1, 0x90060A0E);
            if (editable) hits.add(new Hit(tx, ty, tile, tile, () -> {
                Loadout next = draft.toggle(o);
                if (next.equals(draft) && !draft.has(o)) { play(ModSounds.UI_DENIED, 1.2f); return; }
                draft = next;
                play(ModSounds.UI_LOADOUT_SELECT, draft.has(o) ? 1.1f : 0.85f);
            }, !full));
        }
    }

    private static List<Option> selected(Loadout l) {
        List<Option> out = new ArrayList<>();
        if (l.muzzle() != null) out.add(l.muzzle());
        if (l.ammo() != null) out.add(l.ammo());
        out.addAll(l.modules());
        return out;
    }

    private void icon(DrawContext ctx, Option o, int x, int y, int size, boolean frame, float time) {
        if (frame) {
            ring(ctx, x + size / 2, y + size / 2, size / 2 + 2, alpha(o.color, 0.6f), 64);
        }
        RenderSystem.enableBlend();
        ctx.drawTexture(RailgunMod.id("textures/gui/option/" + o.id + ".png"), x, y, size, size, 0, 0, 96, 96, 96, 96);
        RenderSystem.disableBlend();
    }

    // ---- SYSTEMS ---------------------------------------------------------------------------------

    private void drawSystems(DrawContext ctx, float time) {
        Loadout l = page == Page.SYSTEMS && canConfigure() ? draft : railgun.getLoadout();
        int x = 92, y = 30, w = CW - 100;
        chamfer(ctx, x, y, w, 186, 10, PANEL, CYAN_DIM);
        spaced(ctx, textRenderer, "FIRING SOLUTION", x + 12, y + 8, CYAN, 2f, 1.2f, false);
        ctx.drawText(textRenderer, canConfigure() ? "(draft loadout)" : "(installed loadout)", x + 150, y + 10, MUTED, false);
        float chargeS = l.chargeTicks(RailgunConfig.CHARGE_TICKS) / 20f;
        float coolS = l.cooldownTicks(RailgunConfig.COOLDOWN_TICKS) / 20f;
        Object[][] rows = {
                {"CHARGE TIME", String.format(Locale.ROOT, "%.1f s", chargeS), 1f - chargeS / 12f},
                {"COOLDOWN", String.format(Locale.ROOT, "%.1f s", coolS), 1f - coolS / 14f},
                {"BEAM DAMAGE", String.format(Locale.ROOT, "%.0f", RailgunConfig.BEAM_DAMAGE * l.damageMul()), l.damageMul() / 2.6f},
                {"BLAST YIELD", String.format(Locale.ROOT, "%.1f", RailgunConfig.IMPACT_EXPLOSION_POWER * l.blastMul()), l.blastMul() / 2f},
                {"BORE DEPTH", String.format(Locale.ROOT, "%.0f m", RailgunConfig.BORE_DEPTH * l.boreDepthMul()), (float) (l.boreDepthMul() / 3.6)},
                {"BEAM WIDTH", String.format(Locale.ROOT, "x%.2f", l.beamWidth()), l.beamWidth() / 2.2f},
                {"RANGE", String.format(Locale.ROOT, "%.0f m", RailgunConfig.MAX_FIRE_RANGE * l.rangeMul()), (float) (l.rangeMul() / 1.7)},
                {"BURST", l.burst() + " RND", l.burst() / 3f},
        };
        for (int i = 0; i < rows.length; i++) {
            int ry = y + 30 + i * 19;
            ctx.drawText(textRenderer, (String) rows[i][0], x + 12, ry, TEXT, false);
            ctx.drawText(textRenderer, (String) rows[i][1], x + 100, ry, CYAN, false);
            segBar(ctx, x + 160, ry, w - 176, 8, (Float) rows[i][2], 30, 0xFF1A8FB0, CYAN, 0xFF0C1A22);
        }
        int[] c = l.beamColors();
        ctx.drawText(textRenderer, "BEAM SIGNATURE", x + 12, y + 186 - 16, MUTED, false);
        for (int i = 0; i < 3; i++) ctx.fill(x + 100 + i * 18, y + 186 - 18, x + 114 + i * 18, y + 186 - 6, 0xFF000000 | c[i]);
    }

    private int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> GREEN;
            case CHARGING, FIRING -> ((int) (time / 3)) % 2 == 0 ? RED : AMBER;
            case POD, POD_READY -> CYAN;
            default -> AMBER;
        };
    }

    private void play(SoundEvent sound, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
    }
}
