package com.creedpdf.orbitalrailgun.client.legion.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * {@code arsenal_legion:kinetic_gaze}. The velocity passed with the particle chooses its form:
 * <ul>
 *   <li>(almost) zero - a faint humming distortion: a warped ring of disturbed air that ripples outward and fades
 *   (four sprite frames), left behind an orbiting weapon;</li>
 *   <li>a fast velocity - a bright piercing energy streak lying along that direction (length grows with the speed),
 *   left behind a striking weapon. It does not move; the velocity only gives it its direction and length.</li>
 * </ul>
 */
public class KineticGazeParticle extends SpriteBillboardParticle {
    private final SpriteProvider sprites;
    private final boolean streak;
    private final Vector3f dir;
    private final float len;

    KineticGazeParticle(ClientWorld world, double x, double y, double z, double vx, double vy, double vz, SpriteProvider sprites) {
        super(world, x, y, z);
        this.sprites = sprites;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        this.streak = speed > 0.5;
        this.gravityStrength = 0;
        this.collidesWithWorld = false;
        if (streak) {
            dir = new Vector3f((float) (vx / speed), (float) (vy / speed), (float) (vz / speed));
            len = (float) Math.min(3.2, 0.6 + speed * 0.45);
            velocityX = velocityY = velocityZ = 0;
            maxAge = 7;
            scale = 0.11f;
            setColor(1f, 0.5f, 0.24f);
            alpha = 0.95f;
            setSprite(sprites.getSprite(1, 1));          // the last sprite: kinetic_streak
        } else {
            dir = new Vector3f(0, 1, 0);
            len = 0;
            velocityX = vx + (random.nextDouble() - 0.5) * 0.01;
            velocityY = vy + 0.004;
            velocityZ = vz + (random.nextDouble() - 0.5) * 0.01;
            maxAge = 14 + random.nextInt(8);
            scale = 0.18f + random.nextFloat() * 0.08f;
            setColor(1f, 0.82f, 0.66f);
            alpha = 0.22f;
            setSprite(sprites.getSprite(0, 4));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (streak) {
            alpha = 0.95f * (1f - age / (float) maxAge);
        } else {
            float k = age / (float) maxAge;
            scale *= 1.045f;
            alpha = 0.22f * (1f - k);
            setSprite(sprites.getSprite(Math.min(3, (int) (k * 4)), 4));
            velocityX *= 0.9;
            velocityY *= 0.9;
            velocityZ *= 0.9;
        }
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getBrightness(float tint) {
        return 0xF000F0;
    }

    @Override
    public void buildGeometry(VertexConsumer vc, Camera camera, float tickDelta) {
        if (!streak) {
            super.buildGeometry(vc, camera, tickDelta);
            return;
        }
        // a ribbon from the tail to the head, turned about its axis to face the camera
        Vec3d c = camera.getPos();
        float hx = (float) (MathHelper.lerp(tickDelta, prevPosX, x) - c.x);
        float hy = (float) (MathHelper.lerp(tickDelta, prevPosY, y) - c.y);
        float hz = (float) (MathHelper.lerp(tickDelta, prevPosZ, z) - c.z);
        Vector3f head = new Vector3f(hx, hy, hz);
        Vector3f tail = new Vector3f(dir).mul(-len).add(head);
        Vector3f mid = new Vector3f(head).add(tail).mul(0.5f);
        Vector3f side = new Vector3f(dir).cross(mid);
        if (side.lengthSquared() < 1e-8f) return;
        side.normalize().mul(scale);
        float u0 = getMinU(), u1 = getMaxU(), v0 = getMinV(), v1 = getMaxV();
        int l = getBrightness(tickDelta);
        put(vc, new Vector3f(tail).sub(side), u0, v1, l);
        put(vc, new Vector3f(head).sub(side), u1, v1, l);
        put(vc, new Vector3f(head).add(side), u1, v0, l);
        put(vc, new Vector3f(tail).add(side), u0, v0, l);
    }

    private void put(VertexConsumer vc, Vector3f p, float u, float v, int light) {
        vc.vertex(p.x, p.y, p.z).texture(u, v).color(red, green, blue, alpha).light(light).next();
    }

    public static final class Factory implements ParticleFactory<DefaultParticleType> {
        private final SpriteProvider sprites;

        public Factory(SpriteProvider sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(DefaultParticleType type, ClientWorld world, double x, double y, double z, double vx, double vy, double vz) {
            return new KineticGazeParticle(world, x, y, z, vx, vy, vz, sprites);
        }
    }
}
