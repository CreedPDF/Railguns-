package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.Krl;
import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlLoadout;
import com.creedpdf.orbitalrailgun.krl.KrlMap;
import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.krl.KrlNet;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * KRL fire control - the in-game console screen. Left: the linked launch rails (pick which fire), bus gauges and the
 * event log. Centre: a terrain survey of everything in range - click to mark the aim point, drag to pan, scroll to
 * zoom. Right: the physics read-out for the marked shot from the first selected rail (atmosphere, climb, apogee,
 * re-entry heating, arrival energy), then the master arm toggle, the ride (cutscene) switch, and the LAUNCH and ABORT
 * buttons. All the science is flavour computed from the shared {@link KrlFlight} trajectory.
 */
public class KrlScreen extends Screen {
    // ------------------------------------------------------------------------------------------- the board

    public record Link(BlockPos pos, int status, int countdown, int flight, long launchedAt, KrlLoadout missile, int rounds) {}

    /** A radar contact: position, kind (0 flyer, 1 projectile, 2 meteorite) and whether an interceptor is on it. */
    public record Contact(float x, float y, float z, int kind, boolean engaged) {}

    public record Board(boolean open, BlockPos console, int facing, @Nullable BlockPos target, List<Link> links, long time, boolean radar,
                        List<Contact> contacts) {
        public static Board read(PacketByteBuf b) {
            boolean open = b.readBoolean();
            BlockPos c = b.readBlockPos();
            int facing = b.readInt();
            BlockPos t = b.readBoolean() ? b.readBlockPos() : null;
            int n = b.readVarInt();
            List<Link> l = new ArrayList<>();
            for (int i = 0; i < n; i++)
                l.add(new Link(b.readBlockPos(), b.readVarInt(), b.readVarInt() - 1, b.readInt(), b.readLong(), KrlLoadout.read(b), b.readVarInt()));
            long time = b.readLong();
            boolean radar = b.readBoolean();
            int nc = b.readVarInt();
            List<Contact> cs = new ArrayList<>();
            for (int i = 0; i < nc; i++) cs.add(new Contact(b.readFloat(), b.readFloat(), b.readFloat(), b.readByte(), b.readBoolean()));
            return new Board(open, c, facing, t, l, time, radar, cs);
        }
    }

    /** A small slice of the 1976 US Standard Atmosphere, for the console's read-outs. */
    public static final class Atmos {
        private Atmos() {}

        /** Temperature (K) at an altitude in km (troposphere + lower stratosphere; floored above). */
        public static double temperature(double km) {
            if (km < 11) return 288.15 - 6.5 * km;
            if (km < 20) return 216.65;
            if (km < 32) return 216.65 + 1.0 * (km - 20);
            return 228.65;
        }

        /** Air density (kg/m^3), exponential fit to the same model. */
        public static double density(double km) {
            return 1.225 * Math.exp(-km / 8.5);
        }
    }

