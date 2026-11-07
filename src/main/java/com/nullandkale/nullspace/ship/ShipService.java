package com.nullandkale.nullspace.ship;

import com.mojang.brigadier.arguments.*;
import com.nullandkale.nullspace.pad.PadScanner;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;

public final class ShipService {
  private static final com.nullandkale.nullspace.server.ServerContext.Key<Map<UUID, Long>> KEY =
      new com.nullandkale.nullspace.server.ServerContext.Key<>();

  private static Map<UUID, Long> requests(net.minecraft.server.MinecraftServer server) {
    return com.nullandkale.nullspace.server.ServerContext.get(server).service(KEY, HashMap::new);
  }

  public record Inspection(
      ShipScan.Result scan,
      PadRegistry.Pad pad,
      net.minecraft.nbt.CompoundTag vessel,
      BlockPos base) {}

  public static void initialize() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) -> {
          var z = Commands.argument("z", IntegerArgumentType.integer());
          for (String action : List.of("inspect", "launch", "destinations"))
            z.then(Commands.literal(action).executes(ctx -> act(ctx, action, "")));
          z.then(
              Commands.literal("land")
                  .then(
                      Commands.argument("destination", StringArgumentType.greedyString())
                          .executes(
                              ctx ->
                                  act(
                                      ctx,
                                      "land",
                                      StringArgumentType.getString(ctx, "destination")))));
          dispatcher.register(
              Commands.literal("nullspace")
                  .then(
                      Commands.literal("ship")
                          .then(
                              Commands.argument("x", IntegerArgumentType.integer())
                                  .then(
                                      Commands.argument("y", IntegerArgumentType.integer())
                                          .then(z)))));
        });
  }

  private static int act(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
      String action,
      String destination)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    var player = ctx.getSource().getPlayerOrException();
    var pos =
        new BlockPos(
            IntegerArgumentType.getInteger(ctx, "x"),
            IntegerArgumentType.getInteger(ctx, "y"),
            IntegerArgumentType.getInteger(ctx, "z"));
    return act(player, pos, action, destination);
  }

  public static int act(ServerPlayer player, BlockPos pos, String action, String destination) {
    boolean systemAction =
        java.util.Set.of(
                "systems",
                "upgrade",
                "close_doors",
                "open_doors",
                "power_air",
                "power_engines",
                "power_weapons",
                "priority",
                "configure")
            .contains(action);
    if (!player.level().hasChunkAt(pos)
        || !ShipBlocks.navigation(player.level().getBlockState(pos))
        || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64
        || !player.mayInteract(player.level(), pos)) {
      feedback(
          player,
          pos,
          "Cannot use this controller. Move within 8 blocks and check it still exists and is"
              + " accessible.",
          systemAction);
      return 0;
    }
    if (TravelService.busy(player) || TravelService.locked(player.level(), pos)) return 0;
    long now = System.nanoTime();
    Long previous = requests(player.level().getServer()).put(player.getUUID(), now);
    if (previous != null && now - previous < (systemAction ? 100_000_000L : 500_000_000L)) {
      feedback(player, pos, "Please wait a moment before the next action", systemAction);
      return 0;
    }
    try {
      if (action.equals("docks")) {
        com.nullandkale.nullspace.station.StationService.open(player, pos, "");
        return 1;
      }
      if (action.equals("pilot") || action.equals("chase")) {
        com.nullandkale.nullspace.navigation.NavigationService.open(
            player, pos, action.equals("pilot"));
        return 1;
      }
      if (action.equals("navigation")) {
        com.nullandkale.nullspace.navigation.NavigationService.navigation(player, pos);
        return 1;
      }
      if (action.equals("fast_travel")) {
        com.nullandkale.nullspace.navigation.NavigationService.jump(player, pos, destination);
        return 1;
      }
      if (action.equals("helm")) {
        open(player, pos, "Ready");
        return 1;
      }
      if (action.equals("fire")) {
        open(player, pos, com.nullandkale.nullspace.scene.ShipCombat.fire(player, pos));
        return 1;
      }
      if (systemAction) {
        com.nullandkale.nullspace.systems.ShipSystems.action(
            player,
            pos,
            ComputerTerminal.Action.valueOf(action.toUpperCase(Locale.ROOT)),
            destination);
        return 1;
      }
      if (action.equals("destinations")) {
        String names =
            String.join(
                " | ",
                PadRegistry.states(player.level().getServer()).stream()
                    .map(p -> p.pad().name() + " [" + p.state() + "] " + p.detail())
                    .toList());
        open(player, pos, names.isEmpty() ? "No pads registered" : names);
        return 1;
      }
      var inspection = preflight(player.level(), pos);
      if (action.equals("inspect")) {
        var p = inspection.scan();
        for (BlockPos block : p.blocks())
          if (player.level().getRandom().nextInt(8) == 0)
            player
                .level()
                .sendParticles(
                    net.minecraft.core.particles.ParticleTypes.END_ROD,
                    block.getX() + .5,
                    block.getY() + .5,
                    block.getZ() + .5,
                    1,
                    0,
                    0,
                    0,
                    0);
        open(player, pos, ShipDiagnostics.summary(player.level(), p));
      } else {
        var vessel = inspection.vessel();
        if (com.nullandkale.nullspace.systems.ShipSystems.enabled(vessel)
            && com.nullandkale.nullspace.systems.ShipSystems.power(
                    player.level(),
                    vessel,
                    com.nullandkale.nullspace.systems.PowerBus.System.ENGINES)
                == 0) throw new IllegalArgumentException("Propulsion has no delivered power");
        TravelService.request(player, pos, destination, inspection);
      }
      return 1;
    } catch (Exception error) {
      if (error instanceof ShipScan.Failure failure) {
        ShipDiagnostics.highlight(player, failure);
      }
      if (!(error instanceof IllegalArgumentException))
        com.nullandkale.nullspace.NullSpace.LOGGER.error(
            "Ship controller action failed at {}", pos, error);
      feedback(player, pos, ShipMessages.failure(error), systemAction);
      return 0;
    }
  }

  public static Inspection preflight(ServerLevel level, BlockPos controller) {
    var inspected = inspect(level, controller);
    ShipOccupants.inspect(level, inspected.scan());
    return inspected;
  }

  public static Inspection inspect(ServerLevel level, BlockPos controller) {
    return inspect(level, controller, false);
  }

  static Inspection inspect(ServerLevel level, BlockPos controller, boolean reviewing) {
    if (!ShipBlocks.navigation(level.getBlockState(controller)))
      throw new IllegalArgumentException("Helm or ship computer is missing");
    var store = ShipStore.get(level.getServer());
    var vessel = store.at(level, controller);
    if (level.dimension().equals(TravelService.SPACE)) {
      if (vessel == null)
        throw new IllegalArgumentException("No registered ship occupies this area");
      BlockPos base = ShipStore.pos(vessel, "base");
      var pad =
          new PadScanner.Pad(
              base.getX(), base.getX() + 31, base.getY() - 1, base.getZ(), base.getZ() + 31);
      return new Inspection(
          ShipScan.inspect(
              level, controller, pad, false, ShipDetachment.approved(level, controller), reviewing),
          null,
          vessel,
          base);
    }
    var pads = PadRegistry.below(level, controller).stream().toList();
    for (var pad : pads) {
      var b = pad.bounds();
      if (pad.dimension().equals(level.dimension().identifier().toString())
          && controller.getX() >= b.minX()
          && controller.getX() <= b.maxX()
          && controller.getZ() >= b.minZ()
          && controller.getZ() <= b.maxZ()
          && controller.getY() > b.y()
          && controller.getY() <= b.y() + 32) {
        PadRegistry.validate(level, pad);
        var scan =
            ShipScan.inspect(
                level, controller, b, true, ShipDetachment.approved(level, controller), reviewing);
        // Resolve the physical hull before transfer; a changed pad registration must not
        // silently discard its identity, computers, battery charge or systems upgrade.
        var owner =
            vessel != null && ShipStore.pos(vessel, "base").getY() == b.y() + 1
                ? com.nullandkale.nullspace.systems.ShipTopology.resolve(level, controller)
                : null;
        return new Inspection(scan, pad, owner, new BlockPos(b.minX(), b.y() + 1, b.minZ()));
      }
    }
    throw new IllegalArgumentException(
        "Register the pad below this ship at its pad computer first");
  }

  private static void feedback(ServerPlayer player, BlockPos pos, String message, boolean systems) {
    if (!systems) {
      open(player, pos, message);
      return;
    }
    var vessel = ShipStore.get(player.level().getServer()).at(player.level(), pos);
    int revision =
        vessel == null
            ? 0
            : com.nullandkale.nullspace.systems.ShipSystems.revision(player.level(), vessel);
    ComputerTerminal.send(player, pos, message, "systems", Integer.toString(revision));
  }

  public static void open(ServerPlayer player, BlockPos pos, String message) {
    com.nullandkale.nullspace.navigation.ShipControl.open(player, pos, message);
  }

  private ShipService() {}
}
