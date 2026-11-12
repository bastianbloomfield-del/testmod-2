package com.endlessexpress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public class TrainMovementController {
    public static final BlockPos BONFIRE_POS = new BlockPos(0, 101, -96);
    private static CustomBossEvent bossBar;

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        TrainStateData state = TrainStateData.get(server);

        if (bossBar == null) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("endlessexpress", "train_hud");
            bossBar = server.getCustomBossEvents().get(id);
            if (bossBar == null) {
                bossBar = server.getCustomBossEvents().create(id, Component.literal("§b❄ Endless Polar Express ❄"));
                bossBar.setColor(BossEvent.BossBarColor.BLUE);
                bossBar.setOverlay(BossEvent.BossBarOverlay.NOTCHED_10);
            }
        }

        TrainStructureBuilder.ensureMainTrainBuilt(level);
        TrainInteractionHandler.tickDailyTeams(server, level);

        long elapsedSec = (System.currentTimeMillis() / 1000L) - state.getStartEpoch();

        if (state.getPhase() == TrainStateData.Phase.PROLOGUE && elapsedSec >= TrainStateData.PROLOGUE_SECONDS) {
            startTrain(server, level, state);
        }

        if ((state.getPhase() == TrainStateData.Phase.MOVING || state.getPhase() == TrainStateData.Phase.STOPPED)
                && elapsedSec >= TrainStateData.CRASH_SECONDS) {
            triggerCrash(server, level, state);
        }

        switch (state.getPhase()) {
            case MOVING -> {
                spawnSpeedParticles(level, state);
                if (server.getTickCount() % 1200 == 0) state.decaySystems();

                if (state.getBoilerHealth() <= 10 && server.getTickCount() % 40 == 0) {
                    RandomSource r = level.getRandom();
                    BlockPos snowPos = new BlockPos(r.nextIntBetweenInclusive(-3, 3), 101, r.nextIntBetweenInclusive(-70, 30));
                    if (level.getBlockState(snowPos).isAir()) {
                        level.setBlockAndUpdate(snowPos, Blocks.SNOW.defaultBlockState());
                    }
                }

                if (server.getTickCount() % 40 == 0) {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL &&
                            !TrainStructureBuilder.getFullTrainBounds(state).contains(p.position())) {
                            p.sendSystemMessage(Component.literal("§c⚠ Left behind in the blizzard!"), true);
                            p.hurtServer(level, level.damageSources().freeze(), 4.0F);
                        }
                    }
                }

                state.tickTimer();
                if (state.getPhaseTicksLeft() <= 0) {
                    state.nextStation();
                    state.setPhase(TrainStateData.Phase.STOPPED, TrainStateData.STOP_TICKS);
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§a§l[STATION ARRIVAL] §fStopped at Station #" + state.getStationNumber() + " for 40 mins! Right-click the Locomotive Exit Door or run §e/express exit §fto gather materials!"),
                            false
                    );
                }
            }
            case STOPPED -> {
                state.tickTimer();
                if (state.getPhaseTicksLeft() == TrainStateData.WARNING_TICKS) {
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§e§l[5-MINUTE WARNING] §fTrain leaves in 5 minutes! Return to the campfire or run §a/express board §for be left behind!"),
                            false
                    );
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1.0f, 0.8f);
                    }
                }
                if (state.getPhaseTicksLeft() <= 0) {
                    state.setPhase(TrainStateData.Phase.MOVING, TrainStateData.MOVE_TICKS);
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§b§l[ALL ABOARD] §fTrain is moving for 10 minutes! Complete your carriage maintenance tasks!"),
                            false
                    );
                }
            }
            case CRASHED_SIEGE -> {
                if (state.getBonfireFuel() >= state.getBonfireGoal()) {
                    state.setPhase(TrainStateData.Phase.RESCUED, 0);
                    for (int x = -4; x <= 4; x++) {
                        for (int z = -102; z <= -92; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, 108, z), Blocks.RED_WOOL.defaultBlockState());
                        }
                    }
                    level.setBlockAndUpdate(new BlockPos(0, 109, -97), Blocks.CHEST.defaultBlockState());
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§c§l🎅 SANTA HAS ARRIVED! §fThe Signal Bonfire reached 100%! Climb onto Santa's Sleigh above the wreck!"),
                            false
                    );
                }
            }
            default -> {}
        }

        if (server.getTickCount() % 20 == 0) {
            updateHud(server, state, elapsedSec);
        }
    }

    public static void startTrain(MinecraftServer server, ServerLevel level, TrainStateData state) {
        state.setPhase(TrainStateData.Phase.MOVING, TrainStateData.MOVE_TICKS);
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§6§l[ENGINE IGNITED!] §fThe Polar Express is now moving! 10-minute travel cycle started!"),
                false
        );
    }

    public static void triggerCrash(MinecraftServer server, ServerLevel level, TrainStateData state) {
        state.setPhase(TrainStateData.Phase.CRASHED_SIEGE, 0);
        for (int z = -70; z <= 5; z += 8) {
            level.explode(null, 0, 102, z, 2.5f, false, ServerLevel.ExplosionInteraction.BLOCK);
        }
        level.setBlockAndUpdate(BONFIRE_POS.below(), Blocks.COAL_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(BONFIRE_POS, Blocks.SOUL_CAMPFIRE.defaultBlockState());
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "undeadnights start");
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§4§l[TRAIN CRASHED!] §fThe train derailed! Defend the Signal Bonfire from nightly zombie waves and feed it to summon Santa!"),
                false
        );
    }

    private static void spawnSpeedParticles(ServerLevel level, TrainStateData state) {
        RandomSource rand = level.getRandom();
        for (ServerPlayer player : level.players()) {
            for (int i = 0; i < 4; i++) {
                double px = player.getX() + rand.nextIntBetweenInclusive(-20, 20);
                double py = player.getY() + rand.nextIntBetweenInclusive(-2, 8);
                double pz = player.getZ() + rand.nextIntBetweenInclusive(-20, 20);
                if (!TrainStructureBuilder.getFullTrainBounds(state).contains(px, py, pz)) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, px, py, pz, 1, 0.0, 0.0, 2.5, 0.8);
                }
            }
        }
    }

    private static void updateHud(MinecraftServer server, TrainStateData state, long elapsedSec) {
        int sec = state.getPhaseTicksLeft() / 20;
        String timer = String.format("%02d:%02d", sec / 60, sec % 60);

        switch (state.getPhase()) {
            case PROLOGUE -> {
                long leftSec = Math.max(0, TrainStateData.PROLOGUE_SECONDS - elapsedSec);
                bossBar.setProgress((float) leftSec / TrainStateData.PROLOGUE_SECONDS);
                bossBar.setName(Component.literal("§eFind & Start the Train at (0, 100, 0)! Time Left: " + (leftSec / 3600) + "h"));
            }
            case MOVING, STOPPED -> {
                float avg = (state.getBoilerHealth() + state.getFuelTankHealth() + state.getGreenhouseHealth() + state.getDiningStock()) / 400.0f;
                bossBar.setProgress(avg);
                bossBar.setName(Component.literal(
                        "§bTrain Systems §7| §cBoiler:" + state.getBoilerHealth() + "% §6Fuel:" + state.getFuelTankHealth() +
                        "% §aFarm:" + state.getGreenhouseHealth() + "% §eDining:" + state.getDiningStock() + "%"
                ));
            }
            case CRASHED_SIEGE -> {
                bossBar.setProgress((float) state.getBonfireFuel() / Math.max(1, state.getBonfireGoal()));
                bossBar.setColor(BossEvent.BossBarColor.RED);
                bossBar.setName(Component.literal("§6🔥 Signal Bonfire: " + state.getBonfireFuel() + " / " + state.getBonfireGoal()));
            }
            case RESCUED -> {
                bossBar.setProgress(1.0f);
                bossBar.setColor(BossEvent.BossBarColor.GREEN);
                bossBar.setName(Component.literal("§a§l🎅 RESCUED BY SANTA! MERRY CHRISTMAS! 🎅"));
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
            int lives = state.getLives(player.getUUID());
            String hearts = "§c" + "❤".repeat(lives) + "§8" + "🖤".repeat(3 - lives);
            String role = TrainInteractionHandler.getRole(player.getUUID()).name();
            String status = switch (state.getPhase()) {
                case PROLOGUE -> "§eFind Train";
                case MOVING -> "§bMoving (" + timer + ")";
                case STOPPED -> "§aStation (" + timer + ")";
                case CRASHED_SIEGE -> "§cDefend Bonfire";
                case RESCUED -> "§aWinner!";
            };
            player.sendSystemMessage(Component.literal(hearts + " §8| §f" + role + " §8| " + status), true);
        }
    }

    public static void onDeath(ServerPlayer player) {
        TrainStateData state = TrainStateData.get(player.getServer());
        int left = state.loseLife(player.getUUID());
        if (left > 0) {
            player.sendSystemMessage(Component.literal("§cYou lost a life! Remaining: §e" + left + "/3"), false);
            BlockPos home = TrainStructureBuilder.buildPlayerCarriage(player.level(), player, state);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(player.level().dimension(), home, 0.0f, true), false);
        } else {
            player.setGameMode(GameType.SPECTATOR);
            player.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("§4☠ " + player.getName().getString() + " lost all 3 lives!"), false
            );
        }
    }
}
