package com.creedpdf.orbitalrailgun.client.tablet;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.krl.KrlUi;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
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

/**
 * The ARES uplink tablet, "zoomed into": a tactical command display.
 *
 * <p>The tablet is a real item model held in both hands (see {@code HeldItemRendererMixin}). While this screen is open
 * {@link #zoom(float)} goes 0 to 1 and the mixin pulls the tablet up to the camera; then the display boots.</p>
 *
 * <p>Layout: asset tiles down the left (ARES-9, ARES-0, ORBITAL DROP, HANGAR) with their live status; the chosen
 * asset's pages as tabs across the top; the page below in cards. Anything that cannot be undone - firing, recalls,
 * drops, calling the ship - is a hold-to-confirm button: hold it until the bar fills.</p>
 */
public class TabletScreen extends Screen {
    private static final Identifier DISPLAY = RailgunMod.id("textures/gui/tablet_display.png");
    public static final int ZOOM_TICKS = 9;
    // Display area inside tablet_display.png (512x288).
    private static final float DX0 = 40f / 512, DY0 = 30f / 288, DX1 = 472f / 512, DY1 = 258f / 288;
    private static final int CW = 400, CH = 214; // virtual canvas
    private static final float NAV_X0 = 4, NAV_X1 = 82, MAIN_X0 = 86, MAIN_X1 = CW - 4, TOP = 18, BOTTOM = CH - 10;
    /** How long a hold-to-confirm button must be held, in ticks. */
    private static final int HOLD = 16;

    // palette: graphite glass, ARES red, status colours
    private static final int BG = 0xF40D0E0F, PANEL = 0xE0161819, PANEL_HI = 0xE01E2122, EDGE = 0xFF34373A, TICK = 0xFF6E7378;
    private static final int RED = 0xFFE8382E, RED_DIM = 0xFF7A2420, AMBER = 0xFFFFB02E, GREEN = 0xFF7FD67A, ORANGE = 0xFFFF7A1E,
            VIOLET = 0xFFB07CFF, CYAN = 0xFF5FE3D0, WHITE = 0xFFEEF0EC, GREY = 0xFF9AA09C, DIM = 0xFF5E6462, FAINT = 0xFF33373A;

    @Nullable private static TabletScreen open;
    /** Remembered between openings. */
    @Nullable private static Page lastPage;

    @Nullable private final BlockPos aimed;
    private int ticks;
    private int closeTicks = -1;
    private int bootTicks = -1;
    private final String[] fields = {"", "", ""};
    private int focused = -1;
    private boolean fieldsInitialised;
    private Page page;
    private int pageTicks;
    private int a0Pick = -1;
    private final KrlUi ui = new KrlUi();
    private float ox, oy, s = 1f;

    // hold-to-confirm
    @Nullable private String holdKey;
    @Nullable private Runnable holdAction;
    private int holdTicks;
    private boolean mouseDown;
    private final List<HoldZone> holdZones = new ArrayList<>();

    private record HoldZone(String key, float x, float y, float w, float h, Runnable action, boolean enabled) {}

    private enum Asset { A9, A0, DROP, HANGAR }

    private enum Page {
        A9_DEPLOY(Asset.A9, "DEPLOY"), A9_STATUS(Asset.A9, "STATUS"), A9_FIRE(Asset.A9, "FIRE CONTROL"), A9_SCAN(Asset.A9, "SCAN"), A9_RIFT(Asset.A9, "RIFT"), A9_RECALL(Asset.A9, "RECALL"),
        A0_TARGETS(Asset.A0, "TARGETS"), A0_RECALL(Asset.A0, "RECALL"),
        DROP_A9(Asset.DROP, "ARES-9 POD"), DROP_A0(Asset.DROP, "ARES-0 POD"),
        SHIP(Asset.HANGAR, "STARSHIP");

        final Asset asset;
        final String title;

        Page(Asset asset, String title) {
            this.asset = asset;
            this.title = title;
        }
    }

    public TabletScreen(@Nullable BlockPos aimed) {
        super(Text.translatable("item.orbital_railgun.railgun_remote"));
        this.aimed = aimed;
        this.page = lastPage != null ? lastPage : Page.A9_FIRE;
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
    }

    // ---- state ----------------------------------------------------------------------------------

    private static TabletStatus st() {
        return TabletStatus.latest;
    }

    private boolean a9() {
        return st().found;
    }

    private boolean a0() {
        return st().a0Found;
    }

    private boolean available(Page p) {
        return switch (p) {
            case A9_DEPLOY, A9_STATUS, A9_FIRE, A9_SCAN, A9_RIFT, A9_RECALL -> a9();
            case A0_TARGETS, A0_RECALL -> a0();
            case DROP_A9 -> !a9();
            case DROP_A0 -> !a0();
            case SHIP -> true;
        };
    }

    private Page firstOf(Asset a) {
        if (a == Asset.A9 && a9()) return podForm() ? Page.A9_DEPLOY : Page.A9_FIRE;
        for (Page p : Page.values()) if (p.asset == a && available(p)) return p;
        for (Page p : Page.values()) if (p.asset == a) return p;
        return Page.SHIP;
    }

