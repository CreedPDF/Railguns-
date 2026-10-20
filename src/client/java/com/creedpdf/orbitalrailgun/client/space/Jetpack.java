package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.EvaSuit;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * EVA jetpack flight (client side; the server just accepts the movement).
 * <ul>
 *   <li>In space: true six-axis flight with inertia. W/S thrust along your view, A/D strafe,
 *       Space up, Shift down, Sprint for the main engines. Let go of everything and the
 *       stabilisers slowly kill your drift; hold Space + Shift to brake hard.</li>
 *   <li>In gravity (Earth, planets): hold Space in the air to fly, Sprint for speed, Shift
 *       to hover-descend. Earth's atmosphere caps how high the thrusters can take you.</li>
 * </ul>
 */
public final class Jetpack {
    private Jetpack() {}

    public static final double EARTH_CEILING = 450;
    private static final double SPACE_MAX = 3.0, SPACE_BOOST_MAX = 8.0;

    private static ThrustSound sound;
    private static float thrust;      // 0..1 for sound/particles
    private static boolean boosting;
    private static boolean wasThrusting;

    static void tick(MinecraftClient client, ClientPlayerEntity p, ClientWorld w) {
        JetFlameFx.tickCleanup(w);
        otherPlayers(w, p);
        if (!EvaSuit.jetpack(p) || p.isSpectator() || p.getAbilities().flying || p.hasVehicle()) {
            thrust = 0f;
            return;
        }
        boolean jump = client.options.jumpKey.isPressed();
        boolean sneak = client.options.sneakKey.isPressed();
        boolean sprint = client.options.sprintKey.isPressed();
        float fwd = p.input.movementForward, side = p.input.movementSideways;
        Vec3d v = p.getVelocity();
        float target = 0f;
        boosting = false;

        if (SpaceDims.isSpace(w)) {
            // undo vanilla air drag so momentum is conserved
            if (!p.isOnGround()) v = new Vec3d(v.x / 0.91, v.y / 0.98, v.z / 0.91);
            Vec3d look = p.getRotationVec(1f);
            Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
            right = right.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : right.normalize();
            Vec3d up = right.crossProduct(look).normalize();
            Vec3d dir = look.multiply(fwd).add(right.multiply(-side));
            if (jump && !sneak) dir = dir.add(up);
            if (sneak && !jump) dir = dir.subtract(up);
            boolean input = dir.lengthSquared() > 1e-4;
            if (jump && sneak) {
                v = v.multiply(0.86);
                target = 0.6f;
            } else if (input) {
                boosting = sprint && fwd > 0;
                double acc = boosting ? 0.09 : 0.03;
                v = v.add(dir.normalize().multiply(acc));
                double max = boosting ? SPACE_BOOST_MAX : Math.max(SPACE_MAX, Math.min(v.length(), SPACE_BOOST_MAX));
                if (v.length() > max) v = v.normalize().multiply(max);
                target = boosting ? 1f : 0.5f;
            } else if (v.length() < SPACE_MAX) {
                v = v.multiply(0.985);
            }
            p.setVelocity(v);
        } else if (!p.isOnGround() || jump) {
            float g = SpaceDims.gravity(w);
            double pull = 0.08 * g;
            if (jump && !p.isOnGround()) {
                boolean capped = w.getRegistryKey() == net.minecraft.world.World.OVERWORLD && p.getY() > EARTH_CEILING;
                double lift = capped ? pull * 0.98 : pull + (sprint ? 0.09 : 0.06);
                v = new Vec3d(v.x, Math.min(sprint ? 1.2 : 0.75, v.y + lift), v.z);
                target = sprint ? 1f : 0.7f;
                boosting = sprint;
            } else if (sneak && !p.isOnGround() && v.y < -0.12) {
                v = new Vec3d(v.x, Math.min(-0.12, v.y + pull + 0.05), v.z);
                target = 0.45f;
            }
            if (target > 0 && (fwd != 0 || side != 0)) {
                float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
                double acc = sprint ? 0.07 : 0.035;
                double fx = -MathHelper.sin(yaw) * fwd + MathHelper.cos(yaw) * side;
                double fz = MathHelper.cos(yaw) * fwd + MathHelper.sin(yaw) * side;
                v = v.add(fx * acc, 0, fz * acc);
                double h = Math.sqrt(v.x * v.x + v.z * v.z), max = sprint ? 1.6 : 0.8;
                if (h > max) v = new Vec3d(v.x / h * max, v.y, v.z / h * max);
            }
            if (target > 0) {
                p.setVelocity(v);
                p.fallDistance = 0;
            }
        }

        thrust += (target - thrust) * 0.3f;
        boolean thrusting = target > 0.05f;
        if (thrusting && !wasThrusting) p.playSound(ModSounds.JETPACK_IGNITE, 0.8f, 1f);
        if (boosting && p.age % 30 == 0) p.playSound(ModSounds.JETPACK_BOOST, 0.6f, 1f);
        wasThrusting = thrusting;
        if (thrust > 0.05f) {
            if (sound == null || sound.isDone()) {
                sound = new ThrustSound(p);
                client.getSoundManager().play(sound);
            }
            flames(p, thrust, boosting);
        }
    }

    /** The exhaust: two 3D blue flames, drawn by {@link JetFlameFx}. */
    private static void flames(PlayerEntity p, float t, boolean boost) {
        JetFlameFx.set(p, t, boost);
    }

    /** Other players flying jetpacks get flames too (guessed from their motion). */
    private static void otherPlayers(ClientWorld w, PlayerEntity self) {
        for (PlayerEntity o : w.getPlayers()) {
            if (o == self || !EvaSuit.jetpack(o) || o.isOnGround()) continue;
            Vec3d v = o.getVelocity();
            boolean flying = SpaceDims.isSpace(w) ? v.lengthSquared() > 0.04 : v.y > -0.25;
            if (flying) flames(o, 0.6f, false);
        }
    }

    public static float thrust() {
        return thrust;
    }

    public static boolean boosting() {
        return boosting;
    }

    private static final class ThrustSound extends MovingSoundInstance {
        private final PlayerEntity player;

        ThrustSound(PlayerEntity player) {
            super(ModSounds.JETPACK_THRUST, SoundCategory.PLAYERS, SoundInstance.createRandom());
            this.player = player;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.01f;
        }

        @Override
        public void tick() {
            if (player.isRemoved() || thrust < 0.02f) {
                setDone();
                return;
            }
            this.x = player.getX();
            this.y = player.getY() + 1;
            this.z = player.getZ();
            boolean vacuum = SpaceDims.isSpace(player.getWorld());
            // in vacuum you only hear it through the suit
            this.volume = (vacuum ? 0.45f : 0.9f) * thrust;
            this.pitch = 0.85f + 0.35f * thrust + (boosting ? 0.15f : 0f);
        }
    }
}
