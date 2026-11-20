package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

final class ShipRuntime
{
    CompoundTag source;
    boolean dirty = true;
    /** The server tick a large vessel's systems and its device list were last rebuilt: they are rebuilt at most every few seconds (see ShipSystems.tick). */
    long rebuiltAt = -1_000_000, devicesAt = -1_000_000;
    /**
     * What a large vessel keeps instead of finding its blocks again: the positions of its own blocks, and of the few among them that are devices,
     * turrets, bays or trees (the candidates). Both are changed a block at a time as blocks change, so a rebuild reads a few thousand cells, not all of them.
     */
    it.unimi.dsi.fastutil.longs.LongOpenHashSet members, candidates;
    /** The seals changed since the air was last worked out; the air of a large vessel is worked out on another thread, and what it has begun is pending. */
    boolean airDirty;
    /** How many times the air was worked out in full on the server thread (read by the tests). */
    int airRebuilds;
    /** How many times the seals of this vessel were scanned all at once on the calling thread; a hab scanned as it was built has none. */
    int sealScans;
    long airAt = -1_000_000;
    java.util.concurrent.CompletableFuture<AirSystem.Flooded> pending;
    Set<BlockPos> blocks = Set.of();
    List<BlockPos> equipment = List.of();
    /** The salvage turrets and repair bays of the vessel, found when it is rebuilt. */
    List<BlockPos> turrets = List.of(), bays = List.of();

    /** The golden apple trees of the vessel (their trunk cores), which give it air, found when it is rebuilt. */
    List<BlockPos> trees = List.of();
    List<Atmosphere.Room> rooms = List.of();
    Atmosphere.Topology atmosphere;
    /** The air's last step: where it moved, and the cells in front of the working Air Supplies (for AirSounds). */
    List<com.nullandkale.nullspace.model.air.SpatialAir.Flow> flows = List.of();
    List<net.minecraft.core.BlockPos> outlets = List.of();
    int[] air;
    boolean[] seals;
    int revision;
    BlockPos base;
    final Long2ObjectOpenHashMap<CompoundTag> devices = new Long2ObjectOpenHashMap<>();
    List<CompoundTag> batteryDevices = List.of();
    PowerBus.Result power;
    final ArrayDeque<BlockPos> doors = new ArrayDeque<>();
    boolean open;
    String result = "Ready";
    final List<String> doorResults = new ArrayList<>();
    ServerPlayer commander;
    BlockPos commandController;
}
