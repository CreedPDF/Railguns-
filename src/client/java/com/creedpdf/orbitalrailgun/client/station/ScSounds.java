package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.station.Sc;
import com.creedpdf.orbitalrailgun.station.ScStrike;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The strike's soundtrack for the player who ordered it. Their camera is up in orbit or far off on the ground during
 * the cutscene, so instead of the world's positional sounds (which the server sends to everyone else) they hear the
 * shot as the camera does: the emitter charging, the shot, and the impact.
 */
final class ScSounds {
    private ScSounds() {}

    private record Cue(long at, SoundEvent sound, float pitch, float volume) {}

    private static final List<Cue> CUES = new ArrayList<>();

    static void strikeSequence(MinecraftClient mc) {
        long t = ScClient.ticks;
        CUES.add(new Cue(t + 2, Sc.S_SAT_CHARGE, 1f, 1f));
        CUES.add(new Cue(t + ScStrike.FIRE, Sc.S_SAT_FIRE, 1f, 1f));
        CUES.add(new Cue(t + ScStrike.FIRE + 2, Sc.S_STRIKE_BEAM, 1f, 0.8f));
        CUES.add(new Cue(t + ScStrike.IMPACT, Sc.S_STRIKE_IMPACT, 1f, 1f));
        CUES.add(new Cue(t + ScStrike.IMPACT + 3, Sc.S_STRIKE_RUMBLE, 1f, 0.9f));
    }

    static void tick(MinecraftClient mc) {
        for (Iterator<Cue> it = CUES.iterator(); it.hasNext(); ) {
            Cue c = it.next();
            if (ScClient.ticks < c.at()) continue;
            it.remove();
            mc.getSoundManager().play(PositionedSoundInstance.master(c.sound(), c.pitch(), c.volume()));
        }
    }
}
