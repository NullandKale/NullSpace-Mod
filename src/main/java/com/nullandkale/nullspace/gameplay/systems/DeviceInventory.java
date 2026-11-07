package com.nullandkale.nullspace.gameplay.systems;

import static com.nullandkale.nullspace.gameplay.systems.ShipSystems.*;

import com.nullandkale.nullspace.content.ContentCatalog;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.ship.structure.ShipTopology;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

final class DeviceInventory {
  static boolean device(Block block) {
    return block instanceof DoorBlock
        || block instanceof TrapDoorBlock
        || block instanceof ModBlocks.Machine
            && ContentCatalog.equipment(BuiltInRegistries.BLOCK.getKey(block).getPath())
                .map(d -> d.has("device"))
                .orElse(false);
  }

  static void rebuild(ServerLevel level, CompoundTag ship, ShipRuntime r) {
    r.blocks = ShipTopology.discover(level, ship);
    var s = state(ship);
    var base = WorldStore.pos(ship, "base");
    var devices = s.getListOrEmpty("devices");
    for (var pos : r.blocks) {
      var block = level.getBlockState(pos);
      if (!device(block.getBlock())
          || block.getBlock() instanceof DoorBlock
              && block.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
      long local = pos.subtract(base).asLong();
      String type = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
      if (devices.stream()
          .noneMatch(
              t -> {
                var d = (CompoundTag) t;
                return d.getLongOr("position", Long.MIN_VALUE) == local
                    && d.getStringOr("type", "").equals(type);
              })) {
        var d = new CompoundTag();
        d.putString("id", UUID.randomUUID().toString());
        d.putString("type", type);
        d.putLong("position", local);
        d.putInt("energy", 0);
        devices.add(d);
      }
    }
    r.equipment =
        r.blocks.stream()
            .filter(p -> device(level.getBlockState(p).getBlock()))
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .toList();
    r.base = base;
    r.devices.clear();
    for (Tag tag : devices) {
      var d = (CompoundTag) tag;
      if (level.getBlockEntity(base.offset(BlockPos.of(d.getLongOr("position", 0))))
          instanceof MachineBlockEntity machine) d.putString("label", machine.label);
      r.devices.put(d.getLongOr("position", Long.MIN_VALUE), d);
    }
    r.batteryDevices =
        devices.stream()
            .map(t -> (CompoundTag) t)
            .filter(
                d ->
                    d.getStringOr("type", "").equals("nullspace:battery")
                        && r.blocks.contains(base.offset(BlockPos.of(d.getLongOr("position", 0)))))
            .sorted(Comparator.comparing(d -> d.getStringOr("id", "")))
            .toList();
  }
}
