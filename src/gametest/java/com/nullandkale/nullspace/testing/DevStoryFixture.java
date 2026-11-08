package com.nullandkale.nullspace.testing;

import com.google.gson.*;
import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.content.machine.MachineBlockEntity;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.docking.DockingPorts;
import com.nullandkale.nullspace.gameplay.navigation.ControllerChecks;
import com.nullandkale.nullspace.gameplay.navigation.JourneyService;
import com.nullandkale.nullspace.gameplay.navigation.NavigationService;
import com.nullandkale.nullspace.gameplay.navigation.ShipControl;
import com.nullandkale.nullspace.gameplay.navigation.ShipDestination;
import com.nullandkale.nullspace.gameplay.pads.PadComputers;
import com.nullandkale.nullspace.gameplay.pads.PadRegistry;
import com.nullandkale.nullspace.gameplay.pads.PadScanner;
import com.nullandkale.nullspace.gameplay.scene.SceneService;
import com.nullandkale.nullspace.gameplay.ship.ShipService;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.gameplay.systems.ShipAir;
import com.nullandkale.nullspace.gameplay.systems.ShipSystems;
import com.nullandkale.nullspace.model.galaxy.JourneyPlan;
import com.nullandkale.nullspace.model.galaxy.LocalSpaces;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.*;

