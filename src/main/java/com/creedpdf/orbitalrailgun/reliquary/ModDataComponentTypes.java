package com.creedpdf.orbitalrailgun.reliquary;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;

/**
 * The Reliquary's player data. Minecraft 1.20.1 (this mod's version) has no Data Component API - that arrived in
 * 1.20.5 - so the per-player profile is a small typed record stored on every player by
 * {@code mixin/PlayerEntityReliquaryMixin} and saved in the player's NBT under "CosmicReliquary". This class is its
 * typed access point, the 1.20.1 counterpart of a registered ComponentType.
 *
 * <p>The profile holds one hidden integer, {@link Profile#aethericCharge} (0..{@link #MAX_CHARGE}): +1 for every kill
 * made with a Reliquary weapon. It is never written as text anywhere - the client only ever sees it as the ring round
 * the crosshair.</p>
 */
public final class ModDataComponentTypes {
    private ModDataComponentTypes() {}

    public static final int MAX_CHARGE = 100;

    /** Every player's Reliquary profile. */
    public static final class Profile {
        private int aethericCharge;

        public int aethericCharge() {
            return aethericCharge;
        }

        public void setAethericCharge(int v) {
            aethericCharge = Math.max(0, Math.min(MAX_CHARGE, v));
        }

        public NbtCompound write() {
            NbtCompound n = new NbtCompound();
            n.putInt("AethericCharge", aethericCharge);
            return n;
        }

        public void read(NbtCompound n) {
            setAethericCharge(n.getInt("AethericCharge"));
        }
    }

    /** Implemented on PlayerEntity by the mixin. */
    public interface Holder {
        Profile cosmicReliquary$profile();
    }

    public static Profile of(PlayerEntity p) {
        return ((Holder) p).cosmicReliquary$profile();
    }

    static void init() {}
}
