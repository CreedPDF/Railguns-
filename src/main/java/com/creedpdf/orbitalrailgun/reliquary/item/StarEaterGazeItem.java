package com.creedpdf.orbitalrailgun.reliquary.item;

/**
 * The Star-Eater's Gaze. Hold attack: a stellar ray up to {@link #RANGE} blocks pins everything it passes through
 * (their motion is multiplied by {@link #DRAG} every tick). Keep holding for {@link #COLLAPSE_TICKS} ticks and the ray
 * collapses into a singularity where it ends. Server logic: logic/StarEaterBeams.
 */
public class StarEaterGazeItem extends ReliquaryItem {
    public static final double RANGE = 30;
    public static final double DRAG = 0.1;
    public static final int COLLAPSE_TICKS = 60, COOLDOWN = 80;

    public StarEaterGazeItem(Settings settings) {
        super(settings);
    }
}
