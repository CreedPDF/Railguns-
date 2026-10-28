package com.creedpdf.orbitalrailgun.client.legion.hud;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * A small read-out in the bottom-left corner while weapons are bound: the swarm's weapons in their slots (dimmed when
 * stowed), and "LEGION n / capacity".
 */
public final class SwarmHud {
    private SwarmHud() {}

    private static int cap;
    private static boolean active;
    private static List<ItemStack> stacks = List.of();

    public static void update(int capacity, boolean deployed, List<ItemStack> bound) {
        cap = capacity;
        active = deployed;
        stacks = List.copyOf(bound);
    }

    public static void clear() {
        stacks = List.of();
    }

    public static void render(DrawContext ctx, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (stacks.isEmpty() || mc.player == null || mc.options.hudHidden) return;
        int h = ctx.getScaledWindowHeight();
        int x0 = 6, y0 = h - 26;
        String label = "LEGION  " + stacks.size() + " / " + cap + (active ? "" : "  STOWED");
        ctx.drawText(mc.textRenderer, KrlText.t(label, active ? KrlText.AMBER : KrlText.GREY), x0, y0 - 11, 0xFFFFFFFF, true);
        for (int i = 0; i < stacks.size(); i++) {
            int x = x0 + i * 19;
            boolean flying = active && i < cap;
            ctx.fill(x, y0, x + 18, y0 + 18, flying ? 0x80301008 : 0x80181818);
            ctx.fill(x, y0 + 17, x + 18, y0 + 18, flying ? 0xFFE8382E : 0xFF404040);
            ctx.drawItem(stacks.get(i), x + 1, y0 + 1);
            if (!flying) ctx.fill(x, y0, x + 18, y0 + 18, 0x90000000);
        }
    }
}
