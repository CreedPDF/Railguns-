package com.creedpdf.orbitalrailgun.client.reliquary;

import java.util.HashMap;
import java.util.Map;

/** What the client knows about the Reliquary right now. */
public final class ReliquaryClientState {
    private ReliquaryClientState() {}

    /** Client ticks (effects run on these). */
    public static long ticks;
    /** The local player's Aetheric Charge (only ever drawn as the crosshair ring). */
    public static int charge;
    /** Players projecting a Star-Eater ray, and the tick they started. */
    public static final Map<Integer, Long> BEAMS = new HashMap<>();

    public static void reset() {
        charge = 0;
        BEAMS.clear();
    }
}
