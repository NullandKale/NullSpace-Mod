package com.nullandkale.nullspace.gameplay.systems;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

import com.nullandkale.nullspace.model.air.Atmosphere;
import com.nullandkale.nullspace.model.power.PowerBus;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class ShipRuntime {
  CompoundTag source;
  boolean dirty = true;
  Set<BlockPos> blocks = Set.of();
  List<BlockPos> equipment = List.of();
  List<Atmosphere.Room> rooms = List.of();
  Atmosphere.Topology atmosphere;
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
