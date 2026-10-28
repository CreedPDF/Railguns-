package com.creedpdf.orbitalrailgun.reliquary.item;

/**
 * Void-Carver's Edge. Each swing cuts a plane through space in front of you ({@link #REACH} blocks, {@link #ARC}
 * degrees either side): everything it crosses takes {@link #CUT_DAMAGE} and is marked; a rift stays at the swing for
 * five seconds, arcing into everything marked every half second. Server logic: logic/VoidCuts.
 */
public class VoidCarverBladeItem extends ReliquaryItem {
    public static final double REACH = 5.0, ARC = 80;
    public static final float CUT_DAMAGE = 9f;
    public static final int COOLDOWN = 14;

    public VoidCarverBladeItem(Settings settings) {
        super(settings);
    }
}
