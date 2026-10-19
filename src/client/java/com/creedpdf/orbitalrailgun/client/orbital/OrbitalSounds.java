package com.creedpdf.orbitalrailgun.client.orbital;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;

import java.util.function.BooleanSupplier;

/** Non-positional sounds for the orbital screens (UI, cutscene stingers, looping ambience). */
public final class OrbitalSounds {
    private OrbitalSounds() {}

    public static void play(SoundEvent s, float pitch, float volume) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(s, pitch, volume));
    }

    public static void loop(SoundEvent s, float volume, BooleanSupplier keepPlaying) {
        MinecraftClient.getInstance().getSoundManager().play(new Loop(s, volume, keepPlaying));
    }

    private static final class Loop extends MovingSoundInstance {
        private final BooleanSupplier keep;

        Loop(SoundEvent s, float volume, BooleanSupplier keep) {
            super(s, SoundCategory.MASTER, Random.create());
            this.keep = keep;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = volume;
            this.relative = true;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
        }

        @Override
        public void tick() {
            if (!keep.getAsBoolean()) setDone();
        }
    }
}
