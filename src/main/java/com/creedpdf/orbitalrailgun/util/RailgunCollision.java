package com.creedpdf.orbitalrailgun.util;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.block.RailgunColliderBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Places / removes the invisible collider blocks that give the deployed railgun its shape.
 * The heightfield ({@code orbital_railgun_collision.json}) is generated from the Blender model by
 * tools/blender/build_models.py: per block column (dx, dz) around the railgun, the height of the
 * fortress / stairs / turret base in model pixels.
 */
public final class RailgunCollision {
    private RailgunCollision() {}

    private record Column(int dx, int dz, int heightPx) {}

    private static List<Column> columns;

    private static List<Column> columns() {
        if (columns == null) {
            List<Column> out = new ArrayList<>();
            try (InputStream in = RailgunCollision.class.getResourceAsStream("/orbital_railgun_collision.json")) {
                if (in != null) {
                    JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonArray("columns");
                    for (JsonElement e : arr) {
                        JsonArray c = e.getAsJsonArray();
                        out.add(new Column(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()));
                    }
                } else {
                    RailgunMod.LOGGER.error("orbital_railgun_collision.json missing; railgun will have no collision");
                }
            } catch (Exception e) {
                RailgunMod.LOGGER.error("Failed to read railgun collision map", e);
            }
            columns = out;
        }
        return columns;
    }

    /** Fills empty/replaceable space in the footprint with colliders. */
    public static void place(ServerWorld world, BlockPos origin) {
        for (Column c : columns()) {
            int full = c.heightPx / 16;
            int rest = c.heightPx % 16;
            int layers = full + (rest > 0 ? 1 : 0);
            for (int i = 0; i < layers; i++) {
                int h = i < full ? 16 : rest;
                BlockPos pos = origin.add(c.dx, i, c.dz);
                BlockState existing = world.getBlockState(pos);
                if (existing.isAir() || existing.isReplaceable()) {
                    world.setBlockState(pos, RailgunMod.RAILGUN_COLLIDER.getDefaultState()
                            .with(RailgunColliderBlock.HEIGHT, Math.max(1, h)), 2);
                }
            }
        }
    }

    /** Removes every collider in the footprint. */
    public static void remove(ServerWorld world, BlockPos origin) {
        for (Column c : columns()) {
            int layers = (c.heightPx + 15) / 16;
            for (int i = 0; i < layers + 1; i++) {
                BlockPos pos = origin.add(c.dx, i, c.dz);
                if (world.getBlockState(pos).isOf(RailgunMod.RAILGUN_COLLIDER)) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                }
            }
        }
    }
}
