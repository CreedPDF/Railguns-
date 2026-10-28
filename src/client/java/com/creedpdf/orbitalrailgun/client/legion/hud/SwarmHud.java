package com.creedpdf.orbitalrailgun.client.legion.hud;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.legion.SwarmFormation;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * The Legion read-out, bottom-left, while weapons are bound: "LEGION n / capacity  //  FORMATION", the bound weapons in
 * their slots (dimmed when stowed), and the Fury gauge - twenty segments that fill with strikes, parries and
 * executions and pulse when a Blade Storm is ready (V).
 */
public final class SwarmHud {
    private SwarmHud() {}

    private static int cap, fury;
    private static boolean active;
    private static SwarmFormation formation = SwarmFormation.HALO;
    private static List<ItemStack> stacks = List.of();
    private static float shown;

    public static void update(int capacity, boolean deployed, SwarmFormation f, int fu, List<ItemStack> bound) {
        cap = capacity;
        active = deployed;
        formation = f;
        fury = fu;
        stacks = List.copyOf(bound);
    }

    public static void clear() {
        stacks = List.of();
    }

    public static void render(DrawContext ctx, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (stacks.isEmpty() || mc.player == null || mc.options.hudHidden) return;
        int h = ctx.getScaledWindowHeight();
        int x0 = 6, y0 = h - 30;
        String label = "LEGION  " + stacks.size() + " / " + cap + "  //  " + (active ? formation.label : "STOWED");
        ctx.drawText(mc.textRenderer, KrlText.t(label, active ? KrlText.AMBER : KrlText.GREY), x0, y0 - 11, 0xFFFFFFFF, true);
        for (int i = 0; i < stacks.size(); i++) {
            int x = x0 + i * 19;
            boolean flying = active && i < cap;
            ctx.fill(x, y0, x + 18, y0 + 18, flying ? 0x80301008 : 0x80181818);
            ctx.fill(x, y0 + 17, x + 18, y0 + 18, flying ? 0xFFE8382E : 0xFF404040);
            ctx.drawItem(stacks.get(i), x + 1, y0 + 1);
            if (!flying) ctx.fill(x, y0, x + 18, y0 + 18, 0x90000000);
        }
        // Fury gauge
        shown += (fury - shown) * 0.2f;
        boolean full = fury >= IWeaponSwarm.MAX_FURY;
        long t = mc.world == null ? 0 : mc.world.getTime();
        int gy = y0 + 21, cells = 20, cw = 5;
        int lit = MathHelper.floor(shown / IWeaponSwarm.MAX_FURY * cells + 0.001f);
        for (int i = 0; i < cells; i++) {
            int x = x0 + i * (cw + 1);
            int on = full ? (((t / 3) + i) % 4 == 0 ? 0xFFFFE6B0 : 0xFFFF5A24) : i < lit ? 0xFFE8382E : 0xFF2A1A16;
            ctx.fill(x, gy, x + cw, gy + 3, on);
        }
        ctx.drawText(mc.textRenderer, KrlText.t(full ? "FURY  //  V: BLADE STORM" : "FURY", full ? 0xFFE6B0 : KrlText.GREY),
                x0 + cells * (cw + 1) + 4, gy - 3, 0xFFFFFFFF, true);
    }
}
