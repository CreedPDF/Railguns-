package com.creedpdf.orbitalrailgun.astraea;

import net.minecraft.util.math.MathHelper;

/**
 * The shared clock of ASTRAEA's two set pieces, in ticks from their start, so the server, every client's effects
 * and the cutscene camera agree exactly.
 *
 * <h2>Ascension (the ritual)</h2>
 * <pre>
 *   0  STANCE     the lance is raised horizontally overhead, ritual circles burn into the ground
 *  34  RIFT       a rift tears open high above (fully open at RIFT_OPEN)
 *  96  STAR       a white star forms inside it, pulsing faintly, brighter and brighter
 * 190  DESCEND    its light comes down as a beam onto the lance
 * 212  ABSORB     the light pours into the bearer
 * 252  IMPACT     ASCENSION / SUCCESSFULLY ABSORBED - impact frames; the lance is charged with one throw
 * 268  CLOSE      the rift seals
 * 310  END
 * </pre>
 *
 * <h2>The strike (the throw)</h2>
 * <pre>
 *   0  THROW      the bearer winds up and hurls the lance into the sky (it leaves the hand at RELEASE)
 *  44  ORBIT      it whips round the Earth, faster and faster (laps(u) below), its orbit decaying at the end
 * 440  DRILL      it bores into the planet along its own orbit, carving the cut all the way round
 * 520  SPLIT      Earth splits in half: impact frames, the blast; the chasm tears open on the ground
 * 640  RETURN     the lance falls back from the sky into its bearer's hand (at CATCH)
 * 700  END
 * </pre>
 */
public final class Timeline {
    private Timeline() {}

    // ---- Ascension
    public static final int R_RIFT = 34, R_RIFT_OPEN = 84, R_STAR = 96, R_DESCEND = 190, R_ABSORB = 212, R_IMPACT = 252, R_CLOSE = 268,
            R_END = 310;

    // ---- the strike
    public static final int S_RELEASE = 12, S_ORBIT = 44, S_DRILL = 440, S_SPLIT = 520, S_RETURN = 640, S_CATCH = 682, S_END = 700;

    /** Orbital radius in Earth radii while it circles (it spirals down to the surface over the last part of the orbit). */
    public static final double ORBIT_HIGH = 1.32;

    /** Laps completed after u (0..1) of the orbit phase: slow at first, then faster and faster (~2.6 laps a second at the end). */
    public static double laps(double u) {
        u = MathHelper.clamp(u, 0, 1);
        return 0.35 * u + 18.0 * u * u * u;
    }

    /** Laps per tick at u (for the sound of each pass). */
    public static double lapRate(double u) {
        u = MathHelper.clamp(u, 0, 1);
        return (0.35 + 54.0 * u * u) / (S_DRILL - S_ORBIT);
    }

    /** Orbit radius (Earth radii) at time t of the strike. */
    public static double orbitRadius(double t) {
        if (t < S_ORBIT) return 1.0;
        double u = (t - S_ORBIT) / (S_DRILL - S_ORBIT);
        if (u < 0.72) return ORBIT_HIGH;
        double k = MathHelper.clamp((u - 0.72) / 0.28, 0, 1);
        k = k * k * (3 - 2 * k);
        return ORBIT_HIGH + (1.0 - ORBIT_HIGH) * k;
    }

    /** The lance's angle round its orbit (radians) at time t of the strike; it keeps going round inside the planet while drilling. */
    public static double orbitAngle(double t) {
        if (t <= S_ORBIT) return 0;
        if (t <= S_DRILL) return Math.PI * 2 * laps((t - S_ORBIT) / (S_DRILL - S_ORBIT));
        double end = Math.PI * 2 * laps(1);
        double rate = Math.PI * 2 * lapRate(1);
        return end + rate * Math.min(t, S_SPLIT) - rate * S_DRILL;
    }

    /** 0..1: how far round the planet the cut has been carved while drilling. */
    public static double cutProgress(double t) {
        if (t <= S_DRILL) return 0;
        return MathHelper.clamp((t - S_DRILL) / (double) (S_SPLIT - S_DRILL - 10), 0, 1);
    }

    /** How far apart the halves have drifted (in Earth radii), given ticks since the split. */
    public static double separation(double sinceSplit, int strikes) {
        if (sinceSplit <= 0) return 0;
        double target = Math.min(1.1, 0.42 + 0.22 * (strikes - 1));
        double k = 1 - Math.exp(-sinceSplit / 900.0);
        return target * k + 0.004 * Math.min(sinceSplit, 40) / 40.0;
    }
}
