package com.creedpdf.orbitalrailgun.client.tablet;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.client.ui.Term;
import com.creedpdf.orbitalrailgun.client.ui.Term.Hit;
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

import static com.creedpdf.orbitalrailgun.client.ui.Term.*;

/**
 * The uplink tablet, "zoomed into": a classified command terminal.
 *
 * <p>The tablet is a real item model held in both hands (see {@code HeldItemRendererMixin}). While
 * this screen is open {@link #zoom(float)} goes 0 to 1 and the mixin pulls the tablet up to the
 * camera; then the display boots.</p>
 *
 * <p>Navigation is a file tree on the left; the selected "file" opens on the right:</p>
 * <pre>
 *  ~/ares-9/   status  fire_control  scan  rift  recall
 *  ~/ares-0/   targets  recall
 *  ~/orbital_drop/   ares-9.pod  ares-0.pod
 * </pre>
 * Everything works remotely: firing ARES-9, scans, rifts, recalling either weapon to orbit and
 * firing ARES-0 at a planet, from any distance.
 */
public class TabletScreen extends Screen {
    private static final Identifier DISPLAY = RailgunMod.id("textures/gui/tablet_display.png");
    public static final int ZOOM_TICKS = 9;
    // Display area inside tablet_display.png (512x288).
    private static final float DX0 = 40f / 512, DY0 = 30f / 288, DX1 = 472f / 512, DY1 = 258f / 288;
    private static final int CW = 400, CH = 214; // virtual canvas
    private static final int TREE_X = 5, TREE_W = 112, PANE_X = 128, PANE_W = CW - PANE_X - 6;

    @Nullable private static TabletScreen open;
    /** Remembered between openings. */
    @Nullable private static Node lastNode;

    @Nullable private final BlockPos aimed;
    private int ticks;
    private int closeTicks = -1;
    private int bootTicks = -1;
    private final String[] fields = {"", "", ""};
    private int focused = -1;
    private boolean fieldsInitialised;
    private Node node;
    private int nodeTicks;
    /** Two-step confirm: the command armed and until when. */
    @Nullable private String armed;
    private int armedUntil;
    private int a0Pick = -1;
    private final List<Hit> hits = new ArrayList<>();

    private float ox, oy, s = 1f;

    private enum Node { A9_STATUS, A9_FIRE, A9_SCAN, A9_RIFT, A9_RECALL, A0_TARGETS, A0_RECALL, DROP_A9, DROP_A0 }

