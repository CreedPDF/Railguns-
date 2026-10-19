package com.creedpdf.orbitalrailgun.client.sound;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Vec3d;

/** The rift's howl: swells in as the sky tears, rises in pitch while it collapses, gone when it snaps shut. */
public class RiftSoundInstance extends MovingSoundInstance {
    private final RailgunEntity railgun;

    public RiftSoundInstance(RailgunEntity railgun) {
        super(ModSounds.RIFT_LOOP, SoundCategory.BLOCKS, SoundInstance.createRandom());
        this.railgun = railgun;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        Vec3d s = railgun.getRiftPos(1f);
        this.x = s.x;
        this.y = s.y;
        this.z = s.z;
    }

    @Override
    public void tick() {
        if (railgun.isRemoved() || railgun.getState() != RailgunState.RIFT || railgun.getStateTicks() >= RailgunEntity.RIFT_SNAP) {
            setDone();
            return;
        }
        int t = railgun.getStateTicks();
        float in = Math.max(0f, Math.min(1f, (t - RailgunEntity.RIFT_OPEN) / 30f));
        this.volume = 0.01f + 3.5f * in;
        this.pitch = t > RailgunEntity.RIFT_COLLAPSE ? 1f + 0.5f * (t - RailgunEntity.RIFT_COLLAPSE) / 40f : 1f;
        Vec3d s = railgun.getRiftPos(1f);
        this.x = s.x;
        this.y = s.y;
        this.z = s.z;
    }
}
