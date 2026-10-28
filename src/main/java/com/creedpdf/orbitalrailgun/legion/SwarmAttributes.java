package com.creedpdf.orbitalrailgun.legion;

import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * The swarm capacity attribute, in a class of its own: players' attributes are built during game bootstrap (see
 * {@code PlayerEntitySwarmMixin}), before the rest of Arsenal Legion is registered, so this must not pull anything
 * else in with it.
 */
public final class SwarmAttributes {
    private SwarmAttributes() {}

    public static final EntityAttribute SWARM_CAPACITY = Registry.register(Registries.ATTRIBUTE,
            new Identifier("arsenal_legion", "swarm_capacity"),
            new ClampedEntityAttribute("attribute.name.arsenal_legion.swarm_capacity", 6, 1, 24).setTracked(true));
}