    public TabletScreen(@Nullable BlockPos aimed) {
        super(Text.translatable("item.orbital_railgun.railgun_remote"));
        this.aimed = aimed;
        this.node = lastNode != null ? lastNode : Node.A9_FIRE;
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

    // ---- state helpers --------------------------------------------------------------------------

    private static TabletStatus st() {
        return TabletStatus.latest;
    }

    private boolean a9() {
        return st().found;
    }

    private boolean a0() {
        return st().a0Found;
    }

    /** Opening node once the first status arrives: whatever is deployed, else the drop page. */
    private void pickDefault() {
        if (lastNode != null) return;
        node = a9() ? Node.A9_FIRE : a0() ? Node.A0_TARGETS : Node.DROP_A9;
    }

    private boolean available(Node n) {
        return switch (n) {
            case A9_STATUS, A9_FIRE, A9_SCAN, A9_RIFT, A9_RECALL -> a9();
            case A0_TARGETS, A0_RECALL -> a0();
            case DROP_A9 -> !a9();
            case DROP_A0 -> !a0();
        };
    }

    private void select(Node n) {
        if (node != n) {
            node = n;
            lastNode = n;
            nodeTicks = 0;
            armed = null;
            focused = -1;
            play(ModSounds.UI_CLICK, 1.25f);
            if ((n == Node.DROP_A9 || n == Node.DROP_A0) && aimed != null) ClientPackets.requestSurvey(aimed);
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
        return client != null && client.player != null
                && client.player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE);
    }

    private boolean canRetarget() {
        TabletStatus t = st();
        return t.found && t.sameDimension && (t.state == RailgunState.IDLE || t.state == RailgunState.COOLDOWN);
    }

    private boolean isArmed(String key) {
        return key.equals(armed) && ticks < armedUntil;
    }

    /** First press arms, second press within 3 s executes. */
    private void confirm(String key, Runnable action) {
        if (isArmed(key)) {
            armed = null;
            action.run();
        } else {
            armed = key;
            armedUntil = ticks + 60;
            play(ModSounds.UI_COUNTDOWN_BEEP, 1.2f);
        }
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

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void tick() {
        ticks++;
        nodeTicks++;
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
        if (aimed != null && (node == Node.DROP_A9 || node == Node.DROP_A0) && ticks % 40 == 0) ClientPackets.requestSurvey(aimed);
        TabletStatus t = st();
        if (!fieldsInitialised && t.received) {
            BlockPos init = t.found && t.hasTarget ? t.target : aimed;
            if (init != null) {
                fields[0] = Integer.toString(init.getX());
                fields[1] = Integer.toString(init.getY());
                fields[2] = Integer.toString(init.getZ());
            }
            fieldsInitialised = true;
            pickDefault();
        }
        if (t.received && !available(node)) {
            Node n = a9() ? Node.A9_STATUS : a0() ? Node.A0_TARGETS : Node.DROP_A9;
            if (!available(n)) n = Node.DROP_A0;
            node = n;
            nodeTicks = 0;
        }
        if (t.a0Found && a0Pick < 0) a0Pick = t.a0Target;
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

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!uiLive() || button != 0) return true;
        float cx = (float) ((mouseX - ox) / s), cy = (float) ((mouseY - oy) / s);
        for (Hit h : new ArrayList<>(hits)) {
            if (h.in(cx, cy)) {
                if (h.enabled()) {
                    play(ModSounds.UI_CLICK, 1f);
                    h.action().run();
                } else {
                    play(ModSounds.UI_DENIED, 1.3f);
                }
                return true;
            }
        }
        focused = -1;
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
        // keyboard navigation of the tree
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            Node[] all = Node.values();
            int i = node.ordinal();
            for (int k = 0; k < all.length; k++) {
                i = (i + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : all.length - 1)) % all.length;
                if (available(all[i])) {
                    select(all[i]);
                    break;
                }
            }
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
        ctx.getMatrices().push();
        ctx.getMatrices().translate(ox, oy, 0);
        ctx.getMatrices().scale(s, s, 1f);
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;
        hits.clear();

        backdrop(ctx, CW, CH, time);
        TabletStatus t = st();
        String link;
        int linkColor;
        if (!t.received) { link = "HANDSHAKE..."; linkColor = AMBER; }
        else if (t.found || t.a0Found) { link = "UPLINK SECURE  " + (t.found ? "A9" : "--") + "/" + (t.a0Found ? "A0" : "--"); linkColor = OK; }
        else if (t.pending) { link = "DROP INBOUND"; linkColor = AMBER; }
        else { link = "NO ASSETS DEPLOYED"; linkColor = DIM; }
        header(ctx, textRenderer, CW, path(), link, linkColor, time);
        drawTree(ctx, mx, my, time);
        vline(ctx, PANE_X - 5, 26, CH - 12, RULE);
        drawPane(ctx, mx, my, time);
        footer(ctx, textRenderer, CW, CH, time, "ESC close   UP/DOWN navigate");
        corners(ctx, 1, 1, CW - 2, CH - 2, 8, RED_DIM);

        if (bootTicks >= 0) {
            boot(ctx, textRenderer, CW, CH, bootTicks + delta, new String[]{
                    "> railguns+ uplink v4  // orbital command network",
                    "> handshake 0x5EED0FA2 ............ OK",
                    "> decrypting asset manifests ...... OK",
                    "> clearance level OMEGA verified",
                    "> ACCESS GRANTED"});
        }
        ctx.getMatrices().pop();
    }

    private String path() {
        return switch (node) {
            case A9_STATUS -> "~/ares-9/status";
            case A9_FIRE -> "~/ares-9/fire_control";
            case A9_SCAN -> "~/ares-9/scan";
            case A9_RIFT -> "~/ares-9/rift";
            case A9_RECALL -> "~/ares-9/recall";
            case A0_TARGETS -> "~/ares-0/targets";
            case A0_RECALL -> "~/ares-0/recall";
            case DROP_A9 -> "~/orbital_drop/ares-9.pod";
            case DROP_A0 -> "~/orbital_drop/ares-0.pod";
        };
    }

