package com.creedpdf.orbitalrailgun.mixin;

import com.creedpdf.orbitalrailgun.hangeki.TimeScale;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * HANGEKI's slow motion: the server's tick loop schedules one tick every 50 ms; while a parry slows time the gap is
 * stretched to 50 / scale ms, so the whole world (mobs, projectiles, physics) really does run slower - nothing has to
 * be faked per entity. {@link TimeScale#server} is 1 the rest of the time. Clients get the same scale in a packet and
 * stretch their own tick clock to match (client mixin on RenderTickCounter).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTimeMixin {
    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 50L), require = 0)
    private long orbitalRailgun$tickLength(long ms) {
        float s = TimeScale.server;
        return s >= 0.999f ? ms : Math.max(ms, Math.round(ms / Math.max(0.05f, s)));
    }
}
