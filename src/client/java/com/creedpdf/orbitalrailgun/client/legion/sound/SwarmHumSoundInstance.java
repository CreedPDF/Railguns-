package com.creedpdf.orbitalrailgun.client.legion.sound;

import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

/**
 * The low telekinetic hum ({@code arsenal_legion:swarm_hum}) that follows the player while their swarm is out. It
 * swells with the number of weapons flying, fades in and out, and stops itself once the swarm is gone.
 */
public class SwarmHumSoundInstance extends MovingSoundInstance {
    private final ClientPlayerEntity player;
    private float target;

    public SwarmHumSoundInstance(ClientPlayerEntity player) {
        super(ArsenalLegion.SWARM_HUM, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.player = player;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        this.relative = false;
        this.attenuationType = AttenuationType.LINEAR;
    }

    /** Weapons flying round the player (0 lets the hum fade out and end). */
    public void setWeapons(int n) {
        target = n <= 0 ? 0f : Math.min(0.7f, 0.22f + 0.07f * n);
    }

    @Override
    public void tick() {
        if (player.isRemoved()) {
            setDone();
            return;
        }
        x = player.getX();
        y = player.getY() + 1;
        z = player.getZ();
        volume += (target - volume) * 0.08f;
        pitch = 0.95f + 0.05f * Math.min(1f, target * 2);
        if (target <= 0 && volume < 0.01f) setDone();
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }
}