    private void drawTree(DrawContext ctx, float mx, float my, float time) {
        TabletStatus t = st();
        int x = TREE_X, y = 28, w = TREE_W;
        text(ctx, textRenderer, "~/", x, y, DIM, 0.75f);
        y += 10;
        node(ctx, textRenderer, "ares-9", x, y, w, 1, false, false, true, true, a9() ? "ONLINE" : "--", a9() ? OK : FAINT, mx, my, null);
        y += 10;
        Object[][] a9n = {{"status", Node.A9_STATUS}, {"fire_control", Node.A9_FIRE}, {"scan", Node.A9_SCAN}, {"rift", Node.A9_RIFT}, {"recall", Node.A9_RECALL}};
        for (int i = 0; i < a9n.length; i++) {
            Node n = (Node) a9n[i][1];
            add(node(ctx, textRenderer, (String) a9n[i][0], x, y, w, 2, i == a9n.length - 1, node == n, available(n), false,
                    null, 0, mx, my, () -> select(n)));
            y += 10;
        }
        y += 2;
        node(ctx, textRenderer, "ares-0", x, y, w, 1, false, false, true, true, a0() ? "ONLINE" : "--", a0() ? ORANGE : FAINT, mx, my, null);
        y += 10;
        Object[][] a0n = {{"targets", Node.A0_TARGETS}, {"recall", Node.A0_RECALL}};
        for (int i = 0; i < a0n.length; i++) {
            Node n = (Node) a0n[i][1];
            add(node(ctx, textRenderer, (String) a0n[i][0], x, y, w, 2, i == a0n.length - 1, node == n, available(n), false,
                    null, 0, mx, my, () -> select(n)));
            y += 10;
        }
        y += 2;
        node(ctx, textRenderer, "orbital_drop", x, y, w, 1, true, false, true, true, t.pending ? "INBOUND" : null, AMBER, mx, my, null);
        y += 10;
        add(node(ctx, textRenderer, "ares-9.pod", x, y, w, 2, false, node == Node.DROP_A9, available(Node.DROP_A9), false,
                null, 0, mx, my, () -> select(Node.DROP_A9)));
        y += 10;
        add(node(ctx, textRenderer, "ares-0.pod", x, y, w, 2, true, node == Node.DROP_A0, available(Node.DROP_A0), false,
                null, 0, mx, my, () -> select(Node.DROP_A0)));
        y += 14;
        // tiny live readout under the tree
        if (a9()) {
            text(ctx, textRenderer, "A9 " + stateName(t.state), x, y, stateColor(t.state, time), 0.6f);
            y += 7;
        }
        if (a0()) {
            text(ctx, textRenderer, "A0 " + t.a0State.name(), x, y, t.a0State == Ares0Entity.State.FIRING || t.a0State == Ares0Entity.State.CHARGING
                    ? (((int) (time / 3)) % 2 == 0 ? RED : ORANGE) : ORANGE, 0.6f);
        }
    }

    private void add(@Nullable Hit h) {
        if (h != null) hits.add(h);
    }

    private void drawPane(DrawContext ctx, float mx, float my, float time) {
        switch (node) {
            case A9_STATUS -> a9Status(ctx, time);
            case A9_FIRE -> a9Fire(ctx, mx, my, time);
            case A9_SCAN -> a9Simple(ctx, mx, my, "SCAN", "Holographic pulse from the emplacement: maps ores and tags hostiles within 96 m. "
                    + "Results paint the world for everyone nearby.", "RUN SCAN", 0xFF5FE3FF, st().state == RailgunState.IDLE, ClientPackets::sendScan);
            case A9_RIFT -> a9Simple(ctx, mx, my, "RIFT", "Fires the rail into the sky for one second and tears a rift into deep space. "
                    + "Hostiles nearby are dragged through. The rift holds for 30 seconds; anyone still up there when it closes is stranded.",
                    "TEAR RIFT", VIOLET, st().state == RailgunState.IDLE, ClientPackets::sendRift);
            case A9_RECALL -> recall(ctx, mx, my, "ARES-9", "Retracts the emplacement into its pod and launches it back to orbit. "
                    + "Works from any distance. The weapon must be idle.",
                    st().state == RailgunState.IDLE || st().state == RailgunState.COOLDOWN || st().state == RailgunState.POD
                            || st().state == RailgunState.POD_READY, 0);
            case A0_TARGETS -> a0Targets(ctx, mx, my, time);
            case A0_RECALL -> recall(ctx, mx, my, "ARES-0", "Folds the array and sends it back to orbit. It cannot leave while charging or firing.",
                    st().a0State == Ares0Entity.State.IDLE || st().a0State == Ares0Entity.State.COOLDOWN, 3);
            case DROP_A9 -> drop(ctx, mx, my, time, 0);
            case DROP_A0 -> drop(ctx, mx, my, time, 1);
        }
    }

