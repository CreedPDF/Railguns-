package com.creedpdf.orbitalrailgun.client.reliquary.sound;

import com.creedpdf.orbitalrailgun.client.reliquary.ReliquaryClientState;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;

/** The Star-Eater's drone, following its holder while the ray is on; its pitch climbs with the charge (ticks held). */
public class StarEaterHumSound extends MovingSoundInstance {
    private final PlayerEntity player;

    public StarEaterHumSound(PlayerEntity player) {
        super(ModSounds.STAR_EATER_HUM, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.player = player;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.9f;
        this.pitch = 0.6f;
        this.x = player.getX();
        this.y = player.getEyeY();
        this.z = player.getZ();
    }

    @Override
    public void tick() {
        Long from = ReliquaryClientState.BEAMS.get(player.getId());
        if (player.isRemoved() || from == null) {
            setDone();
            return;
        }
        float k = Math.min(1f, (ReliquaryClientState.ticks - from) / (float) StarEaterGazeItem.COLLAPSE_TICKS);
        pitch = 0.6f + 1.2f * k * k;
        volume = 0.7f + 0.3f * k;
        x = player.getX();
        y = player.getEyeY();
        z = player.getZ();
    }
}
