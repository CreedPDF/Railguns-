package com.creedpdf.orbitalrailgun.client.screen;

import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Sci-fi fire-control console.
 *
 * <p>This is a plain client {@link Screen}, not a ScreenHandler: there is no inventory to sync,
 * and every value it shows (state, charge, target, aim) is already on the client-side entity via
 * TrackedData. Buttons send small C2S command packets that the server validates. That avoids a
 * dependency on owo-lib/Cloth Config while keeping full control over the look.</p>
 *
 * <p>Buttons are custom-drawn (see {@link ConsoleButton}) instead of vanilla ButtonWidgets so the
 * panel can use hazard stripes, pulsing fills and progress-filled states.</p>
 */
public class RailgunControlScreen extends Screen {
    private static final int W = 320, H = 214;

    private static final int C_BG = 0xE6070910;
    private static final int C_PANEL = 0xFF0E1118;
    private static final int C_GRID = 0x22FF3030;
    private static final int C_RED = 0xFFFF3030;
    private static final int C_RED_DIM = 0xFF7A1616;
    private static final int C_TEXT = 0xFFD8D8D8;
    private static final int C_MUTED = 0xFF7C828C;
    private static final int C_AMBER = 0xFFFFB020;
    private static final int C_GREEN = 0xFF40FF80;

    private final RailgunEntity railgun;
    private final List<ConsoleButton> buttons = new ArrayList<>();
    private TextFieldWidget fieldX, fieldY, fieldZ;
    private int left, top;
    private int inputErrorTicks;
    private int ticks;

    public RailgunControlScreen(RailgunEntity railgun) {
        super(Text.translatable("gui.orbital_railgun.title"));
        this.railgun = railgun;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;

        BlockPos initial = railgun.hasTarget() ? railgun.getTarget() : railgun.getBlockPos();
        String sx = fieldX != null ? fieldX.getText() : Integer.toString(initial.getX());
        String sy = fieldY != null ? fieldY.getText() : Integer.toString(initial.getY());
        String sz = fieldZ != null ? fieldZ.getText() : Integer.toString(initial.getZ());

        fieldX = coordField(left + 28, top + 44, sx);
        fieldY = coordField(left + 28, top + 64, sy);
        fieldZ = coordField(left + 28, top + 84, sz);
        setInitialFocus(fieldX);

        buttons.clear();
        buttons.add(new ConsoleButton(left + 10, top + 106, 68, 16, "SET TARGET", C_RED,
                () -> !isLocked(), this::submitTarget));
        buttons.add(new ConsoleButton(left + 82, top + 106, 68, 16, "PAINT", C_AMBER,
                () -> !isLocked(), this::paintTarget));
        buttons.add(new ConsoleButton(left + 208, top + 160, 102, 19, "ABORT", C_AMBER,
                () -> railgun.getState() == RailgunState.CHARGING, () -> ClientPackets.sendAbort(railgun.getId())));
        buttons.add(new ConsoleButton(left + 208, top + 183, 102, 19, "RECALL", C_MUTED,
                () -> railgun.getState() != RailgunState.FIRING, () -> {
                    ClientPackets.sendRecall(railgun.getId());
                    close();
                }));
        buttons.add(new FireButton(left + 10, top + 160, 192, 42));
    }