    private void select(Page p) {
        if (page != p) {
            page = p;
            lastPage = p;
            pageTicks = 0;
            focused = -1;
            cancelHold();
            play(ModSounds.UI_CLICK, 1.25f);
            if ((p == Page.DROP_A9 || p == Page.DROP_A0) && aimed != null) ClientPackets.requestSurvey(aimed);
        }
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
        return client != null && client.player != null && client.player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE);
    }

    private static boolean podForm() {
        RailgunState s = st().state;
        return s == RailgunState.POD || s == RailgunState.PANEL_OPEN || s == RailgunState.POD_READY || s == RailgunState.FALLING;
    }

    /** The loadout being put together on the DEPLOY page (sent when ordered or deployed). */
    private static int pendingLoadout = -1;
    @Nullable private Loadout.Option hoverOpt;

    private boolean canRetarget() {
        TabletStatus t = st();
        return t.found && t.sameDimension && (t.state == RailgunState.IDLE || t.state == RailgunState.COOLDOWN);
    }

    // ---- actions --------------------------------------------------------------------------------

    private void callDown(int kind) {
        if (aimed == null) return;
        ClientPackets.sendDeployRequest(aimed, kind);
        beginClose();
    }

    private void paint() {
        if (aimed == null) return;
        fields[0] = Integer.toString(aimed.getX());
        fields[1] = Integer.toString(aimed.getY());
        fields[2] = Integer.toString(aimed.getZ());
        submitTarget();
    }

    private void submitTarget() {
        try {
            ClientPackets.sendTarget(new BlockPos(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
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
            closeTicks = Math.max(0, ZOOM_TICKS - Math.min(ticks, ZOOM_TICKS));
            play(ModSounds.UI_TABLET_CLOSE, 1f);
        }
    }

    private void cancelHold() {
        holdKey = null;
        holdAction = null;
        holdTicks = 0;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void tick() {
        ticks++;
        pageTicks++;
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
        if (aimed != null && (page == Page.DROP_A9 || page == Page.DROP_A0) && ticks % 40 == 0) ClientPackets.requestSurvey(aimed);
        TabletStatus t = st();
        if (!fieldsInitialised && t.received) {
            BlockPos init = t.found && t.hasTarget ? t.target : aimed;
            if (init != null) {
                fields[0] = Integer.toString(init.getX());
                fields[1] = Integer.toString(init.getY());
                fields[2] = Integer.toString(init.getZ());
            }
            fieldsInitialised = true;
            if (lastPage == null) page = a9() ? firstOf(Asset.A9) : a0() ? Page.A0_TARGETS : Page.DROP_A9;
        }
        if (t.received && !available(page)) {
            Page p = a9() ? Page.A9_STATUS : a0() ? Page.A0_TARGETS : Page.DROP_A9;
            if (!available(p)) p = Page.DROP_A0;
            page = p;
            pageTicks = 0;
        }
        if (t.a0Found && a0Pick < 0) a0Pick = t.a0Target;
        // hold-to-confirm: keep filling while the button is held under the cursor
        if (holdKey != null) {
            HoldZone z = zone(holdKey);
            if (!mouseDown || z == null || !z.enabled() || !inZone(z, lastMx, lastMy)) {
                cancelHold();
            } else if (++holdTicks >= HOLD) {
                Runnable a = holdAction;
                cancelHold();
                mouseDown = false;
                play(ModSounds.UI_CLICK, 0.7f);
                if (a != null) a.run();
            } else if (holdTicks % 4 == 0) {
                play(ModSounds.UI_COUNTDOWN_BEEP, 0.9f + holdTicks * 0.03f);
            }
        }
        if (client != null && client.player != null && !client.player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) beginClose();
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
        return closeTicks < 0 && ticks >= ZOOM_TICKS && (bootTicks < 0 || bootTicks >= 22);
    }

    // ---- input ----------------------------------------------------------------------------------

    private float lastMx, lastMy;

    @Nullable
    private HoldZone zone(String key) {
        for (HoldZone z : holdZones) if (z.key().equals(key)) return z;
        return null;
    }

    private static boolean inZone(HoldZone z, float x, float y) {
        return x >= z.x() && x < z.x() + z.w() && y >= z.y() && y < z.y() + z.h();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!uiLive() || button != 0) return true;
        float cx = (float) ((mouseX - ox) / s), cy = (float) ((mouseY - oy) / s);
        lastMx = cx;
        lastMy = cy;
        mouseDown = true;
        for (HoldZone z : holdZones) {
            if (inZone(z, cx, cy)) {
                if (z.enabled()) {
                    holdKey = z.key();
                    holdAction = z.action();
                    holdTicks = 0;
                    play(ModSounds.UI_COUNTDOWN_BEEP, 0.9f);
                } else {
                    play(ModSounds.UI_DENIED, 1.3f);
                }
                return true;
            }
        }
        int r = ui.click(cx, cy);
        if (r == 1) play(ModSounds.UI_CLICK, 1f);
        else if (r == 0) play(ModSounds.UI_DENIED, 1.3f);
        else focused = -1;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            mouseDown = false;
            cancelHold();
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        lastMx = (float) ((mouseX - ox) / s);
        lastMy = (float) ((mouseY - oy) / s);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        mouseMoved(mouseX, mouseY);
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
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !fields[focused].isEmpty()) {
                fields[focused] = fields[focused].substring(0, fields[focused].length() - 1);
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
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            List<Page> tabs = tabs(page.asset);
            int i = tabs.indexOf(page);
            for (int k = 0; k < tabs.size(); k++) {
                i = (i + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : tabs.size() - 1)) % tabs.size();
                if (available(tabs.get(i))) {
                    select(tabs.get(i));
                    break;
                }
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            Asset[] all = Asset.values();
            int i = page.asset.ordinal();
            i = (i + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : all.length - 1)) % all.length;
            select(firstOf(all[i]));
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focused < 0 || !uiLive()) return false;
        String t = fields[focused];
        if ((Character.isDigit(chr) && t.replace("-", "").length() < 8) || (chr == '-' && t.isEmpty())) {
            fields[focused] = t + chr;
            return true;
        }
        return false;
    }

    // ---- rendering ------------------------------------------------------------------------------

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
        if (alpha < 0.98f || closeTicks >= 0) return;

        float time = ticks + delta;
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(ox, oy, 0);
        ms.scale(s, s, 1f);
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;
        ui.begin(ctx, mx, my);
        holdZones.clear();

        backdrop(time);
        topBar(time);
        nav(time);
        tabsBar();
        drawPage(time);
        footer();

        if (bootTicks >= 0 && bootTicks < 22) boot(bootTicks + delta);
        ms.pop();
    }

    private void backdrop(float time) {
        ui.fill(0, 0, CW, CH, BG);
        // faint hex-dot grid
        for (int y = 16; y < CH; y += 8)
            for (int x = (y / 8) % 2 == 0 ? 2 : 6; x < CW; x += 8) ui.fill(x, y, x + 0.5f, y + 0.5f, 0x14FFFFFF);
        // slow interference band
        float band = (time * 1.1f) % (CH + 40) - 20;
        for (int i = 0; i < 14; i++) {
            float y = band + i;
            if (y < 0 || y >= CH) continue;
            ui.fill(0, y, CW, y + 1, ((int) (9 * (1 - Math.abs(i - 7) / 7f)) << 24) | 0xE8382E);
        }
    }

    private void topBar(float time) {
        ui.fill(0, 0, CW, 14, 0xFF131516);
        ui.fill(0, 14, CW, 14.5f, RED_DIM);
        ui.fill(4, 3, 7, 11, RED);
        ui.head("ARES UPLINK", 10, 2.4f, WHITE, 0.82f);
        ui.text("ORBITAL COMMAND NETWORK  //  CLEARANCE OMEGA", 10 + ui.hw("ARES UPLINK", 0.82f) + 5, 5.3f, DIM, 0.42f);
        TabletStatus t = st();
        String link;
        int col;
        if (!t.received) {
            link = "HANDSHAKE";
            col = AMBER;
        } else if (t.found || t.a0Found) {
            link = "UPLINK SECURE  " + (t.found ? "A9" : "--") + " / " + (t.a0Found ? "A0" : "--");
            col = GREEN;
        } else if (t.pending) {
            link = "DROP INBOUND";
            col = AMBER;
        } else {
            link = "NO ASSETS DEPLOYED";
            col = GREY;
        }
        float lw = ui.tw(link, 0.48f) + 10;
        ui.fill(CW - 4 - lw, 3, CW - 4, 11, KrlUi.alpha(col, 0.16f));
        ui.outline(CW - 4 - lw, 3, CW - 4, 11, 0.5f, col);
        float blink = ((int) (time / 10)) % 2 == 0 ? 1 : 0.4f;
        ui.fill(CW - 4 - lw + 2.5f, 6, CW - 4 - lw + 4.5f, 8, KrlUi.alpha(col, blink));
        ui.text(link, CW - 4 - lw + 6.5f, 4.8f, col, 0.48f);
        if (client != null && client.world != null) {
            long d = (client.world.getTimeOfDay() + 6000) % 24000;
            String clock = String.format(Locale.ROOT, "%02d:%02d LOCAL", d / 1000, (d % 1000) * 60 / 1000);
            ui.textR(clock, CW - 10 - lw, 5.3f, GREY, 0.45f);
        }
    }

    private void nav(float time) {
        TabletStatus t = st();
        Asset[] assets = Asset.values();
        float h = 44, gap = 3;
        for (int i = 0; i < assets.length; i++) {
            Asset a = assets[i];
            float y0 = TOP + i * (h + gap), y1 = y0 + h;
            boolean sel = page.asset == a;
            boolean hv = ui.hover(NAV_X0, y0, NAV_X1 - NAV_X0, h);
            int accent = switch (a) {
                case A9 -> RED;
                case A0 -> ORANGE;
                case DROP -> AMBER;
                case HANGAR -> CYAN;
            };
            ui.fill(NAV_X0, y0, NAV_X1, y1, sel ? PANEL_HI : hv ? 0xE01A1C1D : PANEL);
            ui.outline(NAV_X0, y0, NAV_X1, y1, 0.5f, sel ? accent : EDGE);
            if (sel) ui.fill(NAV_X0, y0, NAV_X0 + 2, y1, accent);
            String name = switch (a) {
                case A9 -> "ARES-9";
                case A0 -> "ARES-0";
                case DROP -> "ORBITAL DROP";
                case HANGAR -> "HANGAR";
            };
            String role = switch (a) {
                case A9 -> "ORBITAL RAILGUN";
                case A0 -> "CORE COLLAPSE ARRAY";
                case DROP -> "POD DELIVERY";
                case HANGAR -> "ARES STARSHIP";
            };
            String status;
            int sc;
            switch (a) {
                case A9 -> {
                    status = a9() ? stateName(t.state) : "NOT DEPLOYED";
                    sc = a9() ? stateColor(t.state, time) : DIM;
                }
                case A0 -> {
                    status = a0() ? t.a0State.name() : "NOT DEPLOYED";
                    sc = a0() ? (t.a0State == Ares0Entity.State.IDLE ? GREEN : ORANGE) : DIM;
                }
                case DROP -> {
                    status = t.pending ? "INBOUND" : onCooldown() ? "RECHARGING" : "READY";
                    sc = t.pending || onCooldown() ? AMBER : GREEN;
                }
                default -> {
                    String[] sst = {"NONE", "IN ORBIT", "DOCKED", "IN FLIGHT"};
                    int ss = MathHelper.clamp(t.shipState, 0, 3);
                    status = sst[ss];
                    sc = ss == 0 ? DIM : ss == 3 ? AMBER : GREEN;
                }
            }
            ui.head(name, NAV_X0 + 6, y0 + 4, sel ? WHITE : GREY, 0.72f);
            ui.text(role, NAV_X0 + 6, y0 + 15, DIM, 0.4f);
            ui.fill(NAV_X0 + 6, y0 + 25, NAV_X0 + 9, y0 + 28, sc);
            ui.text(status, NAV_X0 + 12, y0 + 24.6f, sc, 0.45f);
            // a tiny live bar per asset
            float v = switch (a) {
                case A9 -> a9() ? t.charge : 0;
                case A0 -> a0() && t.a0State == Ares0Entity.State.CHARGING ? Math.min(1f, t.a0Ticks / (float) Ares0Entity.CHARGE_TICKS) : 0;
                default -> 0;
            };
            ui.meter(NAV_X0 + 6, y0 + 34, NAV_X1 - NAV_X0 - 12, 2, v, accent, 0xFF26292B);
            final Asset fa = a;
            ui.hits.add(new KrlUi.Hit(NAV_X0, y0, NAV_X1 - NAV_X0, h, () -> select(firstOf(fa)), true));
        }
    }

    private List<Page> tabs(Asset a) {
        List<Page> out = new ArrayList<>();
        for (Page p : Page.values()) if (p.asset == a) out.add(p);
        return out;
    }

    private void tabsBar() {
        float x = MAIN_X0, y = TOP;
        for (Page p : tabs(page.asset)) {
            float w = ui.hw(p.title, 0.55f) + 12;
            boolean sel = p == page, en = available(p);
            boolean hv = en && ui.hover(x, y, w, 11);
            ui.fill(x, y, x + w, y + 11, sel ? 0xFF1F2224 : hv ? 0xFF1A1C1D : 0x00000000);
            if (sel) ui.fill(x, y + 10, x + w, y + 11, RED);
            ui.headC(p.title, x + w / 2, y + 1.8f, !en ? FAINT : sel ? WHITE : hv ? WHITE : GREY, 0.55f);
            ui.hits.add(new KrlUi.Hit(x, y, w, 11, () -> select(p), en));
            x += w + 2;
        }
        ui.fill(MAIN_X0, y + 11, MAIN_X1, y + 11.5f, EDGE);
    }

    private void footer() {
        ui.fill(0, CH - 8, CW, CH, 0xFF111314);
        ui.text("ESC CLOSE    UP / DOWN ASSET    LEFT / RIGHT PAGE    HOLD TO CONFIRM", 6, CH - 6.3f, DIM, 0.4f);
        StringBuilder sb = new StringBuilder();
        long seed = ticks / 6;
        for (int i = 0; i < 6; i++) sb.append(String.format(Locale.ROOT, "%04X ", (int) (((seed + i) * 0x9E3779B97F4A7C15L >>> 40) & 0xFFFF)));
        ui.textR(sb.toString(), CW - 6, CH - 6.3f, FAINT, 0.4f);
    }

    private void boot(float t) {
        float k = MathHelper.clamp(t / 18f, 0, 1);
        ui.fill(0, 0, CW, CH, 0xFF070808);
        ui.headC("ARES UPLINK", CW / 2f, CH / 2f - 22, RED, 1.2f);
        ui.textC("ORBITAL COMMAND NETWORK  //  RAILGUNS+", CW / 2f, CH / 2f - 6, GREY, 0.5f);
        ui.fill(CW / 2f - 60, CH / 2f + 6, CW / 2f + 60, CH / 2f + 8, 0xFF1E2122);
        ui.fill(CW / 2f - 60, CH / 2f + 6, CW / 2f - 60 + 120 * k, CH / 2f + 8, RED);
        String[] steps = {"HANDSHAKE", "DECRYPTING MANIFESTS", "CLEARANCE OMEGA", "ACCESS GRANTED"};
        ui.textC(steps[Math.min(steps.length - 1, (int) (k * steps.length))], CW / 2f, CH / 2f + 12, k >= 0.99f ? GREEN : DIM, 0.48f);
    }

    // ---- shared page pieces ---------------------------------------------------------------------

    private float cardTop() {
        return TOP + 15;
    }

    private void card(float x0, float y0, float x1, float y1, String title, int accent) {
        ui.panel(x0, y0, x1, y1, PANEL, EDGE, TICK);
        if (title != null) {
            ui.fill(x0 + 0.5f, y0 + 0.5f, x1 - 0.5f, y0 + 9, 0xFF1B1E1F);
            ui.fill(x0 + 3, y0 + 3, x0 + 5, y0 + 6.5f, accent);
            ui.head(title, x0 + 8, y0 + 1.6f, WHITE, 0.52f);
        }
    }

    private void kv(String k, String v, float x, float y, float w, int vc) {
        ui.kv(k, v, x, y, w, GREY, vc, FAINT, 0.48f);
    }

    /** A button that must be held until its bar fills. */
    private void holdButton(String key, String label, float x, float y, float w, float h, int accent, boolean enabled, Runnable action) {
        boolean hv = enabled && ui.hover(x, y, w, h);
        float p = key.equals(holdKey) ? MathHelper.clamp(holdTicks / (float) HOLD, 0, 1) : 0;
        ui.fill(x, y, x + w, y + h, !enabled ? 0x60202224 : hv ? KrlUi.mul(accent, 0.3f) : 0xC0141617);
        if (p > 0) ui.fill(x, y, x + w * p, y + h, KrlUi.mul(accent, 0.75f));
        ui.outline(x, y, x + w, y + h, 0.5f, enabled ? accent : FAINT);
        ui.headC(label, x + w / 2, y + h / 2 - 3.6f, !enabled ? DIM : p > 0 ? WHITE : accent, 0.62f);
        String hint = enabled ? (p > 0 ? "HOLD..." : "HOLD TO CONFIRM") : "UNAVAILABLE";
        ui.textC(hint, x + w / 2, y + h + 1.5f, enabled ? DIM : FAINT, 0.38f);
        holdZones.add(new HoldZone(key, x, y, w, h, action, enabled));
    }

    private void bigButton(String label, float x, float y, float w, float h, int accent, boolean enabled, Runnable action) {
        ui.button(label, x, y, w, h, accent, 0xFF101112, false, enabled, 0.62f, action);
    }

    /** A ring gauge with the value in the middle. */
    private void ringGauge(float cx, float cy, float r, float v, int accent, String big, String small) {
        float a0 = (float) Math.toRadians(135), span = (float) Math.toRadians(270);
        ui.arc(cx, cy, r, a0, a0 + span, 1.6f, 0xFF26292B, 0);
        ui.arc(cx, cy, r, a0, a0 + span * MathHelper.clamp(v, 0, 1), 1.6f, accent, 0);
        for (int i = 0; i <= 10; i++) {
            double a = a0 + span * i / 10.0;
            float x0 = cx + (float) Math.cos(a) * (r - 3), y0 = cy + (float) Math.sin(a) * (r - 3);
            float x1 = cx + (float) Math.cos(a) * (r - 1.5f), y1 = cy + (float) Math.sin(a) * (r - 1.5f);
            ui.line(x0, y0, x1, y1, 0.35f, DIM, 0);
        }
        ui.headC(big, cx, cy - 5, WHITE, 0.9f);
        ui.textC(small, cx, cy + 5, GREY, 0.4f);
    }

    private int distTo(BlockPos p) {
        return client != null && client.player != null ? (int) Math.sqrt(client.player.getBlockPos().getSquaredDistance(p)) : 0;
    }

    private String fmt(BlockPos p) {
        return String.format(Locale.ROOT, "%d  %d  %d", p.getX(), p.getY(), p.getZ());
    }

    // ---- pages ----------------------------------------------------------------------------------

    private void drawPage(float time) {
        switch (page) {
            case A9_DEPLOY -> a9Deploy(time);
            case A9_STATUS -> a9Status(time);
            case A9_FIRE -> a9Fire(time);
            case A9_SCAN -> a9Simple("SCAN", "Holographic pulse from the emplacement: maps ores and tags hostiles within 96 m. Results paint the world for everyone nearby.",
                    "RUN SCAN", 0xFF5FE3FF, st().state == RailgunState.IDLE, ClientPackets::sendScan, false);
            case A9_RIFT -> a9Simple("RIFT", "Fires the rail into the sky for one second and tears a rift into deep space. Hostiles nearby are dragged through. "
                    + "The rift holds for 30 seconds; anyone still up there when it closes is stranded.", "TEAR RIFT", VIOLET,
                    st().state == RailgunState.IDLE, ClientPackets::sendRift, true);
            case A9_RECALL -> recall("ARES-9", "Retracts the emplacement into its pod and launches it back to orbit. Works from any distance. The weapon must be idle.",
                    st().state == RailgunState.IDLE || st().state == RailgunState.COOLDOWN || st().state == RailgunState.POD
                            || st().state == RailgunState.POD_READY, 0);
            case A0_TARGETS -> a0Targets(time);
            case A0_RECALL -> recall("ARES-0", "Folds the array and sends it back to orbit. It cannot leave while charging or firing.",
                    st().a0State == Ares0Entity.State.IDLE || st().a0State == Ares0Entity.State.COOLDOWN, 3);
            case DROP_A9 -> drop(time, 0);
            case DROP_A0 -> drop(time, 1);
            case SHIP -> hangar(time);
        }
    }

    private static String chipName(Loadout.Option o) {
        String t = o.title;
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private void a9Deploy(float time) {
        TabletStatus t = st();
        boolean pod = t.state == RailgunState.POD;
        if (pendingLoadout < 0 || !pod) pendingLoadout = t.loadout;
        Loadout want = Loadout.of(pendingLoadout), have = Loadout.of(t.loadout);
        float y0 = cardTop();
        float lx1 = MAIN_X0 + 206;
        card(MAIN_X0, y0, lx1, BOTTOM, "LOADOUT  //  ORBITAL STORAGE", RED);
        hoverOpt = null;
        float y = y0 + 13;
        y = optRow("MUZZLE DEVICE", Loadout.MUZZLES, want, y, pod, false);
        y = optRow("AMMUNITION", Loadout.AMMO, want, y + 2, pod, false);
        optRow("SUPPORT MODULES  " + want.modules().size() + " / " + Loadout.MAX_MODULES, Loadout.MODULES, want, y + 2, pod, true);
        // right: details and actions
        float rx0 = lx1 + 4;
        card(rx0, y0, MAIN_X1, BOTTOM, "EMPLACEMENT", RED);
        float w = MAIN_X1 - rx0 - 10, ry = y0 + 13;
        kv("STATE", stateName(t.state), rx0 + 5, ry, w, pod ? AMBER : stateColor(t.state, time));
        ry += 9;
        Loadout.Option o = hoverOpt;
        if (o != null) {
            ui.head(o.title, rx0 + 5, ry, 0xFF000000 | o.color, 0.5f);
            ry = ui.para(o.description, rx0 + 5, ry + 8, w, GREY, 0.42f, 6f) + 1;
            for (String stat : o.stats) {
                ui.text("+ " + stat, rx0 + 5, ry, WHITE, 0.42f);
                ry += 6;
            }
        } else {
            ui.para(pod ? "Choose attachments, then hold DEPLOY. They drop from orbital storage and dock as the emplacement unfolds."
                    : "Deployed. Pack it into its pod (RECALL page) to change the loadout.", rx0 + 5, ry, w, GREY, 0.44f, 6.5f);
        }
        boolean dirty = want.raw() != have.raw();
        bigButton(dirty ? "ORDER LOADOUT" : "LOADOUT LOCKED", rx0 + 5, BOTTOM - 54, w, 12, AMBER, pod && dirty,
                () -> ClientPackets.sendRemote(4, pendingLoadout));
        holdButton("deploy", t.state == RailgunState.POD_READY ? "LAUNCH TO ORBIT" : "DEPLOY", rx0 + 5, BOTTOM - 36, w, 22, RED,
                pod || t.state == RailgunState.POD_READY, () -> {
                    if (st().state == RailgunState.POD_READY) {
                        ClientPackets.sendRemote(7, -1);
                        return;
                    }
                    if (pendingLoadout != st().loadout) ClientPackets.sendRemote(4, pendingLoadout);
                    ClientPackets.sendRemote(5, -1);
                });
    }

    private float optRow(String label, List<Loadout.Option> opts, Loadout want, float y, boolean pod, boolean modules) {
        ui.text(label, MAIN_X0 + 6, y, DIM, 0.4f);
        y += 6;
        float x = MAIN_X0 + 6, cw = 38, ch = 9, gap = 1.5f, right = MAIN_X0 + 200;
        for (Loadout.Option o : opts) {
            if (x + cw > right) {
                x = MAIN_X0 + 6;
                y += ch + gap;
            }
            boolean on = want.has(o);
            boolean hv = ui.hover(x, y, cw, ch);
            if (hv) hoverOpt = o;
            int col = 0xFF000000 | o.color;
            ui.fill(x, y, x + cw, y + ch, on ? KrlUi.mul(col, 0.42f) : hv ? 0xFF1F2224 : 0xFF141617);
            ui.outline(x, y, x + cw, y + ch, 0.5f, on ? col : EDGE);
            ui.fill(x, y, x + 1.5f, y + ch, on ? col : KrlUi.mul(col, 0.5f));
            ui.textC(chipName(o), x + cw / 2 + 0.7f, y + 2.6f, on ? WHITE : pod ? GREY : DIM, 0.38f);
            final Loadout.Option fo = o;
            ui.hits.add(new KrlUi.Hit(x, y, cw, ch, () -> pendingLoadout = Loadout.of(pendingLoadout).toggle(fo).raw(), pod));
            x += cw + gap;
        }
        return y + ch + 2;
    }

    private void a9Status(float time) {
        TabletStatus t = st();
        float y0 = cardTop();
        card(MAIN_X0, y0, MAIN_X0 + 96, y0 + 96, "CAPACITOR", RED);
        ringGauge(MAIN_X0 + 48, y0 + 54, 30, t.charge, RED, String.format(Locale.ROOT, "%d%%", (int) (t.charge * 100)), stateName(t.state));
        float x0 = MAIN_X0 + 100, x1 = MAIN_X1;
        card(x0, y0, x1, y0 + 96, "ARES-9  //  TELEMETRY", RED);
        float y = y0 + 13, w = x1 - x0 - 10;
        kv("STATE", stateName(t.state), x0 + 5, y, w, stateColor(t.state, time));
        y += 8;
        kv("POSITION", fmt(t.pos), x0 + 5, y, w, WHITE);
        y += 8;
        kv("DISTANCE", distTo(t.pos) + " m" + (t.sameDimension ? "" : "  [OTHER DIM]"), x0 + 5, y, w, t.sameDimension ? WHITE : AMBER);
        y += 8;
        kv("AZIMUTH / ELEV", String.format(Locale.ROOT, "%+07.2f / %+06.2f", t.yaw, t.pitch), x0 + 5, y, w, WHITE);
        y += 8;
        kv("TARGET", t.hasTarget ? fmt(t.target) : "NONE", x0 + 5, y, w, t.hasTarget ? RED : DIM);
        y += 8;
        kv("MAX RANGE", String.format(Locale.ROOT, "%.0f m", RailgunConfig.MAX_FIRE_RANGE * Loadout.of(t.loadout).rangeMul()), x0 + 5, y, w, WHITE);
        y += 11;
        ui.head("LOADOUT", x0 + 5, y, GREY, 0.5f);
        y += 8;
        Loadout lo = Loadout.of(t.loadout);
        List<Loadout.Option> opts = new ArrayList<>();
        if (lo.muzzle() != null) opts.add(lo.muzzle());
        if (lo.ammo() != null) opts.add(lo.ammo());
        opts.addAll(lo.modules());
        if (opts.isEmpty()) ui.text("STANDARD CONFIGURATION", x0 + 5, y, DIM, 0.45f);
        float cx = x0 + 5, cy = y;
        for (Loadout.Option o : opts) {
            String nm = o.title.toUpperCase(Locale.ROOT);
            float wch = ui.tw(nm, 0.42f) + 8;
            if (cx + wch > x1 - 5) {
                cx = x0 + 5;
                cy += 8;
            }
            ui.fill(cx, cy - 1, cx + wch, cy + 5.5f, KrlUi.alpha(o.color, 0.18f));
            ui.fill(cx, cy - 1, cx + 1.5f, cy + 5.5f, 0xFF000000 | o.color);
            ui.text(nm, cx + 4, cy, WHITE, 0.42f);
            cx += wch + 3;
        }
        // the trace
        float ty = y0 + 100;
        card(MAIN_X0, ty, MAIN_X1, BOTTOM, "RAIL FIELD", RED);
        trace(MAIN_X0 + 5, ty + 12, MAIN_X1 - MAIN_X0 - 10, BOTTOM - ty - 16, t.charge, t.state == RailgunState.FIRING, time);
    }

    private void trace(float x, float y, float w, float h, float charge, boolean firing, float time) {
        ui.fill(x, y + h / 2, x + w, y + h / 2 + 0.3f, FAINT);
        for (int i = 1; i < 8; i++) ui.fill(x + w * i / 8, y, x + w * i / 8 + 0.3f, y + h, 0x18FFFFFF);
        float amp = 1.5f + charge * (h / 2f - 2f);
        float prev = y + h / 2;
        for (float px = 1; px < w; px += 1) {
            float phase = px * (0.12f + charge * 0.35f) - time * (0.25f + charge);
            float noise = firing ? MathHelper.sin(px * 1.7f + time * 5f) * 5f : 0f;
            float py = MathHelper.clamp(y + h / 2 + MathHelper.sin(phase) * amp + noise, y, y + h - 1);
            ui.line(x + px - 1, prev, x + px, py, 0.6f, RED, 0);
            prev = py;
        }
    }

    private void a9Fire(float time) {
        TabletStatus t = st();
        float y0 = cardTop();
        boolean live = canRetarget();
        // coordinates
        float cx1 = MAIN_X0 + 150;
        card(MAIN_X0, y0, cx1, y0 + 64, "TARGET COORDINATES", RED);
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            float fy = y0 + 13 + i * 11;
            boolean f = focused == i;
            ui.head(axes[i], MAIN_X0 + 6, fy - 0.5f, live ? RED : DIM, 0.6f);
            float bx = MAIN_X0 + 16, bw = 72;
            ui.fill(bx, fy - 1.5f, bx + bw, fy + 7.5f, f ? 0xFF22161A : 0xFF101213);
            ui.outline(bx, fy - 1.5f, bx + bw, fy + 7.5f, 0.5f, f ? RED : EDGE);
            String txt = fields[i] + (f && ((int) (time / 6)) % 2 == 0 ? "_" : "");
            ui.text(txt, bx + 3, fy + 0.4f, live ? WHITE : DIM, 0.55f);
            final int idx = i;
            ui.hits.add(new KrlUi.Hit(bx, fy - 1.5f, bw, 9, () -> focused = idx, live));
        }
        bigButton("SET", MAIN_X0 + 92, y0 + 12, 52, 11, RED, live, this::submitTarget);
        bigButton("PAINT AIMED", MAIN_X0 + 92, y0 + 25, 52, 11, AMBER, live && aimed != null, this::paint);
        ui.text("TYPE, TAB, ENTER", MAIN_X0 + 92, y0 + 40, DIM, 0.38f);
        // radar: the emplacement in the middle, the target at its bearing and range
        float rx0 = cx1 + 4, rx1 = MAIN_X1;
        card(rx0, y0, rx1, y0 + 64, "TARGETING RADAR", RED);
        float rcx = rx0 + 34, rcy = y0 + 37, rr = 22;
        double maxR = RailgunConfig.MAX_FIRE_RANGE * Loadout.of(t.loadout).rangeMul();
        for (int i = 1; i <= 3; i++) ui.circle(rcx, rcy, rr * i / 3, 0.35f, i == 3 ? RED_DIM : FAINT, i == 3 ? 0 : 2);
        ui.line(rcx - rr, rcy, rcx + rr, rcy, 0.3f, FAINT, 0);
        ui.line(rcx, rcy - rr, rcx, rcy + rr, 0.3f, FAINT, 0);
        float sweep = time * 0.08f;
        for (int k = 0; k < 10; k++) {
            float a = sweep - k * 0.06f;
            ui.line(rcx, rcy, rcx + (float) Math.cos(a) * rr, rcy + (float) Math.sin(a) * rr, 0.5f, KrlUi.alpha(RED, 0.35f * (1 - k / 10f)), 0);
        }
        ui.fill(rcx - 1, rcy - 1, rcx + 1, rcy + 1, WHITE);
        double range = 0;
        if (t.hasTarget) {
            double dx = t.target.getX() - t.pos.getX(), dz = t.target.getZ() - t.pos.getZ();
            range = Math.sqrt(t.pos.getSquaredDistance(t.target));
            double hr = Math.hypot(dx, dz);
            float k = (float) Math.min(1, hr / maxR);
            float bx = rcx + (float) (hr < 1e-3 ? 0 : dx / hr) * rr * k, by = rcy + (float) (hr < 1e-3 ? 0 : dz / hr) * rr * k;
            float pulse = 1.2f + 0.6f * MathHelper.sin(time * 0.4f);
            ui.fill(bx - pulse, by - pulse, bx + pulse, by + pulse, RED);
            ui.circle(bx, by, 3, 0.4f, RED, 0);
        }
        float kx = rcx + rr + 8, kw = rx1 - kx - 5, ky = y0 + 14;
        ui.text("LOCK", kx, ky, GREY, 0.42f);
        ui.text(t.hasTarget ? fmt(t.target) : "NONE", kx, ky + 5, t.hasTarget ? RED : DIM, 0.48f);
        ui.text("RANGE", kx, ky + 13, GREY, 0.42f);
        ui.text(t.hasTarget ? String.format(Locale.ROOT, "%,.0f / %,.0f m", range, maxR) : "--", kx, ky + 18, WHITE, 0.48f);
        ui.meter(kx, ky + 25, kw, 2, (float) (range / maxR), range > maxR ? AMBER : RED, 0xFF26292B);
        ui.text("CAPACITOR", kx, ky + 31, GREY, 0.42f);
        ui.meter(kx, ky + 37, kw, 3, t.charge, RED, 0xFF26292B);
        // fire
        float fy = y0 + 70;
        card(MAIN_X0, fy, MAIN_X1, BOTTOM, "WEAPON RELEASE", RED);
        boolean canFire = t.state == RailgunState.IDLE && t.hasTarget && t.sameDimension;
        holdButton("fire", "EXECUTE FIRE", MAIN_X0 + 6, fy + 14, 150, 22, RED, canFire, this::fire);
        bigButton("ABORT", MAIN_X0 + 164, fy + 14, 60, 22, AMBER, t.state == RailgunState.CHARGING, ClientPackets::sendAbort);
        String hint = !t.sameDimension ? "RAILGUN IS IN ANOTHER DIMENSION" : switch (t.state) {
            case IDLE -> t.hasTarget ? "ARMED  //  WEAPON FREE" : "SET COORDINATES TO ARM";
            case CHARGING -> "CHARGING  //  FIRES ON LOCK";
            case FIRING -> "FIRING";
            case COOLDOWN -> "BARREL COOLING";
            case POD, POD_READY -> "PACKED IN POD";
            default -> "STANDBY";
        };
        kv("STATUS", hint, MAIN_X0 + 232, fy + 16, MAIN_X1 - MAIN_X0 - 238, t.state == RailgunState.IDLE ? GREEN : stateColor(t.state, time));
        kv("STATE", stateName(t.state), MAIN_X0 + 232, fy + 26, MAIN_X1 - MAIN_X0 - 238, stateColor(t.state, time));
    }

    private void a9Simple(String name, String desc, String label, int accent, boolean enabled, Runnable action, boolean hold) {
        float y0 = cardTop();
        card(MAIN_X0, y0, MAIN_X1, BOTTOM, "ARES-9  //  " + name, accent);
        float y = ui.para(desc, MAIN_X0 + 6, y0 + 14, MAIN_X1 - MAIN_X0 - 12, WHITE, 0.52f, 7f) + 6;
        kv("STATE", stateName(st().state), MAIN_X0 + 6, y, 160, enabled ? GREEN : AMBER);
        y += 14;
        if (hold) holdButton(name, label, MAIN_X0 + 6, y, 150, 22, accent, enabled, action);
        else bigButton(label, MAIN_X0 + 6, y, 150, 22, accent, enabled, action);
        if (!enabled) ui.text("WEAPON MUST BE IDLE", MAIN_X0 + 164, y + 8, DIM, 0.48f);
    }

    private void recall(String unit, String desc, boolean enabled, int action) {
        int accent = action == 0 ? RED : ORANGE;
        float y0 = cardTop();
        card(MAIN_X0, y0, MAIN_X1, BOTTOM, unit + "  //  RECALL TO ORBIT", accent);
        float y = ui.para(desc, MAIN_X0 + 6, y0 + 14, MAIN_X1 - MAIN_X0 - 12, WHITE, 0.52f, 7f) + 6;
        BlockPos p = action == 0 ? st().pos : st().a0Pos;
        kv("ASSET POSITION", fmt(p), MAIN_X0 + 6, y, 180, WHITE);
        y += 8;
        kv("DISTANCE", distTo(p) + " m", MAIN_X0 + 6, y, 180, WHITE);
        y += 16;
        holdButton("recall" + action, "RECALL TO ORBIT", MAIN_X0 + 6, y, 150, 22, accent, enabled, () -> ClientPackets.sendRemote(action, -1));
        if (action == 0) {
            RailgunState s9 = st().state;
            holdButton("pack", "PACK INTO POD", MAIN_X0 + 164, y, 120, 22, AMBER, s9 == RailgunState.IDLE || s9 == RailgunState.COOLDOWN,
                    () -> ClientPackets.sendRemote(6, -1));
            ui.para("PACK INTO POD folds the emplacement back into its pod and leaves it on the ground; pick a new loadout and deploy again from DEPLOY.",
                    MAIN_X0 + 6, y + 34, MAIN_X1 - MAIN_X0 - 12, DIM, 0.44f, 6.5f);
        } else if (!enabled) ui.text("ASSET BUSY", MAIN_X0 + 164, y + 8, DIM, 0.48f);
    }

    private void a0Targets(float time) {
        TabletStatus t = st();
        float y0 = cardTop();
        String state = switch (t.a0State) {
            case IDLE -> "READY";
            case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", Math.min(100, t.a0Ticks * 100 / Ares0Entity.CHARGE_TICKS));
            case FIRING -> "FIRING";
            case COOLDOWN -> "COOLING";
            default -> t.a0State.name();
        };
        float lx1 = MAIN_X0 + 196;
        card(MAIN_X0, y0, lx1, BOTTOM, "CORE COLLAPSE  //  TARGET BODIES", ORANGE);
        float y = y0 + 13;
        ui.text("BODY", MAIN_X0 + 6, y, DIM, 0.4f);
        ui.textR("STATUS", lx1 - 6, y, DIM, 0.4f);
        y += 6;
        for (Body b : Body.ALL) {
            if (!b.destroyable()) continue;
            boolean up = (t.a0Visible & (1 << b.ordinal())) != 0;
            boolean doomed = (t.a0Doomed & (1 << b.ordinal())) != 0 || ClientPlanets.elapsed(b, 0f) >= 0;
            boolean sel = a0Pick == b.ordinal();
            boolean hv = !doomed && ui.hover(MAIN_X0 + 4, y - 1, lx1 - MAIN_X0 - 8, 10);
            ui.fill(MAIN_X0 + 4, y - 1, lx1 - 4, y + 9, sel ? KrlUi.alpha(ORANGE, 0.28f) : hv ? 0x30FF7A1E : 0x00000000);
            if (sel) ui.fill(MAIN_X0 + 4, y - 1, MAIN_X0 + 5.5f, y + 9, ORANGE);
            ui.head(b.displayName().toUpperCase(Locale.ROOT), MAIN_X0 + 9, y + 0.3f, doomed ? FAINT : sel ? WHITE : GREY, 0.55f);
            String status = doomed ? "DESTROYED" : up ? "ABOVE HORIZON" : "BELOW HORIZON";
            int sc = doomed ? RED_DIM : up ? GREEN : DIM;
            float sw = ui.tw(status, 0.42f) + 6;
            ui.fill(lx1 - 6 - sw, y + 0.5f, lx1 - 6, y + 7.5f, KrlUi.alpha(sc, 0.15f));
            ui.text(status, lx1 - 3 - sw, y + 2, sc, 0.42f);
            final Body fb = b;
            if (!doomed) ui.hits.add(new KrlUi.Hit(MAIN_X0 + 4, y - 1, lx1 - MAIN_X0 - 8, 10, () -> {
                a0Pick = fb.ordinal();
                ClientPackets.sendRemote(1, fb.ordinal());
            }, true));
            y += 11;
        }
        // right: the array and the trigger
        float rx0 = lx1 + 4;
        card(rx0, y0, MAIN_X1, BOTTOM, "ARRAY", ORANGE);
        float charge = t.a0State == Ares0Entity.State.CHARGING ? Math.min(1f, t.a0Ticks / (float) Ares0Entity.CHARGE_TICKS)
                : t.a0State == Ares0Entity.State.IDLE ? 1f : 0f;
        ringGauge((rx0 + MAIN_X1) / 2, y0 + 44, 24, charge, ORANGE, state.split(" ")[0], t.a0State == Ares0Entity.State.CHARGING ? state : "ARES-0");
        Body pick = a0Pick >= 0 && a0Pick < Body.ALL.length ? Body.ALL[a0Pick] : null;
        boolean canFire = pick != null && t.a0State == Ares0Entity.State.IDLE && (t.a0Visible & (1 << pick.ordinal())) != 0
                && (t.a0Doomed & (1 << pick.ordinal())) == 0;
        float bw = MAIN_X1 - rx0 - 12;
        ui.textC(pick == null ? "SELECT A BODY" : pick.displayName().toUpperCase(Locale.ROOT), (rx0 + MAIN_X1) / 2, y0 + 76, pick == null ? DIM : WHITE, 0.5f);
        holdButton("a0fire", "COLLAPSE CORE", rx0 + 6, y0 + 86, bw, 22, ORANGE, canFire, () -> {
            ClientPackets.sendRemote(2, pick.ordinal());
            beginClose();
        });
        String hint = pick == null ? "" : !canFire ? (t.a0State != Ares0Entity.State.IDLE ? "ARRAY BUSY" : "TARGET BELOW THE HORIZON") : "THIS CANNOT BE UNDONE";
        ui.textC(hint, (rx0 + MAIN_X1) / 2, y0 + 118, canFire ? RED : DIM, 0.42f);
    }

    private void drop(float time, int kind) {
        TabletStatus t = st();
        int accent = kind == 0 ? RED : ORANGE;
        float y0 = cardTop();
        float lx1 = MAIN_X0 + 196;
        card(MAIN_X0, y0, lx1, BOTTOM, kind == 0 ? "ARES-9  //  RAILGUN POD" : "ARES-0  //  PLANET KILLER", accent);
        float y = y0 + 14, w = lx1 - MAIN_X0 - 12;
        if (aimed == null) {
            ui.head("NO LANDING ZONE", MAIN_X0 + 6, y, RED, 0.62f);
            ui.para("Aim at open ground, then raise the tablet again.", MAIN_X0 + 6, y + 11, w, GREY, 0.5f, 7f);
            return;
        }
        kv("LANDING ZONE", String.format(Locale.ROOT, "%d  %d  %d", aimed.getX(), aimed.getY() + 1, aimed.getZ()), MAIN_X0 + 6, y, w, WHITE);
        y += 8;
        kv("RANGE", distTo(aimed) + " m", MAIN_X0 + 6, y, w, WHITE);
        y += 8;
        TabletStatus.Survey sv = survey();
        String surveyText = sv == null ? "SCANNING" + ".".repeat(1 + (ticks / 5) % 3) : sv.ok() ? "ZONE CLEAR" : "NO ROOM TO DEPLOY";
        kv("SITE SURVEY", surveyText, MAIN_X0 + 6, y, w, sv == null ? AMBER : sv.ok() ? GREEN : RED);
        y += 10;
        if (sv != null && !sv.ok()) {
            y = ui.para(Text.translatable("message.orbital_railgun.denied." + sv.reason()).getString(), MAIN_X0 + 6, y, w, 0xFFFF8A80, 0.48f, 6.5f);
        } else if (sv != null) {
            y = ui.para("Obstructions will be flattened on touchdown.", MAIN_X0 + 6, y, w, GREY, 0.48f, 6.5f);
        }
        boolean can = !t.pending && !onCooldown() && surveyOk() && available(kind == 0 ? Page.DROP_A9 : Page.DROP_A0);
        holdButton("drop" + kind, "AUTHORIZE DROP", MAIN_X0 + 6, BOTTOM - 36, 150, 22, accent, can, () -> callDown(kind));
        String hint = t.pending ? "DROP INBOUND" : onCooldown() ? "UPLINK RECHARGING" : sv == null ? "AWAITING SURVEY" : sv.ok() ? "READY" : "CHOOSE ANOTHER SITE";
        ui.text(hint, MAIN_X0 + 160, BOTTOM - 28, t.pending || onCooldown() ? AMBER : DIM, 0.45f);
        // the orbit: the pod's track round the world, the zone below
        float rx0 = lx1 + 4;
        card(rx0, y0, MAIN_X1, BOTTOM, "ORBITAL TRACK", accent);
        float cx = (rx0 + MAIN_X1) / 2, cy = (y0 + BOTTOM) / 2 + 4, r0 = 18, r1 = 32;
        ui.circle(cx, cy, r0, 1.2f, 0xFF2E3A2E, 0);
        ui.circle(cx, cy, r0 - 2, 0.4f, 0xFF3A4A3A, 3);
        ui.circle(cx, cy, r1, 0.5f, KrlUi.alpha(accent, 0.5f), 4);
        float orbit = time * 0.04f;
        float sx = cx + MathHelper.cos(orbit) * r1, sy = cy + MathHelper.sin(orbit) * r1;
        ui.fill(sx - 1.5f, sy - 1.5f, sx + 1.5f, sy + 1.5f, accent);
        ui.fill(cx - 1, cy - r0 - 1, cx + 1, cy - r0 + 1, GREEN);
        ui.textC("LZ", cx, cy - r0 - 7, GREEN, 0.4f);
    }

    private void hangar(float time) {
        TabletStatus t = st();
        float y0 = cardTop();
        String[] names = {"NOT COMMISSIONED", "HOLDING IN ORBIT", "DOCKED", "IN FLIGHT"};
        int ss = MathHelper.clamp(t.shipState, 0, 3);
        card(MAIN_X0, y0, MAIN_X1, BOTTOM, "HANGAR  //  ARES STARSHIP", CYAN);
        float y = y0 + 14, w = 200;
        kv("STATUS", names[ss], MAIN_X0 + 6, y, w, ss == 0 ? DIM : ss == 3 ? AMBER : GREEN);
        y += 8;
        if (ss >= 2) {
            kv("POSITION", fmt(t.shipPos), MAIN_X0 + 6, y, w, WHITE);
            y += 8;
            kv("WORLD", t.shipDim.isEmpty() ? "?" : t.shipDim.toUpperCase(Locale.ROOT), MAIN_X0 + 6, y, w, WHITE);
            y += 8;
        }
        if (ss == 0) {
            ui.para("Use an ARES Starship Command Key to commission a ship. It waits in orbit until you call it.", MAIN_X0 + 6, y + 4,
                    MAIN_X1 - MAIN_X0 - 12, GREY, 0.5f, 7f);
            return;
        }
        if (aimed != null) {
            kv("LANDING ZONE", String.format(Locale.ROOT, "%d  %d  %d", aimed.getX(), aimed.getY() + 1, aimed.getZ()), MAIN_X0 + 6, y, w, WHITE);
            y += 8;
            kv("RANGE", distTo(aimed) + " m", MAIN_X0 + 6, y, w, WHITE);
            y += 8;
        } else {
            ui.text("NO LANDING ZONE  //  AIM AT THE GROUND AND RAISE THE TABLET", MAIN_X0 + 6, y, DIM, 0.45f);
            y += 8;
        }
        ui.para("The ship scans the zone before it lands and levels uneven ground with its laser.", MAIN_X0 + 6, y + 2, MAIN_X1 - MAIN_X0 - 12, GREY, 0.48f, 6.5f);
        boolean canCall = ss == 1 && aimed != null;
        int facing = 0;
        if (client != null && client.player != null) {
            facing = com.creedpdf.orbitalrailgun.entity.ShipEntity.facingOf(client.player.getHorizontalFacing());
        }
        final int f = facing;
        holdButton("shipcall", "CALL TO LANDING ZONE", MAIN_X0 + 6, BOTTOM - 36, 150, 22, CYAN, canCall, () -> {
            ClientPackets.sendHangar(0, aimed, f);
            beginClose();
        });
        holdButton("shiprecall", "RETURN TO ORBIT", MAIN_X0 + 164, BOTTOM - 36, 130, 22, AMBER, ss == 2,
                () -> ClientPackets.sendHangar(1, BlockPos.ORIGIN, 0));
    }

    // ---- misc -----------------------------------------------------------------------------------

    private static String stateName(RailgunState state) {
        return Text.translatable(state.translationKey()).getString().toUpperCase(Locale.ROOT);
    }

    private static int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> GREEN;
            case CHARGING -> ((int) (time / 3)) % 2 == 0 ? RED : AMBER;
            case FIRING -> 0xFFFFFFFF;
            case RIFT -> VIOLET;
            case COOLDOWN, TRANSFORMING, PANEL_OPEN, PACKING, LAUNCHING -> AMBER;
            default -> DIM;
        };
    }

    private void play(net.minecraft.sound.SoundEvent sound, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
    }
}
