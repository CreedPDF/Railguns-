package com.creedpdf.orbitalrailgun.client.screen;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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
 * <p>The static art (brushed gunmetal, bevelled wells, hazard frames, oscilloscope grid) is the
 * custom texture {@code textures/gui/fire_control.png}, painted by tools/textures/build_textures.py
 * with every well at the coordinates used below. This class draws the live parts on top: text,
 * capacitor segments, the waveform and the buttons.</p>
 *
 * <p>It is a plain client {@link Screen}, not a ScreenHandler: there is no inventory, and every
 * value shown is already on the client-side entity via TrackedData. Buttons send small C2S
 * packets that the server validates.</p>
 */
public class RailgunControlScreen extends Screen {
    private static final Identifier PANEL = RailgunMod.id("textures/gui/fire_control.png");
    private static final int W = 336, H = 232;

    private static final int C_RED = 0xFFFF3030;
    private static final int C_TEXT = 0xFFD8D8D8;
    private static final int C_MUTED = 0xFF8A909A;
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

        fieldX = coordField(left + 33, top + 49, sx);
        fieldY = coordField(left + 33, top + 71, sy);
        fieldZ = coordField(left + 33, top + 93, sz);
        setInitialFocus(fieldX);

        buttons.clear();
        buttons.add(new ConsoleButton(left + 9, top + 111, 77, 20, "SET TARGET", C_RED,
                () -> !isLocked(), this::submitTarget));
        buttons.add(new ConsoleButton(left + 89, top + 111, 77, 20, "PAINT", C_AMBER,
                () -> !isLocked(), this::paintTarget));
        buttons.add(new ConsoleButton(left + 212, top + 171, 117, 25, "ABORT", C_AMBER,
                () -> railgun.getState() == RailgunState.CHARGING, () -> ClientPackets.sendAbort(railgun.getId())));
        buttons.add(new ConsoleButton(left + 212, top + 199, 117, 25, "RECALL", C_MUTED,
                () -> railgun.getState() != RailgunState.FIRING, () -> {
                    ClientPackets.sendRecall(railgun.getId());
                    close();
                }));
        buttons.add(new FireButton(left + 12, top + 174, 193, 47));
    }

    private TextFieldWidget coordField(int x, int y, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, 126, 12, Text.empty());
        field.setDrawsBackground(false); // the panel texture provides the recessed well
        field.setMaxLength(9);
        field.setTextPredicate(s -> s.matches("-?\\d{0,8}"));
        field.setText(value);
        field.setEditableColor(0xFFFF6A5A);
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
        fieldX.tick();
        fieldY.tick();
        fieldZ.tick();
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
                        click(0.6f);
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
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_CLICK, pitch));
    }

    // ---- Rendering ----------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        float time = ticks + delta;

        RenderSystem.enableBlend();
        ctx.drawTexture(PANEL, left, top, 0, 0, W, H, 512, 512);
        RenderSystem.disableBlend();

        // Header
        ctx.drawText(textRenderer, Text.translatable("gui.orbital_railgun.header"), left + 9, top + 9, C_RED, false);
        boolean blink = ((int) (time / 10)) % 2 == 0;
        ctx.fill(left + W - 20, top + 9, left + W - 13, top + 16, blink ? C_GREEN : 0xFF0F3A1C);

        // ---- Left column: targeting --------------------------------------------------------
        ctx.drawText(textRenderer, "TARGET COORDINATES", left + 10, top + 31, C_MUTED, false);
        for (int i = 0; i < 3; i++) {
            ctx.drawCenteredTextWithShadow(textRenderer, new String[]{"X", "Y", "Z"}[i], left + 18, top + 48 + i * 22, 0xFFFFD0C8);
        }
        if (inputErrorTicks > 0) {
            ctx.drawText(textRenderer, "INVALID INPUT", left + 14, top + 139, C_AMBER, false);
        } else if (railgun.hasTarget()) {
            BlockPos t = railgun.getTarget();
            ctx.drawText(textRenderer, String.format(Locale.ROOT, "LOCK  %d %d %d", t.getX(), t.getY(), t.getZ()),
                    left + 14, top + 139, C_RED, false);
            double range = Math.sqrt(railgun.getBlockPos().getSquaredDistance(t));
            ctx.drawText(textRenderer, String.format(Locale.ROOT, "RANGE %.0fm / %.0fm", range, RailgunConfig.MAX_FIRE_RANGE),
                    left + 14, top + 150, C_MUTED, false);
        } else {
            ctx.drawText(textRenderer, "NO TARGET LOCKED", left + 14, top + 139, C_MUTED, false);
        }

        // ---- Right column: status / capacitor ---------------------------------------------
        int rx = left + 176, rw = 150;
        RailgunState state = railgun.getState();
        ctx.getMatrices().push();
        ctx.getMatrices().translate(rx + 5, top + 45, 0);
        ctx.getMatrices().scale(1.4f, 1.4f, 1f);
        ctx.drawText(textRenderer, Text.translatable(state.translationKey()), 0, 0, stateColor(state, time), true);
        ctx.getMatrices().pop();

        float charge = railgun.getCharge();
        ctx.drawText(textRenderer, "CAPACITOR", rx, top + 60, C_MUTED, false);
        String pct = String.format(Locale.ROOT, "%3d%%", (int) (charge * 100));
        ctx.drawText(textRenderer, pct, rx + rw - textRenderer.getWidth(pct), top + 60, C_TEXT, false);
        int segs = 25, segW = (rw - 2) / segs;
        int lit = (int) Math.ceil(charge * segs);
        for (int i = 0; i < segs; i++) {
            int sx = rx + 1 + i * segW;
            int color = i < lit ? lerpColor(0xFF901414, 0xFFFF6048, (float) i / segs) : 0xFF22100F;
            ctx.fill(sx, top + 71, sx + segW - 1, top + 84, color);
        }

        // Oscilloscope: capacitor waveform. Amplitude and frequency follow the charge.
        int ox = rx, oy = top + 90, ow = rw, oh = 39;
        float amp = 2f + charge * (oh / 2f - 4f);
        int prevY = oy + oh / 2;
        for (int px = 1; px < ow - 1; px++) {
            float phase = px * (0.12f + charge * 0.35f) - time * (0.25f + charge);
            float noise = state == RailgunState.FIRING ? MathHelper.sin(px * 1.7f + time * 5f) * 5f : 0f;
            int py = oy + oh / 2 + (int) (MathHelper.sin(phase) * amp + noise);
            py = MathHelper.clamp(py, oy + 1, oy + oh - 2);
            ctx.fill(ox + px, Math.min(prevY, py), ox + px + 1, Math.max(prevY, py) + 1, 0xFF50FF80);
            prevY = py;
        }

        float az = (float) Math.toDegrees(railgun.getAimYawRad(delta));
        float el = (float) Math.toDegrees(railgun.getAimPitchRad(delta));
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "AZ %+07.2f   EL %+06.2f", az, el), rx + 4, top + 137, C_MUTED, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "POS %d %d %d", railgun.getBlockX(), railgun.getBlockY(), railgun.getBlockZ()),
                rx + 4, top + 149, C_MUTED, false);

        // Text fields (children) then custom buttons on top
        super.render(ctx, mouseX, mouseY, delta);
        for (ConsoleButton b : buttons) b.render(ctx, mouseX, mouseY, time);
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
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ---- Widgets ------------------------------------------------------------------------------

    /** Button drawn translucently over a well in the panel texture. */
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
            if (hover) ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, (accent & 0x00FFFFFF) | 0x38000000);
            if (on) {
                ctx.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, hover ? accent : (accent & 0x00FFFFFF) | 0x80000000);
            }
            ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, on ? C_TEXT : 0xFF4A4F58);
        }
    }

    /** The big FIRE button: pulses when armed, fills with charge progress, flashes when firing. */
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

            if (armed) {
                float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
                int a = (int) (0x30 + (hover ? 0x70 : 0x50 * pulse));
                ctx.fill(x, y, x + w, y + h, (a << 24) | 0xE01818);
            }

            float fill = switch (state) {
                case CHARGING -> railgun.getCharge();
                case FIRING -> 1f;
                case COOLDOWN -> 1f - (float) railgun.getStateTicks() / RailgunConfig.COOLDOWN_TICKS;
                default -> 0f;
            };
            if (fill > 0f) {
                int fillColor = state == RailgunState.FIRING ? 0xE0FFE0D0 : state == RailgunState.COOLDOWN ? 0x90604020 : 0xC0C01818;
                ctx.fill(x, y, x + (int) (w * MathHelper.clamp(fill, 0f, 1f)), y + h, fillColor);
            }

            String text = switch (state) {
                case CHARGING -> String.format(Locale.ROOT, "CHARGING %d%%", (int) (railgun.getCharge() * 100));
                case FIRING -> "FIRING";
                case COOLDOWN -> "COOLING";
                case IDLE -> railgun.hasTarget() ? "FIRE" : "NO TARGET";
                default -> "OFFLINE";
            };
            float scale = text.length() <= 6 ? 2.4f : 1.5f;
            int textColor = state == RailgunState.FIRING ? 0xFF300000 : armed ? 0xFFFFFFFF : 0xFF7A808A;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + w / 2f, y + h / 2f - 4 * scale, 0);
            ctx.getMatrices().scale(scale, scale, 1f);
            ctx.drawText(textRenderer, text, -textRenderer.getWidth(text) / 2, 0, textColor, armed);
            ctx.getMatrices().pop();
        }
    }
}