    private TextFieldWidget coordField(int x, int y, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, 120, 16, Text.empty());
        field.setMaxLength(9);
        field.setTextPredicate(s -> s.matches("-?\\d{0,8}"));
        field.setText(value);
        field.setEditableColor(0xFFFF6060);
        addDrawableChild(field);
        return field;
    }

    private boolean isLocked() {
        RailgunState s = railgun.getState();
        return s == RailgunState.CHARGING || s == RailgunState.FIRING || !s.isOperational();
    }

    // ---- Actions ------------------------------------------------------------------------------

    private void submitTarget() {
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(fieldX.getText()), Integer.parseInt(fieldY.getText()),
                    Integer.parseInt(fieldZ.getText()));
            ClientPackets.sendSetTarget(railgun.getId(), pos);
        } catch (NumberFormatException e) {
            inputErrorTicks = 30;
        }
    }

    /** Fills the coordinates with whatever block the player's crosshair is on. */
    private void paintTarget() {
        if (client == null || client.player == null) return;
        HitResult hit = client.player.raycast(RailgunConfig.MAX_FIRE_RANGE, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
            BlockPos p = bh.getBlockPos();
            fieldX.setText(Integer.toString(p.getX()));
            fieldY.setText(Integer.toString(p.getY()));
            fieldZ.setText(Integer.toString(p.getZ()));
            submitTarget();
        } else {
            inputErrorTicks = 30;
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (inputErrorTicks > 0) inputErrorTicks--;
        double max = RailgunConfig.MAX_INTERACT_DISTANCE;
        if (railgun.isRemoved() || client == null || client.player == null
                || client.player.squaredDistanceTo(railgun) > max * max) {
            close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (ConsoleButton b : buttons) {
                if (b.isHovered(mouseX, mouseY)) {
                    if (b.enabled.getAsBoolean()) {
                        click(1.0f);
                        b.action.run();
                    } else {
                        click(0.5f);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && !isLocked()) {
            submitTarget();
            click(1.0f);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void click(float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    // ---- Rendering ----------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        float time = ticks + delta;

        // Frame
        ctx.fill(left, top, left + W, top + H, C_BG);
        for (int gx = left + 8; gx < left + W; gx += 16) ctx.fill(gx, top + 22, gx + 1, top + H - 2, C_GRID);
        for (int gy = top + 30; gy < top + H; gy += 16) ctx.fill(left + 2, gy, left + W - 2, gy + 1, C_GRID);
        ctx.drawBorder(left, top, W, H, C_RED_DIM);
        RailgunHud.bracket(ctx, left, top, W, H, C_RED);

        // Header
        ctx.fill(left + 1, top + 1, left + W - 1, top + 20, 0xFF1A0608);
        ctx.drawText(textRenderer, ">> ARES-9 ORBITAL RAILGUN // FIRE CONTROL", left + 8, top + 6, C_RED, false);
        boolean blink = ((int) (time / 10)) % 2 == 0;
        ctx.fill(left + W - 14, top + 7, left + W - 8, top + 13, blink ? C_GREEN : 0xFF0F3A1C);
        ctx.drawText(textRenderer, "LINK", left + W - 42, top + 6, C_MUTED, false);

        // ---- Left column: targeting ------------------------------------------------------
        sectionLabel(ctx, left + 10, top + 30, "TARGET COORDINATES");
        ctx.drawText(textRenderer, "X", left + 14, top + 48, C_RED, false);
        ctx.drawText(textRenderer, "Y", left + 14, top + 68, C_RED, false);
        ctx.drawText(textRenderer, "Z", left + 14, top + 88, C_RED, false);
        if (inputErrorTicks > 0) {
            ctx.drawText(textRenderer, "INVALID INPUT", left + 10, top + 126, C_AMBER, false);
        } else if (railgun.hasTarget()) {
            BlockPos t = railgun.getTarget();
            ctx.drawText(textRenderer, String.format(Locale.ROOT, "LOCK %d %d %d", t.getX(), t.getY(), t.getZ()),
                    left + 10, top + 126, C_RED, false);
            double range = Math.sqrt(railgun.getBlockPos().getSquaredDistance(t));
            ctx.drawText(textRenderer, String.format(Locale.ROOT, "RANGE %.0fm / %.0fm", range, RailgunConfig.MAX_FIRE_RANGE),
                    left + 10, top + 138, C_MUTED, false);
        } else {
            ctx.drawText(textRenderer, "NO TARGET LOCKED", left + 10, top + 126, C_MUTED, false);
        }

        // ---- Right column: status / capacitor --------------------------------------------
        int rx = left + 166, rw = 144;
        RailgunState state = railgun.getState();
        sectionLabel(ctx, rx, top + 30, "SYSTEM STATUS");
        ctx.getMatrices().push();
        ctx.getMatrices().translate(rx, top + 42, 0);
        ctx.getMatrices().scale(1.5f, 1.5f, 1f);
        ctx.drawText(textRenderer, Text.translatable(state.translationKey()), 0, 0, stateColor(state, time), true);
        ctx.getMatrices().pop();

        float charge = railgun.getCharge();
        sectionLabel(ctx, rx, top + 60, "CAPACITOR");
        String pct = String.format(Locale.ROOT, "%3d%%", (int) (charge * 100));
        ctx.drawText(textRenderer, pct, rx + rw - textRenderer.getWidth(pct), top + 60, C_TEXT, false);
        int segs = 24, segW = rw / segs;
        int lit = (int) Math.ceil(charge * segs);
        for (int i = 0; i < segs; i++) {
            int sx = rx + i * segW;
            int color = i < lit ? lerpColor(0xFF801010, 0xFFFF5040, (float) i / segs) : 0xFF1E1012;
            ctx.fill(sx, top + 72, sx + segW - 1, top + 84, color);
        }

        // Oscilloscope: capacitor waveform. Amplitude and frequency follow the charge.
        int ox = rx, oy = top + 90, ow = rw, oh = 36;
        ctx.fill(ox, oy, ox + ow, oy + oh, C_PANEL);
        ctx.drawBorder(ox, oy, ow, oh, C_RED_DIM);
        ctx.fill(ox + 1, oy + oh / 2, ox + ow - 1, oy + oh / 2 + 1, 0x33FF3030);
        float amp = 1.5f + charge * (oh / 2f - 3f);
        int prevY = oy + oh / 2;
        for (int px = 1; px < ow - 1; px++) {
            float phase = px * (0.12f + charge * 0.35f) - time * (0.25f + charge);
            float noise = state == RailgunState.FIRING ? MathHelper.sin(px * 1.7f + time * 5f) * 4f : 0f;
            int py = oy + oh / 2 + (int) (MathHelper.sin(phase) * amp + noise);
            py = MathHelper.clamp(py, oy + 1, oy + oh - 2);
            ctx.fill(ox + px, Math.min(prevY, py), ox + px + 1, Math.max(prevY, py) + 1, C_RED);
            prevY = py;
        }

        // Barrel orientation readout
        float az = (float) Math.toDegrees(railgun.getAimYawRad(delta));
        float el = (float) Math.toDegrees(railgun.getAimPitchRad(delta));
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "AZ %+07.2f  EL %+06.2f", az, el), rx, top + 132, C_MUTED, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "POS %d %d %d", railgun.getBlockX(), railgun.getBlockY(), railgun.getBlockZ()),
                rx, top + 144, C_MUTED, false);

        // Text fields (children) then custom buttons on top
        super.render(ctx, mouseX, mouseY, delta);
        for (ConsoleButton b : buttons) b.render(ctx, mouseX, mouseY, time);
    }

    private void sectionLabel(DrawContext ctx, int x, int y, String label) {
        ctx.fill(x, y + 3, x + 3, y + 6, C_RED);
        ctx.drawText(textRenderer, label, x + 6, y, C_MUTED, false);
    }

    private int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> C_GREEN;
            case CHARGING -> ((int) (time / 3)) % 2 == 0 ? C_RED : C_AMBER;
            case FIRING -> 0xFFFFFFFF;
            case COOLDOWN -> C_AMBER;
            default -> C_MUTED;
        };
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ---- Widgets ------------------------------------------------------------------------------

    /** Minimal custom-drawn button. */
    private class ConsoleButton {
        final int x, y, w, h;
        final String label;
        final int accent;
        final BooleanSupplier enabled;
        final Runnable action;

        ConsoleButton(int x, int y, int w, int h, String label, int accent, BooleanSupplier enabled, Runnable action) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.label = label; this.accent = accent; this.enabled = enabled; this.action = action;
        }

        boolean isHovered(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        void render(DrawContext ctx, int mx, int my, float time) {
            boolean on = enabled.getAsBoolean();
            boolean hover = on && isHovered(mx, my);
            ctx.fill(x, y, x + w, y + h, hover ? 0xFF2A0C0E : C_PANEL);
            ctx.drawBorder(x, y, w, h, on ? accent : 0xFF2A2E36);
            if (hover) ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, accent);
            ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, on ? C_TEXT : 0xFF4A4F58);
        }
    }

    /** The big FIRE button: hazard-striped, pulsing when armed, fills with charge progress. */
    private class FireButton extends ConsoleButton {
        FireButton(int x, int y, int w, int h) {
            super(x, y, w, h, "FIRE", C_RED,
                    () -> railgun.getState() == RailgunState.IDLE && railgun.hasTarget(),
                    () -> ClientPackets.sendStartCharge(railgun.getId()));
        }

        @Override
        void render(DrawContext ctx, int mx, int my, float time) {
            RailgunState state = railgun.getState();
            boolean armed = enabled.getAsBoolean();
            boolean hover = armed && isHovered(mx, my);

            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            int bg = armed ? lerpColor(0xFF3A0608, 0xFF7A0E10, hover ? 1f : pulse) : C_PANEL;
            ctx.fill(x, y, x + w, y + h, bg);

            // Progress fill while charging / firing / cooling down.
            float fill = switch (state) {
                case CHARGING -> railgun.getCharge();
                case FIRING -> 1f;
                case COOLDOWN -> 1f - (float) railgun.getStateTicks() / RailgunConfig.COOLDOWN_TICKS;
                default -> 0f;
            };
            if (fill > 0f) {
                int fillColor = state == RailgunState.FIRING ? 0xFFFFE0D0 : state == RailgunState.COOLDOWN ? 0xFF4A3A20 : 0xFFB01818;
                ctx.fill(x + 1, y + 1, x + 1 + (int) ((w - 2) * MathHelper.clamp(fill, 0f, 1f)), y + h - 1, fillColor);
            }

            // Hazard stripes along top and bottom edges.
            for (int sx = x; sx < x + w; sx += 8) {
                int c = ((sx - x) / 8) % 2 == 0 ? (armed ? C_AMBER : 0xFF3A3220) : 0xFF101010;
                ctx.fill(sx, y, Math.min(sx + 8, x + w), y + 3, c);
                ctx.fill(sx, y + h - 3, Math.min(sx + 8, x + w), y + h, c);
            }
            ctx.drawBorder(x, y, w, h, armed ? C_RED : 0xFF2A2E36);

            String text = switch (state) {
                case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", (int) (railgun.getCharge() * 100));
                case FIRING -> "FIRING";
                case COOLDOWN -> "COOLING";
                case IDLE -> railgun.hasTarget() ? "FIRE" : "NO TARGET";
                default -> "OFFLINE";
            };
            float scale = text.length() <= 6 ? 2f : 1.4f;
            int textColor = state == RailgunState.FIRING ? 0xFF300000 : armed ? 0xFFFFFFFF : 0xFF6A6F78;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + w / 2f, y + h / 2f - 4 * scale, 0);
            ctx.getMatrices().scale(scale, scale, 1f);
            ctx.drawText(textRenderer, text, -textRenderer.getWidth(text) / 2, 0, textColor, armed);
            ctx.getMatrices().pop();
        }
    }
}
