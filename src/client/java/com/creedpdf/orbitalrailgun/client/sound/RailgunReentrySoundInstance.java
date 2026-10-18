package com.creedpdf.orbitalrailgun.client.sound;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

/**
 * The plasma re-entry roar. It rides on the falling pod, gets louder and higher as the pod
 * accelerates, and cuts off the moment it lands (masked by the landing boom).
 */
public class RailgunReentrySoundInstance extends MovingSoundInstance {
    private final RailgunEntity railgun;
    private int ticks;

    public RailgunReentrySoundInstance(RailgunEntity railgun) {
        super(ModSounds.RAILGUN_REENTRY, SoundCategory.BLOCKS, SoundInstance.createRandom());
        this.railgun = railgun;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 1.0f;
        this.pitch = 0.8f;
        this.attenuationType = AttenuationType.LINEAR;
        this.x = railgun.getX();
        this.y = railgun.getY();
        this.z = railgun.getZ();
    }

    @Override
    public void tick() {
        if (railgun.isRemoved() || railgun.getState() != RailgunState.FALLING) {
            setDone();
            return;
        }
        ticks++;
        float ramp = Math.min(1f, ticks / 60f);
        this.volume = 1.5f + 4.5f * ramp;
        this.pitch = 0.75f + 0.35f * ramp;
        this.x = railgun.getX();
        this.y = railgun.getY();
        this.z = railgun.getZ();
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }
}
