package com.creedpdf.orbitalrailgun.client.orbital;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.entity.OrbitalRailgunEntity;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The orbital uplink's 3D world: the Earth (km scale), the ARES-9 platform in low orbit and its
 * effects (metre scale), drawn in two depth passes from one camera. The screen sets the camera,
 * the platform pose and the beam each frame, then calls {@link #render}.
 *
 * <p>Frames: Earth centre at the origin, platform at {@link #STATION} km, axes shared. Platform
 * local space is metres around the platform origin; model px (x, y, z) are local (x, y, -z) / 16.</p>
 */
public final class SpaceScene {
    public static final double EARTH_R = 6371.0;
    public static final double ORBIT_R = 6771.0;
    public static final Vector3d STATION = new Vector3d(0, ORBIT_R, 0);
    public static final Vector3f SUN = new Vector3f(1.0f, 0.42f, -0.5f).normalize();
    /** Muzzle in model px: (0, 118, -316). */
    public static final Vector3f MUZZLE_LOCAL = new Vector3f(0f, 118f / 16f, 316f / 16f);

    // camera (world km) and orientation
    public final Vector3d cam = new Vector3d(STATION).add(0.08, 0.02, 0.05);
    public final Vector3f forward = new Vector3f(0, 0, -1);
    public final Vector3f up = new Vector3f(0, 1, 0);
    public float fov = 50f;
    /** Screen shake in degrees. */
    public float shake;

    // platform
    public final Quaternionf stationRot = new Quaternionf();
    public OrbitalRailgunEntity rig;
    public float time;           // seconds, for animated textures
    public float earthSpin;

    // beam (platform local metres for the near part, km for the long part)
    public boolean beamOn;
    public boolean beamTravelling;
    public float beamP;
    public double beamFrontKm;    // distance of the beam head from the muzzle
    public Loadout beamLoadout = Loadout.of(0);
    public float chargeGlow;      // 0..1 muzzle charge orb
    public float flash;           // muzzle flash 0..1

    public final List<Particle> particles = new ArrayList<>();

    public static final class Particle {
        public final Vector3f pos, vel;
        public float age, life, size, r, g, b, a;
        public boolean glint;

        public Particle(Vector3f pos, Vector3f vel, float life, float size, float r, float g, float b, float a) {
            this.pos = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    public SpaceScene() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) rig = new OrbitalRailgunEntity(RailgunMod.ORBITAL_RAILGUN, mc.world);
    }

    /** Advance particles by one tick. */
    public void tick() {
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.age += 1;
            p.pos.add(p.vel);
            p.vel.mul(0.94f);
            if (p.age >= p.life) it.remove();
        }
    }

    /** An RCS / thruster puff at a platform-local point, blowing along {@code dir}. */
    public void puff(Vector3f localPos, Vector3f dir, float strength) {
        for (int i = 0; i < 4; i++) {
            Vector3f v = new Vector3f(dir).mul(0.35f * strength * (0.7f + 0.3f * (float) Math.random()))
                    .add((float) (Math.random() - 0.5) * 0.05f, (float) (Math.random() - 0.5) * 0.05f, (float) (Math.random() - 0.5) * 0.05f);
            particles.add(new Particle(new Vector3f(localPos), v, 12 + (float) Math.random() * 8, 0.6f + 0.6f * strength, 0.92f, 0.96f, 1f, 0.55f));
        }
        Particle g = new Particle(new Vector3f(localPos), new Vector3f(), 3, 1.4f * strength, 0.7f, 0.9f, 1f, 0.9f);
        g.glint = true;
        particles.add(g);
    }

    public void sparks(Vector3f localPos, int n) {
        for (int i = 0; i < n; i++) {
            Vector3f v = new Vector3f((float) Math.random() - 0.5f, (float) Math.random() - 0.5f, (float) Math.random() - 0.5f).normalize().mul(0.25f);
            Particle p = new Particle(new Vector3f(localPos), v, 10 + (float) Math.random() * 10, 0.25f, 1f, 0.85f, 0.5f, 1f);
            p.glint = true;
            particles.add(p);
        }
    }

    public Vector3f muzzleLocal() {
        return stationRot.transform(new Vector3f(MUZZLE_LOCAL));
    }

    public Vector3f beamDir() {
        return stationRot.transform(new Vector3f(0, 0, 1)).normalize();
    }

    /** Distance (km) from the muzzle along the beam to the Earth's surface. */
    public double beamLengthKm() {
        Vector3f d = beamDir();
        Vector3f m = muzzleLocal();
        double ox = STATION.x + m.x / 1000.0, oy = STATION.y + m.y / 1000.0, oz = STATION.z + m.z / 1000.0;
        double b = ox * d.x + oy * d.y + oz * d.z;
        double c = ox * ox + oy * oy + oz * oz - EARTH_R * EARTH_R;
        double disc = b * b - c;
        if (disc < 0) return 2000.0;
        return -b - Math.sqrt(disc);
    }

    // ---------------------------------------------------------------------------------------------

    public void render(int width, int height, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float aspect = width / (float) Math.max(1, height);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorter savedSort = RenderSystem.getVertexSorting();
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        Vector3f f = new Vector3f(forward).normalize();
        Vector3f u = new Vector3f(up);
        if (Math.abs(f.dot(u.normalize())) > 0.999f) u.set(0, 0, 1);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, f.x, f.y, f.z, u.x, u.y, u.z);
        if (shake > 0) {
            view.rotateLocalX((float) Math.toRadians((Math.random() - 0.5) * shake));
            view.rotateLocalY((float) Math.toRadians((Math.random() - 0.5) * shake));
        }
        Matrix4f inv = new Matrix4f(view).transpose();
        Vector3f right = inv.transformDirection(new Vector3f(1, 0, 0));
        Vector3f camUp = inv.transformDirection(new Vector3f(0, 1, 0));

        // ---------------- pass A: stars, sun, Earth (km) ----------------
        double alt = cam.length() - EARTH_R;
        float near = (float) Math.max(0.02, Math.min(50.0, alt * 0.25));
        SpaceRenderer.projection(fov, aspect, near, 400000f);
        SpaceRenderer.clearDepth();
        SpaceRenderer.stars(view, 1f);
        Vector3f sunPos = new Vector3f(SUN).mul(150000f);
        SpaceRenderer.sprite(view, SpaceRenderer.SUN, sunPos, 24000f, right, camUp, 1f, 0.97f, 0.9f, 1f, true, false, 0f);

        Vector3f earthC = new Vector3f((float) -cam.x, (float) -cam.y, (float) -cam.z);
        Quaternionf tilt = new Quaternionf().rotationZ(0.41f);
        Quaternionf rot = new Quaternionf(tilt).mul(new Quaternionf().rotationY(earthSpin));
        Quaternionf cloudRot = new Quaternionf(tilt).mul(new Quaternionf().rotationY(earthSpin * 1.15f + 0.6f));
        SpaceRenderer.earth(view, earthC, (float) EARTH_R, rot, cloudRot, SUN);

        // long beam in km (from the platform to its head)
        Vector3f stationRel = new Vector3f((float) (STATION.x - cam.x), (float) (STATION.y - cam.y), (float) (STATION.z - cam.z));
        float stationDistKm = stationRel.length();
        if (beamOn && beamFrontKm > 2.0) {
            Vector3f m = muzzleLocal().div(1000f).add(stationRel);
            Vector3f head = new Vector3f(beamDir()).mul((float) beamFrontKm).add(m);
            float w0 = Math.max(0.004f, m.length() * 0.004f), w1 = Math.max(0.004f, head.length() * 0.004f);
            float[] c = beamColor(beamLoadout);
            SpaceRenderer.ribbon(view, m, head, w0, w1, c[0], c[1], c[2], 0.9f, 0.9f);
            SpaceRenderer.sprite(view, SpaceRenderer.HEAD, head, Math.max(0.6f, head.length() * 0.05f), right, camUp, 1f, 1f, 1f, 1f, true, false, 0f);
        }
        // platform glint when it is too small to see
        if (stationDistKm > 1.5f) {
            float s = Math.max(stationDistKm * 0.012f, 0.02f);
            SpaceRenderer.sprite(view, SpaceRenderer.GLINT, stationRel, s, right, camUp, 1f, 0.9f, 0.8f, 1f, true, true, time * 0.2f);
        }
        lensFlare(view, f, right, camUp, earthC);

        // ---------------- pass B: platform and effects (metres) ----------------
        SpaceRenderer.projection(fov, aspect, 0.05f, 60000f);
        SpaceRenderer.clearDepth();
        Vector3f rel = new Vector3f(stationRel).mul(1000f);   // platform origin relative to the camera (m)
        if (stationDistKm < 30f && rig != null) {
            VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
            MatrixStack ms = new MatrixStack();
            ms.peek().getPositionMatrix().set(view);
            ms.peek().getNormalMatrix().set(new Matrix3f(view));
            Vector3f sunV = view.transformDirection(new Vector3f(SUN)).normalize();
            Vector3f fillV = view.transformDirection(new Vector3f(0, -1, 0.2f)).normalize();
            RenderSystem.setShaderLights(sunV, fillV);

            ms.push();
            ms.translate(rel.x, rel.y, rel.z);
            ms.multiply(stationRot);
            EntityRenderer<? super OrbitalRailgunEntity> r = mc.getEntityRenderDispatcher().getRenderer(rig);
            r.render(rig, 0f, tickDelta, ms, imm, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            ms.pop();
            imm.draw();

            // beam near the platform, charge orb, flash
            Vector3f mz = muzzleLocal().add(rel);
            if (beamOn) {
                float len = (float) Math.min(beamFrontKm * 1000.0, 6000.0);
                Vector3f end = new Vector3f(beamDir()).mul(len).add(mz);
                BeamRenderer.renderSpaceBeam(imm, ms, mz, end, beamLoadout, beamP, time * 20f, 1.8f, beamTravelling && beamFrontKm < 5.5);
                imm.draw();
            }
            if (chargeGlow > 0.01f) {
                float[] c = beamColor(beamLoadout);
                SpaceRenderer.sprite(view, SpaceRenderer.HEAD, mz, 2f + 7f * chargeGlow, right, camUp, c[0], c[1], c[2], Math.min(1f, chargeGlow * 1.3f), true, true, time);
                SpaceRenderer.sprite(view, SpaceRenderer.GLINT, mz, 4f + 16f * chargeGlow, right, camUp, 1f, 1f, 1f, chargeGlow * 0.7f, true, true, -time * 0.7f);
            }
            if (flash > 0.01f) {
                SpaceRenderer.sprite(view, SpaceRenderer.HEAD, mz, 60f * flash, right, camUp, 1f, 1f, 1f, flash, true, false, 0f);
            }
            for (Particle p : particles) {
                float k = 1f - p.age / p.life;
                Vector3f pp = new Vector3f(p.pos).add(rel);
                float size = p.glint ? p.size : p.size * (1f + 2.5f * (1f - k));
                SpaceRenderer.sprite(view, p.glint ? SpaceRenderer.GLINT : SpaceRenderer.PUFF, pp, size, right, camUp,
                        p.r, p.g, p.b, p.a * k, true, true, p.age * 0.1f);
            }
        }

        // restore
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        mv.pop();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, savedSort);
        SpaceRenderer.clearDepth();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** Ghost rings between the sun and the screen centre when the sun is in view and unblocked. */
    private void lensFlare(Matrix4f view, Vector3f f, Vector3f right, Vector3f camUp, Vector3f earthC) {
        float d = SUN.dot(f);
        if (d < 0.55f) return;
        // occluded by the Earth? ray from the camera toward the sun
        float b = earthC.dot(SUN);
        float c2 = earthC.lengthSquared() - (float) (EARTH_R * EARTH_R);
        if (b > 0 && b * b - c2 > 0) return;
        float strength = (d - 0.55f) / 0.45f;
        float D = 5000f;
        Vector3f s = new Vector3f(SUN).mul(D), cdir = new Vector3f(f).mul(D);
        float[] ks = {0.35f, 0.7f, 1.2f, 1.6f};
        float[] sz = {900f, 500f, 1400f, 700f};
        for (int i = 0; i < ks.length; i++) {
            Vector3f p = new Vector3f(s).lerp(cdir, ks[i]);
            p.normalize().mul(D);
            SpaceRenderer.sprite(view, SpaceRenderer.LENS, p, sz[i], right, camUp, 0.6f, 0.75f, 1f, 0.35f * strength, true, false, 0f);
        }
    }

    public static float[] beamColor(Loadout l) {
        if (l.ammo() == null) return new float[]{1f, 0.35f, 0.25f};
        int c = l.beamColors()[1];
        return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
    }
}
