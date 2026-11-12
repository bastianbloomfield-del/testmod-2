package com.endlessexpress;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public class TrainStructureBuilder {
    public static final int CAR_LENGTH = 16;

    public static final BlockPos ENGINE_CORE_POS = new BlockPos(0, 101, -84);
    public static final BlockPos EXIT_DOOR_POS = new BlockPos(-3, 101, -80);
    public static final BlockPos BOILER_CORE_POS = new BlockPos(0, 101, -64);
    public static final BlockPos FUEL_CORE_POS = new BlockPos(0, 101, -46);
    public static final BlockPos GREENHOUSE_CORE_POS = new BlockPos(0, 101, -33);
    public static final AABB DINING_CAR_BOX = new AABB(-4, 100, -18, 4, 106, -3);

    public static void registerHullProtection() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return true;
            TrainStateData data = TrainStateData.get(world.getServer());
            if (data.getPhase() == TrainStateData.Phase.CRASHED_SIEGE) return true;

            int maxZ = (data.getTotalPlayerCarriages() + 2) * (CAR_LENGTH + 2);
            if (pos.getX() >= -4 && pos.getX() <= 4 &&
                pos.getY() >= 100 && pos.getY() <= 106 &&
                pos.getZ() >= -92 && pos.getZ() <= maxZ) {

                if (pos.getZ() < 15) {
                    player.sendSystemMessage(Component.literal("§cYou cannot break the main train carriages!"), true);
                    return false;
                }

                boolean insideInterior = pos.getX() >= -3 && pos.getX() <= 3 &&
                                         pos.getY() >= 101 && pos.getY() <= 105;
                int localZ = Math.floorMod(pos.getZ(), CAR_LENGTH + 2);
                boolean isEndWall = (localZ == 0 || localZ >= CAR_LENGTH - 1);

                if (!insideInterior || isEndWall) {
                    player.sendSystemMessage(Component.literal("§cYou cannot break your carriage's outer hull!"), true);
                    return false;
                }
            }
            return true;
        });
    }

    public static void ensureMainTrainBuilt(ServerLevel level) {
        if (level.getBlockState(new BlockPos(0, 100, -85)).is(Blocks.NETHERITE_BLOCK)) return;

        // 1. Front Locomotive (Z: -92 to -75)
        buildShell(level, -92, -75, Blocks.NETHERITE_BLOCK, Blocks.RED_NETHER_BRICKS, Blocks.BLACKSTONE);
        level.setBlockAndUpdate(ENGINE_CORE_POS, Blocks.SMOKER.defaultBlockState());
        level.setBlockAndUpdate(EXIT_DOOR_POS, Blocks.OAK_DOOR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(0, 105, -84), Blocks.SOUL_LANTERN.defaultBlockState());

        // 2. 4 Shared Carriages (Z: -72 to -3)
        for (int i = 0; i < 4; i++) {
            int startZ = -72 + (i * 18);
            int endZ = startZ + 15;
            buildShell(level, startZ, endZ, Blocks.IRON_BLOCK,
                    (i == 2 ? Blocks.GLASS : Blocks.COPPER_BLOCK), Blocks.DEEPSLATE_BRICKS);

            switch (i) {
                case 0 -> { // Boiler Room
                    level.setBlockAndUpdate(BOILER_CORE_POS, Blocks.BLAST_FURNACE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(-2, 101, startZ + 8), Blocks.MAGMA_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(2, 101, startZ + 8), Blocks.MAGMA_BLOCK.defaultBlockState());
                }
                case 1 -> { // Fuel & Tanks Carriage
                    level.setBlockAndUpdate(FUEL_CORE_POS, Blocks.HOPPER.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(-3, 101, startZ + 4), Blocks.WATER_CAULDRON.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(3, 101, startZ + 4), Blocks.LAVA_CAULDRON.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(-3, 101, startZ + 11), Blocks.WATER_CAULDRON.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(3, 101, startZ + 11), Blocks.LAVA_CAULDRON.defaultBlockState());
                }
                case 2 -> { // Food Storage & Greenhouse Carriage
                    level.setBlockAndUpdate(GREENHOUSE_CORE_POS, Blocks.BARREL.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(3, 101, startZ + 3), Blocks.BARREL.defaultBlockState());
                    for (int gz = startZ + 6; gz <= startZ + 12; gz++) {
                        level.setBlockAndUpdate(new BlockPos(-2, 100, gz), Blocks.FARMLAND.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(-2, 101, gz), Blocks.WHEAT.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(2, 100, gz), Blocks.FARMLAND.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(2, 101, gz), Blocks.CARROTS.defaultBlockState());
                    }
                    level.setBlockAndUpdate(new BlockPos(0, 100, startZ + 9), Blocks.WATER.defaultBlockState());
                }
                case 3 -> { // Dining Room Carriage
                    for (int dz = startZ + 3; dz <= startZ + 12; dz++) {
                        level.setBlockAndUpdate(new BlockPos(0, 101, dz), Blocks.SPRUCE_FENCE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(0, 102, dz), Blocks.DARK_OAK_PRESSURE_PLATE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(-2, 101, dz), Blocks.SPRUCE_STAIRS.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(2, 101, dz), Blocks.SPRUCE_STAIRS.defaultBlockState());
                    }
                    level.setBlockAndUpdate(new BlockPos(0, 105, startZ + 8), Blocks.LANTERN.defaultBlockState());
                }
            }
        }
    }

    public static BlockPos buildPlayerCarriage(ServerLevel level, ServerPlayer player, TrainStateData state) {
        int carIndex = state.getOrAssignCarriageIndex(player.getUUID());
        int startZ = carIndex * (CAR_LENGTH + 2);
        int endZ = startZ + CAR_LENGTH - 1;

        if (!level.getBlockState(new BlockPos(0, 100, startZ + 1)).is(Blocks.DARK_OAK_PLANKS)) {
            buildShell(level, startZ, endZ, Blocks.DARK_OAK_PLANKS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.SPRUCE_LOG);
            level.setBlockAndUpdate(new BlockPos(0, 105, startZ + 8), Blocks.LANTERN.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(-3, 101, startZ + 2), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(3, 101, startZ + 2), Blocks.CRAFTING_TABLE.defaultBlockState());
        }
        return new BlockPos(0, 101, startZ + 8);
    }

    private static void buildShell(ServerLevel level, int startZ, int endZ, Block floor, Block roof, Block wall) {
        for (int z = startZ - 2; z < startZ; z++) {
            level.setBlockAndUpdate(new BlockPos(0, 100, z), Blocks.IRON_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(-1, 101, z), Blocks.IRON_BARS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(1, 101, z), Blocks.IRON_BARS.defaultBlockState());
        }

        for (int x = -4; x <= 4; x++) {
            for (int y = 100; y <= 106; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (y == 100) {
                        level.setBlockAndUpdate(pos, floor.defaultBlockState());
                    } else if (y == 106) {
                        level.setBlockAndUpdate(pos, roof.defaultBlockState());
                    } else if (z == startZ || z == endZ) {
                        level.setBlockAndUpdate(pos, (x == 0 && (y == 101 || y == 102) ? Blocks.AIR : wall).defaultBlockState());
                    } else if (x == -4 || x == 4) {
                        level.setBlockAndUpdate(pos, (y == 103 && z % 3 != 0 ? Blocks.GLASS_PANE : wall).defaultBlockState());
                    } else {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    public static AABB getFullTrainBounds(TrainStateData state) {
        int maxZ = (state.getTotalPlayerCarriages() + 2) * (CAR_LENGTH + 2);
        return new AABB(-8, 98, -94, 8, 110, maxZ);
    }
}
