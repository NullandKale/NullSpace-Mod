package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.air.SpatialAir;
import com.nullandkale.nullspace.model.power.PowerBus;
import com.nullandkale.nullspace.testing.ShipTestFixture;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;

/** Real native blocks, two independent viewers, durability boundaries and immediate grants. */
public final class EngineeringGameTest {
  private record Fixture(BlockPos base, BlockPos controller, CompoundTag ship) {}

  private static Fixture fixture(GameTestHelper ctx, int offset) {
    var level = ctx.getLevel();
    var base = ctx.absolutePos(new BlockPos(offset, 64, offset));
    var helm = ShipTestFixture.build(level, base);
    level.setBlock(base.offset(0, 3, 1), Blocks.STONE.defaultBlockState(), 18);
    level.setBlock(base.offset(6, 3, 1), Blocks.STONE.defaultBlockState(), 18);
    for (int x : new int[] {1, 2, 4, 5})
      level.setBlock(
          base.offset(x, 3, 6),
          (switch (x) {
                case 1 -> ModBlocks.GENERATOR;
                case 2 -> ModBlocks.BATTERY;
                case 4 -> ModBlocks.AIR_SUPPLY;
                default -> ModBlocks.COMPUTER;
              })
              .defaultBlockState(),
          18);
    level.setBlock(base.offset(6, 5, 4), ModBlocks.WEAPON.defaultBlockState(), 18);
    FuelSystem.provision(level, base.offset(1, 3, 6), 64);
    var ship = new CompoundTag();
    ship.putString("id", UUID.randomUUID().toString());
    ship.putString("dimension", level.dimension().identifier().toString());
    ship.putLong("base", base.above().asLong());
    ship.putLong("controller", helm.asLong());
    ship.putInt("width", 8);
    ship.putInt("depth", 8);
    WorldStore.get(level.getServer()).ships().add(ship);
    ShipSystems.upgrade(level, ship);
    ShipSystems.tick(level, ship);
    return new Fixture(base, helm, ship);
  }

  @GameTest
  public void journalReplayPreservesDeviceIdentityEnergyAndAir(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var f = fixture(ctx, 27500);
    var before = f.ship.getCompoundOrEmpty("systems").copy();
    var bounds = WorldStore.bounds(f.ship);
    var template =
        new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
    template.fillFromWorld(
        level,
        bounds.min(),
        new net.minecraft.core.Vec3i(
            bounds.max().getX() - bounds.min().getX() + 1,
            bounds.max().getY() - bounds.min().getY() + 1,
            bounds.max().getZ() - bounds.min().getZ() + 1),
        false,
        List.of());
    TravelService.place(level, template, bounds.min());
    ctx.assertTrue(
        f.ship.getCompoundOrEmpty("systems").equals(before),
        "Replaying an authoritative journal cannot delete device records, battery energy or stored"
            + " air");
    ShipSystems.revision(level, f.ship);
    ctx.assertTrue(
        f.ship.getCompoundOrEmpty("systems").equals(before),
        "Rebuilding caches after replay must preserve the exact persisted systems state");
    var battery = f.base.offset(2, 3, 6);
    var local = battery.subtract(WorldStore.pos(f.ship, "base")).asLong();
    level.setBlock(battery, Blocks.AIR.defaultBlockState(), 18);
    ctx.assertTrue(
        f.ship.getCompoundOrEmpty("systems").getListOrEmpty("devices").stream()
            .map(t -> (CompoundTag) t)
            .noneMatch(d -> d.getLongOr("position", Long.MIN_VALUE) == local),
        "Real destruction outside journal replay must still remove the destroyed device record");
    ctx.succeed();
  }

