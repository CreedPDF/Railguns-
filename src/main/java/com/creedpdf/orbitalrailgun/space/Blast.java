package com.creedpdf.orbitalrailgun.space;

/** Timeline of a planet's death (ticks after ARES-0's beam reaches it). Shared by server and client. */
public final class Blast {
    private Blast() {}

    /** Beam bores in; crust fractures and glows from within. */
    public static final int CRACK_END = 170;
    /** The core collapses: the planet shrinks and dims for a heartbeat. */
    public static final int DETONATE = 215;
    /** Fragments, plasma shell and shockwave expand. */
    public static final int DEBRIS_END = 1400;
    /** Players on the surface are thrown into space a moment before it goes. */
    public static final int EJECT = 200;
}
