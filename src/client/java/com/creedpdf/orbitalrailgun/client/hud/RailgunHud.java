package com.creedpdf.orbitalrailgun.client.hud;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.systems.RenderSystem;
import com.creedpdf.orbitalrailgun.client.ui.UiKit;

import java.util.Locale;

/**
 * In-game overlay:
 * <ul>
 *   <li>Orbital-drop countdown panel (started by the S2C drop_countdown packet).</li>
 *   <li>Targeting readout under the crosshair while holding the remote.</li>
 *   <li>Full-screen flash when a nearby railgun fires.</li>
 * </ul>
 * All sounds here are played through the client's SoundManager with
 * {@link PositionedSoundInstance#master}, i.e. non-positional UI sounds only this player hears.
 */
public final class RailgunHud {
    private RailgunHud() {}

    private static final int RED = 0xFFFF3030;
    private static final int RED_DIM = 0xFF8A1A1A;

    @Nullable private static BlockPos landing;
    private static long countdownEnd;
    private static int countdownTotal;
    private static int lastBeepSecond = -1;
    private static float flash;
    private static final Identifier PANEL = RailgunMod.id("textures/gui/hud_countdown.png");

    // Camera shake: random yaw/pitch offsets that are undone the next tick, so the view never drifts.
    private static float shake;
    private static float shakeYaw, shakePitch;
    private static final java.util.Random SHAKE_RANDOM = new java.util.Random();

    /** Adds camera shake (0..~2). Used for landings, firing and nearby impacts. */
    public static void shake(float strength) {
        shake = Math.max(shake, strength);
    }

