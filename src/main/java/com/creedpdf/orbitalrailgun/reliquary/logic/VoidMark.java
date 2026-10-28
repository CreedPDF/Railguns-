package com.creedpdf.orbitalrailgun.reliquary.logic;

import net.minecraft.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The flag a Void-Carver cut leaves on everything it crossed: which rift it belongs to and until when. Held in a weak
 * map keyed by the entity itself (a server-side memory object, never saved), so marks vanish with their entities.
 */
public record VoidMark(int riftId, long until) {
    private static final Map<Entity, VoidMark> MARKS = new WeakHashMap<>();

    public static void put(Entity e, VoidMark m) {
        MARKS.put(e, m);
    }

    public static boolean marked(Entity e, int riftId, long now) {
        VoidMark m = MARKS.get(e);
        return m != null && m.riftId == riftId && now <= m.until;
    }

    public static void clear() {
        MARKS.clear();
    }
}
