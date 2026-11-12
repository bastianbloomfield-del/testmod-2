package com.endlessexpress;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class TrainInteractionHandler {
    public enum Role { NICE, NAUGHTY, NEUTRAL }

    private static final Map<UUID, Role> dailyRoles = new HashMap<>();
    private static final Set<UUID> ateTonight = new HashSet<>();
    private static long lastDay = -1;

    public static void registerInteractions() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            TrainStateData state = TrainStateData.get(sp.getServer());
            ServerLevel level = sp.level();

            if (pos.equals(TrainStructureBuilder.ENGINE_CORE_POS)) {
                if (state.getPhase() == TrainStateData.Phase.PROLOGUE) {
                    TrainMovementController.startTrain(sp.getServer(), level, state);
                } else {
                    sp.sendSystemMessage(Component.literal("§bThe Polar Express engine is already running!"), true);
                }
                return InteractionResult.SUCCESS;
            }

            if (pos.equals(TrainStructureBuilder.EXIT_DOOR_POS)) {
                disembarkToStation(level, sp, state);
                return InteractionResult.SUCCESS;
            }

            if (world.getBlockState(pos).is(Blocks.CAMPFIRE) && !TrainStructureBuilder.getFullTrainBounds(state).contains(sp.position())) {
                boardTrain(level, sp, state);
                return InteractionResult.SUCCESS;
            }

            if (pos.equals(TrainStructureBuilder.BOILER_CORE_POS)) {
                handleCarriageTask(sp, state, "BOILER");
                return InteractionResult.SUCCESS;
            }

            if (pos.equals(TrainStructureBuilder.FUEL_CORE_POS)) {
                handleCarriageTask(sp, state, "FUEL");
                return InteractionResult.SUCCESS;
            }

            if (pos.equals(TrainStructureBuilder.GREENHOUSE_CORE_POS)) {
                handleCarriageTask(sp, state, "GREENHOUSE");
                return InteractionResult.SUCCESS;
            }

            if (TrainStructureBuilder.DINING_CAR_BOX.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) &&
                (world.getBlockState(pos).is(Blocks.DARK_OAK_PRESSURE_PLATE) || world.getBlockState(pos).is(Blocks.SPRUCE_FENCE))) {
                handleDine(sp);
                return InteractionResult.SUCCESS;
            }

            if (pos.equals(TrainMovementController.BONFIRE_POS) && state.getPhase() == TrainStateData.Phase.CRASHED_SIEGE) {
                feedBonfire(sp, state);
                return InteractionResult.SUCCESS;
            }

            return InteractionResult.PASS;
        });
    }

    public static void handleCarriageTask(ServerPlayer player, TrainStateData state, String system) {
        ItemStack held = player.getMainHandItem();
        int baseVal = switch (system) {
            case "BOILER" -> (held.is(Items.COAL) || held.is(Items.IRON_INGOT) || held.is(Items.BLAZE_POWDER)) ? 8 : 0;
            case "FUEL" -> (held.is(Items.COAL) || held.is(Items.CHARCOAL) || held.is(Items.WATER_BUCKET)) ? 6 : 0;
            case "GREENHOUSE" -> (held.is(Items.WHEAT) || held.is(Items.CARROT) || held.is(Items.BONE_MEAL)) ? 6 : 0;
            default -> 0;
        };

        if (baseVal <= 0) {
            player.sendSystemMessage(Component.literal("§cHold valid items! Boiler: Coal/Iron | Fuel: Coal/Water Bucket | Greenhouse: Wheat/Carrots/Bonemeal"), true);
            return;
        }

        int total = baseVal * held.getCount();
        if (getRole(player.getUUID()) == Role.NICE) total = (int) (total * 1.5);
        held.setCount(0);
        state.repairSystem(system, total);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.7f, 1.2f);
        player.sendSystemMessage(Component.literal("§a✔ Restored +" + total + "% to " + system + "!"), true);
    }

    public static void handleDine(ServerPlayer player) {
        if (!TrainStructureBuilder.DINING_CAR_BOX.contains(player.position())) {
            player.sendSystemMessage(Component.literal("§cYou must be inside the Dining Room Carriage!"), true);
            return;
        }
        if (ateTonight.contains(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§eYou already ate dinner and claimed tonight's gift!"), true);
            return;
        }
        ateTonight.add(player.getUUID());
        player.getFoodData().eat(8, 0.8f);
        TrainStateData.get(player.getServer()).repairSystem("DINING", 10);

        ItemStack gift = switch (getRole(player.getUUID())) {
            case NICE -> new ItemStack(Items.GOLDEN_APPLE, 1);
            case NAUGHTY -> new ItemStack(Items.COAL_BLOCK, 2);
            case NEUTRAL -> new ItemStack(Items.IRON_INGOT, 4);
        };
        player.getInventory().add(gift);
        player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.2f);
        player.sendSystemMessage(Component.literal("§a🎁 Dinner complete! You received tonight's holiday gift!"), false);
    }

    public static void feedBonfire(ServerPlayer player, TrainStateData state) {
        ItemStack held = player.getMainHandItem();
        int pts = (held.is(Items.COAL) || held.is(Items.OAK_LOG) || held.is(Items.SPRUCE_LOG)) ? 5 :
                  (held.is(Items.COAL_BLOCK) ? 45 : (held.is(Items.DIAMOND) ? 50 : 0));
        if (pts <= 0) {
            player.sendSystemMessage(Component.literal("§cHold Logs, Coal, Coal Blocks, or Diamonds to feed the Signal Bonfire!"), true);
            return;
        }
        int added = pts * held.getCount();
        held.setCount(0);
        state.addBonfireFuel(added);
        player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§6🔥 " + player.getName().getString() + " added +" + added + " fuel to the Signal Bonfire!"), false
        );
    }

    public static void disembarkToStation(ServerLevel level, ServerPlayer player, TrainStateData state) {
        if (state.getPhase() != TrainStateData.Phase.STOPPED && state.getPhase() != TrainStateData.Phase.PROLOGUE) {
            player.sendSystemMessage(Component.literal("§cThe train is moving at full speed! Wait for the station stop."), true);
            return;
        }
        int stopZ = -state.getStationNumber() * 600;
        level.getChunk(0, stopZ >> 4);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, stopZ);
        BlockPos landing = new BlockPos(0, surfaceY, stopZ);

        level.setBlockAndUpdate(landing.below(), Blocks.CAMPFIRE.defaultBlockState());
        player.teleportTo(level, landing.getX() + 0.5, landing.getY() + 1.0, landing.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal("§b❄ Stepped out at Station #" + state.getStationNumber() + "! Right-click the campfire or run §e/express board §bto return!"), false);
    }

    public static void boardTrain(ServerLevel level, ServerPlayer player, TrainStateData state) {
        if (state.getPhase() == TrainStateData.Phase.MOVING) {
            player.sendSystemMessage(Component.literal("§cThe train already departed! You were left behind!"), true);
            return;
        }
        BlockPos home = TrainStructureBuilder.buildPlayerCarriage(level, player, state);
        player.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal("§aSafely back aboard your train carriage!"), true);
    }

    public static void tickDailyTeams(MinecraftServer server, ServerLevel level) {
        long dayTime = level.getDayTime();
        long day = dayTime / 24000L;
        long timeOfDay = dayTime % 24000L;

        if (day != lastDay) {
            lastDay = day;
            ateTonight.clear();
            List<ServerPlayer> list = new ArrayList<>(server.getPlayerList().getPlayers());
            Collections.shuffle(list);
            dailyRoles.clear();

            for (int i = 0; i < list.size(); i++) {
                ServerPlayer p = list.get(i);
                Role role = switch (i % 3) {
                    case 0 -> Role.NICE;
                    case 1 -> Role.NAUGHTY;
                    default -> Role.NEUTRAL;
                };
                dailyRoles.put(p.getUUID(), role);
                switch (role) {
                    case NICE -> {
                        p.sendSystemMessage(Component.literal("§a❄ TEAM NICE: §fHaste I + 1.5x task repair bonus!"), false);
                        p.addEffect(new MobEffectInstance(MobEffects.HASTE, 24000, 0, true, false));
                    }
                    case NAUGHTY -> {
                        p.sendSystemMessage(Component.literal("§c😈 TEAM NAUGHTY: §fSpeed I + §e/express siphon §fto steal fuel!"), false);
                        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 24000, 0, true, false));
                    }
                    case NEUTRAL -> {
                        p.sendSystemMessage(Component.literal("§e⚖ TEAM NEUTRAL: §fResistance I to scout stations & defend the train."), false);
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 24000, 0, true, false));
                    }
                }
            }
        }

        if (timeOfDay == 13000L) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§6🔔 [DINING CAR] §fDinner is served! Click the Dining Table or run §e/express dine §ffor food & a gift!"), false
            );
        }

        if (timeOfDay == 23800L) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ateTonight.contains(p.getUUID())) {
                    p.sendSystemMessage(Component.literal("§cYou skipped dinner in the Dining Car! You feel weak and cold."), false);
                    p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 6000, 0));
                }
            }
        }
    }

    public static Role getRole(UUID uuid) {
        return dailyRoles.getOrDefault(uuid, Role.NEUTRAL);
    }
}
