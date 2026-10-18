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
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

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
    private static final int PANEL_BG = 0xC0080A0E;

    @Nullable private static BlockPos landing;
    private static long countdownEnd;
    private static int countdownTotal;
    private static int lastBeepSecond = -1;
    private static float flash;

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

        if (client.player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)
                || client.player.getOffHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) {
            renderTargetingReadout(ctx, client, font, sw, sh, tickDelta);
        }
    }

    private static void renderCountdown(DrawContext ctx, MinecraftClient client, TextRenderer font, int sw, float tickDelta) {
        float remaining = (countdownEnd - client.world.getTime()) - tickDelta;
        float progress = MathHelper.clamp(1f - remaining / countdownTotal, 0f, 1f);
        boolean released = remaining <= 0;
        boolean blink = (client.world.getTime() / 5) % 2 == 0;

        int w = 230, h = 58;
        int x = (sw - w) / 2, y = 10;
        ctx.fill(x, y, x + w, y + h, PANEL_BG);
        ctx.drawBorder(x, y, w, h, RED_DIM);
        bracket(ctx, x, y, w, h, RED);

        Text title = Text.translatable(released ? "hud.orbital_railgun.released" : "hud.orbital_railgun.inbound");
        ctx.drawCenteredTextWithShadow(font, title, sw / 2, y + 5, released || blink ? RED : RED_DIM);

        String clock = released ? "IMPACT" : String.format(Locale.ROOT, "T-%05.2f", Math.max(0f, remaining / 20f));
        ctx.getMatrices().push();
        ctx.getMatrices().translate(sw / 2f, y + 17, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        ctx.drawText(font, clock, -font.getWidth(clock) / 2, 0, 0xFFFFFFFF, true);
        ctx.getMatrices().pop();

        int barX = x + 8, barY = y + 38, barW = w - 16;
        ctx.fill(barX, barY, barX + barW, barY + 4, 0xFF1A0505);
        ctx.fill(barX, barY, barX + (int) (barW * progress), barY + 4, RED);

        double dist = Math.sqrt(client.player.getBlockPos().getSquaredDistance(landing));
        String info = String.format(Locale.ROOT, "LZ %d %d %d   |   %.0fm", landing.getX(), landing.getY(), landing.getZ(), dist);
        ctx.drawCenteredTextWithShadow(font, info, sw / 2, y + 46, 0xFFB0B0B0);
    }

    private static void renderTargetingReadout(DrawContext ctx, MinecraftClient client, TextRenderer font, int sw, int sh, float tickDelta) {
        boolean cooling = client.player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE);
        HitResult hit = client.player.raycast(RailgunConfig.REMOTE_RANGE, tickDelta, false);
        int cx = sw / 2, cy = sh / 2;

        String line;
        int color;
        if (cooling) {
            float cd = client.player.getItemCooldownManager().getCooldownProgress(RailgunMod.RAILGUN_REMOTE, tickDelta);
            line = String.format(Locale.ROOT, "UPLINK RECHARGING %d%%", (int) ((1f - cd) * 100));
            color = 0xFF808080;
        } else if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
            BlockPos p = bh.getBlockPos();
            double d = Math.sqrt(client.player.getEyePos().squaredDistanceTo(hit.getPos()));
            line = String.format(Locale.ROOT, "[ LOCK %d %d %d  %.0fm ]", p.getX(), p.getY(), p.getZ(), d);
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
        ctx.drawCenteredTextWithShadow(font, line, cx, cy + 16, color);
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