    public static void startCountdown(BlockPos landingPos, int ticks) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        landing = landingPos;
        countdownTotal = ticks;
        countdownEnd = client.world.getTime() + ticks;
        lastBeepSecond = ticks / 20 + 1;
        client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_UPLINK, 1.0f));
    }

    public static void triggerFireFlash(float strength) {
        flash = Math.max(flash, strength);
    }

    public static void tick(MinecraftClient client) {
        flash = Math.max(0f, flash - 0.06f);
        if (client.player != null && !client.isPaused()) {
            client.player.setYaw(client.player.getYaw() - shakeYaw);
            client.player.setPitch(client.player.getPitch() - shakePitch);
            shakeYaw = shakePitch = 0f;
            if (shake > 0.01f) {
                shakeYaw = (SHAKE_RANDOM.nextFloat() - 0.5f) * 2.4f * shake;
                shakePitch = (SHAKE_RANDOM.nextFloat() - 0.5f) * 1.8f * shake;
                client.player.setYaw(client.player.getYaw() + shakeYaw);
                client.player.setPitch(MathHelper.clamp(client.player.getPitch() + shakePitch, -90f, 90f));
                shake *= 0.9f;
            } else {
                shake = 0f;
            }
        }
        if (landing == null || client.world == null) return;

        long remaining = countdownEnd - client.world.getTime();
        int second = (int) Math.ceil(remaining / 20.0);
        if (remaining > 0 && second < lastBeepSecond) {
            lastBeepSecond = second;
            float pitch = second <= 1 ? 2.0f : 1.4f;
            client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.UI_COUNTDOWN_BEEP, pitch));
        }
        if (remaining < -40) landing = null; // keep "RELEASED" on screen for 2s
    }

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;
        TextRenderer font = client.textRenderer;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();

        if (flash > 0f) {
            int a = (int) (MathHelper.clamp(flash, 0f, 1f) * 150);
            ctx.fill(0, 0, sw, sh, (a << 24) | 0xFF2010);
        }

        if (landing != null) renderCountdown(ctx, client, font, sw, tickDelta);

        if (client.currentScreen == null && client.player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) {
            renderTargetingReadout(ctx, client, font, sw, sh, tickDelta);
        }
    }

    /**
     * The drop countdown: one low-profile strip at the top of the screen - who is coming, the clock, the distance to
     * the landing zone and a hairline progress bar. No animation beyond a tick on the status block in the last seconds.
     */
    private static void renderCountdown(DrawContext ctx, MinecraftClient client, TextRenderer font, int sw, float tickDelta) {
        float remaining = (countdownEnd - client.world.getTime()) - tickDelta;
        float progress = MathHelper.clamp(1f - remaining / countdownTotal, 0f, 1f);
        boolean released = remaining <= 0;
        float time = client.world.getTime() + tickDelta;
        boolean last = !released && remaining < 60;
        boolean tick = ((int) (time / 4)) % 2 == 0;
        int amber = 0xFFFFB02E, red = 0xFFE8382E, grey = 0xFF8E948F, white = 0xFFEDEFEA;
        int accent = released ? red : amber;

        int w = 172, h = 19;
        int x = (sw - w) / 2, y = 5;
        float in = MathHelper.clamp((countdownTotal - remaining) / 6f, 0f, 1f);
        int a = (int) (in * 255);
        if (a < 8) return;
        ctx.fill(x, y, x + w, y + h, (Math.min(a, 0xC8) << 24) | 0x0E0F10);
        ctx.fill(x, y, x + w, y + 1, (a << 24) | 0x2E3134);
        // status block
        int blk = released || (last && tick) ? accent : UiKit.alpha(accent, 0.55f);
        ctx.fill(x, y, x + 3, y + h, blk);
        var ms = ctx.getMatrices();
        drawK(ctx, font, com.creedpdf.orbitalrailgun.krl.KrlText.h("ARES-9"), x + 7, y + 2.5f, 0.72f, grey);
        drawK(ctx, font, com.creedpdf.orbitalrailgun.krl.KrlText.h(released ? "RELEASED" : "INBOUND"), x + 7, y + 10.5f, 0.72f, accent);
        // landing zone distance, middle
        double dist = Math.sqrt(client.player.getBlockPos().getSquaredDistance(landing));
        String lz = String.format(Locale.ROOT, "LZ  %d %d", landing.getX(), landing.getZ());
        String rng = String.format(Locale.ROOT, "%.0f m", dist);
        drawK(ctx, font, com.creedpdf.orbitalrailgun.krl.KrlText.t(lz), x + 52, y + 2.5f, 0.66f, grey);
        drawK(ctx, font, com.creedpdf.orbitalrailgun.krl.KrlText.t(rng), x + 52, y + 10.5f, 0.66f, dist < 12 ? red : white);
        // the clock, right
        String clock = released ? "IMPACT" : String.format(Locale.ROOT, "T-%05.2f", Math.max(0f, remaining / 20f));
        Text ct = com.creedpdf.orbitalrailgun.krl.KrlText.t(clock);
        float cs = 1.15f;
        drawK(ctx, font, ct, x + w - 6 - font.getWidth(ct) * cs, y + 4.5f, cs, released ? red : white);
        // hairline progress
        ctx.fill(x, y + h, x + w, y + h + 1, (a << 24) | 0x26292B);
        ctx.fill(x, y + h, x + (int) (w * progress), y + h + 1, accent);
    }

    private static void drawK(DrawContext ctx, TextRenderer font, Text t, float x, float y, float scale, int color) {
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(scale, scale, 1f);
        com.creedpdf.orbitalrailgun.client.ui.Px.snap(ctx);
        float px = com.creedpdf.orbitalrailgun.client.ui.Px.of(ctx);
        // redraw the same string in the face resolution that matches its size on screen
        boolean head = t.getStyle().getFont() != null && t.getStyle().getFont().getPath().startsWith("krl_head");
        String str = t.getString();
        ctx.drawText(font, head ? com.creedpdf.orbitalrailgun.krl.KrlText.h(str, px) : com.creedpdf.orbitalrailgun.krl.KrlText.t(str, px), 0, 0, color, false);
        ms.pop();
    }

    private static void renderTargetingReadout(DrawContext ctx, MinecraftClient client, TextRenderer font, int sw, int sh, float tickDelta) {
        boolean cooling = client.player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE);
        BlockPos aimed = AimAssist.aim(tickDelta);
        int cx = sw / 2, cy = sh / 2;

        String line;
        int color;
        if (cooling) {
            float cd = client.player.getItemCooldownManager().getCooldownProgress(RailgunMod.RAILGUN_REMOTE, tickDelta);
            line = String.format(Locale.ROOT, "UPLINK RECHARGING %d%%", (int) ((1f - cd) * 100));
            color = 0xFF808080;
        } else if (aimed != null) {
            BlockPos p = aimed;
            double d = Math.sqrt(client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)));
            line = String.format(Locale.ROOT, "[ %d %d %d  %.0fm ]  RMB: UPLINK", p.getX(), p.getY(), p.getZ(), d);
            color = RED;
            // Animated targeting reticle around the crosshair.
            int r = 9 + (int) (2 * MathHelper.sin((client.world.getTime() + tickDelta) * 0.4f));
            ctx.fill(cx - r, cy - r, cx - r + 4, cy - r + 1, RED);
            ctx.fill(cx - r, cy - r, cx - r + 1, cy - r + 4, RED);
            ctx.fill(cx + r - 3, cy - r, cx + r + 1, cy - r + 1, RED);
            ctx.fill(cx + r, cy - r, cx + r + 1, cy - r + 4, RED);
            ctx.fill(cx - r, cy + r, cx - r + 4, cy + r + 1, RED);
            ctx.fill(cx - r, cy + r - 3, cx - r + 1, cy + r + 1, RED);
            ctx.fill(cx + r - 3, cy + r, cx + r + 1, cy + r + 1, RED);
            ctx.fill(cx + r, cy + r - 3, cx + r + 1, cy + r + 1, RED);
        } else {
            line = "NO TARGET LOCK";
            color = 0xFF808080;
        }
        Text lt = com.creedpdf.orbitalrailgun.krl.KrlText.t(line);
        float sc = 0.85f;
        drawK(ctx, font, lt, cx - font.getWidth(lt) * sc / 2f, cy + 16, sc, color);
    }

    /** Sci-fi corner brackets. */
    public static void bracket(DrawContext ctx, int x, int y, int w, int h, int color) {
        int l = 8;
        ctx.fill(x - 1, y - 1, x + l, y, color);
        ctx.fill(x - 1, y - 1, x, y + l, color);
        ctx.fill(x + w - l, y - 1, x + w + 1, y, color);
        ctx.fill(x + w, y - 1, x + w + 1, y + l, color);
        ctx.fill(x - 1, y + h, x + l, y + h + 1, color);
        ctx.fill(x - 1, y + h - l, x, y + h + 1, color);
        ctx.fill(x + w - l, y + h, x + w + 1, y + h + 1, color);
        ctx.fill(x + w, y + h - l, x + w + 1, y + h + 1, color);
    }
}
