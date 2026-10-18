package com.creedpdf.orbitalrailgun.client.panel;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * The railgun's physical control panel, zoomed into.
 *
 * <p>Right-clicking the panel in the core's hatch (pod or deployed)
 * opens this screen. Over {@link #ZOOM_TICKS} the camera flies from the player's eyes to a point
 * just in front of that panel on the model, squared up to it ({@code CameraMixin} reads
 * {@link #cameraTarget}); the player's own body stays visible behind. When the camera arrives
 * the panel's screen fills the view and its UI boots: DEPLOY on the pod, RETRACT (with a
 * confirmation) when deployed, RETURN TO ORBIT once packed. After an action the camera flies
 * back so you can watch it happen.</p>
 */
public class PanelScreen extends Screen {
    private static final Identifier DISPLAY = RailgunMod.id("textures/gui/panel_display.png");
    public static final int ZOOM_TICKS = 12;
    private static final float DX0 = 40f / 512, DY0 = 36f / 288, DX1 = 472f / 512, DY1 = 252f / 288;
    private static final int CW = 400, CH = 200;
    private static final int RED = 0xFFFF3B30, AMBER = 0xFFFFB020, GREEN = 0xFF4CFF8A, TEXT = 0xFFFFE6DC, MUTED = 0xFFB08A80;

    @Nullable private static PanelScreen open;

    private final RailgunEntity railgun;
    private final Vec3d camPos;
    private final float camYaw, camPitch;
    private int ticks;
    private int closeTicks = -1;
    private int armedTicks;
    private float ox, oy, s = 1f;

    public PanelScreen(RailgunEntity railgun) {
        super(Text.literal("ARES-9 Control Panel"));
        this.railgun = railgun;
        // Where the camera ends up: squarely in front of the tablet mounted in the core's hatch.
        // It faces world -Z, so we look along +Z (yaw 0). Same spot in pod and deployed form.
        camPos = railgun.getPanelPos().add(0, 0.02, -1.02);
        camYaw = 0f;
        camPitch = 1.0f;
    }

    // ---- Camera hook (CameraMixin) -------------------------------------------------------------

    /** 0 = normal view, 1 = camera at the panel. Smoothstepped. */
    public static float zoom(float tickDelta) {
        PanelScreen p = open;
        if (p == null) return 0f;
        float t = p.closeTicks >= 0 ? 1f - (p.closeTicks + tickDelta) / ZOOM_TICKS : (p.ticks + tickDelta) / ZOOM_TICKS;
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Target camera pose while the panel is open, or null. */
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
    }

    private void beginClose() {
        if (closeTicks < 0) {
            closeTicks = Math.max(0, ZOOM_TICKS - Math.min(ticks, ZOOM_TICKS));
            play(ModSounds.UI_TABLET_CLOSE, 0.8f);
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
        return false;
    }

    private boolean live() {
        return closeTicks < 0 && ticks >= ZOOM_TICKS;
    }

    // ---- Actions ---------------------------------------------------------------------------------

    private record Action(String label, int color, Runnable run) {}

    @Nullable
    private Action action() {
        RailgunState st = railgun.getState();
        return switch (st) {
            case POD -> new Action("DEPLOY RAILGUN", RED, () -> send(RailgunEntity.PanelAction.DEPLOY));
            case IDLE, COOLDOWN -> armedTicks > 0
                    ? new Action("CONFIRM RETRACT", RED, () -> send(RailgunEntity.PanelAction.RETRACT))
                    : new Action("RETRACT RAILGUN", AMBER, () -> {
                        armedTicks = 60;
                        play(ModSounds.UI_COUNTDOWN_BEEP, 1f);
                    });
            case POD_READY -> new Action("RETURN TO ORBIT", RED, () -> send(RailgunEntity.PanelAction.RETURN));
            default -> null;
        };
    }

    private void send(RailgunEntity.PanelAction a) {
        ClientPackets.sendPanelAction(railgun.getId(), a);
        beginClose(); // fly back out to watch
    }

    // ---- Input -----------------------------------------------------------------------------------

    private boolean overButton(double mouseX, double mouseY) {
        float cx = (float) ((mouseX - ox) / s), cy = (float) ((mouseY - oy) / s);
        return cx >= 100 && cx < 300 && cy >= 136 && cy < 188;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!live() || button != 0) return true;
        Action a = action();
        if (a != null && overButton(mouseX, mouseY)) {
            play(ModSounds.UI_CLICK, 1f);
            a.run().run();
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
        // Float rounding means the fade can stop a hair under 1.0; the UI is live once zoomed in.
        if (alpha < 0.98f || closeTicks >= 0) return;

        float time = ticks + delta;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(ox, oy, 0);
        ctx.getMatrices().scale(s, s, 1f);

        RailgunState st = railgun.getState();
        ctx.drawText(textRenderer, "ARES-9 // FIELD CONTROL PANEL", 6, 4, RED, false);
        String unit = String.format(Locale.ROOT, "UNIT %04d", Math.abs(railgun.getUuid().hashCode()) % 10000);
        ctx.drawText(textRenderer, unit, CW - 6 - textRenderer.getWidth(unit), 4, MUTED, false);
        ctx.fill(4, 16, CW - 4, 17, 0x66FF3B30);

        ctx.getMatrices().push();
        ctx.getMatrices().translate(CW / 2f, 30, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        Text state = Text.translatable(st.translationKey());
        ctx.drawText(textRenderer, state, -textRenderer.getWidth(state) / 2, 0, stateColor(st, time), true);
        ctx.getMatrices().pop();

        String[] lines = switch (st) {
            case POD -> new String[]{"Pod landed and secure. Rail stowed.", "Deploying clears the surrounding area,",
                    "builds the emplacement and anchors it to the ground."};
            case PANEL_OPEN, TRANSFORMING -> new String[]{"Deployment in progress.", "Stand clear of the clamps."};
            case IDLE -> new String[]{"Emplacement online and anchored.", "Fire control: use your uplink tablet.",
                    "Retracting packs it back into its pod."};
            case COOLDOWN -> new String[]{"Barrel cooling.", "Retracting packs it back into its pod."};
            case CHARGING, FIRING -> new String[]{"WEAPON HOT", "Panel locked until the shot completes."};
            case PACKING -> new String[]{"Retracting into pod.", "Stand clear."};
            case POD_READY -> new String[]{"Packed and sealed for orbit.", "Returning launches the pod straight up."};
            default -> new String[]{};
        };
        for (int i = 0; i < lines.length; i++) {
            ctx.drawCenteredTextWithShadow(textRenderer, lines[i], CW / 2, 60 + i * 12, TEXT);
        }

        // Progress for the long sequences.
        float progress = switch (st) {
            case PANEL_OPEN -> 0.08f * railgun.getStateTicks() / RailgunConfig.PANEL_TICKS;
            case TRANSFORMING -> 0.08f + 0.92f * railgun.getStateTicks() / RailgunConfig.TRANSFORM_TICKS;
            case PACKING -> (float) railgun.getStateTicks() / RailgunConfig.PACK_TICKS;
            default -> -1f;
        };
        if (progress >= 0) {
            ctx.fill(60, 110, 340, 122, 0xFF1A0806);
            ctx.fill(60, 110, 60 + (int) (280 * MathHelper.clamp(progress, 0f, 1f)), 122, AMBER);
            String pct = String.format(Locale.ROOT, "%d%%", (int) (MathHelper.clamp(progress, 0f, 1f) * 100));
            ctx.drawCenteredTextWithShadow(textRenderer, pct, CW / 2, 112, 0xFF000000 | 0x101010);
        }

        // Big action button
        Action a = action();
        float mx = (mouseX - ox) / s, my = (mouseY - oy) / s;
        int bx = 100, by = 136, bw = 200, bh = 52;
        boolean hover = a != null && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
        for (int sx = bx; sx < bx + bw; sx += 8) {
            int c = ((sx - bx) / 8) % 2 == 0 ? (a != null ? 0xFFD69614 : 0xFF3A3220) : 0xFF101010;
            ctx.fill(sx, by, Math.min(sx + 8, bx + bw), by + 3, c);
            ctx.fill(sx, by + bh - 3, Math.min(sx + 8, bx + bw), by + bh, c);
        }
        if (a != null) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            int al = (int) (0x40 + (hover ? 0x90 : 0x50 * pulse));
            ctx.fill(bx, by + 3, bx + bw, by + bh - 3, (al << 24) | (a.color() & 0xFFFFFF));
            ctx.getMatrices().push();
            ctx.getMatrices().translate(bx + bw / 2f, by + bh / 2f - 7, 0);
            ctx.getMatrices().scale(1.8f, 1.8f, 1f);
            ctx.drawText(textRenderer, a.label(), -textRenderer.getWidth(a.label()) / 2, 0, 0xFFFFFFFF, true);
            ctx.getMatrices().pop();
        } else {
            ctx.fill(bx, by + 3, bx + bw, by + bh - 3, 0x30404448);
            ctx.drawCenteredTextWithShadow(textRenderer, "NO ACTION AVAILABLE", bx + bw / 2, by + bh / 2 - 4, 0xFF6A6F78);
        }
        ctx.drawText(textRenderer, "ESC: STEP BACK", 6, CH - 10, MUTED, false);
        ctx.getMatrices().pop();
    }

    private int stateColor(RailgunState state, float time) {
        return switch (state) {
            case IDLE -> GREEN;
            case CHARGING, FIRING -> ((int) (time / 3)) % 2 == 0 ? RED : AMBER;
            case POD, POD_READY -> 0xFF5FE3FF;
            default -> AMBER;
        };
    }

    private void play(SoundEvent sound, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
    }
}
