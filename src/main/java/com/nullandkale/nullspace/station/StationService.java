package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.navigation.*;
import com.nullandkale.nullspace.scene.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.ship.*;
import com.nullandkale.nullspace.systems.*;
import com.nullandkale.nullspace.terminal.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/**
 * Station identity lives independently of its core. Deployment is a saved, replayable block plan.
 */
public final class StationService {
  private static final ServerContext.Key<Map<String, Integer>> PROGRESS = new ServerContext.Key<>();
  private static final ThreadLocal<Boolean> PLACING = ThreadLocal.withInitial(() -> false);

  public static boolean station(CompoundTag s) {
    return s != null && s.getStringOr("kind", "").equals("station");
  }

  public static boolean ready(CompoundTag s) {
    return !s.contains("stationBuild");
  }

  public static boolean building(MinecraftServer server) {
    return ShipStore.get(server).ships().stream()
        .anyMatch(t -> ((CompoundTag) t).contains("stationBuild"));
  }

  /** Aggregate status for administrative maintenance, never a lock on unrelated player ships. */
  public static boolean exclusiveConstruction(MinecraftServer server) {
    return ShipStore.get(server).ships().stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(s -> s.contains("stationBuild") && !s.getBooleanOr("worldGenerated", false));
  }

  public static boolean buildingAt(ServerLevel level, BlockPos at) {
    if (PLACING.get()) return false;
    var s = ShipStore.get(level.getServer()).at(level, at);
    return s != null && !ready(s);
  }

  public static boolean owner(ServerPlayer p, CompoundTag s) {
    return s.getStringOr("owner", "").equals(p.getUUID().toString())
        || p.permissions()
            .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
  }

  public static boolean builder(ServerPlayer p, CompoundTag s) {
    return !station(s)
        || owner(p, s)
        || s.getCompoundOrEmpty("builders").getBooleanOr(p.getUUID().toString(), false);
  }

  public static boolean allowed(ServerPlayer p, CompoundTag s) {
    return !station(s) || s.getStringOr("access", "private").equals("public") || builder(p, s);
  }

  static boolean returningVisitor(ServerPlayer p, CompoundTag s) {
    return s.getListOrEmpty("crew").stream()
        .map(t -> (CompoundTag) t)
        .anyMatch(
            m ->
                m.getStringOr("id", "").equals(p.getUUID().toString())
                    && !m.getStringOr("returnShip", "").isEmpty());
  }

  public static void requireBuilder(ServerPlayer p, CompoundTag s) {
    if (!builder(p, s))
      throw new IllegalArgumentException(
          "Station building is restricted to its owner and invited builders");
  }

  public static CompoundTag at(ServerPlayer p, BlockPos at) {
    if (!p.isAlive()
        || !p.level().hasChunkAt(at)
        || p.distanceToSqr(Vec3.atCenterOf(at)) > 64
        || !p.mayInteract(p.level(), at)
        || TravelService.busy(p))
      throw new IllegalArgumentException("Move within 8 blocks of the station controller");
    var s = ShipStore.get(p.level().getServer()).at(p.level(), at);
    if (s == null || !ready(s))
      throw new IllegalArgumentException("Controller is not on a ready, registered vessel");
    if (station(s)
        && p.level().getBlockState(at).is(ShipBlocks.STATION_CORE)
        && builder(p, s)
        && !p.level().getBlockState(ShipStore.pos(s, "controller")).is(ShipBlocks.STATION_CORE)) {
      s.putLong("controller", at.asLong());
      ShipSystems.changed(p.level(), at, false);
      ShipStore.get(p.level().getServer()).requestSave();
    }
    return s;
  }

  public static String mode(ServerLevel level, BlockPos at) {
    var state = level.getBlockState(at);
    if (state.is(ShipBlocks.STATION_CORE)) return "station_core";
    if (state.is(ShipBlocks.DOCKING_PORT) || state.is(ShipBlocks.DOCKING_BASE))
      return "docking_port";
    if (state.is(ShipBlocks.DOOR_CONNECTOR)) return "door_connector";
    return ShipBlocks.navigation(state) ? "docks" : "";
  }

  public static void open(ServerPlayer p, BlockPos at, String notice) {
    var s = at(p, at);
    String mode = mode(p.level(), at);
    if (mode.isEmpty()) throw new IllegalArgumentException("Station controller missing");
    String text = notice.isEmpty() ? status(p, s, at) : notice;
    ComputerTerminal.send(p, at, text, mode, "0");
  }

