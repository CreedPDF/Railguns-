package com.creedpdf.orbitalrailgun.reliquary.item;

/**
 * The Core-Infused Revolver. Each round travels {@link #RANGE} blocks in four stages, passes through every mob on its
 * line ({@link #DAMAGE} each), stops at the first solid surface and leaves a molten scar there. Server logic:
 * logic/RevolverShot.
 */
public class CoreRevolverItem extends ReliquaryItem {
    public static final double RANGE = 64;
    public static final float DAMAGE = 12f;
    public static final int COOLDOWN = 16;

    public CoreRevolverItem(Settings settings) {
        super(settings);
    }
}