    // ---- ARES-9 ---------------------------------------------------------------------------------

    private int distTo(BlockPos p) {
        return client != null && client.player != null ? (int) Math.sqrt(client.player.getBlockPos().getSquaredDistance(p)) : 0;
    }

    private void a9Status(DrawContext ctx, float time) {
        TabletStatus t = st();
        int x = PANE_X, y = 28, w = PANE_W;
        section(ctx, textRenderer, "ARES-9 // STATUS.LOG", x, y, w, RED);
        y += 12;
        typedKv(ctx, "STATE", stateName(t.state), x, y, w, stateColor(t.state, time), 0); y += 10;
        typedKv(ctx, "POSITION", String.format(Locale.ROOT, "%d %d %d", t.pos.getX(), t.pos.getY(), t.pos.getZ()), x, y, w, TEXT, 2); y += 10;
        typedKv(ctx, "DISTANCE", distTo(t.pos) + " m" + (t.sameDimension ? "" : "  [OTHER DIM]"), x, y, w, t.sameDimension ? TEXT : AMBER, 4); y += 10;
        typedKv(ctx, "AZIMUTH / ELEV", String.format(Locale.ROOT, "%+07.2f / %+06.2f", t.yaw, t.pitch), x, y, w, TEXT, 6); y += 10;
        typedKv(ctx, "TARGET", t.hasTarget ? String.format(Locale.ROOT, "%d %d %d", t.target.getX(), t.target.getY(), t.target.getZ()) : "NONE",
                x, y, w, t.hasTarget ? RED : DIM, 8); y += 14;
        text(ctx, textRenderer, "CAPACITOR", x, y, DIM, 0.75f);
        meter(ctx, textRenderer, t.charge, x + 60, y, 30, RED, 0.75f);
        y += 14;
        section(ctx, textRenderer, "LOADOUT", x, y, w, RED_DIM);
        y += 11;
        Loadout lo = Loadout.of(t.loadout);
        List<Loadout.Option> opts = new ArrayList<>();
        if (lo.muzzle() != null) opts.add(lo.muzzle());
        if (lo.ammo() != null) opts.add(lo.ammo());
        opts.addAll(lo.modules());
        if (opts.isEmpty()) text(ctx, textRenderer, "standard configuration", x, y, DIM, 0.7f);
        for (int i = 0; i < opts.size(); i++) {
            int cx = x + (i % 2) * (w / 2), cy = y + (i / 2) * 9;
            ctx.fill(cx, cy + 2, cx + 3, cy + 5, 0xFF000000 | opts.get(i).color);
            text(ctx, textRenderer, opts.get(i).title.toLowerCase(Locale.ROOT), cx + 6, cy, TEXT, 0.7f);
        }
        trace(ctx, x, CH - 40, w, 24, t.charge, t.state == RailgunState.FIRING, time);
    }

    private void typedKv(DrawContext ctx, String k, String v, int x, int y, int w, int vc, int delay) {
        float tt = nodeTicks - delay;
        if (tt <= 0) return;
        String vv = v.substring(0, Math.min(v.length(), (int) (tt * 4)));
        kv(ctx, textRenderer, k, vv, x, y, w, vc, 0.75f);
    }

    /** Thin oscilloscope trace. */
    private void trace(DrawContext ctx, int x, int y, int w, int h, float charge, boolean firing, float time) {
        hline(ctx, x, x + w, y + h / 2, FAINT);
        float amp = 1.5f + charge * (h / 2f - 2f);
        int prev = y + h / 2;
        for (int px = 1; px < w; px++) {
            float phase = px * (0.12f + charge * 0.35f) - time * (0.25f + charge);
            float noise = firing ? MathHelper.sin(px * 1.7f + time * 5f) * 5f : 0f;
            int py = MathHelper.clamp(y + h / 2 + (int) (MathHelper.sin(phase) * amp + noise), y, y + h - 1);
            ctx.fill(x + px, Math.min(prev, py), x + px + 1, Math.max(prev, py) + 1, RED);
            prev = py;
        }
    }

