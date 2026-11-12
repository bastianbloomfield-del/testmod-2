package com.endlessexpress;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

public class EndlessExpressMod implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        TrainStructureBuilder.registerHullProtection();
        TrainInteractionHandler.registerInteractions();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            ServerLevel level = server.overworld();
            TrainStateData state = TrainStateData.get(server);

            TrainStructureBuilder.ensureMainTrainBuilt(level);
            BlockPos carHome = TrainStructureBuilder.buildPlayerCarriage(level, player, state);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(level.dimension(), carHome, 0.0f, true), false);

            if (state.getPhase() == TrainStateData.Phase.PROLOGUE &&
                player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME)) < 40) {
                RandomSource r = level.getRandom();
                int sx = r.nextIntBetweenInclusive(-500, 500);
                int sz = r.nextIntBetweenInclusive(250, 650);
                int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, sx, sz);
                player.teleportTo(level, sx + 0.5, sy + 1.0, sz + 0.5, java.util.Set.of(), 0, 0, false);
                player.sendSystemMessage(Component.literal("§e❄ Find the Polar Express at (0, 100, 0) and gather gear before it starts!"), false);
            } else if (state.getLives(player.getUUID()) <= 0) {
                player.setGameMode(GameType.SPECTATOR);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(TrainMovementController::tick);

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                TrainMovementController.onDeath(player);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("express")
                    .then(Commands.literal("start").executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        TrainStateData state = TrainStateData.get(p.getServer());
                        if (state.getPhase() == TrainStateData.Phase.PROLOGUE) {
                            TrainMovementController.startTrain(p.getServer(), p.level(), state);
                        }
                        return 1;
                    }))
                    .then(Commands.literal("exit").executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        TrainInteractionHandler.disembarkToStation(p.level(), p, TrainStateData.get(p.getServer()));
                        return 1;
                    }))
                    .then(Commands.literal("board").executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        TrainInteractionHandler.boardTrain(p.level(), p, TrainStateData.get(p.getServer()));
                        return 1;
                    }))
                    .then(Commands.literal("dine").executes(ctx -> {
                        TrainInteractionHandler.handleDine(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                    .then(Commands.literal("task").then(Commands.argument("carriage", StringArgumentType.word()).executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        TrainInteractionHandler.handleCarriageTask(p, TrainStateData.get(p.getServer()), StringArgumentType.getString(ctx, "carriage").toUpperCase());
                        return 1;
                    })))
                    .then(Commands.literal("siphon").executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        if (TrainInteractionHandler.getRole(p.getUUID()) != TrainInteractionHandler.Role.NAUGHTY) {
                            p.sendSystemMessage(Component.literal("§cOnly Team Naughty can siphon fuel!"), true);
                            return 0;
                        }
                        TrainStateData.get(p.getServer()).siphonFuel(10);
                        p.getInventory().add(new ItemStack(Items.COAL_BLOCK, 2));
                        p.sendSystemMessage(Component.literal("§c😈 Siphoned 10% fuel for 2 Coal Blocks!"), false);
                        return 1;
                    }))
                    .then(Commands.literal("bonfire").executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        TrainInteractionHandler.feedBonfire(p, TrainStateData.get(p.getServer()));
                        return 1;
                    }))
                    .then(Commands.literal("crash").requires(src -> src.hasPermission(2)).executes(ctx -> {
                        TrainMovementController.triggerCrash(ctx.getSource().getServer(), ctx.getSource().getLevel(), TrainStateData.get(ctx.getSource().getServer()));
                        return 1;
                    }))
                    .then(Commands.literal("reset").requires(src -> src.hasPermission(2))
                            .then(Commands.argument("bonfireGoal", IntegerArgumentType.integer(100)).executes(ctx -> {
                                int goal = IntegerArgumentType.getInteger(ctx, "bonfireGoal");
                                TrainStateData.get(ctx.getSource().getServer()).resetEvent(goal);
                                ctx.getSource().sendSuccess(() -> Component.literal("§aEvent reset! Bonfire Goal: " + goal), true);
                                return 1;
                            }))));
        });
    }
}