  @GameTest
  public void telemetryIsPassiveIndependentAndAvailableDuringBlackout(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var f = fixture(ctx, 24000);
    var p = ctx.makeMockServerPlayerInLevel();
    var other = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(f.base.getX() + 4.5, f.base.getY() + 3, f.base.getZ() + 3.5);
    other.teleportTo(p.getX(), p.getY(), p.getZ());
    var observer = WorldStore.writeObserver;
    try {
      int revision = ShipSystems.revision(level, f.ship);
      var before = f.ship.getCompoundOrEmpty("systems").copy();
      var writes = new int[1];
      WorldStore.writeObserver = progress -> writes[0]++;
      for (String query :
          List.of(
              "help",
              "profiles",
              "rooms",
              "room here",
              "air audit",
              "devices",
              "doors list",
              "groups"))
        ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, query);
      ctx.assertTrue(
          writes[0] == 0
              && before.equals(f.ship.getCompoundOrEmpty("systems"))
              && revision == ShipSystems.revision(level, f.ship),
          "Passive telemetry cannot save, change allocations/air, or advance the editing revision");
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "rooms");
      ShipSystems.action(other, f.controller, ComputerTerminal.Action.CONFIGURE, "profiles");
      ctx.assertTrue(
          ComputerTerminal.reading(p, f.controller).startsWith("ROOMS")
              && ComputerTerminal.reading(other, f.controller).startsWith("CRUISE"),
          "Each viewer must retain their own live page");
      WorldStore.writeObserver = observer;
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "power controls 0");
      ctx.assertTrue(
          ShipSystems.power(level, f.ship, PowerBus.System.CONTROLS) == 0,
          "Allocation changes must update grants immediately");
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "room here");
      ctx.assertTrue(
          ComputerTerminal.reading(p, f.controller).contains("BREATHABLE"),
          "A blacked-out console must still explain cabin air");
      boolean blocked = false;
      try {
        ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "profile cruise");
      } catch (IllegalArgumentException expected) {
        blocked = true;
      }
      ctx.assertTrue(blocked, "Read access cannot bypass a control blackout for writes");
      var session =
          ComputerTerminal.issue(p, f.controller, "", "systems", Integer.toString(revision));
      ComputerTerminal.authorize(
          p,
          new ComputerTerminal.Request(
              session.session(), ComputerTerminal.Action.CONFIGURE, "rooms", "", revision));
      boolean stale = false;
      try {
        ComputerTerminal.authorize(
            p,
            new ComputerTerminal.Request(
                session.session(), ComputerTerminal.Action.CONFIGURE, "power air 0", "", revision));
      } catch (IllegalArgumentException expected) {
        stale = expected.getMessage().contains("Systems changed");
      }
      ctx.assertTrue(stale, "Stale status reads are safe; stale writes must still reject");
      ctx.succeed();
    } finally {
      WorldStore.writeObserver = observer;
      level.getServer().getPlayerList().remove(p);
      level.getServer().getPlayerList().remove(other);
    }
  }

  @GameTest
  public void profilesRefreshGrantsWithoutSpendingEnergyAndPreserveSwitches(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var f = fixture(ctx, 25000);
    var p = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(f.base.getX() + 4.5, f.base.getY() + 3, f.base.getZ() + 3.5);
    try {
      var state = f.ship.getCompoundOrEmpty("systems");
      var battery =
          state.getListOrEmpty("devices").stream()
              .map(t -> (CompoundTag) t)
              .filter(d -> d.getStringOr("type", "").equals("nullspace:battery"))
              .findFirst()
              .orElseThrow();
      battery.putInt("energy", 100);
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "profile combat");
      ctx.assertTrue(
          ShipSystems.power(level, f.ship, PowerBus.System.WEAPONS) == 2,
          "Combat allocation must immediately power the physical weapon");
      int energy = battery.getIntOr("energy", 0);
      for (int i = 0; i < 40; i++)
        for (var system : PowerBus.System.values()) ShipSystems.power(level, f.ship, system);
      ctx.assertTrue(
          battery.getIntOr("energy", 0) == energy,
          "Reading refreshed grants cannot charge or discharge batteries");
      ShipSystems.equipmentAction(
          p, f.base.offset(4, 3, 6), ComputerTerminal.Action.DEVICE_DISABLE);
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.CONFIGURE, "profile emergency");
      ctx.assertTrue(
          ShipSystems.power(level, f.ship, PowerBus.System.WEAPONS) == 0
              && ShipSystems.power(level, f.ship, PowerBus.System.ENGINES) == 0
              && ShipSystems.power(level, f.ship, PowerBus.System.AIR) == 0,
          "Emergency cuts optional loads and cannot re-enable disabled air hardware");
      ShipSystems.equipmentAction(p, f.base.offset(4, 3, 6), ComputerTerminal.Action.DEVICE_ENABLE);
      ctx.assertTrue(
          ShipSystems.power(level, f.ship, PowerBus.System.AIR) == SpatialAir.MAX_SUPPLY_POWER,
          "Emergency overdrives the one re-enabled Air Supply to its 8-power cap: "
              + ShipSystems.power(level, f.ship, PowerBus.System.AIR));
      ShipSystems.equipmentAction(
          p, f.base.offset(1, 3, 6), ComputerTerminal.Action.DEVICE_DISABLE);
      ShipSystems.equipmentAction(
          p, f.base.offset(2, 3, 6), ComputerTerminal.Action.DEVICE_DISABLE);
      ctx.assertTrue(
          ShipSystems.power(level, f.ship, PowerBus.System.CONTROLS) == 0,
          "Removing both physical supplies must invalidate grants before the next tick");
      ctx.assertTrue(
          battery.getIntOr("energy", 0) == energy,
          "Profiles, switches, and grant queries never manufacture or consume stored energy");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
    }
  }

  @GameTest
  public void anotherViewersReadCannotStealOrCancelDoorWork(GameTestHelper ctx) {
    var level = ctx.getLevel();
    var f = fixture(ctx, 26000);
    var p = ctx.makeMockServerPlayerInLevel();
    var other = ctx.makeMockServerPlayerInLevel();
    p.teleportTo(f.base.getX() + 4.5, f.base.getY() + 3, f.base.getZ() + 3.5);
    other.teleportTo(p.getX(), p.getY(), p.getZ());
    try {
      ShipSystems.action(p, f.controller, ComputerTerminal.Action.OPEN_DOORS);
      ShipSystems.action(other, f.controller, ComputerTerminal.Action.CONFIGURE, "rooms");
      var session = ComputerTerminal.issue(other, f.controller, "", "systems", "");
      ComputerTerminal.close(other, session.session());
      ShipSystems.tick(level, f.ship);
      ctx.assertTrue(
          level.getBlockState(f.base.offset(3, 3, 0)).getValue(DoorBlock.OPEN),
          "Closing a second viewer's status page cannot cancel the first player's door queue");
      ctx.succeed();
    } finally {
      level.getServer().getPlayerList().remove(p);
      level.getServer().getPlayerList().remove(other);
    }
  }
}