    private void a9Fire(DrawContext ctx, float mx, float my, float time) {
        TabletStatus t = st();
        int x = PANE_X, y = 28, w = PANE_W;
        section(ctx, textRenderer, "ARES-9 // FIRE_CONTROL", x, y, w, RED);
        y += 12;
        kv(ctx, textRenderer, "STATE", stateName(t.state), x, y, w, stateColor(t.state, time), 0.75f);
        y += 12;
        String[] axes = {"X", "Y", "Z"};
        boolean live = canRetarget();
        for (int i = 0; i < 3; i++) {
            int fy = y + i * 11;
            boolean f = focused == i;
            text(ctx, textRenderer, axes[i] + ":", x, fy, live ? RED : DIM, 0.75f);
            int bx = x + 12, bw = 90;
            ctx.fill(bx, fy - 2, bx + bw, fy + 8, f ? 0xFF1A0706 : BG2);
            hline(ctx, bx, bx + bw, fy + 8, f ? RED : RULE_HI);
            String txt = fields[i] + (f && ((int) (time / 6)) % 2 == 0 ? "_" : "");
            text(ctx, textRenderer, txt, bx + 3, fy, live ? TEXT : DIM, 0.75f);
            final int idx = i;
            hits.add(new Hit(bx, fy - 2, bw, 11, () -> focused = idx, live));
        }
        int cx = x + 112;
        hits.add(cmd(ctx, textRenderer, "SET", cx, y, RED, live, mx, my, 0.75f, this::submitTarget));
        hits.add(cmd(ctx, textRenderer, "PAINT AIMED", cx, y + 12, AMBER, live && aimed != null, mx, my, 0.75f, this::paint));
        y += 38;
        if (t.hasTarget) {
            kv(ctx, textRenderer, "LOCK", String.format(Locale.ROOT, "%d %d %d", t.target.getX(), t.target.getY(), t.target.getZ()), x, y, w, RED, 0.75f);
            y += 10;
            double range = Math.sqrt(t.pos.getSquaredDistance(t.target));
            kv(ctx, textRenderer, "RANGE", String.format(Locale.ROOT, "%.0f / %.0f m", range, RailgunConfig.MAX_FIRE_RANGE * Loadout.of(t.loadout).rangeMul()),
                    x, y, w, TEXT, 0.75f);
        } else {
            text(ctx, textRenderer, "no target locked", x, y, DIM, 0.75f);
            y += 10;
        }
        y += 14;
        text(ctx, textRenderer, "CAPACITOR", x, y, DIM, 0.75f);
        meter(ctx, textRenderer, t.charge, x + 60, y, 30, RED, 0.75f);
        y += 16;
        boolean canFire = t.state == RailgunState.IDLE && t.hasTarget && t.sameDimension;
        hits.add(cmd(ctx, textRenderer, isArmed("fire") ? "CONFIRM // FIRE" : "EXECUTE FIRE", x, y, RED, canFire, mx, my, 1f,
                () -> confirm("fire", this::fire)));
        hits.add(cmd(ctx, textRenderer, "ABORT", x + 130, y + 2, AMBER, t.state == RailgunState.CHARGING, mx, my, 0.75f, ClientPackets::sendAbort));
        y += 16;
        String hint = !t.sameDimension ? "railgun is in another dimension" : switch (t.state) {
            case IDLE -> t.hasTarget ? "armed. weapon free." : "set coordinates to arm";
            case CHARGING -> "charging // fires on lock";
            case FIRING -> "FIRING";
            case COOLDOWN -> "barrel cooling";
            case POD, POD_READY -> "packed in pod";
            default -> "standby";
        };
        text(ctx, textRenderer, "> " + hint, x, y, t.state == RailgunState.IDLE ? OK : DIM, 0.7f);
    }

    private void a9Simple(DrawContext ctx, float mx, float my, String name, String desc, String label, int accent, boolean enabled, Runnable action) {
        int x = PANE_X, y = 28, w = PANE_W;
        section(ctx, textRenderer, "ARES-9 // " + name, x, y, w, accent);
        y += 14;
        y = para(ctx, textRenderer, desc, x, y, w, TEXT, 0.75f) + 10;
        kv(ctx, textRenderer, "STATE", stateName(st().state), x, y, w, enabled ? OK : AMBER, 0.75f);
        y += 18;
        hits.add(cmd(ctx, textRenderer, label, x, y, accent, enabled, mx, my, 1f, action));
        if (!enabled) text(ctx, textRenderer, "> weapon must be idle", x, y + 16, DIM, 0.7f);
    }

