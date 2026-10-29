package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.hangeki.TimeScale;

/**
 * The client half of the slow motion: the client's tick clock (RenderTickCounter) runs at {@link TimeScale#client},
 * which eases toward the scale the server announced (quickly into slow motion, near-instantly back out).
 */
public final class HangekiTime {
    private HangekiTime() {}

    private static long last = -1;

    /** Advance the eased scale to `now` (real ms) and return it. Called once per frame by the tick counter. */
    public static float frame(long now) {
        float target = TimeScale.clientTarget, cur = TimeScale.client;
        if (last < 0) last = now;
        float dt = Math.min(0.25f, (now - last) / 1000f);
        last = now;
        if (cur != target) {
            float rate = target < cur ? 9f : 30f;
            cur += (target - cur) * (1f - (float) Math.exp(-rate * dt));
            if (Math.abs(target - cur) < 0.004f) cur = target;
            TimeScale.client = cur;
        }
        return cur;
    }
}
