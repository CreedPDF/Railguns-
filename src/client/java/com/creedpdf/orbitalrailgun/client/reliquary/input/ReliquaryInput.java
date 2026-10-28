package com.creedpdf.orbitalrailgun.client.reliquary.input;

import com.creedpdf.orbitalrailgun.client.reliquary.ReliquaryClientState;
import com.creedpdf.orbitalrailgun.reliquary.ModDataComponentTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.item.ReliquaryItem;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;

/**
 * The attack key with a Reliquary weapon in hand. Vanilla attacking and block breaking are switched off for these
 * weapons (MinecraftClientReliquaryMixin) and replaced with:
 * <ul>
 *   <li>Core-Infused Revolver: each press fires a round;</li>
 *   <li>Void-Carver's Edge: each press swings;</li>
 *   <li>Star-Eater's Gaze: holding projects the ray (start / stop are sent as the key goes down / up).</li>
 * </ul>
 * The server checks the item, its cooldown and everything else.
 */
public final class ReliquaryInput {
    private ReliquaryInput() {}

    private static boolean beaming;
    private static long beamFrom;
    private static long lastFire = -1000;
    private static int shots;

    public static boolean holding(MinecraftClient mc) {
        return mc.player != null && mc.player.getMainHandStack().getItem() instanceof ReliquaryItem;
    }

    /** The attack key was pressed: returns true if a Reliquary weapon took it. */
    public static boolean onAttack(MinecraftClient mc) {
        if (!holding(mc)) return false;
        ItemStack held = mc.player.getMainHandStack();
        if (mc.player.getItemCooldownManager().isCoolingDown(held.getItem())) return true;
        if (held.isOf(ModItems.CORE_REVOLVER)) {
            ClientPlayNetworking.send(ReliquaryNet.FIRE, PacketByteBufs.empty());
            lastFire = ReliquaryClientState.ticks;
            shots++;
        } else if (held.isOf(ModItems.VOID_CARVER_BLADE)) {
            ClientPlayNetworking.send(ReliquaryNet.SLASH, PacketByteBufs.empty());
        }
        return true;
    }

    public static void tick(MinecraftClient mc) {
        boolean want = mc.player != null && mc.currentScreen == null && mc.options.attackKey.isPressed()
                && mc.player.getMainHandStack().isOf(ModItems.STAR_EATER_GAZE) && !mc.player.getItemCooldownManager().isCoolingDown(ModItems.STAR_EATER_GAZE);
        if (want != beaming) {
            beaming = want;
            beamFrom = ReliquaryClientState.ticks;
            PacketByteBuf b = PacketByteBufs.create();
            b.writeBoolean(want);
            ClientPlayNetworking.send(ReliquaryNet.BEAM, b);
        }
        if (beaming && ReliquaryClientState.ticks - beamFrom >= StarEaterGazeItem.COLLAPSE_TICKS + 2) beaming = false;   // the server collapsed it
    }

    /** The Orbital Link: sent only with a full charge (the server checks again). */
    public static void link() {
        if (ReliquaryClientState.charge >= ModDataComponentTypes.MAX_CHARGE) ClientPlayNetworking.send(ReliquaryNet.LINK, PacketByteBufs.empty());
    }

    /** The local player's ray charge 0..1 (for the held model). */
    public static float localCharge() {
        return beaming ? Math.min(1f, (ReliquaryClientState.ticks - beamFrom) / (float) StarEaterGazeItem.COLLAPSE_TICKS) : 0f;
    }

    public static float sinceFire() {
        return ReliquaryClientState.ticks - lastFire + MinecraftClient.getInstance().getTickDelta();
    }

    public static int shots() {
        return shots;
    }

    public static void reset() {
        beaming = false;
    }
}