    private void recall(DrawContext ctx, float mx, float my, String unit, String desc, boolean enabled, int action) {
        int x = PANE_X, y = 28, w = PANE_W;
        int accent = action == 0 ? RED : ORANGE;
        section(ctx, textRenderer, unit + " // RECALL", x, y, w, accent);
        y += 14;
        y = para(ctx, textRenderer, desc, x, y, w, TEXT, 0.75f) + 10;
        BlockPos p = action == 0 ? st().pos : st().a0Pos;
        kv(ctx, textRenderer, "ASSET POSITION", String.format(Locale.ROOT, "%d %d %d", p.getX(), p.getY(), p.getZ()), x, y, w, TEXT, 0.75f);
        y += 10;
        kv(ctx, textRenderer, "DISTANCE", distTo(p) + " m", x, y, w, TEXT, 0.75f);
        y += 20;
        String key = "recall" + action;
        hits.add(cmd(ctx, textRenderer, isArmed(key) ? "CONFIRM // RECALL TO ORBIT" : "RECALL TO ORBIT", x, y, accent, enabled, mx, my, 1f,
                () -> confirm(key, () -> ClientPackets.sendRemote(action, -1))));
        if (isArmed(key)) text(ctx, textRenderer, "> press again to confirm", x, y + 16, AMBER, 0.7f);
        else if (!enabled) text(ctx, textRenderer, "> asset busy", x, y + 16, DIM, 0.7f);
    }

    // ---- ARES-0 ---------------------------------------------------------------------------------

    private void a0Targets(DrawContext ctx, float mx, float my, float time) {
        TabletStatus t = st();
        int x = PANE_X, y = 28, w = PANE_W;
        section(ctx, textRenderer, "ARES-0 // CORE_COLLAPSE.TARGETS", x, y, w, ORANGE);
        y += 12;
        String state = switch (t.a0State) {
            case IDLE -> "READY";
            case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", Math.min(100, t.a0Ticks * 100 / Ares0Entity.CHARGE_TICKS));
            case FIRING -> "FIRING";
            case COOLDOWN -> "COOLING";
            default -> t.a0State.name();
        };
        kv(ctx, textRenderer, "ARRAY", state, x, y, w, t.a0State == Ares0Entity.State.IDLE ? OK : ORANGE, 0.75f);
        y += 12;
        text(ctx, textRenderer, "BODY", x, y, FAINT, 0.6f);
        text(ctx, textRenderer, "STATUS", x + 120, y, FAINT, 0.6f);
        y += 8;
        for (Body b : Body.ALL) {
            if (!b.destroyable()) continue;
            boolean up = (t.a0Visible & (1 << b.ordinal())) != 0;
            boolean doomed = (t.a0Doomed & (1 << b.ordinal())) != 0 || ClientPlanets.elapsed(b, 0f) >= 0;
            boolean sel = a0Pick == b.ordinal();
            boolean hover = !doomed && mx >= x - 1 && mx < x + w && my >= y - 1 && my < y + 8;
            if (sel) ctx.fill(x - 1, y - 1, x + w, y + 8, ORANGE);
            else if (hover) ctx.fill(x - 1, y - 1, x + w, y + 8, 0x40FF7A1E);
            int col = sel ? 0xFF050505 : doomed ? FAINT : TEXT;
            text(ctx, textRenderer, (sel ? "> " : "  ") + b.displayName().toLowerCase(Locale.ROOT).replace(' ', '_') + ".body", x, y, col, 0.75f);
            String status = doomed ? "DESTROYED" : up ? "ABOVE HORIZON" : "BELOW HORIZON";
            text(ctx, textRenderer, status, x + 120, y, sel ? 0xFF050505 : doomed ? RED_DIM : up ? OK : DIM, 0.7f);
            final Body fb = b;
            if (!doomed) hits.add(new Hit(x - 1, y - 1, w + 1, 9, () -> {
                a0Pick = fb.ordinal();
                armed = null;
                ClientPackets.sendRemote(1, fb.ordinal());
            }, true));
            y += 10;
        }
        y += 8;
        Body pick = a0Pick >= 0 && a0Pick < Body.ALL.length ? Body.ALL[a0Pick] : null;
        boolean canFire = pick != null && t.a0State == Ares0Entity.State.IDLE && (t.a0Visible & (1 << pick.ordinal())) != 0
                && (t.a0Doomed & (1 << pick.ordinal())) == 0;
        hits.add(cmd(ctx, textRenderer, isArmed("a0fire") ? "CONFIRM // COLLAPSE CORE" : "EXECUTE CORE COLLAPSE", x, y, ORANGE, canFire, mx, my, 1f,
                () -> confirm("a0fire", () -> {
                    ClientPackets.sendRemote(2, pick.ordinal());
                    beginClose();
                })));
        y += 16;
        String hint = pick == null ? "select a body" : !canFire ? (t.a0State != Ares0Entity.State.IDLE ? "array busy"
                : "target below the horizon") : isArmed("a0fire") ? "press again. this cannot be undone." : "target acquired";
        text(ctx, textRenderer, "> " + hint, x, y, isArmed("a0fire") ? RED : DIM, 0.7f);
    }

