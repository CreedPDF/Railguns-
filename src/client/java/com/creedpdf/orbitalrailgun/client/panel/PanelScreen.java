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

import static com.creedpdf.orbitalrailgun.client.ui.Term.*;
import com.creedpdf.orbitalrailgun.client.ui.UiKit;

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


    public PanelScreen(RailgunEntity railgun) {
        super(Text.literal("ARES-9 Field Terminal"));
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
                if (h.enabled()) { play(ModSounds.UI_CLICK, 1f); h.action().run(); }
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

    private static final int PX = 122;

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

        backdrop(ctx, CW, CH, time);
        RailgunState st = railgun.getState();
        String unit = String.format(Locale.ROOT, "unit_%04d", Math.abs(railgun.getUuid().hashCode()) % 10000);
        header(ctx, textRenderer, CW, "~/ares-9/" + unit + "/" + page.name().toLowerCase(Locale.ROOT), stateName(st), stateColor(st, time), time);
        drawNav(ctx, mx, my, time, unit);
        vline(ctx, PX - 6, 26, CH - 12, RULE);
        switch (page) {
            case STATUS -> drawStatus(ctx, mx, my, time);
            case LOADOUT -> drawLoadout(ctx, mx, my, time);
            case SYSTEMS -> drawSystems(ctx, time);
        }
        footer(ctx, textRenderer, CW, CH, time, "ESC step back");
        corners(ctx, 1, 1, CW - 2, CH - 2, 10, RED_DIM);
        if (bootTicks >= 0) {
            boot(ctx, textRenderer, CW, CH, bootTicks + delta, new String[]{
                    "> ares-9 field terminal // local bus",
                    "> reactor ................ NOMINAL",
                    "> orbital storage ........ LINKED",
                    "> operator authenticated"});
        }
        ctx.getMatrices().pop();
    }

    private static String stateName(RailgunState st) {
        return Text.translatable(st.translationKey()).getString().toUpperCase(Locale.ROOT);
    }

    private void drawNav(DrawContext ctx, float mx, float my, float time, String unit) {
        int x = 6, y = 28, w = PX - 14;
        text(ctx, textRenderer, "~/" + unit + "/", x, y, RED, 0.75f);
        y += 11;
        Page[] pages = Page.values();
        String[] names = {"status", "loadout", "systems"};
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            boolean en = p != Page.LOADOUT || canConfigure() || railgun.getState() == RailgunState.ARMING;
            Hit h = node(ctx, textRenderer, names[i], x, y, w, 1, i == pages.length - 1, page == p, en, p == Page.LOADOUT,
                    p == Page.LOADOUT && !en ? "LOCKED" : null, FAINT, mx, my, () -> { page = p; play(ModSounds.UI_CLICK, 1.1f); });
            if (h != null) hits.add(h);
            y += 11;
        }
        y += 8;
        section(ctx, textRenderer, "INSTALLED", x, y, w, RED_DIM);
        y += 11;
        List<Option> list = selected(railgun.getLoadout());
        if (list.isEmpty()) text(ctx, textRenderer, "standard", x, y, DIM, 0.7f);
        for (Option o : list) {
            ctx.fill(x, y + 2, x + 3, y + 5, 0xFF000000 | o.color);
            text(ctx, textRenderer, o.title.toLowerCase(Locale.ROOT), x + 6, y, TEXT, 0.65f);
            y += 8;
            if (y > CH - 20) break;
        }
    }

    // ---- STATUS ----------------------------------------------------------------------------------

    private void drawStatus(DrawContext ctx, float mx, float my, float time) {
        RailgunState st = railgun.getState();
        int x = PX, y = 28, w = CW - PX - 8;
        section(ctx, textRenderer, "STATUS.LOG", x, y, w, RED);
        y += 12;
        drawSchematic(ctx, x + w - 52, y + 44, st, time);
        int tw = w - 116;
        kv(ctx, textRenderer, "STATE", stateName(st), x, y, tw, stateColor(st, time), 0.75f);
        y += 12;
        String[] lines = switch (st) {
            case POD -> railgun.getLoadout().isStandard()
                    ? new String[]{"pod landed and anchored.", "confirm attachments in loadout/,", "then deploy the emplacement."}
                    : new String[]{"loadout confirmed with orbital storage.", "parts launch and dock themselves", "as soon as the emplacement deploys."};
            case ARMING -> new String[]{"orbital storage drop in progress.", "attachments vectoring in to dock.", "stand clear of the hardpoints."};
            case PANEL_OPEN, TRANSFORMING -> new String[]{"deployment sequence running.", "stand clear of the clamps."};
            case IDLE -> new String[]{"emplacement online.", "fire control: uplink tablet, any range."};
            case COOLDOWN -> new String[]{"barrel cooling."};
            case CHARGING, FIRING -> new String[]{"WEAPON HOT.", "terminal locked until the shot completes."};
            case PACKING -> new String[]{"retracting into pod.", "stand clear."};
            case POD_READY -> new String[]{"sealed for orbit.", "return launches the pod home."};
            default -> new String[]{};
        };
        for (int i = 0; i < lines.length; i++) {
            typed(ctx, textRenderer, "> " + lines[i], x, y, TEXT, 0.75f, ticks - i * 6);
            y += 10;
        }
        float progress = switch (st) {
            case PANEL_OPEN -> 0.08f * railgun.getStateTicks() / RailgunConfig.PANEL_TICKS;
            case TRANSFORMING -> 0.08f + 0.92f * railgun.getStateTicks() / RailgunConfig.TRANSFORM_TICKS;
            case PACKING -> (float) railgun.getStateTicks() / RailgunConfig.PACK_TICKS;
            case ARMING -> (float) railgun.getStateTicks() / Math.max(1, com.creedpdf.orbitalrailgun.loadout.PartDrop.armingTicks(railgun.getLoadout()));
            default -> -1f;
        };
        y = Math.max(y + 6, 86);
        if (progress >= 0) {
            text(ctx, textRenderer, "PROGRESS", x, y, DIM, 0.75f);
            meter(ctx, textRenderer, progress, x + 56, y, 34, RED, 0.75f);
            y += 12;
        }
        if (st == RailgunState.ARMING) {
            boolean blink = ((int) (time / 6)) % 2 == 0;
            text(ctx, textRenderer, "ORBITAL STORAGE // PARTS DOCKING", x, y, blink ? AMBER : DIM, 0.75f);
            y += 12;
        }

        // main command
        int by = CH - 62;
        hline(ctx, x, x + w, by - 8, RULE);
        switch (st) {
            case POD -> hits.add(cmd(ctx, textRenderer, "DEPLOY RAILGUN", x, by, RED, true, mx, my, 1.2f,
                    () -> send(RailgunEntity.PanelAction.DEPLOY)));
            case IDLE, COOLDOWN -> {
                if (armedTicks > 0) {
                    hits.add(cmd(ctx, textRenderer, "CONFIRM // RETRACT", x, by, RED, true, mx, my, 1.2f,
                            () -> send(RailgunEntity.PanelAction.RETRACT)));
                    text(ctx, textRenderer, "> press again to confirm", x, by + 16, AMBER, 0.7f);
                } else {
                    hits.add(cmd(ctx, textRenderer, "RETRACT RAILGUN", x, by, AMBER, true, mx, my, 1.2f,
                            () -> { armedTicks = 60; play(ModSounds.UI_COUNTDOWN_BEEP, 1f); }));
                }
            }
            case POD_READY -> hits.add(cmd(ctx, textRenderer, "RETURN TO ORBIT", x, by, RED, true, mx, my, 1.2f,
                    () -> send(RailgunEntity.PanelAction.RETURN)));
            default -> text(ctx, textRenderer, "> no command available", x, by, FAINT, 0.75f);
        }
        if (st == RailgunState.POD && canConfigure()) {
            text(ctx, textRenderer, "tip: confirm attachments in loadout/ before deploying", x, by + 18, DIM, 0.65f);
        }
    }

    private void drawSchematic(DrawContext ctx, int cx, int cy, RailgunState st, float time) {
        boolean deployed = st.isOperational() || st == RailgunState.TRANSFORMING || st == RailgunState.PACKING;
        float f = switch (st) {
            case TRANSFORMING -> MathHelper.clamp(railgun.getStateTicks() / (float) RailgunConfig.TRANSFORM_TICKS, 0, 1);
            case PACKING -> 1f - MathHelper.clamp(railgun.getStateTicks() / (float) RailgunConfig.PACK_TICKS, 0, 1);
            default -> deployed ? 1f : 0f;
        };
        int dim = 0x66E8382E;
        UiKit.ring(ctx, cx, cy, 44, 0x30E8382E, 96);
        UiKit.octagon(ctx, cx, cy, 10, 0, RED);
        for (int k = 0; k < 4; k++) {
            float a = MathHelper.PI / 4 + k * MathHelper.PI / 2;
            float r = 12 + 24 * f;
            float px = cx + MathHelper.cos(a) * r, py = cy + MathHelper.sin(a) * r;
            UiKit.octagon(ctx, px, py, 5, a, f > 0.5f ? RED : dim);
            if (f > 0.05f) UiKit.line(ctx, cx + MathHelper.cos(a) * 10, cy + MathHelper.sin(a) * 10, px - MathHelper.cos(a) * 5, py - MathHelper.sin(a) * 5, dim);
        }
        float yaw = railgun.getAimYawRad(0);
        if (deployed) UiKit.line(ctx, cx, cy, cx + MathHelper.sin(yaw) * 40 * f, cy - MathHelper.cos(yaw) * 40 * f, TEXT);
        float sweep = time * 0.08f;
        for (int i = 0; i < 16; i++) {
            float a = sweep - i * 0.05f;
            int px = cx + (int) (MathHelper.cos(a) * 42), py = cy + (int) (MathHelper.sin(a) * 42);
            ctx.fill(px, py, px + 1, py + 1, UiKit.alpha(RED, 1f - i / 16f));
        }
    }

    // ---- LOADOUT ---------------------------------------------------------------------------------

    private void drawLoadout(DrawContext ctx, float mx, float my, float time) {
        boolean editable = canConfigure();
        int x = PX;
        drawCategory(ctx, "muzzle_device", Category.MUZZLE, x, 28, mx, my, time, editable);
        drawCategory(ctx, "ammunition", Category.AMMO, x, 74, mx, my, time, editable);
        drawCategory(ctx, "support_modules  " + draft.modules().size() + "/" + Loadout.MAX_MODULES, Category.MODULE, x, 120, mx, my, time, editable);

        // spec sheet
        int dx = 372, dy = 28, dw = CW - dx - 8, dh = 176;
        vline(ctx, dx - 6, dy, dy + dh, RULE);
        Option o = hovered;
        if (o == null) {
            section(ctx, textRenderer, "MANIFEST", dx, dy, dw, RED);
            int y = dy + 12;
            List<Option> sel = selected(draft);
            if (sel.isEmpty()) text(ctx, textRenderer, "standard config", dx, y, DIM, 0.7f);
            for (Option so : sel) {
                ctx.fill(dx, y + 2, dx + 3, y + 5, 0xFF000000 | so.color);
                text(ctx, textRenderer, so.title.toLowerCase(Locale.ROOT), dx + 6, y, TEXT, 0.65f);
                y += 8;
                if (y > dy + dh - 10) break;
            }
            text(ctx, textRenderer, "hover an option for its spec sheet", dx, dy + dh - 8, FAINT, 0.6f);
        } else {
            section(ctx, textRenderer, "SPEC", dx, dy, dw, 0xFF000000 | o.color);
            icon(ctx, o, dx + (dw - 32) / 2, dy + 12, 32);
            int y = dy + 48;
            for (String t : UiKit.wrap(textRenderer, o.title.toUpperCase(Locale.ROOT), (int) (dw / 0.75f))) {
                text(ctx, textRenderer, t, dx, y, 0xFF000000 | o.color, 0.75f);
                y += 8;
            }
            y += 2;
            y = para(ctx, textRenderer, o.description, dx, y, dw, 0xFFA8A49C, 0.6f) + 3;
            for (String stat : o.stats) {
                text(ctx, textRenderer, "+ " + stat, dx, y, TEXT, 0.65f);
                y += 8;
            }
        }

        // transmit / reset
        boolean changed = !draft.equals(railgun.getLoadout());
        int by = CH - 30;
        hline(ctx, x, CW - 8, by - 8, RULE);
        if (railgun.getState() == RailgunState.ARMING) {
            boolean blink = ((int) (time / 6)) % 2 == 0;
            text(ctx, textRenderer, "PARTS DOCKING // STAND CLEAR", x, by, blink ? AMBER : DIM, 0.85f);
        } else {
            hits.add(cmd(ctx, textRenderer, changed ? "TRANSMIT LOADOUT" : "LOADOUT CONFIRMED", x, by, RED, editable && changed, mx, my, 1f, this::transmit));
            hits.add(cmd(ctx, textRenderer, "RESET", x + 150, by + 1, AMBER, true, mx, my, 0.8f,
                    () -> { draft = railgun.getLoadout(); play(ModSounds.UI_CLICK, 0.8f); }));
        }
    }

    private void drawCategory(DrawContext ctx, String name, Category cat, int x, int y, float mx, float my, float time, boolean editable) {
        section(ctx, textRenderer, name, x, y, 236, RED_DIM);
        List<Option> opts = Option.of(cat);
        int tile = 22, gap = 3;
        int ty = y + 12;
        int i = 0;
        if (cat != Category.MODULE) {
            boolean sel = (cat == Category.MUZZLE ? draft.muzzle() : draft.ammo()) == null;
            int tx = x;
            boolean hov = mx >= tx && mx < tx + tile && my >= ty && my < ty + tile;
            ctx.fill(tx, ty, tx + tile, ty + tile, sel ? 0xFF1A0706 : BG2);
            box(ctx, tx, ty, tile, tile, sel ? RED : hov ? RULE_HI : RULE);
            text(ctx, textRenderer, "STD", tx + 4, ty + 8, sel ? TEXT : DIM, 0.75f);
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
            ctx.fill(tx, ty, tx + tile, ty + tile, sel ? UiKit.alpha(0xFF000000 | o.color, 0.22f) : BG2);
            box(ctx, tx, ty, tile, tile, sel ? 0xFF000000 | o.color : hov ? RULE_HI : RULE);
            icon(ctx, o, tx + 3, ty + 3, tile - 6);
            if (inst) ctx.fill(tx + tile - 4, ty + 1, tx + tile - 1, ty + 4, OK);
            boolean full = cat == Category.MODULE && !sel && draft.modules().size() >= Loadout.MAX_MODULES;
            if (full) ctx.fill(tx + 1, ty + 1, tx + tile - 1, ty + tile - 1, 0xB0030303);
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

    private void icon(DrawContext ctx, Option o, int x, int y, int size) {
        RenderSystem.enableBlend();
        ctx.drawTexture(RailgunMod.id("textures/gui/option/" + o.id + ".png"), x, y, size, size, 0, 0, 96, 96, 96, 96);
        RenderSystem.disableBlend();
    }

    // ---- SYSTEMS ---------------------------------------------------------------------------------

    private void drawSystems(DrawContext ctx, float time) {
        Loadout l = canConfigure() ? draft : railgun.getLoadout();
        int x = PX, y = 28, w = CW - PX - 8;
        section(ctx, textRenderer, "FIRING_SOLUTION " + (canConfigure() ? "(draft)" : "(installed)"), x, y, w, RED);
        y += 14;
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
            int ry = y + i * 16;
            float tt = ticks - i * 2;
            if (tt <= 0) continue;
            text(ctx, textRenderer, (String) rows[i][0], x, ry, DIM, 0.75f);
            text(ctx, textRenderer, (String) rows[i][1], x + 80, ry, TEXT, 0.75f);
            meter(ctx, textRenderer, (Float) rows[i][2], x + 140, ry, 36, RED, 0.75f);
        }
        int[] c = l.beamColors();
        int sy = y + rows.length * 16 + 6;
        text(ctx, textRenderer, "BEAM SIGNATURE", x, sy, DIM, 0.75f);
        for (int i = 0; i < 3; i++) {
            ctx.fill(x + 80 + i * 14, sy - 1, x + 91 + i * 14, sy + 7, 0xFF000000 | c[i]);
            box(ctx, x + 80 + i * 14, sy - 1, 11, 8, RULE_HI);
        }
    }

    private int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> OK;
            case CHARGING, FIRING -> ((int) (time / 3)) % 2 == 0 ? RED : AMBER;
            case POD, POD_READY -> TEXT;
            default -> AMBER;
        };
    }

    private void play(SoundEvent sound, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
    }
}
