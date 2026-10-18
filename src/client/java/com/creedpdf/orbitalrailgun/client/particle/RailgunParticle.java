package com.creedpdf.orbitalrailgun.client.particle;

import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;

/**
 * One configurable particle class backs every custom particle type. A {@link Style} describes
 * lifetime, size, motion, how the sprite frames are chosen, colour fade and whether it is
 * full-bright (self-lit plasma) or lit by the world (smoke, debris).
 */
public class RailgunParticle extends SpriteBillboardParticle {

    /** Immutable behaviour preset. */
    public record Style(
            int minLife, int maxLife,
            float minSize, float maxSize,
            float growth,          // size multiplier reached at end of life
            float gravity,
            float friction,
            boolean collides,
            boolean fullBright,
            boolean animateByAge,  // false = random fixed frame (e.g. debris variants)
            boolean loopFrames,    // cycle frames repeatedly instead of once over the lifetime
            float alphaStart, float alphaEnd,
            float spin             // max random rotation speed, radians/tick
    ) {}

    private final SpriteProvider sprites;
    private final Style style;
    private final float baseScale;
    private final float spinSpeed;

    public RailgunParticle(ClientWorld world, double x, double y, double z, double vx, double vy, double vz,
                           SpriteProvider sprites, Style style) {
        super(world, x, y, z, vx, vy, vz);
        this.sprites = sprites;
        this.style = style;
        // super() adds random jitter to the velocity; use exactly what the spawner asked for.
        this.velocityX = vx;
        this.velocityY = vy;
        this.velocityZ = vz;
        this.maxAge = style.minLife() + random.nextInt(Math.max(1, style.maxLife() - style.minLife() + 1));
        this.baseScale = style.minSize() + random.nextFloat() * (style.maxSize() - style.minSize());
        this.scale = baseScale;
        this.gravityStrength = style.gravity();
        this.velocityMultiplier = style.friction();
        this.collidesWithWorld = style.collides();
        this.alpha = style.alphaStart();
        this.spinSpeed = (random.nextFloat() - 0.5f) * 2f * style.spin();
        this.angle = this.prevAngle = random.nextFloat() * MathHelper.TAU;
        if (style.animateByAge()) setSpriteForAge(sprites);
        else setSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (dead) return;
        float life = (float) age / maxAge;
        this.scale = baseScale * MathHelper.lerp(life, 1f, style.growth());
        this.alpha = MathHelper.lerp(life, style.alphaStart(), style.alphaEnd());
        this.prevAngle = angle;
        this.angle += spinSpeed;
        if (style.animateByAge()) {
            if (style.loopFrames()) {
                setSprite(sprites.getSprite(age % 8, 8));
            } else {
                setSpriteForAge(sprites);
            }
        }
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getBrightness(float tint) {
        return style.fullBright() ? 0xF000F0 : super.getBrightness(tint);
    }
}