    // ---- orbital drop ---------------------------------------------------------------------------

    private void drop(DrawContext ctx, float mx, float my, float time, int kind) {
        TabletStatus t = st();
        int x = PANE_X, y = 28, w = PANE_W;
        int accent = kind == 0 ? RED : ORANGE;
        section(ctx, textRenderer, "ORBITAL_DROP // " + (kind == 0 ? "ARES-9 RAILGUN POD" : "ARES-0 PLANET KILLER"), x, y, w, accent);
        y += 12;
        if (aimed == null) {
            text(ctx, textRenderer, "no landing zone", x, y, RED, 0.75f);
            y += 10;
            para(ctx, textRenderer, "Aim at open ground, then raise the tablet again.", x, y, w, DIM, 0.75f);
            return;
        }
        kv(ctx, textRenderer, "LANDING ZONE", String.format(Locale.ROOT, "%d %d %d", aimed.getX(), aimed.getY() + 1, aimed.getZ()), x, y, w, TEXT, 0.75f);
        y += 10;
        kv(ctx, textRenderer, "RANGE", distTo(aimed) + " m", x, y, w, TEXT, 0.75f);
        y += 10;
        TabletStatus.Survey sv = survey();
        String surveyText = sv == null ? "SCANNING" + ".".repeat(1 + (ticks / 5) % 3) : sv.ok() ? "ZONE CLEAR" : "NO ROOM TO DEPLOY";
        kv(ctx, textRenderer, "SITE SURVEY", surveyText, x, y, w, sv == null ? AMBER : sv.ok() ? OK : RED, 0.75f);
        y += 10;
        if (sv != null && !sv.ok()) {
            y = para(ctx, textRenderer, Text.translatable("message.orbital_railgun.denied." + sv.reason()).getString(), x, y, w, 0xFFFF8A80, 0.7f);
        } else if (sv != null) {
            y = para(ctx, textRenderer, "obstructions will be flattened on touchdown", x, y, w, DIM, 0.7f);
        }
        // a small orbital track
        int cx = x + w - 34, cy = CH - 52;
        com.creedpdf.orbitalrailgun.client.ui.UiKit.ring(ctx, cx, cy, 20, 0x60E8382E, 64);
        com.creedpdf.orbitalrailgun.client.ui.UiKit.ring(ctx, cx, cy, 3, accent, 12);
        float orbit = time * 0.05f;
        int sx = cx + (int) (MathHelper.cos(orbit) * 20), sy = cy + (int) (MathHelper.sin(orbit) * 20);
        ctx.fill(sx - 1, sy - 1, sx + 2, sy + 2, accent);
        y += 10;
        boolean can = !t.pending && !onCooldown() && surveyOk() && available(kind == 0 ? Node.DROP_A9 : Node.DROP_A0);
        hits.add(cmd(ctx, textRenderer, isArmed("drop" + kind) ? "CONFIRM // RELEASE POD" : "AUTHORIZE DROP", x, y, accent, can, mx, my, 1f,
                () -> confirm("drop" + kind, () -> callDown(kind))));
        y += 16;
        String hint = t.pending ? "drop inbound" : onCooldown() ? "uplink recharging" : sv == null ? "awaiting survey" : sv.ok() ? "ready" : "choose another site";
        text(ctx, textRenderer, "> " + hint, x, y, t.pending || onCooldown() ? AMBER : DIM, 0.7f);
    }

    // ---- misc -----------------------------------------------------------------------------------

    private static String stateName(RailgunState state) {
        return Text.translatable(state.translationKey()).getString().toUpperCase(Locale.ROOT);
    }

    private static int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> OK;
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
