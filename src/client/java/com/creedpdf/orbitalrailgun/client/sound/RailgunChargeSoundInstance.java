package com.creedpdf.orbitalrailgun.client.sound;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

/**
 * Looping capacitor whine that follows the railgun and intensifies with charge: volume and pitch
 * are re-read from the synced charge value every client tick, so it rises smoothly from a low hum
 * to a shriek. It stops itself as soon as the railgun leaves the CHARGING state.
 */
public class RailgunChargeSoundInstance extends MovingSoundInstance {
    private final RailgunEntity railgun;

    public RailgunChargeSoundInstance(RailgunEntity railgun) {
        super(ModSounds.RAILGUN_CHARGE_LOOP, SoundCategory.BLOCKS, SoundInstance.createRandom());
        this.railgun = railgun;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.4f;
        this.pitch = 0.5f;
        this.x = railgun.getX();
        this.y = railgun.getY() + 2.0;
        this.z = railgun.getZ();
    }

    @Override
    public void tick() {
        if (railgun.isRemoved() || railgun.getState() != RailgunState.CHARGING) {
            setDone();
            return;
        }
        float charge = railgun.getCharge();
        this.volume = 0.4f + 1.8f * charge;
        this.pitch = 0.5f + 1.5f * charge * charge;
        this.x = railgun.getX();
        this.y = railgun.getY() + 2.0;
        this.z = railgun.getZ();
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }
}
