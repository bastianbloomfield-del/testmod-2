package com.endlessexpress;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TrainStateData extends SavedData {
    public static final long PROLOGUE_SECONDS = 2L * 24L * 60L * 60L; // 2 real-life days
    public static final long CRASH_SECONDS = 14L * 24L * 60L * 60L;   // End of Week 2 (Day 14)

    public static final int MOVE_TICKS = 10 * 60 * 20;    // 10 minutes moving
    public static final int STOP_TICKS = 40 * 60 * 20;    // 40 minutes at station
    public static final int WARNING_TICKS = 5 * 60 * 20;  // 5-minute departure warning

    public enum Phase { PROLOGUE, MOVING, STOPPED, CRASHED_SIEGE, RESCUED }

    private long startEpoch;
    private String phase;
    private int phaseTicksLeft;
    private int stationNumber;

    private int boilerHealth;
    private int fuelTankHealth;
    private int greenhouseHealth;
    private int diningStock;

    private int bonfireFuel;
    private int bonfireGoal;

    private final Map<String, Integer> playerCarriages;
    private final Map<String, Integer> playerLives;

    public static final Codec<TrainStateData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("startEpoch").forGetter(d -> d.startEpoch),
            Codec.STRING.fieldOf("phase").forGetter(d -> d.phase),
            Codec.INT.fieldOf("phaseTicksLeft").forGetter(d -> d.phaseTicksLeft),
            Codec.INT.fieldOf("stationNumber").forGetter(d -> d.stationNumber),
            Codec.INT.fieldOf("boilerHealth").forGetter(d -> d.boilerHealth),
            Codec.INT.fieldOf("fuelTankHealth").forGetter(d -> d.fuelTankHealth),
            Codec.INT.fieldOf("greenhouseHealth").forGetter(d -> d.greenhouseHealth),
            Codec.INT.fieldOf("diningStock").forGetter(d -> d.diningStock),
            Codec.INT.fieldOf("bonfireFuel").forGetter(d -> d.bonfireFuel),
            Codec.INT.fieldOf("bonfireGoal").forGetter(d -> d.bonfireGoal),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("playerCarriages").forGetter(d -> d.playerCarriages),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("playerLives").forGetter(d -> d.playerLives)
    ).apply(instance, TrainStateData::new));

    public static final SavedDataType<TrainStateData> TYPE = new SavedDataType<>(
            "polar_express_train_data",
            TrainStateData::new,
            CODEC,
            null
    );

    public TrainStateData() {
        this(System.currentTimeMillis() / 1000L, Phase.PROLOGUE.name(), 0, 0,
                100, 100, 100, 100, 0, 5000, new HashMap<>(), new HashMap<>());
    }

    public TrainStateData(long startEpoch, String phase, int phaseTicksLeft, int stationNumber,
                          int boilerHealth, int fuelTankHealth, int greenhouseHealth, int diningStock,
                          int bonfireFuel, int bonfireGoal,
                          Map<String, Integer> playerCarriages, Map<String, Integer> playerLives) {
        this.startEpoch = startEpoch;
        this.phase = phase;
        this.phaseTicksLeft = phaseTicksLeft;
        this.stationNumber = stationNumber;
        this.boilerHealth = boilerHealth;
        this.fuelTankHealth = fuelTankHealth;
        this.greenhouseHealth = greenhouseHealth;
        this.diningStock = diningStock;
        this.bonfireFuel = bonfireFuel;
        this.bonfireGoal = bonfireGoal;
        this.playerCarriages = new HashMap<>(playerCarriages);
        this.playerLives = new HashMap<>(playerLives);
    }

    public static TrainStateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int getOrAssignCarriageIndex(UUID uuid) {
        return playerCarriages.computeIfAbsent(uuid.toString(), k -> {
            int nextIndex = playerCarriages.size() + 1;
            setDirty();
            return nextIndex;
        });
    }

    public int getTotalPlayerCarriages() {
        return Math.max(1, playerCarriages.size());
    }

    public int getLives(UUID uuid) {
        return playerLives.computeIfAbsent(uuid.toString(), k -> {
            setDirty();
            return 3;
        });
    }

    public int loseLife(UUID uuid) {
        int remaining = Math.max(0, getLives(uuid) - 1);
        playerLives.put(uuid.toString(), remaining);
        setDirty();
        return remaining;
    }

    public void decaySystems() {
        boilerHealth = Math.max(0, boilerHealth - 2);
        fuelTankHealth = Math.max(0, fuelTankHealth - 3);
        greenhouseHealth = Math.max(0, greenhouseHealth - 2);
        diningStock = Math.max(0, diningStock - 1);
        setDirty();
    }

    public void repairSystem(String sys, int amount) {
        switch (sys.toUpperCase()) {
            case "BOILER" -> boilerHealth = Math.min(100, boilerHealth + amount);
            case "FUEL" -> fuelTankHealth = Math.min(100, fuelTankHealth + amount);
            case "GREENHOUSE" -> greenhouseHealth = Math.min(100, greenhouseHealth + amount);
            case "DINING" -> diningStock = Math.min(100, diningStock + amount);
        }
        setDirty();
    }

    public void siphonFuel(int amount) {
        fuelTankHealth = Math.max(0, fuelTankHealth - amount);
        setDirty();
    }

    public void addBonfireFuel(int amount) {
        bonfireFuel = Math.min(bonfireGoal, bonfireFuel + amount);
        setDirty();
    }

    public Phase getPhase() { return Phase.valueOf(phase); }

    public void setPhase(Phase newPhase, int ticks) {
        this.phase = newPhase.name();
        this.phaseTicksLeft = ticks;
        setDirty();
    }

    public void tickTimer() {
        if (phaseTicksLeft > 0) {
            phaseTicksLeft--;
            setDirty();
        }
    }

    public void nextStation() {
        stationNumber++;
        setDirty();
    }

    public long getStartEpoch() { return startEpoch; }
    public int getPhaseTicksLeft() { return phaseTicksLeft; }
    public int getStationNumber() { return stationNumber; }
    public int getBoilerHealth() { return boilerHealth; }
    public int getFuelTankHealth() { return fuelTankHealth; }
    public int getGreenhouseHealth() { return greenhouseHealth; }
    public int getDiningStock() { return diningStock; }
    public int getBonfireFuel() { return bonfireFuel; }
    public int getBonfireGoal() { return bonfireGoal; }

    public void resetEvent(int newBonfireGoal) {
        this.startEpoch = System.currentTimeMillis() / 1000L;
        this.phase = Phase.PROLOGUE.name();
        this.phaseTicksLeft = 0;
        this.stationNumber = 0;
        this.boilerHealth = 100;
        this.fuelTankHealth = 100;
        this.greenhouseHealth = 100;
        this.diningStock = 100;
        this.bonfireFuel = 0;
        this.bonfireGoal = newBonfireGoal;
        this.playerLives.clear();
        setDirty();
    }
}
