package com.creedpdf.orbitalrailgun.client.tablet;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.ui.UiKit;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The uplink tablet, "zoomed into".
 *
 * <h2>How the zoom works</h2>
 * The tablet is a real item model held in both hands (see {@code HeldItemRendererMixin}). While
 * this screen is open, {@link #zoom(float)} goes 0 → 1 over {@link #ZOOM_TICKS}; the mixin uses it
 * to lift the tablet off the hands, square it up and pull it toward the camera until its display
 * fills the view. Only then does this screen fade in the display image (the same tablet, seen
 * from a few centimetres away) and boot the UI with a scan line. Closing plays it in reverse.
 * Nothing is a pop-up window; the world keeps running behind it.
 *
 * <h2>Pages</h2>
 * <ul>
 *   <li><b>Orbital drop</b> (no railgun yet): shows the block you were aiming at when you raised
 *       the tablet and a CALL DOWN button.</li>
 *   <li><b>Fire control</b> (railgun exists): status, capacitor, waveform, target entry (typed or
 *       PAINT = the block you aimed at), SET, a big FIRE button for remote firing, and ABORT.</li>
 * </ul>
 * The status comes from the server every few ticks (railguns can be far away, out of the client's
 * entity range), see {@link TabletStatus}.
 */
public class TabletScreen extends Screen {
    private static final Identifier DISPLAY = RailgunMod.id("textures/gui/tablet_display.png");
    public static final int ZOOM_TICKS = 9;
    // Display area inside tablet_display.png (512x288).
    private static final float DX0 = 40f / 512, DY0 = 30f / 288, DX1 = 472f / 512, DY1 = 258f / 288;
    private static final int CW = 400, CH = 214; // virtual canvas

    private static final int RED = 0xFFFF3B30, RED_DIM = 0xFF7A1A16, TEXT = 0xFFDDE6EE, MUTED = 0xFF7D8A96;
    private static final int CYAN = 0xFF5FE3FF, AMBER = 0xFFFFB020, GREEN = 0xFF4CFF8A;

    @Nullable private static TabletScreen open;

    @Nullable private final BlockPos aimed;
    private int ticks;
    private int closeTicks = -1;
    private int bootTicks = -1;
    private final List<Btn> buttons = new ArrayList<>();
    private final NumberField[] fields = new NumberField[3];
    private int focused = -1;
    private boolean fieldsInitialised;

    // canvas transform
    private float ox, oy, s = 1f;

    public TabletScreen(@Nullable BlockPos aimed) {
        super(Text.translatable("item.orbital_railgun.railgun_remote"));
        this.aimed = aimed;
        for (int i = 0; i < 3; i++) fields[i] = new NumberField();
    }

    /** 0 = tablet resting in the hands, 1 = display fills the view. Smoothstepped. */
    public static float zoom(float tickDelta) {
        TabletScreen screen = open;
        if (screen == null) return 0f;
        float p = screen.closeTicks >= 0
                ? 1f - (screen.closeTicks + tickDelta) / ZOOM_TICKS
                : (screen.ticks + tickDelta) / ZOOM_TICKS;
        p = MathHelper.clamp(p, 0f, 1f);
        return p * p * (3f - 2f * p);
    }

    @Override
    protected void init() {
        open = this;
        float dw = (DX1 - DX0) * width, dh = (DY1 - DY0) * height;
        s = Math.min(dw / CW, dh / CH);
        ox = DX0 * width + (dw - CW * s) / 2f;
        oy = DY0 * height + (dh - CH * s) / 2f;
        if (ticks == 0) {
            play(ModSounds.UI_TABLET_OPEN, 1f);
            ClientPackets.requestStatus();
            if (aimed != null) ClientPackets.requestSurvey(aimed);
        }
        buildButtons();
    }

    /** Tablet switched to calling down ARES-0 (the planet killer) instead of ARES-9. */
    private static boolean ares0Page;

    private void buildButtons() {
        buttons.clear();
        buttons.add(new Btn(196, 3, 56, 12, ares0Page ? "< ARES-9" : "ARES-0 >", 0xFF7088FF, () -> true, () -> {
            ares0Page = !ares0Page;
            buildButtons();
            if (aimed != null) ClientPackets.requestSurvey(aimed);
        }, false, null));
        // Orbital drop page
        buttons.add(new Btn(214, 150, 178, 52, "CALL DOWN", RED, () -> page() == Page.DROP && aimed != null
                && !TabletStatus.latest.pending && !onCooldown() && surveyOk(), this::callDown, true, Page.DROP));
        // Fire-control page
        buttons.add(new Btn(8, 128, 90, 16, "SET", RED, this::canRetarget, this::submitTarget, false, Page.FIRE));
        buttons.add(new Btn(104, 128, 92, 16, "PAINT", AMBER, () -> canRetarget() && aimed != null, this::paint, false, Page.FIRE));
        buttons.add(new Btn(8, 178, 60, 28, "ABORT", AMBER, () -> TabletStatus.latest.state == RailgunState.CHARGING,
                ClientPackets::sendAbort, false, Page.FIRE));
        buttons.add(new Btn(72, 178, 60, 28, "SCAN", CYAN, () -> TabletStatus.latest.state == RailgunState.IDLE,
                ClientPackets::sendScan, false, Page.FIRE));
        buttons.add(new Btn(136, 178, 60, 28, "RIFT", 0xFFB070FF, () -> TabletStatus.latest.state == RailgunState.IDLE,
                ClientPackets::sendRift, false, Page.FIRE));
        buttons.add(new Btn(208, 150, 184, 56, "FIRE", RED, () -> {
            TabletStatus st = TabletStatus.latest;
            return st.state == RailgunState.IDLE && st.hasTarget && st.sameDimension;
        }, this::fire, true, Page.FIRE));
    }

    private enum Page { DROP, FIRE }

    private Page page() {
        return ares0Page || !TabletStatus.latest.found ? Page.DROP : Page.FIRE;
    }

    @Nullable
    private TabletStatus.Survey survey() {
        TabletStatus.Survey sv = TabletStatus.survey;
        return sv != null && aimed != null && sv.ground().equals(aimed) ? sv : null;
    }

    private boolean surveyOk() {
        TabletStatus.Survey sv = survey();
        return sv != null && sv.ok();
    }

    private boolean onCooldown() {
        return client != null && client.player != null
                && client.player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE);
    }

    private boolean canRetarget() {
        TabletStatus st = TabletStatus.latest;
        return st.found && st.sameDimension && (st.state == RailgunState.IDLE || st.state == RailgunState.COOLDOWN);
    }

    // ---- Actions -------------------------------------------------------------------------------

    private void callDown() {
        if (aimed == null) return;
        ClientPackets.sendDeployRequest(aimed, ares0Page ? 1 : 0);
        beginClose();
    }

    private void paint() {
        if (aimed == null) return;
        fields[0].text = Integer.toString(aimed.getX());
        fields[1].text = Integer.toString(aimed.getY());
        fields[2].text = Integer.toString(aimed.getZ());
        submitTarget();
    }

    private void submitTarget() {
        try {
            ClientPackets.sendTarget(new BlockPos(Integer.parseInt(fields[0].text), Integer.parseInt(fields[1].text),
                    Integer.parseInt(fields[2].text)));
        } catch (NumberFormatException e) {
            play(ModSounds.UI_DENIED, 1f);
        }
    }

    private void fire() {
        ClientPackets.sendFire();
        beginClose(); // lower the tablet so you can watch the shot
    }

    private void beginClose() {
        if (closeTicks < 0) {
            // Mirror the current zoom so a quick open/close doesn't jump.
            closeTicks = Math.max(0, ZOOM_TICKS - Math.min(ticks, ZOOM_TICKS));
            play(ModSounds.UI_TABLET_CLOSE, 1f);
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    @Override
    public void tick() {
        ticks++;
        if (closeTicks >= 0) {
            closeTicks++;
            if (closeTicks >= ZOOM_TICKS) {
                open = null;
                MinecraftClient.getInstance().setScreen(null);
            }
            return;
        }
        if (ticks == ZOOM_TICKS) bootTicks = 0;
        if (bootTicks >= 0) bootTicks++;
        if (ticks % RailgunConfig.TABLET_POLL_TICKS == 0) ClientPackets.requestStatus();
        if (aimed != null && page() == Page.DROP && ticks % 40 == 0) ClientPackets.requestSurvey(aimed);
        TabletStatus st = TabletStatus.latest;
        if (!fieldsInitialised && st.received) {
            BlockPos init = st.found && st.hasTarget ? st.target : aimed;
            if (init != null) {
                fields[0].text = Integer.toString(init.getX());
                fields[1].text = Integer.toString(init.getY());
                fields[2].text = Integer.toString(init.getZ());
            }
            fieldsInitialised = true;
        }
        if (client != null && client.player != null && !client.player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) {
            beginClose();
        }
    }

    @Override
    public void removed() {
        if (open == this) open = null;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // we animate out instead
    }

    private boolean uiLive() {
        return closeTicks < 0 && ticks >= ZOOM_TICKS;
    }

    // ---- Input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!uiLive() || button != 0) return true;
        float cx = (float) ((mouseX - ox) / s), cy = (float) ((mouseY - oy) / s);
        for (Btn b : buttons) {
            if ((b.page == null || b.page == page()) && b.hit(cx, cy)) {
                if (b.enabled.getAsBoolean()) {
                    play(ModSounds.UI_CLICK, 1f);
                    b.action.run();
                } else {
                    play(ModSounds.UI_CLICK, 0.6f);
                }
                return true;
            }
        }
        focused = -1;
        if (page() == Page.FIRE) {
            for (int i = 0; i < 3; i++) {
                if (cx >= 24 && cx < 196 && cy >= 70 + i * 18 && cy < 84 + i * 18) {
                    focused = i;
                    play(ModSounds.UI_CLICK, 1.3f);
                }
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || (client != null && client.options.inventoryKey.matchesKey(keyCode, scanCode) && focused < 0)) {
            beginClose();
            return true;
        }
        if (!uiLive()) return true;
        if (focused >= 0) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !fields[focused].text.isEmpty()) {
                fields[focused].text = fields[focused].text.substring(0, fields[focused].text.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                focused = (focused + 1) % 3;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (canRetarget()) {
                    play(ModSounds.UI_CLICK, 1f);
                    submitTarget();
                }
                focused = -1;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focused < 0 || !uiLive()) return false;
        String t = fields[focused].text;
        if ((Character.isDigit(chr) && t.replace("-", "").length() < 8) || (chr == '-' && t.isEmpty())) {
            fields[focused].text = t + chr;
            return true;
        }
        return false;
    }

    // ---- Rendering -----------------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext context) {
        // No dimming: the world stays visible behind the tablet while it zooms in.
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float z = zoom(delta);
        float alpha = z >= 0.999f ? 1f : MathHelper.clamp((z - 0.82f) / 0.18f, 0f, 1f);
        if (alpha <= 0f) return;

        RenderSystem.enableBlend();
        ctx.setShaderColor(1f, 1f, 1f, alpha);
        ctx.drawTexture(DISPLAY, 0, 0, width, height, 0, 0, 512, 288, 512, 288);
        ctx.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        // Float rounding means the fade can stop a hair under 1.0; the UI is live once zoomed in.
        if (alpha < 0.98f || closeTicks >= 0) return;

        float time = ticks + delta;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(ox, oy, 0);
        ctx.getMatrices().scale(s, s, 1f);
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;

        ctx.fill(0, 0, CW, CH, UiKit.BG);
        UiKit.hexGrid(ctx, 0, 0, CW, CH, time, 0x1833E0FF);
        drawHeader(ctx, time);
        if (page() == Page.DROP) drawDropPage(ctx, time);
        else drawFirePage(ctx, time);
        for (Btn b : buttons) if (b.page == null || b.page == page()) b.render(ctx, mx, my, time);
        UiKit.scanlines(ctx, 0, 0, CW, CH, time);
        UiKit.brackets(ctx, 1, 1, CW - 2, CH - 2, 12, CYAN);

        // Boot-up scan line sweeping down the display.
        if (bootTicks >= 0 && bootTicks < 10) {
            int y = (int) ((bootTicks + delta) / 10f * CH);
            ctx.fill(0, 0, CW, y, 0x22000000);
            ctx.fill(0, y, CW, y + 2, 0xAA5FE3FF);
            ctx.fill(0, y + 2, CW, CH, 0xE0050709);
        }
        ctx.getMatrices().pop();
    }

    private void drawHeader(DrawContext ctx, float time) {
        TabletStatus st = TabletStatus.latest;
        UiKit.chamfer(ctx, 0, 0, CW, 18, 7, 0xE0081420, UiKit.CYAN_DIM);
        UiKit.spaced(ctx, textRenderer, "ARES-9", 8, 4, CYAN, 2f, 1.25f, true);
        ctx.drawText(textRenderer, "// ORBITAL UPLINK", 70, 6, MUTED, false);
        String link;
        int linkColor;
        if (!st.received) { link = "LINKING"; linkColor = AMBER; }
        else if (st.found) { link = st.sameDimension ? "RAILGUN ONLINE" : "OTHER DIMENSION"; linkColor = st.sameDimension ? GREEN : AMBER; }
        else if (st.pending) { link = "DROP INBOUND"; linkColor = AMBER; }
        else { link = "NO RAILGUN"; linkColor = MUTED; }
        boolean blink = ((int) (time / 8)) % 2 == 0;
        int w = textRenderer.getWidth(link) + 8;
        UiKit.tag(ctx, textRenderer, link, CW - w - 14, 4, blink || !st.found ? linkColor : UiKit.alpha(linkColor, 0.7f));
        ctx.fill(CW - 10, 7, CW - 6, 11, blink ? linkColor : 0xFF1A2228);
        UiKit.glowLine(ctx, 6, 18, CW - 6, CYAN);
    }

    private void drawDropPage(DrawContext ctx, float time) {
        UiKit.chamfer(ctx, 6, 24, 196, 182, 10, UiKit.PANEL, UiKit.CYAN_DIM);
        title(ctx, ares0Page ? "ORBITAL DROP // ARES-0" : "ORBITAL DROP", 12, 30);
        // Targeting reticle with orbital track
        int cx = 104, cy = 118;
        int r = 52 + (int) (2 * MathHelper.sin(time * 0.2f));
        UiKit.ring(ctx, cx, cy, r, 0x9933E0FF, 128);
        UiKit.ring(ctx, cx, cy, r - 18, 0x5533E0FF, 96);
        UiKit.ring(ctx, cx, cy, 8, 0x8833E0FF, 32);
        UiKit.octagon(ctx, cx, cy, r - 30, time * 0.01f, 0x4433E0FF);
        ctx.fill(cx - r - 6, cy, cx - 10, cy + 1, 0xAA33E0FF);
        ctx.fill(cx + 10, cy, cx + r + 6, cy + 1, 0xAA33E0FF);
        ctx.fill(cx, cy - r - 6, cx + 1, cy - 10, 0xAA33E0FF);
        ctx.fill(cx, cy + 10, cx + 1, cy + r + 6, 0xAA33E0FF);
        float sweep = time * 0.12f;
        for (int i = 0; i < 28; i++) {
            float a = sweep - i * 0.035f;
            int px = cx + (int) (MathHelper.cos(a) * (r - 2)), py = cy + (int) (MathHelper.sin(a) * (r - 2));
            ctx.fill(px, py, px + 2, py + 2, UiKit.alpha(CYAN, 1f - i / 28f));
        }
        float orbit = time * 0.03f;
        int sx = cx + (int) (MathHelper.cos(orbit) * (r + 10)), sy = cy + (int) (MathHelper.sin(orbit) * (r + 10) * 0.35f) - 40;
        ctx.fill(sx - 2, sy - 1, sx + 3, sy + 2, AMBER);
        ctx.fill(cx - 2, cy - 2, cx + 3, cy + 3, aimed != null ? (surveyOk() ? GREEN : RED) : MUTED);

        int x = 214;
        UiKit.chamfer(ctx, 208, 24, 186, 120, 8, UiKit.PANEL, UiKit.CYAN_DIM);
        TabletStatus st = TabletStatus.latest;
        if (aimed != null) {
            line(ctx, "LANDING ZONE", x, 30, MUTED);
            UiKit.spaced(ctx, textRenderer, String.format(Locale.ROOT, "%d %d %d", aimed.getX(), aimed.getY() + 1, aimed.getZ()), x, 42, TEXT, 1f, 1.2f, false);
            double dist = client != null && client.player != null ? Math.sqrt(client.player.getBlockPos().getSquaredDistance(aimed)) : 0;
            line(ctx, String.format(Locale.ROOT, "RANGE  %.0f m", dist), x, 58, MUTED);
            line(ctx, "SITE SURVEY", x, 74, MUTED);
            TabletStatus.Survey sv = survey();
            if (sv == null) {
                line(ctx, "SCANNING" + ".".repeat(1 + (ticks / 5) % 3), x, 86, AMBER);
            } else if (sv.ok()) {
                UiKit.tag(ctx, textRenderer, "ZONE CLEAR", x, 85, GREEN);
                line(ctx, "Obstructions will be smashed", x, 100, MUTED);
                line(ctx, "flat on touchdown.", x, 110, MUTED);
            } else {
                UiKit.tag(ctx, textRenderer, "NO ROOM TO DEPLOY", x, 85, RED);
                String why = Text.translatable("message.orbital_railgun.denied." + sv.reason()).getString();
                int yy = 100;
                for (String l : UiKit.wrap(textRenderer, why, 172)) { line(ctx, l, x, yy, 0xFFFF9A90); yy += 10; }
            }
        } else {
            line(ctx, "NO LANDING ZONE", x, 30, RED);
            line(ctx, "Aim at open ground, then", x, 46, MUTED);
            line(ctx, "raise the tablet again.", x, 58, MUTED);
        }
        line(ctx, ares0Page ? "PAYLOAD  ARES-0 PLANET KILLER" : "PAYLOAD  ARES-9 RAILGUN POD", x, 130, ares0Page ? 0xFF9FB0FF : MUTED);
        if (st.pending) line(ctx, "DROP INBOUND", x, 138 + 4, AMBER);
        else if (onCooldown()) line(ctx, "UPLINK RECHARGING", x, 142, AMBER);
    }

    private void drawFirePage(DrawContext ctx, float time) {
        TabletStatus st = TabletStatus.latest;
        UiKit.chamfer(ctx, 4, 22, 198, 188, 10, UiKit.PANEL, UiKit.CYAN_DIM);
        UiKit.chamfer(ctx, 204, 22, 192, 124, 10, UiKit.PANEL, UiKit.CYAN_DIM);
        title(ctx, "FIRE CONTROL", 8, 24);
        // Installed loadout chips
        Loadout lo = Loadout.of(st.loadout);
        java.util.List<Loadout.Option> opts = new java.util.ArrayList<>();
        if (lo.muzzle() != null) opts.add(lo.muzzle());
        if (lo.ammo() != null) opts.add(lo.ammo());
        opts.addAll(lo.modules());
        for (int i = 0; i < opts.size(); i++) {
            RenderSystem.enableBlend();
            ctx.drawTexture(RailgunMod.id("textures/gui/option/" + opts.get(i).id + ".png"), 112 + i * 14, 22, 13, 13, 0, 0, 96, 96, 96, 96);
            RenderSystem.disableBlend();
        }

        // State + location
        ctx.getMatrices().push();
        ctx.getMatrices().translate(8, 40, 0);
        ctx.getMatrices().scale(1.3f, 1.3f, 1f);
        ctx.drawText(textRenderer, Text.translatable(st.state.translationKey()), 0, 0, stateColor(st.state, time), false);
        ctx.getMatrices().pop();
        double dist = client != null && client.player != null ? Math.sqrt(client.player.getBlockPos().getSquaredDistance(st.pos)) : 0;
        line(ctx, String.format(Locale.ROOT, "AT %d %d %d  (%.0f m)", st.pos.getX(), st.pos.getY(), st.pos.getZ(), dist), 8, 54, MUTED);

        // Target fields
        String[] axes = {"X", "Y", "Z"};
        boolean live = canRetarget();
        for (int i = 0; i < 3; i++) {
            int y = 70 + i * 18;
            ctx.fill(8, y, 20, y + 14, live ? 0xFF4A1210 : 0xFF1E2226);
            ctx.drawCenteredTextWithShadow(textRenderer, axes[i], 14, y + 3, 0xFFFFD0C8);
            boolean f = focused == i;
            ctx.fill(24, y, 196, y + 14, f ? 0xFF0F2A33 : 0xFF0A1418);
            ctx.fill(24, y + 13, 196, y + 14, f ? CYAN : 0x665FE3FF);
            String text = fields[i].text + (f && ((int) (time / 6)) % 2 == 0 ? "_" : "");
            ctx.drawText(textRenderer, text, 28, y + 3, live ? 0xFFFF7A6A : MUTED, false);
        }
        if (st.hasTarget) {
            line(ctx, String.format(Locale.ROOT, "LOCK  %d %d %d", st.target.getX(), st.target.getY(), st.target.getZ()), 8, 150, RED);
            double range = Math.sqrt(st.pos.getSquaredDistance(st.target));
            line(ctx, String.format(Locale.ROOT, "RANGE %.0f / %.0f m", range, RailgunConfig.MAX_FIRE_RANGE * Loadout.of(st.loadout).rangeMul()), 8, 162, MUTED);
        } else {
            line(ctx, "NO TARGET LOCKED", 8, 150, MUTED);
        }

        // Capacitor
        int rx = 208, rw = 184;
        line(ctx, "CAPACITOR", rx, 24, MUTED);
        String pct = String.format(Locale.ROOT, "%3d%%", (int) (st.charge * 100));
        ctx.drawText(textRenderer, pct, rx + rw - textRenderer.getWidth(pct), 24, TEXT, false);
        int segs = 23, segW = rw / segs;
        int lit = (int) Math.ceil(st.charge * segs);
        for (int i = 0; i < segs; i++) {
            int sx = rx + i * segW;
            ctx.fill(sx, 35, sx + segW - 1, 47, i < lit ? lerpColor(0xFF901414, 0xFFFF6048, (float) i / segs) : 0xFF1A1214);
        }
        // Oscilloscope
        int oy = 52, oh = 50;
        ctx.fill(rx, oy, rx + rw, oy + oh, 0xFF06100B);
        for (int gx = rx; gx < rx + rw; gx += 12) ctx.fill(gx, oy, gx + 1, oy + oh, 0xFF0E2416);
        for (int gy = oy; gy < oy + oh; gy += 10) ctx.fill(rx, gy, rx + rw, gy + 1, 0xFF0E2416);
        float amp = 2f + st.charge * (oh / 2f - 4f);
        int prevY = oy + oh / 2;
        for (int px = 1; px < rw - 1; px++) {
            float phase = px * (0.12f + st.charge * 0.35f) - time * (0.25f + st.charge);
            float noise = st.state == RailgunState.FIRING ? MathHelper.sin(px * 1.7f + time * 5f) * 6f : 0f;
            int py = MathHelper.clamp(oy + oh / 2 + (int) (MathHelper.sin(phase) * amp + noise), oy + 1, oy + oh - 2);
            ctx.fill(rx + px, Math.min(prevY, py), rx + px + 1, Math.max(prevY, py) + 1, GREEN);
            prevY = py;
        }
        line(ctx, String.format(Locale.ROOT, "AZ %+07.2f   EL %+06.2f", st.yaw, st.pitch), rx, 108, MUTED);

        // Context hint
        String hint = switch (st.state) {
            case POD -> "Packed. Use the pod's panel to deploy.";
            case PANEL_OPEN, TRANSFORMING -> String.format(Locale.ROOT, "Deploying... %d%%",
                    Math.min(100, (int) (100f * (st.stateTicks + (st.state == RailgunState.TRANSFORMING ? RailgunConfig.PANEL_TICKS : 0))
                            / (RailgunConfig.PANEL_TICKS + RailgunConfig.TRANSFORM_TICKS))));
            case PACKING -> "Retracting into pod...";
            case POD_READY -> "Packed. Use its panel to RETURN.";
            case LAUNCHING -> "Returning to orbit.";
            case CHARGING -> "Charging. Weapon will fire on lock.";
            case FIRING -> "FIRING";
            case COOLDOWN -> "Cooling down.";
            case IDLE -> st.hasTarget ? "Ready. Press FIRE." : "Set a target to arm.";
            default -> "";
        };
        if (!st.sameDimension) hint = "Railgun is in another dimension.";
        line(ctx, hint, rx, 124, st.state == RailgunState.IDLE ? GREEN : AMBER);
    }

    private void title(DrawContext ctx, String text, int x, int y) {
        ctx.fill(x, y + 2, x + 3, y + 7, CYAN);
        UiKit.spaced(ctx, textRenderer, text, x + 6, y, TEXT, 1.5f, 1f, false);
    }

    private void line(DrawContext ctx, String text, int x, int y, int color) {
        ctx.drawText(textRenderer, text, x, y, color, false);
    }

    private static void ring(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int i = 0; i < 96; i++) {
            float a = i / 96f * MathHelper.TAU;
            int px = cx + (int) (MathHelper.cos(a) * r), py = cy + (int) (MathHelper.sin(a) * r);
            ctx.fill(px, py, px + 1, py + 1, color);
        }
    }

    private int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> GREEN;
            case CHARGING -> ((int) (time / 3)) % 2 == 0 ? RED : AMBER;
            case FIRING -> 0xFFFFFFFF;
            case COOLDOWN, TRANSFORMING, PANEL_OPEN, PACKING -> AMBER;
            default -> MUTED;
        };
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private void play(net.minecraft.sound.SoundEvent sound, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
    }

    // ---- Widgets -------------------------------------------------------------------------------

    private static final class NumberField {
        String text = "";
    }

    private final class Btn {
        final int x, y, w, h;
        final String label;
        final int accent;
        final BooleanSupplier enabled;
        final Runnable action;
        final boolean big;
        final Page page;

        Btn(int x, int y, int w, int h, String label, int accent, BooleanSupplier enabled, Runnable action, boolean big, Page page) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.label = label; this.accent = accent;
            this.enabled = enabled; this.action = action; this.big = big; this.page = page;
        }

        boolean hit(float mx, float my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        void render(DrawContext ctx, float mx, float my, float time) {
            boolean on = enabled.getAsBoolean();
            boolean hover = on && hit(mx, my);
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            if (big) {
                UiKit.chamfer(ctx, x, y, w, h, 10, on ? UiKit.alpha(accent, hover ? 0.6f : 0.22f + 0.15f * pulse) : 0x40202830, on ? accent : 0xFF2A333C);
                for (int sx = x + 12; sx < x + w - 12; sx += 10) {
                    ctx.fill(sx, y + 3, sx + 5, y + 5, on ? 0xE0D69614 : 0xFF2A2A2A);
                    ctx.fill(sx + 3, y + h - 5, sx + 8, y + h - 3, on ? 0xE0D69614 : 0xFF2A2A2A);
                }
            } else {
                UiKit.chamfer(ctx, x, y, w, h, 4, on ? (hover ? UiKit.alpha(accent, 0.45f) : 0xE0101A22) : 0xC00C1014, on ? accent : 0xFF2A2E36);
            }
            float scale = big ? 2.0f : 1.0f;
            int color = on ? 0xFFFFFFFF : 0xFF5A626C;
            float tw = UiKit.spacedWidth(textRenderer, label, big ? 2f : 1f, scale);
            UiKit.spaced(ctx, textRenderer, label, x + (w - tw) / 2f, y + h / 2f - 4 * scale, color, big ? 2f : 1f, scale, big && on);
        }
    }
}