    static void board(Board b) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof KrlScreen s && s.board.console().equals(b.console())) {
            s.update(b);
            return;
        }
        if (b.open() && KrlClient.riding < 0) mc.setScreen(new KrlScreen(b));
    }

    public static boolean openFor(BlockPos console) {
        return MinecraftClient.getInstance().currentScreen instanceof KrlScreen s && s.board.console().equals(console);
    }

    // ------------------------------------------------------------------------------------------- state

    private Board board;
    private int selected;
    private boolean armed;
    private float armAnim;
    private double mapX, mapZ;
    private int zoom = 1;
    private static final double[] ZOOMS = {3400, 1700, 850, 425, 212, 106, 53};
    private boolean dragging, dragMoved;
    private double dragX, dragY, dragMapX, dragMapZ;
    private int unlinkArm = -1;
    private long unlinkAt;
    private final Deque<String> log = new ArrayDeque<>();
    private int ticks;

    KrlScreen(Board b) {
        super(Text.literal("KRL Fire Control"));
        this.board = b;
        this.mapX = b.console().getX() + 0.5;
        this.mapZ = b.console().getZ() + 0.5;
        for (int i = 0; i < b.links().size(); i++) if (b.links().get(i).status() == KrlNet.L_READY) selected |= 1 << i;
        if (b.target() != null) {
            mapX = (mapX + b.target().getX()) / 2;
            mapZ = (mapZ + b.target().getZ()) / 2;
        }
        say("FIRE CONTROL ONLINE  " + b.links().size() + "/" + Krl.MAX_LINKS + " RAILS LINKED");
        if (KrlSurvey.get(b.console()) == null) {
            PacketByteBuf o = PacketByteBufs.create();
            o.writeBlockPos(b.console());
            ClientPlayNetworking.send(KrlNet.MAP, o);
            say("TERRAIN SURVEY REQUESTED");
        }
    }

    private void update(Board b) {
        for (int i = 0; i < Math.min(b.links().size(), board.links().size()); i++) {
            int was = board.links().get(i).status(), now = b.links().get(i).status();
            if (was != now) {
                String n = "RAIL-" + (char) ('A' + i);
                if (now == KrlNet.L_BURNING) say(n + " LAUNCH SEQUENCE");
                else if (was == KrlNet.L_BURNING && now == KrlNet.L_EMPTY) say(n + " LIFTOFF");
                else if (was == KrlNet.L_BURNING && now == KrlNet.L_READY) say(n + " SAFED");
                else if (now == KrlNet.L_READY) say(n + " MISSILE LATCHED");
            }
        }
        if (b.links().size() != board.links().size()) say("LINK TABLE " + b.links().size() + "/" + Krl.MAX_LINKS);
        if (b.target() != null && (board.target() == null || !b.target().equals(board.target())))
            say(String.format(Locale.ROOT, "AIM POINT %d %d EL %d", b.target().getX(), b.target().getZ(), b.target().getY()));
        selected &= (1 << b.links().size()) - 1;
        board = b;
    }

    private void say(String s) {
        log.addFirst(clock(KrlClient.now()) + "  " + s);
        while (log.size() > 9) log.removeLast();
    }

    private static String clock(long worldTime) {
        long d = (worldTime + 6000) % 24000;
        int h = (int) (d / 1000), m = (int) ((d % 1000) * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks % 10 == 0) {
            PacketByteBuf o = PacketByteBufs.create();
            o.writeBlockPos(board.console());
            ClientPlayNetworking.send(KrlNet.POLL, o);
        }
        armAnim = MathHelper.clamp(armAnim + (armed ? 0.2f : -0.2f), 0, 1);
        if (ticks % 4 == 0) requestTiles();
        if (unlinkArm >= 0 && KrlClient.now() - unlinkAt > 50) unlinkArm = -1;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.squaredDistanceTo(Vec3d.ofCenter(board.console())) > (Krl.USE_RANGE + 2) * (Krl.USE_RANGE + 2)) close();
    }

    // ------------------------------------------------------------------------------------------- layout (a 480 x 270 canvas)

    private static final float VW = 480, VH = 270;
    private static final float MX0 = 126, MY0 = 18, MX1 = 354, MY1 = 246;
    private float s, ox, oy;

    private void layout() {
        s = Math.min(width / VW, height / VH);
        ox = (width - VW * s) / 2;
        oy = (height - VH * s) / 2;
    }

    private float vx(double mx) {
        return (float) ((mx - ox) / s);
    }

    private float vy(double my) {
        return (float) ((my - oy) / s);
    }

    private double scaleMap() {
        return ZOOMS[zoom] * 2 / (MX1 - MX0);
    }

    private double worldX(float x) {
        return mapX + (x - (MX0 + MX1) / 2) * scaleMap();
    }

    private double worldZ(float y) {
        return mapZ + (y - (MY0 + MY1) / 2) * scaleMap();
    }

    private float mapPx(double wx) {
        return (float) ((MX0 + MX1) / 2 + (wx - mapX) / scaleMap());
    }

    private float mapPy(double wz) {
        return (float) ((MY0 + MY1) / 2 + (wz - mapZ) / scaleMap());
    }

    // ------------------------------------------------------------------------------------------- colours

    static final int BG = 0xF2161917, PANEL = 0xE01E221F, EDGE = 0xFF4A524C, EDGE_HI = 0xFF8F9A90, RED = 0xFFFFB02E, RED_DIM = 0xFF8F8A70,
            AMBER = 0xFFF0D060, WHITE = 0xFFEEF0E8, GREY = 0xFF939A92;

    // ------------------------------------------------------------------------------------------- drawing helpers (virtual units)

    private DrawContext dc;
    private TextRenderer tr;

    private void fillf(float x0, float y0, float x1, float y1, int c) {
        var ms = dc.getMatrices();
        ms.push();
        ms.scale(0.25f, 0.25f, 1);
        dc.fill(Math.round(x0 * 4), Math.round(y0 * 4), Math.round(x1 * 4), Math.round(y1 * 4), c);
        ms.pop();
    }

    private void frame(float x0, float y0, float x1, float y1, int fill, int edge) {
        fillf(x0, y0, x1, y1, fill);
        fillf(x0, y0, x1, y0 + 0.5f, edge);
        fillf(x0, y1 - 0.5f, x1, y1, edge);
        fillf(x0, y0, x0 + 0.5f, y1, edge);
        fillf(x1 - 0.5f, y0, x1, y1, edge);
        fillf(x0, y0, x0 + 4, y0 + 1, EDGE_HI);
        fillf(x0, y0, x0 + 1, y0 + 4, EDGE_HI);
        fillf(x1 - 4, y1 - 1, x1, y1, EDGE_HI);
        fillf(x1 - 1, y1 - 4, x1, y1, EDGE_HI);
    }

    private void text(String str, float x, float y, int color, float sc) {
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(sc, sc, 1);
        dc.drawText(tr, KrlText.t(str), 0, 0, color, false);
        ms.pop();
    }

    private float tw(String str, float sc) {
        return tr.getWidth(KrlText.t(str)) * sc;
    }

    /** A heading in the KRL's title face. */
    private void head(String str, float x, float y, int color, float sc) {
        var ms = dc.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(sc, sc, 1);
        dc.drawText(tr, KrlText.h(str), 0, 0, color, false);
        ms.pop();
    }

    private void textR(String str, float x, float y, int color, float sc) {
        text(str, x - tw(str, sc), y, color, sc);
    }

    private void textC(String str, float x, float y, int color, float sc) {
        text(str, x - tw(str, sc) / 2, y, color, sc);
    }

    private void heading(String str, float x, float y) {
        head(str, x, y - 1, RED, 0.58f);
    }

    private void line(float x0, float y0, float x1, float y1, float w, int c, float dash) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float step = Math.max(0.4f, w * 0.8f);
        for (float d = 0; d < len; d += step) {
            if (dash > 0 && ((int) (d / dash)) % 2 == 1) continue;
            float x = x0 + dx * d / len, y = y0 + dy * d / len;
            fillf(x - w / 2, y - w / 2, x + w / 2, y + w / 2, c);
        }
    }

    private void circle(float cx, float cy, float r, float w, int c, int dashEvery) {
        int n = Math.max(24, Math.min(400, (int) (r * 1.6f)));
        for (int i = 0; i < n; i++) {
            if (dashEvery > 0 && (i / dashEvery) % 2 == 1) continue;
            double a = i * Math.PI * 2 / n;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            fillf(x - w / 2, y - w / 2, x + w / 2, y + w / 2, c);
        }
    }

    private void texQuad(Identifier id, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float alpha) {
        RenderSystem.setShaderTexture(0, id);
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.enableBlend();
        Matrix4f m = dc.getMatrices().peek().getPositionMatrix();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        bb.vertex(m, x0, y0, 0).texture(u0, v0).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x0, y1, 0).texture(u0, v1).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x1, y1, 0).texture(u1, v1).color(1f, 1f, 1f, alpha).next();
        bb.vertex(m, x1, y0, 0).texture(u1, v0).color(1f, 1f, 1f, alpha).next();
        BufferRenderer.drawWithGlobalProgram(bb.end());
    }

    // ------------------------------------------------------------------------------------------- render

    @Override
    public void renderBackground(DrawContext context) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.dc = context;
        this.tr = MinecraftClient.getInstance().textRenderer;
        layout();
        context.fill(0, 0, width, height, 0xB0000000);
        var ms = context.getMatrices();
        ms.push();
        ms.translate(ox, oy, 0);
        ms.scale(s, s, 1);
        float time = KrlClient.now() + delta;
        float mx = vx(mouseX), my = vy(mouseY);

        frame(0, 0, VW, VH, BG, EDGE);
        fillf(1, 1, VW - 1, 14, 0xFF1A1E1B);
        text("KRL-1  FIRE CONTROL", 5, 4, RED, 0.85f);
        text("KINETIC RAIL LAUNCHER  /  TRAJECTORY DIRECTOR", 118, 5.5f, RED_DIM, 0.5f);
        BlockPos c = board.console();
        textR(String.format(Locale.ROOT, "CONSOLE %d %d %d", c.getX(), c.getY(), c.getZ()), VW - 5, 5.5f, GREY, 0.5f);
        for (float y = 16; y < VH - 2; y += 3) fillf(1, y, VW - 1, y + 0.4f, 0x08B0FFB0);

        drawLaunchers(mx, my);
        drawGauges(time);
        drawLog();
        drawMap(mx, my, time);
        drawSolution(time);
        drawControls(mx, my, time);
        ms.pop();
    }

    // ---- launch rails

    private float rowY(int i) {
        return 26 + i * 29;
    }

    private void drawLaunchers(float mx, float my) {
        frame(4, 18, 122, 172, PANEL, EDGE);
        heading("LAUNCH RAILS  " + board.links().size() + "/" + Krl.MAX_LINKS, 8, 20.5f);
        for (int i = 0; i < Krl.MAX_LINKS; i++) {
            float y = rowY(i);
            boolean has = i < board.links().size();
            boolean sel = has && (selected & (1 << i)) != 0;
            boolean hover = has && mx >= 6 && mx < 120 && my >= y && my < y + 27;
            fillf(6, y, 120, y + 27, sel ? 0xFF2C3A2E : hover ? 0xFF252B27 : 0xFF151816);
            if (sel) fillf(6, y, 7, y + 27, RED);
            String name = "RAIL-" + (char) ('A' + i);
            if (!has) {
                text(name, 10, y + 3, 0xFF4F5650, 0.6f);
                text("NO LINK", 10, y + 12, 0xFF4F5650, 0.5f);
                continue;
            }
            Link l = board.links().get(i);
            text(name, 10, y + 2.5f, sel ? WHITE : GREY, 0.65f);
            BlockPos lp = l.pos();
            text(String.format(Locale.ROOT, "%d %d %d", lp.getX(), lp.getY(), lp.getZ()), 40, y + 3.5f, GREY, 0.42f);
            if (l.rounds() > 0) textR("AA " + l.rounds(), 108, y + 3.5f, 0xFF7FD67A, 0.42f);
            int st = l.status();
            String sts;
            int sc;
            switch (st) {
                case KrlNet.L_READY -> {
                    sts = "MISSILE ON RAIL";
                    sc = 0xFF35C06A;
                }
                case KrlNet.L_BURNING -> {
                    sts = l.countdown() > Krl.FLAME ? "SLEWING TO TARGET" : String.format(Locale.ROOT, "IGNITION  T-%03.1f", Math.max(0, l.countdown()) / 20.0);
                    sc = RED;
                }
                case KrlNet.L_EMPTY -> {
                    sts = "RAIL EMPTY";
                    sc = AMBER;
                }
                case KrlNet.L_GONE -> {
                    sts = "LINK LOST";
                    sc = RED_DIM;
                }
                default -> {
                    sts = "NOT LOADED";
                    sc = GREY;
                }
            }
            // status lamp
            fillf(10, y + 13, 14, y + 17, sc);
            fillf(10.5f, y + 13.5f, 13.5f, y + 16.5f, sc);
            text(sts, 17, y + 12.5f, sc, 0.5f);
            // range to the marked aim point
            BlockPos t = board.target();
            if (t != null) {
                double d = Math.sqrt(Math.pow(t.getX() - lp.getX(), 2) + Math.pow(t.getZ() - lp.getZ(), 2));
                double rg = l.missile().range();
                boolean ok = d <= rg && d >= KrlNet.MIN_RANGE;
                text(String.format(Locale.ROOT, "RNG %,5.0f m", d), 17, y + 19.5f, ok ? GREY : RED, 0.46f);
                text(ok ? "IN ENVELOPE" : d > rg ? "OUT OF RANGE" : "TOO CLOSE", 62, y + 19.5f, ok ? 0xFF35C06A : RED, 0.46f);
            } else if (st == KrlNet.L_READY || st == KrlNet.L_BURNING) {
                text(l.missile().summary(), 17, y + 19.5f, GREY, 0.46f);
            }
            // unlink tab
            boolean uh = mx >= 110 && mx < 120 && my >= y + 1 && my < y + 10;
            text(unlinkArm == i ? "X!" : "x", 113, y + 2.5f, unlinkArm == i ? RED : uh ? WHITE : 0xFF5E665F, 0.6f);
        }
        text("CLICK A RAIL TO ARM IT FOR THIS SALVO", 8, 166, 0xFF5E665F, 0.42f);
    }

    // ---- gauges

    /** Air defence: the radar switch, the sweep's picture and the interceptors left in the magazines. */
    private void drawGauges(float time) {
        frame(4, 176, 122, 222, PANEL, EDGE);
        heading("AIR DEFENCE", 8, 178.5f);
        boolean on = board.radar();
        int rounds = 0;
        for (Link l : board.links()) rounds += l.rounds();
        int threats = board.contacts().size(), eng = 0;
        for (Contact c : board.contacts()) if (c.engaged()) eng++;
        // the switch
        fillf(8, 186, 48, 200, on ? 0xFF1E3A22 : 0xFF202522);
        frame(8, 186, 48, 200, 0x00000000, on ? 0xFF7FD67A : EDGE);
        textC(on ? "RADAR ON" : "RADAR OFF", 28, 190.5f, on ? 0xFF7FD67A : GREY, 0.5f);
        // a small sweep scope
        float cx = 104, cy = 199, r = 15;
        circle(cx, cy, r, 0.6f, 0xFF2C322E, 0);
        circle(cx, cy, r * 0.5f, 0.4f, 0xFF2C322E, 3);
        if (on) {
            float a0 = time * 0.12f;
            for (int k = 0; k < 8; k++) {
                float a1 = a0 - k * 0.07f;
                line(cx, cy, cx + MathHelper.cos(a1) * r, cy + MathHelper.sin(a1) * r, 0.6f, ((int) (150 * (1 - k / 8f)) << 24) | 0x7FD67A, 0);
            }
            BlockPos c = board.console();
            for (Contact k : board.contacts()) {
                float dx = (float) ((k.x() - c.getX()) / com.creedpdf.orbitalrailgun.krl.KrlDefense.RADIUS), dz = (float) ((k.z() - c.getZ()) / com.creedpdf.orbitalrailgun.krl.KrlDefense.RADIUS);
                float px = cx + dx * r, py = cy + dz * r;
                int col = k.kind() == 2 ? 0xFFFF8A30 : 0xFFFF5040;
                fillf(px - 0.9f, py - 0.9f, px + 0.9f, py + 0.9f, col);
            }
        }
        text(on ? String.format(Locale.ROOT, "SEARCH %d m", (int) com.creedpdf.orbitalrailgun.krl.KrlDefense.RADIUS) : "STANDBY", 8, 203, on ? 0xFF7FD67A : 0xFF5E665F, 0.42f);
        text(String.format(Locale.ROOT, "TRACKS %d  ENGAGED %d", threats, eng), 8, 208, threats > 0 ? RED : GREY, 0.42f);
        text(String.format(Locale.ROOT, "INTERCEPTORS %d", rounds), 8, 213, rounds > 0 ? WHITE : 0xFFFF5040, 0.42f);
        if (rounds == 0) text("PLACE A MAGAZINE BY A RAIL", 8, 218, 0xFF5E665F, 0.36f);
    }

    private void gauge(float cx, float cy, float r, float v, String label, String read, float time) {
        circle(cx, cy, r, 1.1f, 0xFF2C322E, 0);
        int n = 40;
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 0.75 + i * Math.PI * 1.5 / n;
            boolean on = i / (float) n <= v;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            fillf(x - 0.8f, y - 0.8f, x + 0.8f, y + 0.8f, on ? (v > 0.85f ? RED : AMBER) : 0xFF3A403B);
        }
        double na = Math.PI * 0.75 + v * Math.PI * 1.5;
        line(cx, cy, cx + (float) Math.cos(na) * (r - 2), cy + (float) Math.sin(na) * (r - 2), 1f, WHITE, 0);
        textC(read, cx, cy + r - 5, WHITE, 0.5f);
        textC(label, cx, cy + r + 1, GREY, 0.42f);
    }

    // ---- log

    private void drawLog() {
        frame(4, 226, 122, 252, PANEL, EDGE);
        heading("EVENT LOG", 8, 228.5f);
        float y = 235;
        int a = 0;
        for (String s : log) {
            if (a > 4) break;
            text(s, 8, y, a == 0 ? AMBER : 0xFF7E867E, 0.42f);
            y += 4.4f;
            a++;
        }
    }

    // ---- the map

    private boolean detailZoom() {
        return ZOOMS[zoom] <= 850;
    }

    /** Ask for the detail tiles under the view, one at a time. */
    private void requestTiles() {
        // a request the server let drop (out of reach) must not block the queue for ever
        if (ticks % 160 == 0) KrlSurvey.ASKED.clear();
        if (!detailZoom() || KrlSurvey.ASKED.size() > 1) return;
        double half = ZOOMS[zoom] * 1.1;
        int t0x = Math.floorDiv((int) Math.floor(mapX - half), KrlMap.TILE), t1x = Math.floorDiv((int) Math.floor(mapX + half), KrlMap.TILE);
        int t0z = Math.floorDiv((int) Math.floor(mapZ - half), KrlMap.TILE), t1z = Math.floorDiv((int) Math.floor(mapZ + half), KrlMap.TILE);
        int bx = Math.floorDiv((int) Math.floor(mapX), KrlMap.TILE), bz = Math.floorDiv((int) Math.floor(mapZ), KrlMap.TILE);
        long best = 0;
        double bd = Double.MAX_VALUE;
        boolean found = false;
        for (int tz = t0z; tz <= t1z; tz++)
            for (int tx = t0x; tx <= t1x; tx++) {
                long k = KrlSurvey.key(tx, tz);
                if (KrlSurvey.tile(tx, tz) != null || KrlSurvey.ASKED.contains(k)) continue;
                double d = (tx - bx) * (tx - bx) + (tz - bz) * (tz - bz);
                if (d < bd) {
                    bd = d;
                    best = k;
                    found = true;
                }
            }
        if (!found) return;
        int tx = (int) (best >> 32), tz = (int) best;
        KrlSurvey.ASKED.add(best);
        PacketByteBuf o = PacketByteBufs.create();
        o.writeBlockPos(board.console());
        o.writeInt(tx);
        o.writeInt(tz);
        ClientPlayNetworking.send(KrlNet.TILE, o);
    }

    private static final int[] GRIDS = {25, 50, 100, 250, 500, 1000};

    private void drawMap(float mx, float my, float time) {
        frame(MX0 - 2, MY0 - 2, MX1 + 2, MY1 + 2, 0xFF0B0D0C, EDGE);
        KrlSurvey s = KrlSurvey.get(board.console());
        boolean inMap = mx >= MX0 && mx < MX1 && my >= MY0 && my < MY1;
        int px0 = (int) Math.floor(ox + MX0 * this.s), py0 = (int) Math.floor(oy + MY0 * this.s);
        int px1 = (int) Math.ceil(ox + MX1 * this.s), py1 = (int) Math.ceil(oy + MY1 * this.s);
        dc.enableScissor(px0, py0, px1, py1);
        if (s != null) {
            texQuad(s.texture(), mapPx(s.x0), mapPy(s.z0), mapPx(s.x0 + s.n * s.cell), mapPy(s.z0 + s.n * s.cell), 0, 0, 1, 1, 1f);
        } else {
            textC("SURVEYING TERRAIN . . .", (MX0 + MX1) / 2, (MY0 + MY1) / 2, RED_DIM, 0.7f);
        }
        // detail tiles over the overview
        int tiles = 0, missing = 0;
        if (detailZoom()) {
            double half = ZOOMS[zoom] * 1.1;
            int t0x = Math.floorDiv((int) Math.floor(mapX - half), KrlMap.TILE), t1x = Math.floorDiv((int) Math.floor(mapX + half), KrlMap.TILE);
            int t0z = Math.floorDiv((int) Math.floor(mapZ - half), KrlMap.TILE), t1z = Math.floorDiv((int) Math.floor(mapZ + half), KrlMap.TILE);
            for (int tz = t0z; tz <= t1z; tz++)
                for (int tx = t0x; tx <= t1x; tx++) {
                    KrlSurvey t = KrlSurvey.tile(tx, tz);
                    if (t == null) {
                        missing++;
                        continue;
                    }
                    tiles++;
                    texQuad(t.texture(), mapPx(t.x0), mapPy(t.z0), mapPx(t.x0 + t.n * t.cell), mapPy(t.z0 + t.n * t.cell), 0, 0, 1, 1, 1f);
                }
        }
        // a coordinate grid, labelled along the top and left edges
        double span = ZOOMS[zoom] * 2;
        int g = GRIDS[GRIDS.length - 1];
        for (int c : GRIDS) {
            if (span / c <= 9) {
                g = c;
                break;
            }
        }
        double gx0 = Math.floor(worldX(MX0) / g) * g, gz0 = Math.floor(worldZ(MY0) / g) * g;
        for (double gx = gx0; gx <= worldX(MX1); gx += g) {
            float x = mapPx(gx);
            boolean major = Math.floorMod((long) gx, (long) g * 5) == 0;
            line(x, MY0, x, MY1, 0.3f, major ? 0x55FFFFFF : 0x2AFFFFFF, 0);
            if (x > MX0 + 10 && x < MX1 - 14) text(String.format(Locale.ROOT, "%d", (long) gx), x + 1, MY0 + 11, 0xC8E8ECE4, 0.36f);
        }
        for (double gz = gz0; gz <= worldZ(MY1); gz += g) {
            float y = mapPy(gz);
            boolean major = Math.floorMod((long) gz, (long) g * 5) == 0;
            line(MX0, y, MX1, y, 0.3f, major ? 0x55FFFFFF : 0x2AFFFFFF, 0);
            if (y > MY0 + 16 && y < MY1 - 12) text(String.format(Locale.ROOT, "%d", (long) gz), MX0 + 1.5f, y + 0.8f, 0xC8E8ECE4, 0.36f);
        }
        BlockPos c = board.console();
        float ccx = mapPx(c.getX() + 0.5), ccy = mapPy(c.getZ() + 0.5);
        // each selected rail's envelope (its missile's range) and the minimum arc
        BlockPos t = board.target();
        for (int i = 0; i < board.links().size(); i++) {
            Link l = board.links().get(i);
            if ((selected & (1 << i)) == 0) continue;
            float lx = mapPx(l.pos().getX() + 0.5), ly = mapPy(l.pos().getZ() + 0.5);
            circle(lx, ly, (float) (l.missile().range() / scaleMap()), 0.55f, 0xB0FFB02E, 4);
            circle(lx, ly, (float) (KrlNet.MIN_RANGE / scaleMap()), 0.45f, 0x90FF5040, 2);
            if (t != null) {
                float tx = mapPx(t.getX() + 0.5), ty = mapPy(t.getZ() + 0.5);
                line(lx, ly, tx, ty, 0.4f, 0xA0FFB02E, 2.5f);
                double d = Math.hypot(t.getX() - l.pos().getX(), t.getZ() - l.pos().getZ());
                String dl = String.format(Locale.ROOT, "%c %,.0f m", (char) ('A' + i), d);
                float lmx = (lx + tx) / 2, lmy = (ly + ty) / 2;
                fillf(lmx - 1, lmy - 1, lmx + tw(dl, 0.4f) + 1, lmy + 3.6f, 0xB0000000);
                text(dl, lmx, lmy - 0.3f, d <= l.missile().range() ? 0xFFFFB02E : 0xFFFF5040, 0.4f);
            }
        }
        // the radar's reach and its contacts
        if (board.radar()) {
            float rr = (float) (com.creedpdf.orbitalrailgun.krl.KrlDefense.RADIUS / scaleMap());
            circle(ccx, ccy, rr, 0.5f, 0xA07FD67A, 5);
            float a0 = time * 0.12f;
            line(ccx, ccy, ccx + MathHelper.cos(a0) * rr, ccy + MathHelper.sin(a0) * rr, 0.5f, 0x707FD67A, 0);
            for (Contact k : board.contacts()) {
                float px = mapPx(k.x()), py = mapPy(k.z());
                int col = k.kind() == 2 ? 0xFFFF8A30 : 0xFFFF5040;
                float d = 2.2f;
                line(px - d, py, px, py - d, 0.5f, col, 0);
                line(px, py - d, px + d, py, 0.5f, col, 0);
                line(px + d, py, px, py + d, 0.5f, col, 0);
                line(px, py + d, px - d, py, 0.5f, col, 0);
                if (k.engaged() && ((int) (time / 4)) % 2 == 0) circle(px, py, 3.6f, 0.4f, 0xFFFFFFFF, 0);
                String lbl = (k.kind() == 2 ? "MET " : k.kind() == 1 ? "AIR " : "AIR ") + String.format(Locale.ROOT, "%.0f", k.y());
                text(lbl, px + 3.5f, py - 1.5f, col, 0.34f);
            }
        }
        // console marker
        fillf(ccx - 1.4f, ccy - 1.4f, ccx + 1.4f, ccy + 1.4f, WHITE);
        fillf(ccx - 0.8f, ccy - 0.8f, ccx + 0.8f, ccy + 0.8f, 0xFF101311);
        text("FC", ccx + 2.5f, ccy - 2, WHITE, 0.45f);
        // the linked rails
        for (int i = 0; i < board.links().size(); i++) {
            Link l = board.links().get(i);
            float lx = mapPx(l.pos().getX() + 0.5), ly = mapPy(l.pos().getZ() + 0.5);
            int col = (selected & (1 << i)) != 0 ? RED : GREY;
            fillf(lx - 1.6f, ly - 1.6f, lx + 1.6f, ly + 1.6f, 0xFF000000);
            fillf(lx - 1.2f, ly - 1.2f, lx + 1.2f, ly + 1.2f, col);
            text(String.valueOf((char) ('A' + i)), lx + 2.5f, ly - 3, col, 0.42f);
        }
        // live flights: track and current position
        for (KrlClient.CFlight f : KrlClient.flights()) {
            if (!KrlClient.here(f)) continue;
            double ft = f.t(0);
            if (ft < 0 || ft >= KrlFlight.TOTAL) continue;
            float px = mapPx(f.launch.x), py = mapPy(f.launch.z);
            for (int k = 1; k <= 24; k++) {
                Vec3d q = f.path.pos(ft * k / 24);
                float qx = mapPx(q.x), qy = mapPy(q.z);
                line(px, py, qx, qy, 0.45f, 0xC0F0D060, 0);
                px = qx;
                py = qy;
            }
            float blink = ((int) (time / 4)) % 2 == 0 ? 1.4f : 1f;
            fillf(px - blink, py - blink, px + blink, py + blink, 0xFFF0D060);
            text(String.format(Locale.ROOT, "M%.1f", f.path.mach(ft)), px + 2.5f, py - 3, 0xFFF0D060, 0.38f);
        }
        // aim point
        if (t != null) {
            float tx = mapPx(t.getX() + 0.5), ty = mapPy(t.getZ() + 0.5);
            float pulse = 0.6f + 0.4f * MathHelper.sin(time * 0.3f);
            circle(tx, ty, 4 + pulse * 2, 0.6f, RED, 0);
            line(tx - 7, ty, tx - 2.5f, ty, 0.5f, RED, 0);
            line(tx + 2.5f, ty, tx + 7, ty, 0.5f, RED, 0);
            line(tx, ty - 7, tx, ty - 2.5f, 0.5f, RED, 0);
            line(tx, ty + 2.5f, tx, ty + 7, 0.5f, RED, 0);
            fillf(tx - 0.6f, ty - 0.6f, tx + 0.6f, ty + 0.6f, WHITE);
            text(String.format(Locale.ROOT, "AIM %d %d  EL %d", t.getX(), t.getZ(), t.getY()), tx + 5, ty + 3.5f, RED, 0.4f);
        }
        // compass
        float cpx = MX1 - 9, cpy = MY0 + 19;
        circle(cpx, cpy, 5, 0.4f, 0xA0E8ECE4, 0);
        line(cpx, cpy + 3.5f, cpx, cpy - 4.5f, 0.6f, 0xFFE8ECE4, 0);
        textC("N", cpx, cpy - 9.5f, 0xFFE8ECE4, 0.42f);
        // the cursor's read-out
        if (inMap) {
            double wx = worldX(mx), wz = worldZ(my);
            KrlSurvey b = KrlSurvey.best(wx, wz);
            double gy = b != null ? b.heightAt(wx, wz) : Double.NaN;
            int kind = b != null ? b.kindAt(wx, wz) : -1;
            double d = Math.hypot(wx - c.getX(), wz - c.getZ());
            String ln = String.format(Locale.ROOT, "X %,d   Z %,d   EL %s m", Math.round(wx), Math.round(wz), Double.isNaN(gy) ? "--" : String.valueOf((int) gy));
            String ln2 = String.format(Locale.ROOT, "%s   RNG FROM FC %,.0f m   BRG %03.0f", kind >= 0 ? KrlSurvey.kindName(kind) : "UNSURVEYED", d,
                    (Math.toDegrees(Math.atan2(wx - c.getX(), -(wz - c.getZ()))) + 360) % 360);
            fillf(MX0, MY1 - 9.5f, MX1, MY1, 0xC8000000);
            text(ln, MX0 + 2, MY1 - 8.5f, WHITE, 0.44f);
            text(ln2, MX0 + 2, MY1 - 4.3f, GREY, 0.44f);
            // crosshair on the cursor
            line(mx - 3, my, mx + 3, my, 0.3f, 0xC0FFFFFF, 0);
            line(mx, my - 3, mx, my + 3, 0.3f, 0xC0FFFFFF, 0);
        }
        dc.disableScissor();
        // scale bar, zoom, detail status
        float barM = (float) (ZOOMS[zoom] / 4);
        float barPx = (float) (barM / scaleMap());
        fillf(MX1 - barPx - 6, MY1 - 16, MX1 - 2, MY1 - 10, 0xB0000000);
        line(MX1 - barPx - 4, MY1 - 11.5f, MX1 - 4, MY1 - 11.5f, 0.6f, WHITE, 0);
        line(MX1 - barPx - 4, MY1 - 13, MX1 - barPx - 4, MY1 - 10.5f, 0.4f, WHITE, 0);
        line(MX1 - 4, MY1 - 13, MX1 - 4, MY1 - 10.5f, 0.4f, WHITE, 0);
        textR(String.format(Locale.ROOT, "%,.0f m", barM), MX1 - 4, MY1 - 15.6f, WHITE, 0.38f);
        fillf(MX0, MY0, MX1, MY0 + 9.5f, 0xB0000000);
        text(String.format(Locale.ROOT, "ZOOM x%.0f   GRID %d m   %s", ZOOMS[1] / ZOOMS[zoom], g,
                detailZoom() ? (missing > 0 ? "DETAIL SURVEY " + tiles + "/" + (tiles + missing) : "DETAIL SURVEY COMPLETE") : "OVERVIEW"),
                MX0 + 2, MY0 + 1, GREY, 0.42f);
        text("CLICK: MARK AIM POINT   DRAG: PAN   SCROLL: ZOOM   RIGHT-CLICK: CLEAR", MX0 + 2, MY0 + 5.3f, 0xFF7E867E, 0.36f);
    }

    // ---- the firing solution

    @Nullable
    private Integer firstSelected() {
        for (int i = 0; i < board.links().size(); i++) if ((selected & (1 << i)) != 0) return i;
        return null;
    }

    private void drawSolution(float time) {
        frame(356, 18, 476, 196, PANEL, EDGE);
        heading("FIRING SOLUTION", 360, 20.5f);
        BlockPos t = board.target();
        Integer fi = firstSelected();
        if (t == null || fi == null) {
            text(t == null ? "NO AIM POINT MARKED" : "NO RAIL ARMED", 360, 30, RED_DIM, 0.5f);
            text("Mark a point on the survey and", 360, 40, GREY, 0.45f);
            text("arm at least one launch rail to", 360, 45, GREY, 0.45f);
            text("compute the trajectory.", 360, 50, GREY, 0.45f);
            return;
        }
        BlockPos lp = board.links().get(fi).pos();
        Vec3d target = Vec3d.ofCenter(t).add(0, 1, 0);
        Vec3d launch = KrlFlight.nozzleFor(Vec3d.ofBottomCenter(lp), target);
        KrlLoadout ms = board.links().get(fi).missile();
        KrlFlight p = new KrlFlight(launch, target, ms);
        double d = p.dist;
        boolean ok = d <= ms.range() && d >= KrlNet.MIN_RANGE;
        float y = 27;
        text("RAIL-" + (char) ('A' + fi) + "  ->  " + t.getX() + ", " + t.getY() + ", " + t.getZ(), 360, y, WHITE, 0.5f);
        y += 7;
        // trajectory profile sketch
        drawProfile(358, y, 116, 34, p);
        y += 38;
        double apogeeKm = p.altitudeKm(KrlFlight.BOOST + 4);
        double machMax = p.machMax;
        double vImpact = machMax * KrlCine.speedOfSound(0.1);
        double flightSec = KrlFlight.TOTAL / 20.0;
        double stagK = KrlCine.stagnationK(machMax);
        // spear ~ 1.5 t of tungsten alloy; arrival energy for flavour
        double mass = 1500;
        double energyMJ = 0.5 * mass * vImpact * vImpact / 1e6;
        row(360, y, "DOWNRANGE", String.format(Locale.ROOT, "%,.0f m", d), ok ? WHITE : RED);
        y += 5.6f;
        row(360, y, "APOGEE", String.format(Locale.ROOT, "%.1f km", apogeeKm), WHITE);
        y += 5.6f;
        row(360, y, "FLIGHT TIME", String.format(Locale.ROOT, "%.1f s + 2 s ignition", flightSec), WHITE);
        y += 5.6f;
        row(360, y, "PEAK VELOCITY", String.format(Locale.ROOT, "MACH %.0f  %,.0f m/s", machMax, vImpact), RED);
        y += 5.6f;
        row(360, y, "SHEATH TEMP", String.format(Locale.ROOT, "%,.0f K", stagK), AMBER);
        y += 5.6f;
        row(360, y, "ARRIVAL ENERGY", String.format(Locale.ROOT, "%,.0f MJ", energyMJ), AMBER);
        y += 5.6f;
        row(360, y, "WARHEAD", KrlLoadout.WARHEADS[ms.warhead()] + (ms.warhead() == KrlLoadout.KINETIC ? "" : " +" + ms.charge()), AMBER);
        y += 5.6f;
        row(360, y, "YIELD", String.format(Locale.ROOT, "%.1f TNT-EQ  %s", ms.power() / 4f, KrlLoadout.MOTORS[ms.motor()]), GREY);
        y += 5.6f;
        int kind = KrlSurvey.get(board.console()) != null && KrlSurvey.get(board.console()).covers(t.getX(), t.getZ())
                ? KrlSurvey.get(board.console()).kindAt(t.getX(), t.getZ()) : -1;
        row(360, y, "SURFACE", kind >= 0 ? KrlSurvey.kindName(kind) : "UNSURVEYED", GREY);
        y += 5.6f;
        row(360, y, "BEARING", String.format(Locale.ROOT, "%03.0f deg", bearing(launch, target)), GREY);
        y += 8;
        if (!ok) text(d > ms.range() ? String.format(Locale.ROOT, "! BEYOND %,.0f m ENVELOPE", ms.range()) : "! INSIDE MINIMUM ARC", 360, y, RED, 0.5f);
        else text("SOLUTION VALID  -  READY TO ARM", 360, y, 0xFF35C06A, 0.5f);
    }

    private void row(float x, float y, String k, String v, int vc) {
        text(k, x, y, GREY, 0.46f);
        textR(v, 474, y, vc, 0.46f);
    }

    private static double bearing(Vec3d a, Vec3d b) {
        double deg = Math.toDegrees(Math.atan2(b.x - a.x, -(b.z - a.z)));
        return (deg + 360) % 360;
    }

    private void drawProfile(float x, float y, float w, float h, KrlFlight p) {
        fillf(x, y, x + w, y + h, 0xFF101311);
        line(x, y + h - 1, x + w, y + h - 1, 0.4f, EDGE, 0);
        float py = Float.NaN, pxp = Float.NaN;
        double maxH = p.height;
        float prevx = -1, prevy = -1;
        for (int i = 0; i <= 60; i++) {
            double tt = i / 60.0 * KrlFlight.TOTAL;
            Vec3d q = p.pos(tt);
            float px = x + 2 + (float) (i / 60.0) * (w - 4);
            float alt = (float) ((q.y - Math.min(p.launch.y, p.target.y)) / (maxH + 1));
            float yy = y + h - 2 - alt * (h - 4);
            if (prevx >= 0) line(prevx, prevy, px, yy, 0.6f, i < 36 ? AMBER : RED, 0);
            prevx = px;
            prevy = yy;
        }
        text("CLIMB", x + 3, y + 1, 0xFF7E867E, 0.4f);
        text("RE-ENTRY", x + w - 24, y + 1, 0xFF7E867E, 0.4f);
    }

    // ---- controls

    private boolean canFire() {
        if (board.target() == null) return false;
        Integer fi = firstSelected();
        if (fi == null) return false;
        for (int i = 0; i < board.links().size(); i++)
            if ((selected & (1 << i)) != 0 && board.links().get(i).status() == KrlNet.L_READY) return true;
        return false;
    }

    private boolean anyBurning() {
        for (int i = 0; i < board.links().size(); i++)
            if ((selected & (1 << i)) != 0 && board.links().get(i).status() == KrlNet.L_BURNING) return true;
        return false;
    }

    private void drawControls(float mx, float my, float time) {
        frame(356, 200, 476, 252, PANEL, EDGE);
        // master arm
        boolean armHover = hit(mx, my, 360, 204, 56, 20);
        fillf(360, 204, 416, 224, armed ? 0xFF4A3A10 : 0xFF202522);
        if (armed) {
            float g = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            frame(360, 204, 416, 224, 0x00000000, RED);
            text("ARMED", 368, 210, RED, 0.7f);
            fillf(405, 208, 412, 212, KrlClient.alphaRed(g));
        } else {
            frame(360, 204, 416, 224, 0x00000000, armHover ? EDGE_HI : EDGE);
            text("SAFE", 372, 210, GREY, 0.7f);
        }
        text("MASTER ARM", 360, 226, GREY, 0.42f);
        // ride / cutscene toggle
        boolean rideHover = hit(mx, my, 420, 204, 52, 20);
        fillf(420, 204, 472, 224, KrlSettings.ride ? 0xFF2C3A2E : 0xFF202522);
        frame(420, 204, 472, 224, 0x00000000, rideHover ? EDGE_HI : EDGE);
        text(KrlSettings.ride ? "RIDE: ON" : "RIDE: OFF", 426, 207, KrlSettings.ride ? AMBER : GREY, 0.55f);
        text("CUTSCENE", 420, 226, GREY, 0.42f);
        // LAUNCH + ABORT
        boolean burning = anyBurning();
        boolean fire = armed && canFire();
        boolean lHover = hit(mx, my, 360, 232, 56, 16);
        fillf(360, 232, 416, 248, fire ? (lHover ? 0xFFC0301E : 0xFF7A1A12) : 0xFF202522);
        frame(360, 232, 416, 248, 0x00000000, fire ? RED : EDGE);
        textC("LAUNCH", 388, 237, fire ? WHITE : 0xFF5E665F, 0.7f);
        boolean aHover = hit(mx, my, 420, 232, 52, 16);
        fillf(420, 232, 472, 248, burning ? (aHover ? 0xFFF0D060 : 0xFF8C6A10) : 0xFF202522);
        frame(420, 232, 472, 248, 0x00000000, burning ? AMBER : EDGE);
        textC("ABORT", 446, 237, burning ? 0xFF1A1408 : 0xFF5E665F, 0.7f);
    }

    private boolean hit(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float mx = vx(mouseX), my = vy(mouseY);
        // rails
        for (int i = 0; i < board.links().size(); i++) {
            float y = rowY(i);
            if (mx >= 110 && mx < 120 && my >= y + 1 && my < y + 10) {
                if (unlinkArm == i) {
                    PacketByteBuf o = PacketByteBufs.create();
                    o.writeBlockPos(board.console());
                    o.writeVarInt(i);
                    ClientPlayNetworking.send(KrlNet.UNLINK, o);
                    unlinkArm = -1;
                } else {
                    unlinkArm = i;
                    unlinkAt = KrlClient.now();
                }
                click();
                return true;
            }
            if (mx >= 6 && mx < 110 && my >= y && my < y + 27) {
                selected ^= 1 << i;
                click();
                return true;
            }
        }
        // map
        if (mx >= MX0 && mx < MX1 && my >= MY0 && my < MY1) {
            if (button == 0) {
                dragging = true;
                dragMoved = false;
                dragX = mouseX;
                dragY = mouseY;
                dragMapX = mapX;
                dragMapZ = mapZ;
            } else if (button == 1) {
                sendTarget(0, 0, true);
                click();
            }
            return true;
        }
        // radar
        if (hit(mx, my, 8, 186, 40, 14)) {
            PacketByteBuf o = PacketByteBufs.create();
            o.writeBlockPos(board.console());
            o.writeBoolean(!board.radar());
            ClientPlayNetworking.send(KrlNet.RADAR, o);
            say(board.radar() ? "RADAR STANDBY" : "RADAR ACTIVE  SEARCHING");
            click();
            return true;
        }
        // master arm
        if (hit(mx, my, 360, 204, 56, 20)) {
            armed = !armed;
            click();
            return true;
        }
        if (hit(mx, my, 420, 204, 52, 20)) {
            KrlSettings.ride = !KrlSettings.ride;
            KrlSettings.save();
            click();
            return true;
        }
        if (hit(mx, my, 360, 232, 56, 16) && armed && canFire()) {
            doLaunch();
            return true;
        }
        if (hit(mx, my, 420, 232, 52, 16) && anyBurning()) {
            PacketByteBuf o = PacketByteBufs.create();
            o.writeBlockPos(board.console());
            o.writeVarInt(selected);
            ClientPlayNetworking.send(KrlNet.ABORT, o);
            click();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            double sc = scaleMap();
            if (Math.abs(mouseX - dragX) + Math.abs(mouseY - dragY) > 3) dragMoved = true;
            mapX = dragMapX - (mouseX - dragX) / s * sc;
            mapZ = dragMapZ - (mouseY - dragY) / s * sc;
            double lim = KrlLoadout.MAX_RANGE + 600;
            mapX = MathHelper.clamp(mapX, board.console().getX() - lim, board.console().getX() + lim);
            mapZ = MathHelper.clamp(mapZ, board.console().getZ() - lim, board.console().getZ() + lim);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            float mx = vx(mouseX), my = vy(mouseY);
            if (!dragMoved && mx >= MX0 && mx < MX1 && my >= MY0 && my < MY1) {
                sendTarget(worldX(mx), worldZ(my), false);
                click();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        float mx = vx(mouseX), my = vy(mouseY);
        if (mx >= MX0 && mx < MX1 && my >= MY0 && my < MY1) {
            double wx = worldX(mx), wz = worldZ(my);
            int nz = MathHelper.clamp(zoom + (amount > 0 ? 1 : -1), 0, ZOOMS.length - 1);
            if (nz != zoom) {
                zoom = nz;
                // keep the point under the cursor fixed
                mapX = wx - (mx - (MX0 + MX1) / 2) * scaleMap();
                mapZ = wz - (my - (MY0 + MY1) / 2) * scaleMap();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private void sendTarget(double wx, double wz, boolean clear) {
        PacketByteBuf o = PacketByteBufs.create();
        o.writeBlockPos(board.console());
        o.writeInt((int) Math.floor(wx));
        o.writeInt((int) Math.floor(wz));
        o.writeBoolean(clear);
        ClientPlayNetworking.send(KrlNet.TARGET, o);
    }

    private void doLaunch() {
        PacketByteBuf o = PacketByteBufs.create();
        o.writeBlockPos(board.console());
        o.writeVarInt(selected);
        o.writeBoolean(KrlSettings.ride);
        ClientPlayNetworking.send(KrlNet.LAUNCH, o);
        MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(Krl.S_ARM, 1f, 0.9f));
        if (KrlSettings.ride) close();
    }

    private void click() {
        MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(Krl.S_CLICK, 1f, 0.6f));
    }
}