  public static String status(ServerPlayer p, CompoundTag s, BlockPos at) {
    if (ShipBlocks.navigation(p.level().getBlockState(at))) return DockingService.contacts(p, s);
    if (p.level().getBlockState(at).is(ShipBlocks.DOCKING_PORT)
        || p.level().getBlockState(at).is(ShipBlocks.DOCKING_BASE))
      return DockingService.status(p, s, at);
    var graph = StationGraph.inspect(p.level(), s);
    StringBuilder text = new StringBuilder(s.getStringOr("home", "Station"));
    var power = ShipSystems.powerStats(p.level(), s);
    var pressure = new ShipScan.LivePressure(p.level(), ShipStore.bounds(s));
    int oxygen = 100, supplies = 0, leaks = 0;
    for (var pos : ShipSystems.connected(p.level(), s))
      if (p.level().getBlockState(pos).is(ShipBlocks.AIR_SUPPLY) && ShipSystems.active(s, pos)) {
        var outlet =
            pos.relative(p.level().getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING));
        var local = outlet.subtract(ShipStore.pos(s, "base"));
        supplies++;
        oxygen =
            Math.min(
                oxygen,
                ShipStore.bounds(s).contains(outlet)
                    ? ShipSystems.pressure(p.level(), s, local.getX(), local.getY(), local.getZ())
                        / 10
                    : 0);
        if (!pressure.breathable(outlet)) leaks++;
      }
    text.append("\nPower: ")
        .append(power[0])
        .append(" / ")
        .append(power[1])
        .append(" demand | Charging: ")
        .append(power[2])
        .append("\nFuel: ")
        .append(
            com.nullandkale.nullspace.systems.FuelSystem.display(
                com.nullandkale.nullspace.systems.FuelSystem.available(p.level(), s)))
        .append(" | Battery: ")
        .append(power[3])
        .append("\nAir: ")
        .append(supplies == 0 ? "No active supply" : oxygen + "% at outlets")
        .append(" | Leaks: ")
        .append(leaks)
        .append(" | Ports: ")
        .append(graph.nodes().stream().filter(StationGraph.Node::dock).count())
        .append("\nAccess: ")
        .append(s.getStringOr("access", "private"))
        .append(" | Door nodes: ")
        .append(graph.nodes().size());
    for (var n : graph.nodes())
      text.append("\n")
          .append(n.dock() ? "Dock " : "Door ")
          .append(n.controller().subtract(ShipStore.pos(s, "base")).toShortString())
          .append(" | routes ")
          .append(graph.routes().get(n.id()).size());
    text.append("\n").append(ShipSystems.summary(p.level(), s));
    return text.substring(0, Math.min(2000, text.length()));
  }

  private static String showProblem(ServerPlayer p, CompoundTag station) {
    var level = p.level();
    var bounds = ShipStore.bounds(station);
    var pressure = new ShipScan.LivePressure(level, bounds);
    for (var at : ShipSystems.connected(level, station)) {
      var state = level.getBlockState(at);
      var target = at;
      String message = "";
      if (state.is(ShipBlocks.AIR_SUPPLY) && ShipSystems.active(station, at)) {
        target = at.relative(state.getValue(HorizontalDirectionalBlock.FACING));
        if (ShipScan.seals(level, target))
          message = "Clear the air outlet at " + target.toShortString();
        else if (!pressure.breathable(target)) {
          ShipDiagnostics.highlight(
              p, ShipScan.leakTrace(level, bounds, ShipSystems.connected(level, station), target));
          return "Leak path highlighted from air outlet " + target.toShortString();
        }
      } else if (state.is(ShipBlocks.DOCKING_BASE) && !ShipDoors.framed(level, at.above())) {
        target = at.above();
        message = "Repair the framed dock door above the base at " + at.toShortString();
      } else if (state.is(ShipBlocks.GENERATOR) && FuelSystem.available(level, station) == 0)
        message =
            "Load furnace fuel here; empty any blocked container returns at " + at.toShortString();
      if (!message.isEmpty()) {
        ShipDiagnostics.highlight(p, List.of(target));
        return message;
      }
    }
    for (var port : DockingService.ports(level, station)) {
      var obstruction = DockingService.doorwayObstruction(level, station, port);
      if (obstruction.isPresent()) {
        ShipDiagnostics.highlight(p, List.of(obstruction.get()));
        return "Clear the docking doorway at "
            + obstruction.get().toShortString()
            + "; keep a two-block-high passage in front of the door.";
      }
    }
    return "Station fuel, air outlets and docking frames have no detected obstruction or leak";
  }

  private static BlockPos reserve(MinecraftServer server) {
    return reserve(server, 32, 32);
  }

  private static BlockPos reserve(MinecraftServer server, int width, int depth) {
    return reserve(server, width, depth, 0)
        .orElseThrow(
            () -> new IllegalArgumentException("No free space construction sections remain"));
  }

  private static Optional<BlockPos> reserve(
      MinecraftServer server, int width, int depth, int first) {
    var level = server.getLevel(TravelService.SPACE);
    if (level == null) return Optional.empty();
    int candidates = 0;
    for (int slot = first; slot <= 7000; slot++)
      if (TravelService.spaceSlotFree(server, slot)) {
        if (++candidates > 16) break;
        var base = new BlockPos(slot * TravelService.SPACE_SPACING, 96, 0);
        for (int x = 0; x < width; x += 16)
          for (int z = 0; z < depth; z += 16) level.getChunk(base.offset(x, 0, z));
        boolean clear = true;
        for (var p : BlockPos.betweenClosed(base, base.offset(width - 1, 31, depth - 1)))
          if (!level.getBlockState(p).isAir()) {
            clear = false;
            break;
          }
        if (clear
            && level
                .getEntities(
                    null, new ShipScan.Bounds(base, base.offset(width - 1, 31, depth - 1)).box())
                .isEmpty()) return Optional.of(base);
      }
    return Optional.empty();
  }

  static ListTag encode(StationBlueprint.Plan plan) {
    var list = new ListTag();
    for (var e : plan.blocks().entrySet()) {
      var t = new CompoundTag();
      t.putLong("pos", e.getKey().asLong());
      t.put("state", NbtUtils.writeBlockState(e.getValue()));
      list.add(t);
    }
    return list;
  }

  public static CompoundTag queue(
      MinecraftServer server,
      String id,
      String name,
      String address,
      Vec3 position,
      String owner,
      StationBlueprint.Plan plan,
      boolean foundation) {
    return queue(server, id, name, address, position, owner, plan, foundation, new CompoundTag());
  }

  /** Ambient places use their own storage range and never consume a player's station quota. */
  static CompoundTag queueAmbient(
      MinecraftServer server,
      String id,
      String name,
      String address,
      Vec3 position,
      StationBlueprint.Plan plan,
      CompoundTag metadata) {
    return queue(server, id, name, address, position, "", plan, false, metadata);
  }

  private static CompoundTag queue(
      MinecraftServer server,
      String id,
      String name,
      String address,
      Vec3 position,
      String owner,
      StationBlueprint.Plan plan,
      boolean foundation,
      CompoundTag metadata) {
    boolean ambient = metadata.getBooleanOr("worldGenerated", false);
    var store = ShipStore.get(server);
    var existing = store.repository().find(id);
    if (existing != null) return existing;
    long count =
        store.ships().stream()
            .map(t -> (CompoundTag) t)
            .filter(s -> station(s) && s.getStringOr("owner", "").equals(owner))
            .count();
    if (!ambient && count >= 32)
      throw new IllegalArgumentException("Station quota reached for this owner");
    int width = StationSpace.extent(plan, true), depth = StationSpace.extent(plan, false);
    var base =
        ambient ? reserve(server, width, depth, 6000).orElse(null) : reserve(server, width, depth);
    if (base == null) return null;
    var level = server.getLevel(TravelService.SPACE);
    if (level == null) throw new IllegalArgumentException("Space dimension unavailable");
    var s = metadata.copy();
    s.putString("id", id);
    s.putString("kind", "station");
    s.putString("home", name);
    s.putString("owner", owner);
    s.putString("dimension", TravelService.SPACE.identifier().toString());
    s.putString("localSpace", address);
    s.putString("access", owner.isEmpty() ? "public" : "private");
    s.putLong("base", base.asLong());
    s.putLong("controller", base.offset(plan.core()).asLong());
    s.putInt("width", width);
    s.putInt("depth", depth);
    s.putInt("height", 32);
    s.putDouble("simX", position.x);
    s.putDouble("simY", position.y);
    s.putDouble("simZ", position.z);
    s.put("crew", new ListTag());
    s.put("stationBuild", encode(plan));
    s.putBoolean("foundation", foundation);
    s.putInt("stationVersion", 1);
    s.putBoolean("buildPaid", owner.isEmpty());
    store.ships().add(s);
    try {
      store.save();
    } catch (RuntimeException failure) {
      store.ships().remove(s);
      throw failure;
    }
    return s;
  }

  public static CompoundTag waypoint(ServerPlayer p, boolean random) {
    var own = SceneService.observer(p);
    if (own == null) throw new IllegalArgumentException("Use your helm in space");
    var server = p.level().getServer();
    String address = LocalSpaces.address(own);
    String id =
        UUID.nameUUIDFromBytes(
                ("nullspace:waypoint:" + address + (random ? ":generated" : ""))
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    var old = ShipStore.get(server).repository().find(id);
    if (old != null) return old;
    var probe = new CompoundTag();
    probe.putString("id", id);
    probe.putString("localSpace", address);
    probe.putLong("base", reserve(server).asLong());
    Vec3 position = DockingService.constructionSite(server, own);
    var layout =
        random
            ? StationLayout.generate(server.overworld().getSeed() ^ address.hashCode())
            : StationLayout.waypoint();
    var result =
        queue(
            server,
            id,
            random ? "Survey Station" : "Waypoint Station",
            address,
            position,
            "",
            StationBlueprint.build(layout),
            false);
    var pieces = new CompoundTag();
    for (var room : layout.rooms()) pieces.putString(room.x() + "_" + room.z(), room.role().name());
    result.put("roomPieces", pieces);
    ShipStore.get(server).save();
    return result;
  }

  public static CompoundTag showcase(ServerPlayer p) {
    var own = SceneService.observer(p);
    if (own == null || !p.level().dimension().equals(TravelService.SPACE))
      throw new IllegalArgumentException("Use your Ship Controller in space to find the showcase");
    var server = p.level().getServer();
    String address = LocalSpaces.address(own);
    String id =
        UUID.nameUUIDFromBytes(
                ("nullspace:manufacturer-showcase:v1:" + address).getBytes(StandardCharsets.UTF_8))
            .toString();
    var old = ShipStore.get(server).repository().find(id);
    if (old != null) return old;
    var result =
        queue(
            server,
            id,
            "Manufacturer Showcase",
            address,
            DockingService.constructionSite(server, own),
            "",
            ManufacturerStation.build(),
            false);
    result.putString("template", "manufacturer_showcase_v1");
    ShipStore.get(server).save();
    return result;
  }

  private static ItemStack kit(ServerPlayer p, Item item) {
    for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
      var stack = p.getInventory().getItem(i);
      if (stack.is(item)) return stack;
    }
    throw new IllegalArgumentException(
        "Carry a crafted " + new ItemStack(item).getHoverName().getString() + " first");
  }

  /** A receipt is stored atomically with player inventory; a redeemed kit cannot create twice. */
  private static String receipt(ServerPlayer p, ItemStack stack) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    String token = data.getStringOr("stationReceipt", "");
    if (!token.isEmpty()
        && ShipStore.get(p.level().getServer()).section("stationReceipts").contains(token))
      throw new IllegalArgumentException(
          "That kit was already deployed; its inventory receipt is being settled");
    if (token.isEmpty()) {
      token = UUID.randomUUID().toString();
      data.putString("stationReceipt", token);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
    p.level().getServer().getPlayerList().saveAll();
    try {
      var file =
          p.level()
              .getServer()
              .getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
              .resolve(p.getUUID() + ".dat");
      if (!ShipStore.read(file).toString().contains(token))
        throw new IllegalStateException(
            "Could not verify the saved inventory receipt; construction has not been committed");
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException("Cannot verify the saved construction inventory", unreadable);
    }
    return token;
  }

  private static void redeem(ServerPlayer p, ItemStack stack, String token, int cost) {
    var ledger = ShipStore.get(p.level().getServer()).section("stationReceipts");
    if (ledger.contains(token))
      throw new IllegalArgumentException("This kit has already been deployed");
    ledger.putInt(token, p.isCreative() ? 0 : cost);
    for (var tag : ShipStore.get(p.level().getServer()).ships()) {
      var s = (CompoundTag) tag;
      if (idOf(s).equals(token) || s.getStringOr("buildReceipt", "").equals(token))
        s.putBoolean("buildPaid", true);
    }
    ShipStore.get(p.level().getServer()).save();
    if (!p.isCreative()) stack.shrink(cost);
    stack.remove(DataComponents.CUSTOM_DATA);
    p.level().getServer().getPlayerList().saveAll();
  }

  public static CompoundTag start(ServerPlayer p, CompoundTag own) {
    return start(p, own, kit(p, ShipBlocks.STATION_CORE.asItem()));
  }

  private static CompoundTag start(ServerPlayer p, CompoundTag own, ItemStack stack) {
    DockingService.requireFree(p, own);
    var source = DockingService.firstPort(p.level(), own);
    String id = receipt(p, stack);
    var existing = ShipStore.get(p.level().getServer()).repository().find(id);
    if (existing != null)
      throw new IllegalArgumentException(
          "This core already owns a station; finish its construction");
    var probe = new CompoundTag();
    probe.putString("id", id);
    probe.putString("localSpace", LocalSpaces.address(own));
    probe.putLong("base", reserve(p.level().getServer()).asLong());
    var s =
        queue(
            p.level().getServer(),
            id,
            p.getPlainTextName() + " Station",
            LocalSpaces.address(own),
            DockingService.constructionSite(p.level().getServer(), own),
            p.getUUID().toString(),
            StationBlueprint.foundation(),
            true);
    s.putString("constructionShip", own.getStringOr("id", ""));
    s.putLong(
        "constructionSourcePort",
        source.controller().subtract(ShipStore.pos(own, "base")).asLong());
    redeem(p, stack, id, 1);
    return s;
  }

  public static boolean stationStartTarget(net.minecraft.world.level.Level level, BlockPos pos) {
    var state = level.getBlockState(pos);
    var block = state.getBlock();
    return !state.isAir()
        && state.getMenuProvider(level, pos) == null
        && !(block instanceof EntityBlock)
        && !(block instanceof ShipBlocks.Machine)
        && !(block instanceof DoorBlock)
        && !(block instanceof TrapDoorBlock)
        && !(block instanceof FenceGateBlock)
        && !(block instanceof ButtonBlock)
        && !(block instanceof LeverBlock)
        && !(block instanceof BedBlock)
        && !(block instanceof NoteBlock)
        && !(block instanceof ComposterBlock)
        && !(block instanceof CakeBlock)
        && !(block instanceof RespawnAnchorBlock)
        && !(block instanceof DaylightDetectorBlock)
        && !(block instanceof FlowerPotBlock);
  }

  private static boolean stationStartGesture(
      net.minecraft.world.entity.player.Player player,
      net.minecraft.world.level.Level level,
      InteractionHand hand) {
    return player.isShiftKeyDown()
        && level.dimension().equals(TravelService.SPACE)
        && player.getItemInHand(hand).is(ShipBlocks.STATION_CORE.asItem());
  }

  private static InteractionResult startFromGesture(
      net.minecraft.world.entity.player.Player player, InteractionHand hand) {
    if (!(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
    try {
      var own = SceneService.observer(p);
      if (own == null) throw new IllegalArgumentException("Use your helm in space");
      start(p, own, p.getItemInHand(hand));
      p.sendSystemMessage(Component.literal("Station construction started"));
    } catch (IllegalArgumentException blocked) {
      p.sendSystemMessage(Component.literal(blocked.getMessage()));
    }
    return InteractionResult.SUCCESS;
  }

  public static void buildRoom(ServerPlayer p, CompoundTag s, String argument, boolean generate) {
    requireBuilder(p, s);
    if (!station(s)
        || !ready(s)
        || s.contains("dock")
        || TravelService.busy(p.level().getServer(), s))
      throw new IllegalArgumentException("Finish construction or travel before adding a module");
    var stack = kit(p, ShipBlocks.STATION_MODULE);
    String token = receipt(p, stack);
    var roles = StationLayout.Role.values();
    String[] words = argument.strip().toUpperCase(Locale.ROOT).split(" +");
    StationBlueprint.Plan plan;
    var pieces = s.getCompoundOrEmpty("roomPieces").copy();
    var additions = new ArrayList<StationLayout.Room>();
    if (generate) {
      if (!p.isCreative() && stack.getCount() < (s.getIntOr("width", 32) > 32 ? 28 : 4))
        throw new IllegalArgumentException(
            "Carry enough module kits: four for a starter or 28 for a full-size ring");
      if (!pieces.isEmpty())
        throw new IllegalArgumentException(
            "Generated assembly requires an empty construction section; add individual room kits to"
                + " expand");
      var layout =
          s.getIntOr("width", 32) > 32
              ? StationLayout.harbor()
              : StationLayout.generate(s.getStringOr("id", "").hashCode());
      plan = StationBlueprint.build(layout);
      additions.addAll(layout.rooms());
    } else {
      StationLayout.Role role;
      try {
        role = StationLayout.Role.valueOf(words[0]);
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Enter arrival, workshop, habitation or engineering; optional tile x z (0..7)");
      }
      int x = 0, z = 0;
      if (words.length == 3) {
        try {
          x = Integer.parseInt(words[1]);
          z = Integer.parseInt(words[2]);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Use room x z with x and z 0..7");
        }
      } else if (words.length != 1)
        throw new IllegalArgumentException("Use room x z, for example engineering 1 1");
      // Every kit includes a registered core location, but an existing station retains its core.
      var single =
          new StationLayout.Layout(List.of(new StationLayout.Room("piece", role, x, z)), List.of());
      if (pieces.contains(x + "_" + z))
        throw new IllegalArgumentException("That room tile is already occupied");
      if (!pieces.isEmpty()
          && pieces.keySet().stream()
              .noneMatch(
                  key -> {
                    var coords = key.split("_");
                    var room = single.rooms().getFirst();
                    return Math.abs(Integer.parseInt(coords[0]) - room.x())
                            + Math.abs(Integer.parseInt(coords[1]) - room.z())
                        == 1;
                  }))
        throw new IllegalArgumentException("Choose a room tile adjoining an existing room");
      if (pieces.isEmpty() && (x != 0 || z != 0))
        throw new IllegalArgumentException(
            "Build the arrival tile at 0,0 first, then expand through adjacent tiles");
      additions.addAll(single.rooms());
      plan = StationBlueprint.build(single);
    }
    for (var room : additions)
      if ((room.x() + 1) * 16 > s.getIntOr("width", 32)
          || (room.z() + 1) * 16 > s.getIntOr("depth", 32))
        throw new IllegalArgumentException(
            "Expand the station build area at its core before building this tile");
    var out = new LinkedHashMap<>(plan.blocks());
    var base = ShipStore.pos(s, "base");
    var oldCore = ShipStore.pos(s, "controller").subtract(base);
    out.remove(plan.core());
    out.put(oldCore, ShipBlocks.STATION_CORE.defaultBlockState());
    var seamExpected = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
    for (var room : additions)
      for (var key : pieces.keySet()) {
        var coords = key.split("_");
        var previous =
            new StationLayout.Room(
                key,
                StationLayout.Role.valueOf(pieces.getStringOr(key, "")),
                Integer.parseInt(coords[0]),
                Integer.parseInt(coords[1]));
        if (Math.abs(room.x() - previous.x()) + Math.abs(room.z() - previous.z()) != 1) continue;
        var door = StationLayout.doorway(previous, StationLayout.direction(previous, room));
        var expected =
            StationBlueprint.build(new StationLayout.Layout(List.of(previous), List.of())).blocks();
        for (int y = -1; y <= 2; y++) seamExpected.put(door.above(y), expected.get(door.above(y)));
        StationBlueprint.connect(out, previous, room);
      }
    var dockingProblem = DockingService.constructionProblem(p.level(), s, out);
    if (dockingProblem.isPresent()) {
      var problem = dockingProblem.get();
      ShipDiagnostics.highlight(p, List.of(problem.position()));
      throw ShipMessages.blockProblem(p.level(), problem.position(), problem.message());
    }
    var foundation =
        s.getBooleanOr("foundation", false)
            ? StationBlueprint.foundation().blocks()
            : Map.<BlockPos, net.minecraft.world.level.block.state.BlockState>of();
    for (var e : out.entrySet()) {
      var before = p.level().getBlockState(base.offset(e.getKey()));
      if (!before.isAir()
          && !before.equals(e.getValue())
          && !before.equals(foundation.get(e.getKey()))
          && !before.equals(seamExpected.get(e.getKey()))
          && !e.getKey().equals(oldCore))
        throw new IllegalArgumentException(
            "Module is obstructed at "
                + base.offset(e.getKey()).toShortString()
                + "; existing construction is preserved");
      if (!e.getValue().isAir()
          && !before.equals(e.getValue())
          && !p.level().getEntities(null, new AABB(base.offset(e.getKey()))).isEmpty())
        throw new IllegalArgumentException("Move occupants out of the module footprint first");
    }
    s.put("stationBuild", encode(new StationBlueprint.Plan(out, oldCore, plan.docks())));
    s.putString("buildReceipt", token);
    s.put("piecesBefore", pieces.copy());
    for (var room : additions) pieces.putString(room.x() + "_" + room.z(), room.role().name());
    s.put("roomPieces", pieces);
    s.putBoolean("buildPaid", false);
    s.putBoolean("foundation", false);
    ShipStore.get(p.level().getServer()).save();
    redeem(p, stack, token, generate ? additions.size() : 1);
  }

  public static void expand(ServerPlayer player, CompoundTag station) {
    requireBuilder(player, station);
    if (!station(station)
        || !ready(station)
        || TravelService.busy(player.level().getServer(), station))
      throw new IllegalArgumentException("Finish station construction and travel before expanding");
    if (station.getIntOr("width", 32) == 128 && station.getIntOr("depth", 32) == 128) return;
    var level = player.level();
    var base = ShipStore.pos(station, "base");
    var oldBounds = ShipStore.bounds(station);
    var bounds = new ShipScan.Bounds(base, base.offset(127, 31, 127));
    for (var tag : ShipStore.get(level.getServer()).ships()) {
      var other = (CompoundTag) tag;
      if (other != station
          && other.getStringOr("dimension", "").equals(station.getStringOr("dimension", ""))
          && ShipStore.bounds(other).box().intersects(bounds.box()))
        throw new IllegalArgumentException("Station expansion overlaps another registered vessel");
    }
    for (int x = base.getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = base.getZ() >> 4; z <= bounds.max().getZ() >> 4; z++) level.getChunk(x, z);
    for (var at : BlockPos.betweenClosed(bounds.min(), bounds.max()))
      if (!oldBounds.contains(at) && !level.getBlockState(at).isAir())
        throw new IllegalArgumentException(
            "Expansion is obstructed at " + at.toShortString() + "; existing blocks are preserved");
    if (level.getEntities(null, bounds.box()).stream()
        .anyMatch(e -> !oldBounds.box().contains(e.position())))
      throw new IllegalArgumentException(
          "Move occupants out of the new station build area before expanding");
    ShipSystems.resizeStation(level, station, 128, 128);
    ShipStore.get(level.getServer()).save();
  }

  public static void tickBuilds(MinecraftServer server) {
    var progress = ServerContext.get(server).service(PROGRESS, HashMap::new);
    var store = ShipStore.get(server);
    // Explicit player work goes first; construction is confined to its reserved station area.
    for (var tag :
        store.ships().stream()
            .sorted(
                Comparator.comparing(t -> ((CompoundTag) t).getBooleanOr("worldGenerated", false)))
            .toList()) {
      var s = (CompoundTag) tag;
      if (!s.contains("stationBuild") || !s.getBooleanOr("buildPaid", true)) continue;
      var level = ShipStore.level(server, s.getStringOr("dimension", ""));
      var base = ShipStore.pos(s, "base");
      StationSpace.chunks(level, s);
      var blocks = s.getListOrEmpty("stationBuild");
      String id = s.getStringOr("id", "");
      int index = progress.getOrDefault(id, 0);
      if (s.getBooleanOr("stationRegeneration", false)) {
        var occupants = level.getEntities(null, ShipStore.bounds(s).box());
        if (occupants.stream().anyMatch(e -> e instanceof ServerPlayer)) return;
        if (index == 0) for (var entity : occupants) entity.discard();
      }

      PLACING.set(true);
      try {
        int end = Math.min(blocks.size(), index + 384);
        for (; index < end; index++) {
          var e = blocks.getCompound(index).orElseThrow();
          var at = base.offset(ShipStore.pos(e, "pos"));
          var state =
              NbtUtils.readBlockState(
                  level.registryAccess().lookupOrThrow(Registries.BLOCK),
                  e.getCompoundOrEmpty("state"));
          if (s.getBooleanOr("stationRegeneration", false)
              && state.isAir()
              && level.getBlockEntity(at) instanceof net.minecraft.world.Container container)
            container.clearContent();
          level.setBlock(at, state, 18);
        }
        if (index == blocks.size()
            && !s.getBooleanOr("stationInitialized", false)
            && s.getStringOr("template", "").equals("manufacturer_showcase_v1"))
          ManufacturerStation.label(level, base);
      } finally {
        PLACING.set(false);
      }
      progress.put(id, index);
      if (index < blocks.size()) return;
      for (var entry : blocks) {
        var block = (CompoundTag) entry;
        var position = base.offset(ShipStore.pos(block, "pos"));
        com.nullandkale.nullspace.systems.FuelSystem.provision(level, position, 64);
      }
      if (!ShipSystems.enabled(s)) ShipSystems.upgrade(level, s);
      if (!s.getBooleanOr("stationInitialized", false)) {
        for (var device : s.getCompoundOrEmpty("systems").getListOrEmpty("devices")) {
          var d = (CompoundTag) device;
          if (d.getStringOr("type", "").equals("nullspace:battery")) d.putInt("energy", 1200);
        }
        if (s.contains("encounterKind")) EncounterSites.stock(level, s);
        else if (s.getStringOr("owner", "").isEmpty()) {
          for (var at :
              BlockPos.betweenClosed(ShipStore.bounds(s).min(), ShipStore.bounds(s).max()))
            if ((level.getBlockEntity(at)
                        instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                    || level.getBlockEntity(at)
                        instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)
                && level.getBlockEntity(at) instanceof net.minecraft.world.Container container) {
              container.clearContent();
              container.setItem(0, new ItemStack(Items.QUARTZ_BLOCK, 32));
              container.setItem(1, new ItemStack(Items.IRON_INGOT, 16));
              container.setChanged();
              break;
            }
        }
        s.putBoolean("stationInitialized", true);
      }
      s.getCompoundOrEmpty("systems")
          .putIntArray(
              "requests",
              new int[] {
                1,
                Math.max(
                    12,
                    (int)
                            ShipSystems.connected(level, s).stream()
                                .filter(p -> level.getBlockState(p).is(ShipBlocks.AIR_SUPPLY))
                                .count()
                        * 2),
                1,
                0,
                2,
                0
              });
      ShipSystems.changed(level, base, false);
      s.remove("stationBuild");
      s.remove("piecesBefore");
      s.remove("stationRegeneration");
      // Persist blocks before publishing a ready station. Replay is safe while construction is
      // inaccessible.
      try {
        server.saveEverything(false, true, true);
        store.save();
      } catch (RuntimeException e) {
        s.put("stationBuild", blocks);
        throw e;
      }
      progress.remove(id);
      if (s.getBooleanOr("foundation", false)) DockingService.connectConstruction(server, s);
      else DockingService.commissionConstruction(server, s);
      return;
    }
  }

  public static void action(
      ServerPlayer p, BlockPos at, ComputerTerminal.Action action, String argument) {
    var s = at(p, at);
    String notice = "";
    switch (action.name()) {
      case "STATION_CLAIM" -> {
        notice = EncounterClaims.claim(p, s);
        if (!station(s)) {
          // Claimed: the core is now a ship controller. Continue at the ship's controls.
          com.nullandkale.nullspace.navigation.ShipControl.open(
              p, ShipStore.pos(s, "controller"), notice);
          return;
        }
      }
      case "STATION_REFRESH" -> {
        if (p.level().getBlockState(at).is(ShipBlocks.STATION_CORE)) notice = showProblem(p, s);
      }
      case "STATION_SYSTEMS" -> {
        requireBuilder(p, s);
        ShipSystems.action(p, at, ComputerTerminal.Action.SYSTEMS);
        return;
      }
      case "STATION_WAYPOINT" -> {
        notice = "Stations are discovered automatically. Choose Destination > Ports.";
      }
      case "STATION_START" -> {
        start(p, s);
        notice =
            "Station deployed. Automatic construction docking starts when its platform is ready.";
      }
      case "STATION_MODULE", "STATION_GENERATE" -> {
        buildRoom(p, s, argument, action.name().equals("STATION_GENERATE"));
        notice = "Module construction started.";
      }
      case "STATION_EXPAND" -> {
        expand(p, s);
        notice =
            "Station build area expanded to 128 x 32 x 128. Existing blocks and systems are"
                + " preserved.";
      }
      case "STATION_RENAME" -> {
        requireBuilder(p, s);
        if (argument.isBlank()) throw new IllegalArgumentException("Enter a station name");
        s.putString("home", argument);
      }
      case "STATION_INVITE" -> {
        if (!owner(p, s)) throw new IllegalArgumentException("Only the owner can invite builders");
        var target = p.level().getServer().getPlayerList().getPlayerByName(argument);
        if (target == null) throw new IllegalArgumentException("That player must be online");
        var builders = s.getCompoundOrEmpty("builders");
        builders.putBoolean(target.getUUID().toString(), true);
        s.put("builders", builders);
        var names = s.getCompoundOrEmpty("builderNames");
        names.putString(target.getUUID().toString(), target.getPlainTextName());
        s.put("builderNames", names);
      }
      case "STATION_REMOVE" -> {
        if (!owner(p, s)) throw new IllegalArgumentException("Only the owner can remove builders");
        var builders = s.getCompoundOrEmpty("builders");
        builders.keySet().stream()
            .filter(id -> id.replace("-", "").equals(argument))
            .findFirst()
            .ifPresent(
                id -> {
                  builders.remove(id);
                  s.getCompoundOrEmpty("builderNames").remove(id);
                });
      }
      case "STATION_ACCESS" -> {
        if (!owner(p, s))
          throw new IllegalArgumentException("Only the owner can change station access");
        s.putString(
            "access", s.getStringOr("access", "private").equals("public") ? "private" : "public");
      }
      case "STATION_DOOR" -> {
        if (!allowed(p, s) && !returningVisitor(p, s))
          throw new IllegalArgumentException("Station access is private");
        var door = at.below(2);
        if (DockingService.cycleOwnedInner(p, s, door)) break;
        if (DockingService.ownsDoor(p.level(), door))
          throw new IllegalArgumentException("This door is owned by docking");
        if (ShipSystems.power(p.level(), s, PowerBus.System.DOORS) == 0)
          throw new IllegalArgumentException("Doors have no power");
        if (!ShipDoors.framed(p.level(), door) || ShipDoors.powered(p.level(), door))
          throw new IllegalArgumentException("Door is missing, unframed or overridden by redstone");
        if (station(s) && !builder(p, s) && !ShipDoors.open(p.level().getBlockState(door))) {
          var facing = p.level().getBlockState(door).getValue(DoorBlock.FACING);
          if (!ShipSystems.breathable(p.level(), s, door.relative(facing).above())
              || !ShipSystems.breathable(p.level(), s, door.relative(facing.getOpposite()).above()))
            throw new IllegalArgumentException(
                "This bulkhead remains closed because pressure is unsafe");
        }
        ShipDoors.setOpen(p.level(), door, !ShipDoors.open(p.level().getBlockState(door)));
      }
      default -> {
        DockingService.action(p, s, at, action.name());
        return;
      }
    }
    ShipStore.get(p.level().getServer()).requestSave();
    if (ready(s)) open(p, at, notice);
  }

  public static void changed(ServerLevel level, BlockPos at, boolean replaced) {
    if (PLACING.get() || TravelService.worldMutation()) return;
    var s = ShipStore.get(level.getServer()).at(level, at);
    if (s == null) return;
    s.putInt("stationRevision", s.getIntOr("stationRevision", 0) + 1);
    if (replaced)
      s.getCompoundOrEmpty("doorNodes")
          .remove(Long.toString(at.subtract(ShipStore.pos(s, "base")).asLong()));
  }

  public static boolean mayEdit(ServerPlayer p, BlockPos at) {
    var s = ShipStore.get(p.level().getServer()).at(p.level(), at);
    return s == null
        || (!station(s) || builder(p, s) || s.getBooleanOr("salvage", false)) && ready(s);
  }

  public static boolean mayUse(ServerPlayer p, BlockPos at) {
    var s = ShipStore.get(p.level().getServer()).at(p.level(), at);
    if (!station(s)) return true;
    if (!ready(s)) return false;
    if (s.getBooleanOr("salvage", false)) return true;
    var state = p.level().getBlockState(at);
    if (!builder(p, s)
        && (allowed(p, s) || returningVisitor(p, s))
        && state.getBlock() instanceof DoorBlock) {
      var door =
          state.getValue(DoorBlock.HALF)
                  == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
              ? at.below()
              : at;
      if (p.level().getBlockState(door.below()).is(ShipBlocks.DOCKING_BASE)) return true;
      var facing = state.getValue(DoorBlock.FACING);
      return ShipDoors.framed(p.level(), door)
          && (ShipDoors.open(state)
              || SpaceSurvival.suited(p)
              || ShipSystems.breathable(p.level(), s, door.relative(facing).above())
                  && ShipSystems.breathable(
                      p.level(), s, door.relative(facing.getOpposite()).above()));
    }
    return builder(p, s)
        || (allowed(p, s) || returningVisitor(p, s))
            && (state.is(ShipBlocks.DOCKING_PORT)
                || state.is(ShipBlocks.DOCKING_BASE)
                || state.is(ShipBlocks.STATION_CORE)
                || state.is(ShipBlocks.DOOR_CONNECTOR)
                || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.STONECUTTER));
  }

  public static boolean useDoor(ServerPlayer p, BlockPos at) {
    var level = p.level();
    if (TravelService.busy(p) || TravelService.locked(level, at)) return false;
    var state = level.getBlockState(at);
    if (!station(ShipStore.get(level.getServer()).at(level, at))
        || !(state.getBlock() instanceof DoorBlock)
        || !mayUse(p, at)) return false;
    var door =
        state.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
            ? at.below()
            : at;
    if (DockingService.ownsDoor(level, door)) return false;
    if (!ShipDoors.powered(level, door)) ShipDoors.setOpen(level, door, !ShipDoors.open(state));
    else p.sendOverlayMessage(Component.literal("Redstone is controlling this door"));
    return true;
  }

  public static void initialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          try {
            StationRegeneration.applyPending(server);
          } catch (Exception error) {
            com.nullandkale.nullspace.NullSpace.LOGGER.error(
                "Station regeneration was not applied; its request and backup are preserved",
                error);
          }
          var store = ShipStore.get(server);
          for (var tag : List.copyOf(store.ships())) {
            var s = (CompoundTag) tag;
            if (s.contains("stationBuild") && !s.getBooleanOr("buildPaid", true)) {
              if (!s.contains("systems")) store.ships().remove(s);
              else {
                s.remove("stationBuild");
                s.put("roomPieces", s.getCompoundOrEmpty("piecesBefore"));
                s.remove("piecesBefore");
              }
            }
          }
        });
    ServerContext.tick(
        ServerContext.Phase.SESSIONS,
        server -> {
          var ledger = ShipStore.get(server).section("stationReceipts");
          boolean changed = false;
          for (var p : server.getPlayerList().getPlayers())
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
              var stack = p.getInventory().getItem(i);
              String token =
                  stack
                      .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                      .copyTag()
                      .getStringOr("stationReceipt", "");
              if (!token.isEmpty() && ledger.contains(token)) {
                stack.shrink(ledger.getIntOr(token, 1));
                stack.remove(DataComponents.CUSTOM_DATA);
                changed = true;
              }
            }
          if (changed) server.getPlayerList().saveAll();
        });
    ServerContext.tick(ServerContext.Phase.TRAVEL, StationService::tickBuilds);
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> ServerContext.get(server).remove(PROGRESS));
    UseBlockCallback.EVENT.register(
        (player, world, hand, hit) -> {
          if (stationStartGesture(player, world, hand)
              && stationStartTarget(world, hit.getBlockPos())) {
            if (player instanceof ServerPlayer p && !mayUse(p, hit.getBlockPos()))
              return InteractionResult.FAIL;
            return startFromGesture(player, hand);
          }
          if (player instanceof ServerPlayer p
              && (!mayUse(p, hit.getBlockPos())
                  || p.getItemInHand(hand).getItem() instanceof BlockItem
                      && !mayEdit(p, hit.getBlockPos().relative(hit.getDirection()))))
            return InteractionResult.FAIL;
          if (player instanceof ServerPlayer p && useDoor(p, hit.getBlockPos()))
            return InteractionResult.SUCCESS;
          return InteractionResult.PASS;
        });
    UseItemCallback.EVENT.register(
        (player, world, hand) -> {
          if (stationStartGesture(player, world, hand)
              && player.pick(player.blockInteractionRange(), 0, false).getType()
                  == HitResult.Type.MISS) return startFromGesture(player, hand);
          return InteractionResult.PASS;
        });
    PlayerBlockBreakEvents.BEFORE.register(
        (world, player, pos, state, be) -> !(player instanceof ServerPlayer p) || mayEdit(p, pos));
    UseEntityCallback.EVENT.register(
        (player, world, hand, entity, hit) ->
            player instanceof ServerPlayer p && !mayEdit(p, entity.blockPosition())
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
    AttackEntityCallback.EVENT.register(
        (player, world, hand, entity, hit) ->
            player instanceof ServerPlayer p && !mayEdit(p, entity.blockPosition())
                ? InteractionResult.FAIL
                : InteractionResult.PASS);
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("station")
                            .then(
                                Commands.literal("showcase")
                                    .executes(
                                        ctx -> {
                                          showcase(ctx.getSource().getPlayerOrException());
                                          ctx.getSource()
                                              .sendSuccess(
                                                  () ->
                                                      Component.literal(
                                                          "Manufacturer Showcase prepared; select"
                                                              + " its port as Destination when"
                                                              + " ready."),
                                                  false);
                                          return 1;
                                        }))
                            .then(
                                Commands.literal("waypoint")
                                    .executes(
                                        ctx -> {
                                          var p = ctx.getSource().getPlayerOrException();
                                          waypoint(p, false);
                                          p.sendSystemMessage(
                                              Component.literal(
                                                  "Waypoint Station prepared; use Docking at the"
                                                      + " helm."));
                                          return 1;
                                        }))
                            .then(
                                Commands.literal("generate")
                                    .executes(
                                        ctx -> {
                                          var p = ctx.getSource().getPlayerOrException();
                                          waypoint(p, true);
                                          return 1;
                                        })))));
  }

  private StationService() {}

  private static String idOf(CompoundTag s) {
    return s.getStringOr("id", "");
  }
}