/** Opt-in, native-world preparation for dev.ps1. No test fixture code is shipped in the mod. */
public final class DevStoryFixture {
  public static void prepare(MinecraftServer server, ServerPlayer player, CompoundTag ship)
      throws Exception {
    if (!Boolean.getBoolean("nullspace.dev.prepare")) return;
    var level = player.level();
    for (var at : ShipSystems.connected(level, ship)) {
      if (level.getBlockEntity(at) instanceof MachineBlockEntity machine && machine.upgrades()) {
        for (int slot = 0; slot < 3; slot++)
          machine.setItem(slot, new ItemStack(ModBlocks.SYSTEM_UPGRADE));
      }
    }
    ShipSystems.tick(level, ship);
    var home = home(server);
    ship.putString("homeId", home.id());
    var respawn = home.computer().offset(-2, 0, 0);
    player.setRespawnPosition(
        new ServerPlayer.RespawnConfig(
            LevelData.RespawnData.of(server.overworld().dimension(), respawn, 90, 0), true),
        false);
    var here = LocalSpaces.get(server, LocalSpaces.address(ship));
    var choices =
        LocalSpaces.destinations(server).stream()
            .filter(d -> !d.id().equals(here.id()) && !d.moon())
            .sorted(
                Comparator.comparingInt(
                        (LocalSpaces.Destination d) -> {
                          boolean same = d.system().equals(here.system());
                          int kind =
                              JourneyPlan.between(
                                      server.overworld().getSeed(),
                                      here.id(),
                                      d.id(),
                                      same ? 0 : 30,
                                      3)
                                  .kind();
                          return (kind == 1 ? 0 : 2) + (same ? 0 : 1);
                        })
                    .thenComparing(LocalSpaces.Destination::id))
            .toList();
    var target = choices.getFirst();
    ShipDestination.select(
        player,
        ship,
        new ShipDestination(
            ShipDestination.Kind.REGION,
            target.id(),
            BlockPos.ZERO,
            target.id(),
            target.systemName() + " / " + target.name(),
            "Orbit"));
    var plan = JourneyService.preview(server, ship, target.id());
    // The story meets the route's own signal rather than a random one.
    ship.putLong("nextSignalNonce", plan.seed());
    var profile = new JsonObject();
    profile.addProperty("version", 1);
    profile.addProperty("player", player.getPlainTextName());
    profile.addProperty("playerUuid", player.getStringUUID());
    profile.addProperty("seed", server.overworld().getSeed());
    profile.addProperty("ship", ship.getStringOr("blueprint", ""));
    profile.addProperty("shipId", ship.getStringOr("id", ""));
    profile.addProperty("origin", here.id());
    profile.addProperty("originName", here.name());
    profile.addProperty("destination", target.id());
    profile.addProperty("destinationName", target.name());
    profile.addProperty("signal", plan.name());
    profile.addProperty("seconds", plan.seconds());
    profile.addProperty("homePad", home.id());
    profile.addProperty("homeComputer", home.computer().toShortString());
    profile.addProperty("respawn", respawn.toShortString());
    profile.addProperty(
        "story",
        "Jump, investigate the signal, dock, collect supplies, return, undock and resume. Home on"
            + " Terra is your landing and recovery pad.");
    var root = server.getWorldPath(LevelResource.ROOT);
    Files.writeString(
        root.resolve("nullspace/dev-story-profile.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(profile));
    validate(server, player, ship);
    WorldStore.get(server).save();
    server.getPlayerList().saveAll();
    server.saveEverything(false, true, true);
  }

  public static void validate(MinecraftServer server, ServerPlayer player, CompoundTag ship) {
    var helm = WorldStore.pos(ship, "controller");
    var inspection = ShipService.preflight(player.level(), helm);
    if (inspection.vessel() != ship
        || !ShipAir.breathable(player.level(), ship, player.blockPosition().above()))
      throw new AssertionError("Prepared ship is not ready with breathable air");
    if (NavigationService.propulsion(player.level(), ship) == 0
        || FuelSystem.available(player.level(), ship) <= 0)
      throw new AssertionError("Prepared ship needs working propulsion and real fuel");
    if (DockingPorts.ports(player.level(), ship).isEmpty())
      throw new AssertionError("Prepared ship has no working docking doorway");
    var destination = ShipDestination.selected(ship);
    if (destination == null
        || !ControllerChecks.reason(player, ship, destination, ShipControl.Action.JUMP).isEmpty())
      throw new AssertionError("Prepared ship cannot start its selected journey");
    if (SceneService.observer(player) != ship || player.getRespawnConfig() == null)
      throw new AssertionError("Player needs ship ownership and a recovery spawn");
    var home =
        PadRegistry.read(server).stream()
            .filter(p -> p.id().equals(ship.getStringOr("homeId", "")))
            .findFirst()
            .orElseThrow();
    var surface = server.overworld();
    if (home.bounds().y() < surface.getSeaLevel())
      throw new AssertionError("Home recovery pad must be above the generated terrain");
    for (int x = home.bounds().minX(); x <= home.bounds().maxX(); x++)
      for (int z = home.bounds().minZ(); z <= home.bounds().maxZ(); z++)
        for (int y = home.bounds().y() + 1; y <= home.bounds().y() + 33; y++)
          if (!surface.getBlockState(new BlockPos(x, y, z)).isAir())
            throw new AssertionError("Home pad landing column is obstructed");
  }

  private static PadRegistry.Pad home(MinecraftServer server) throws Exception {
    var level = server.overworld();
    // Height queries for absent chunks report the minimum Y; generate the native terrain first.
    for (int x = -16; x <= 48; x += 16)
      for (int z = -16; z <= 48; z += 16) level.getChunk(new BlockPos(x, 0, z));
    int floor = level.getMinY();
    for (int x = -6; x <= 33; x++)
      for (int z = -1; z <= 32; z++)
        floor = Math.max(floor, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
    floor += 2;
    if (floor + 34 >= level.getMaxY())
      throw new IllegalStateException("No safe height for the playtest Home pad");
    for (int x = -1; x <= 32; x++)
      for (int z = -1; z <= 32; z++)
        level.setBlock(
            new BlockPos(x, floor, z),
            (x == -1 || x == 32 || z == -1 || z == 32 ? Blocks.COBBLESTONE : Blocks.DIRT)
                .defaultBlockState(),
            18);
    for (int x = -5; x <= -2; x++)
      for (int z = 13; z <= 19; z++)
        level.setBlock(new BlockPos(x, floor, z), Blocks.OAK_PLANKS.defaultBlockState(), 18);
    for (int z : new int[] {13, 19})
      level.setBlock(new BlockPos(-5, floor + 1, z), Blocks.TORCH.defaultBlockState(), 3);
    var computer = new BlockPos(-1, floor + 1, 16);
    level.setBlock(computer, PadComputers.BLOCK.defaultBlockState(), 18);
    PadComputers.register(level, computer, new PadScanner.Pad(0, 31, floor, 0, 31), "Home", false);
    return PadRegistry.read(server).stream()
        .filter(
            p ->
                p.computer().equals(computer)
                    && p.dimension().equals(level.dimension().identifier().toString()))
        .findFirst()
        .orElseThrow();
  }

  private DevStoryFixture() {}
}
