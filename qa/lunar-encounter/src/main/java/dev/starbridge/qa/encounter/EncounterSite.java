package dev.starbridge.qa.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Natural rooms from the production-worlds seed 576618501. */
record EncounterSite(
        String name, int dimension, BlockPos spawner, BlockPos chest, String keyNamespace) {
    static final EncounterSite SELECTED = select();

    private static EncounterSite select() {
        Path setting = Path.of("starbridge-encounter-site.txt");
        try {
            String id = Files.isRegularFile(setting) ? Files.readString(setting).trim() : "moon";
            return switch (id) {
                case "moon" -> new EncounterSite(
                        id,
                        -28,
                        new BlockPos(396, 27, 193),
                        new BlockPos(369, 25, 190),
                        "galacticraftcore");
                case "ceres" -> new EncounterSite(
                        id,
                        -1007,
                        new BlockPos(102, 32, 306),
                        new BlockPos(76, 30, 269),
                        "galaxyspace");
                case "venus" -> new EncounterSite(
                        id,
                        -14,
                        new BlockPos(168, 27, 894),
                        new BlockPos(187, 25, 888),
                        "extraplanets");
                default -> throw new IllegalArgumentException("Unknown test dungeon " + id);
            };
        } catch (IOException badFile) {
            throw new IllegalStateException("Cannot read encounter fixture", badFile);
        }
    }

    String path() {
        return "legacy_dim_" + dimension;
    }

    boolean isKey(ResourceLocation id) {
        return id != null && keyNamespace.equals(id.getNamespace()) && id.getPath().contains("key");
    }
}
