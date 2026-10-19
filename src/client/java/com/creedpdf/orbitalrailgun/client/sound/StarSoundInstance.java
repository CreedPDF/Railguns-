package com.creedpdf.orbitalrailgun.client.sound;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Vec3d;

/** The star's burning roar: swells in after ignition, deepens while it collapses, gone at the nova. */
public class StarSoundInstance extends MovingSoundInstance {
    private final RailgunEntity railgun;

    public StarSoundInstance(RailgunEntity railgun) {
        super(ModSounds.STAR_LOOP, SoundCategory.BLOCKS, SoundInstance.createRandom());
        this.railgun = railgun;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        Vec3d s = railgun.getStarPos(1f);
        this.x = s.x;
        this.y = s.y;
        this.z = s.z;
    }

    @Override
    public void tick() {
        if (railgun.isRemoved() || railgun.getState() != RailgunState.STAR || railgun.getStateTicks() >= RailgunEntity.STAR_NOVA) {
            setDone();
            return;
        }
        int t = railgun.getStateTicks();
        float in = Math.max(0f, Math.min(1f, (t - RailgunEntity.STAR_IGNITE) / 30f));
        this.volume = 0.01f + 3.5f * in;
        this.pitch = t > RailgunEntity.STAR_COLLAPSE ? 1f - 0.4f * (t - RailgunEntity.STAR_COLLAPSE) / 40f : 1f;
        Vec3d s = railgun.getStarPos(1f);
        this.x = s.x;
        this.y = s.y;
        this.z = s.z;
    }
}
