package com.creedpdf.orbitalrailgun.client.sound;

import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.rift.RiftDimension;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

/** The deep, slow hum of the dimension beyond the rift. Plays only while the player is inside it. */
public class RiftAmbientSoundInstance extends MovingSoundInstance {
    private int age;

    public RiftAmbientSoundInstance() {
        super(ModSounds.RIFT_AMBIENT, SoundCategory.AMBIENT, SoundInstance.createRandom());
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        this.relative = true;
        this.attenuationType = SoundInstance.AttenuationType.NONE;
    }

    @Override
    public void tick() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || p.getWorld().getRegistryKey() != RiftDimension.KEY) {
            setDone();
            return;
        }
        age++;
        this.volume = Math.min(0.9f, 0.01f + age / 80f);
    }
}
