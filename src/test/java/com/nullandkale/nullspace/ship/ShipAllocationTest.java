package com.nullandkale.nullspace.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.pad.PadScanner;
import com.nullandkale.nullspace.systems.Atmosphere;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ShipAllocationTest {
  private static CompoundTag ship() {
    var ship = new CompoundTag();
    ship.putLong("base", new BlockPos(100, 65, 100).asLong());
    ship.putLong("controller", new BlockPos(102, 66, 103).asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    ship.putInt("height", 32);
    var systems = new CompoundTag();
    var devices = new ListTag();
    var battery = new CompoundTag();
    battery.putString("id", "battery-uuid");
    battery.putInt("energy", 777);
    battery.putLong("position", new BlockPos(2, 1, 3).asLong());
    devices.add(battery);
    systems.put("devices", devices);
    int[] air = new int[Atmosphere.CELLS];
    Arrays.fill(air, -1);
    air[Atmosphere.index(2, 1, 3)] = 875;
    systems.putIntArray("air", air);
    ship.put("systems", systems);
    var computers = new CompoundTag();
    var computer = new CompoundTag();
    computer.putString("id", "guest-uuid");
    computer.putString("image", "preserve guest files");
    computers.put(Long.toString(new BlockPos(2, 1, 3).asLong()), computer);
    ship.put("computers", computers);
    var monitors = new CompoundTag();
    monitors.putInt(Long.toString(new BlockPos(3, 1, 3).asLong()), 2);
    ship.put("monitorChannels", monitors);
    var crew = new ListTag();
    var member = new CompoundTag();
    member.putString("id", "offline-member");
    member.putDouble("x", 2.5);
    member.putDouble("y", 1);
    member.putDouble("z", 3.5);
    member.putFloat("yaw", 47);
    crew.add(member);
    ship.put("crew", crew);
    return ship;
  }

  @Test
  void expandingWestAndNorthPreservesWorldLocationsAndResources() {
    var ship = ship();
    var original = ship.copy();
    var next = new PadScanner.Pad(96, 115, 64, 95, 114);
    ShipAllocation.resize(ship, next, 24);
    var shifted = new BlockPos(6, 1, 8);
    var battery =
        ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").getCompound(0).orElseThrow();
    assertEquals(shifted.asLong(), battery.getLongOr("position", 0));
    assertEquals("battery-uuid", battery.getStringOr("id", ""));
    assertEquals(777, battery.getIntOr("energy", 0));
    assertEquals(
        875,
        ship.getCompoundOrEmpty("systems")
            .getIntArray("air")
            .orElseThrow()[Atmosphere.index(6, 1, 8)]);
    assertEquals(
        original.getCompoundOrEmpty("computers").get(Long.toString(new BlockPos(2, 1, 3).asLong())),
        ship.getCompoundOrEmpty("computers").get(Long.toString(shifted.asLong())));
    assertEquals(
        2,
        ship.getCompoundOrEmpty("monitorChannels")
            .getIntOr(Long.toString(new BlockPos(7, 1, 8).asLong()), -1));
    var member = ship.getListOrEmpty("crew").getCompound(0).orElseThrow();
    assertEquals(6.5, member.getDoubleOr("x", 0));
    assertEquals(8.5, member.getDoubleOr("z", 0));
    assertEquals(47, member.getFloatOr("yaw", 0));
    assertEquals(original.getLongOr("controller", 0), ship.getLongOr("controller", 0));
    assertEquals(20, ship.getIntOr("width", 0));
    assertEquals(24, ship.getIntOr("height", 0));
    ShipAllocation.resize(ship, new PadScanner.Pad(100, 107, 64, 100, 107), 32);
    assertEquals(original, ship, "Reframing back to the original pad is lossless");
  }

  @Test
  void growthAtTheSameOriginPreservesEveryResourceAndIsIdempotent() {
    var ship = ship();
    var systems = ship.getCompoundOrEmpty("systems").copy();
    var next = new PadScanner.Pad(100, 123, 64, 100, 123);
    ShipAllocation.resize(ship, next, 32);
    assertEquals(systems, ship.getCompoundOrEmpty("systems"));
    var saved = ship.copy();
    ShipAllocation.resize(ship, next, 32);
    assertEquals(saved, ship);
  }

  @Test
  void airOutsideTheNewMaximumGridIsAccountedAsConstructionVenting() {
    var ship = ship();
    ShipAllocation.resize(ship, new PadScanner.Pad(90, 121, 64, 90, 121), 32);
    ShipAllocation.resize(ship, new PadScanner.Pad(120, 151, 64, 120, 151), 32);
    var systems = ship.getCompoundOrEmpty("systems");
    assertEquals(875, systems.getLongOr("constructionVented", 0));
    assertTrue(Arrays.stream(systems.getIntArray("air").orElseThrow()).allMatch(v -> v == -1));
  }
}
