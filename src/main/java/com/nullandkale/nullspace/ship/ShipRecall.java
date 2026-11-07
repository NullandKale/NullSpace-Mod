package com.nullandkale.nullspace.ship;

import com.nullandkale.nullspace.navigation.NavigationService;
import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.server.ServerContext;
import com.nullandkale.nullspace.terminal.ComputerTerminal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;

/** Uncrewed recovery uses the same durable transfer journal as normal flight. */
public final class ShipRecall {
  private static final ServerContext.Key<Map<UUID, Job>> KEY = new ServerContext.Key<>();

  private static Map<UUID, Job> jobs(MinecraftServer server) {
    return ServerContext.get(server).service(KEY, LinkedHashMap::new);
  }

  private static final class Job {
    final UUID player, session;
    final BlockPos computer;
    final String ship, dimension;
    final ShipScan.Bounds source, target;
    final boolean store;
    int ticks;

    Job(ServerPlayer p, BlockPos computer, CompoundTag ship, PadRegistry.Pad pad, UUID session) {
      this(p, computer, ship, pad, session, false);
    }

    Job(
        ServerPlayer p,
        BlockPos computer,
        CompoundTag ship,
        PadRegistry.Pad pad,
        UUID session,
        boolean store) {
      this.store = store;
      player = p.getUUID();
      this.session = session;
      this.computer = computer.immutable();
      this.ship = ship.getStringOr("id", "");
      dimension = pad.dimension();
      source = ShipStore.bounds(ship);
      var b = pad.bounds();
      target =
          new ShipScan.Bounds(
              new BlockPos(b.minX(), b.y() + 1, b.minZ()),
              new BlockPos(b.maxX(), b.y() + 32, b.maxZ()));
    }
  }

  public static void initialize() {
    ServerContext.tick(ServerContext.Phase.TRAVEL, ShipRecall::tick);
  }

  public static boolean active(MinecraftServer server) {
    return !jobs(server).isEmpty();
  }

  public static boolean active(MinecraftServer server, String ship) {
    return jobs(server).values().stream().anyMatch(j -> j.ship.equals(ship));
  }

  static boolean areaBusy(ServerLevel level, ShipScan.Bounds bounds, UUID exceptOwner) {
    return jobs(level.getServer()).values().stream()
        .anyMatch(
            j ->
                !j.player.equals(exceptOwner)
                    && (level.dimension().equals(TravelService.SPACE)
                            && j.source.box().intersects(bounds.box())
                        || level.dimension().identifier().toString().equals(j.dimension)
                            && j.target.box().intersects(bounds.box())));
  }

  public static boolean locked(ServerLevel level, BlockPos at) {
    if (TravelService.worldMutation()) return false;
    return areaBusy(level, new ShipScan.Bounds(at, at), null);
  }

  /** Crew death must remove respawn targets without erasing the right to recover their ship. */
  public static void authorize(CompoundTag ship, ServerPlayer p) {
    if (Set.of("station", "debris").contains(ship.getStringOr("kind", ""))) return;
    if (ship.getStringOr("owner", "").isEmpty()) ship.putString("owner", p.getUUID().toString());
    var users = ship.getCompoundOrEmpty("recallUsers");
    users.putBoolean(p.getUUID().toString(), true);
    ship.put("recallUsers", users);
  }

  public static void rememberDeath(ServerPlayer p) {
    var store = ShipStore.get(p.level().getServer());
    for (var tag : store.ships()) {
      var ship = (CompoundTag) tag;
      for (var member : ship.getListOrEmpty("crew")) {
        var crew = (CompoundTag) member;
        if (!crew.getStringOr("id", "").equals(p.getUUID().toString())) continue;
        String returnShip = crew.getStringOr("returnShip", "");
        var home =
            !returnShip.isEmpty()
                ? store.repository().find(returnShip)
                : Set.of("station", "debris").contains(ship.getStringOr("kind", "")) ? null : ship;
        if (home != null) authorize(home, p);
      }
    }
    store.requestSave();
  }

  private static String key(CompoundTag ship) {
    return UUID.nameUUIDFromBytes(
            ("recall:" + ship.getStringOr("id", "")).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static PadRegistry.Pad pad(ServerPlayer p, BlockPos at) {
    return PadRepository.get(p.level().getServer())
        .dimension(p.level().dimension().identifier().toString())
        .stream()
        .filter(pad -> pad.computer().equals(at))
        .findFirst()
        .orElse(null);
  }

  private static boolean permitted(ServerPlayer p, CompoundTag ship, PadRegistry.Pad pad) {
    String user = p.getUUID().toString();
    return ship.getStringOr("owner", "").equals(user)
        || ship.getCompoundOrEmpty("recallUsers").getBooleanOr(user, false)
        // Existing saves did not record ownership. Administrators may recover an unclaimed ship
        // only at its recorded home pad, rather than exposing other players' claimed ships.
        || pad != null
            && ship.getStringOr("owner", "").isEmpty()
            && ship.getCompoundOrEmpty("recallUsers").isEmpty()
            && (ship.getStringOr("homeId", "").equals(pad.id())
                || ship.getStringOr("homeId", "").isEmpty()
                    && ship.getStringOr("home", "").equalsIgnoreCase(pad.name()))
            && (p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || p.level().getServer().isSingleplayerOwner(p.nameAndId()));
  }

  private static String unavailable(MinecraftServer server, CompoundTag ship, UUID requester) {
    String shipId = ship.getStringOr("id", "");
    if (jobs(server).values().stream()
        .anyMatch(j -> j.ship.equals(shipId) && !j.player.equals(requester)))
      return "This ship is already being recalled by another player";
    if (TravelService.physicalBusy(server, ship)
        || NavigationService.pending(server, shipId)
        || com.nullandkale.nullspace.station.DockingService.boarding(server, shipId))
      return "This ship is already travelling or boarding";
    if (!ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())
        || Set.of("station", "debris").contains(ship.getStringOr("kind", "")))
      return "Only ships in space can be recalled";
    if (ship.contains("dock") || !ship.getCompoundOrEmpty("dockLinks").isEmpty())
      return "Undock this ship before recalling it";
    if (ship.contains("stationBuild"))
      return "Finish station construction before recalling this ship";
    var bounds = ShipStore.bounds(ship);
    for (var p : server.getPlayerList().getPlayers())
      if (p.isAlive()
          && p.level().dimension().equals(TravelService.SPACE)
          && bounds.box().intersects(p.getBoundingBox()))
        return "Crew aboard: "
            + p.getPlainTextName()
            + " at "
            + p.blockPosition().toShortString()
            + ". Everyone must leave before recall";
    for (var item : ship.getListOrEmpty("crew")) {
      var member = (CompoundTag) item;
      String id = member.getStringOr("id", "");
      if (!id.equals(requester.toString())
          && server.getPlayerList().getPlayers().stream()
              .noneMatch(p -> p.getUUID().toString().equals(id)))
        return "Offline crew aboard: " + id + ". They must log in and leave before recall";
    }
    return "";
  }

  /**
   * My ships: owned or recoverable ships in space (deployable here; stored ones need their title)
   * and owned ships standing on this pad (storable).
   */
  public static List<ComputerTerminal.Destination> candidates(ServerPlayer p, BlockPos at) {
    var pad = pad(p, at);
    var server = p.level().getServer();
    String space = TravelService.SPACE.identifier().toString();
    return ShipStore.get(server).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(s -> !Set.of("station", "debris").contains(s.getStringOr("kind", "")))
        .filter(
            s ->
                s.getStringOr("dimension", "").equals(space) && permitted(p, s, pad)
                    || pad != null && onPad(s, pad) && ShipTitles.owner(p, s))
        .sorted(Comparator.comparing(s -> s.getStringOr("home", "Ship")))
        .limit(128)
        .map(
            s -> {
              boolean here = pad != null && onPad(s, pad);
              String problem =
                  here
                      ? surfaceProblem(server, p.level(), s, p.getUUID())
                      : unavailable(server, s, p.getUUID());
              String name =
                  s.getStringOr("home", "Ship")
                      + " / "
                      + s.getStringOr("id", "")
                          .substring(0, Math.min(8, s.getStringOr("id", "").length()));
              String state =
                  !problem.isEmpty()
                      ? problem.startsWith("Undock")
                          ? "DOCKED"
                          : problem.startsWith("Offline") ? "OFFLINE CREW" : "CREW ABOARD"
                      : here ? "ON_PAD" : ShipTitles.stored(s) ? "STORED" : "AVAILABLE";
              return new ComputerTerminal.Destination(
                  key(s), name.substring(0, Math.min(32, name.length())), state, problem);
            })
        .toList();
  }

  public static void open(ServerPlayer p, BlockPos at, String notice) {
    ComputerTerminal.send(
        p,
        at,
        notice.isEmpty()
            ? "Deploy brings an empty ship here with its cargo and damage; no fuel needed."
                + " Store clears this pad. Stay beside the computer while ships move."
            : notice,
        "recall",
        "");
  }

  public static String request(ServerPlayer p, BlockPos at, String selection, UUID session) {
    if (!p.isAlive()
        || p.level().dimension().equals(TravelService.SPACE)
        || !p.level().hasChunkAt(at)
        || !p.level().getBlockState(at).is(PadComputers.BLOCK)
        || p.distanceToSqr(at.getX() + .5, at.getY() + .5, at.getZ() + .5) > 64
        || !p.mayInteract(p.level(), at))
      return "Use an accessible surface Pad Computer within 8 blocks";
    if (active(p) || TravelService.busy(p))
      return "Finish or cancel your current travel or recall first";
    var pad = pad(p, at);
    if (pad == null) return "Register this landing pad before recalling a ship";
    var ship =
        ShipStore.get(p.level().getServer()).ships().stream()
            .map(t -> (CompoundTag) t)
            .filter(s -> key(s).replace("-", "").equals(selection))
            .findFirst()
            .orElse(null);
    if (ship == null || !permitted(p, ship, pad))
      return "Choose one of your ships from the recall list";
    // The owner never needs the paper; a title lets someone else deploy (playtest 3).
    if (ShipTitles.stored(ship) && !ShipTitles.owner(p, ship) && !ShipTitles.holds(p, ship))
      return "Carry " + ship.getStringOr("home", "this ship") + "'s Ship Title to deploy it";
    String problem = unavailable(p.level().getServer(), ship, p.getUUID());
    if (!problem.isEmpty()) return problem;
    var area =
        new ShipScan.Bounds(
            new BlockPos(pad.bounds().minX(), pad.bounds().y() + 1, pad.bounds().minZ()),
            new BlockPos(pad.bounds().maxX(), pad.bounds().y() + 32, pad.bounds().maxZ()));
    if (TravelService.areaBusy(p.level(), area))
      return "This landing pad is reserved by another ship";
    authorize(ship, p);
    ShipStore.get(p.level().getServer()).save();
    NavigationService.closeShip(p.level().getServer(), ship.getStringOr("id", ""));
    NavigationService.stop(ship);
    var job = new Job(p, at, ship, pad, session);
    jobs(p.level().getServer()).put(p.getUUID(), job);
    return "Recall requested. Stay beside this computer; the pad and ship must remain clear";
  }

  public static boolean active(ServerPlayer p) {
    return jobs(p.level().getServer()).containsKey(p.getUUID());
  }

  public static String cancel(ServerPlayer p) {
    var job = jobs(p.level().getServer()).remove(p.getUUID());
    if (job == null) return "No recall of yours is waiting";
    return "Ship recall cancelled";
  }

  static TravelService.Result move(ServerPlayer p, BlockPos at, CompoundTag ship) {
    var server = p.level().getServer();
    var destination = pad(p, at);
    if (destination == null || !permitted(p, ship, destination))
      return TravelService.Result.rejected(
          "Pad registration or ship access changed; reopen Recall ships");
    String problem = unavailable(server, ship, p.getUUID());
    if (!problem.isEmpty()) return TravelService.Result.rejected(problem);
    var padBounds =
        new ShipScan.Bounds(
            new BlockPos(
                destination.bounds().minX(),
                destination.bounds().y() + 1,
                destination.bounds().minZ()),
            new BlockPos(
                destination.bounds().maxX(),
                destination.bounds().y() + 32,
                destination.bounds().maxZ()));
    if (TravelService.areaBusy(p.level(), padBounds, p.getUUID()))
      return TravelService.Result.rejected("This landing pad is reserved by another ship");
    var source = server.getLevel(TravelService.SPACE);
    if (source == null) return TravelService.Result.rejected("Space is unavailable");
    var bounds = ShipStore.bounds(ship);
    if (bounds.max().getX() - bounds.min().getX() > 31
        || bounds.max().getZ() - bounds.min().getZ() > 31
        || bounds.max().getY() < bounds.min().getY())
      return TravelService.Result.rejected("Saved ship bounds are invalid");
    for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
      for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++) {
        source.getChunk(x, z);
        source
            .getChunkSource()
            .addTicketWithRadius(
                net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(x, z), 2);
      }
    if (!ShipEntities.loaded(source, bounds)) return TravelService.Result.waiting();
    var blocks = new HashSet<BlockPos>();
    int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
    for (var cursor : BlockPos.betweenClosed(bounds.min(), bounds.max()))
      if (!source.getBlockState(cursor).isAir()) {
        var pos = cursor.immutable();
        ShipBlockSupport.validate(source, pos);
        blocks.add(pos);
        x0 = Math.min(x0, pos.getX());
        y0 = Math.min(y0, pos.getY());
        z0 = Math.min(z0, pos.getZ());
        x1 = Math.max(x1, pos.getX());
        y1 = Math.max(y1, pos.getY());
        z1 = Math.max(z1, pos.getZ());
      }
    if (blocks.isEmpty()) return TravelService.Result.rejected("No ship blocks remain to recover");
    ShipBlockSupport.validatePistons(source, blocks);
    for (var event :
        ((com.nullandkale.nullspace.mixin.ShipBlockEventsAccessor) source).nullspace$blockEvents())
      if (blocks.contains(event.pos()))
        return TravelService.Result.rejected(
            "A ship mechanism has a queued action at "
                + event.pos().toShortString()
                + ". Wait for it to finish");
    var actual = new ShipScan.Bounds(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    var scan =
        new ShipScan.Result(
            Set.copyOf(blocks),
            actual,
            ShipScan.pressure(source, actual, blocks),
            ShipStore.pos(ship, "controller"));
    return TravelService.transferRecall(
        p,
        source,
        ShipStore.pos(ship, "controller"),
        "id:" + destination.id(),
        new ShipService.Inspection(scan, null, ship, ShipStore.pos(ship, "base")));
  }

  private static CompoundTag find(MinecraftServer server, String selection) {
    return ShipStore.get(server).ships().stream()
        .map(t -> (CompoundTag) t)
        .filter(s -> key(s).replace("-", "").equals(selection))
        .findFirst()
        .orElse(null);
  }

  private static ShipScan.Bounds padColumn(PadRegistry.Pad pad) {
    var b = pad.bounds();
    return new ShipScan.Bounds(
        new BlockPos(b.minX(), b.y() + 1, b.minZ()), new BlockPos(b.maxX(), b.y() + 32, b.maxZ()));
  }

  /** A ship sits on this pad when its record is in the pad's level and inside its column. */
  private static boolean onPad(CompoundTag ship, PadRegistry.Pad pad) {
    return ship.getStringOr("dimension", "").equals(pad.dimension())
        && !Set.of("station", "debris").contains(ship.getStringOr("kind", ""))
        && padColumn(pad).box().intersects(ShipStore.bounds(ship).box());
  }

  /** Crew, docking and travel checks for a ship on a surface pad (mirrors unavailable()). */
  private static String surfaceProblem(
      MinecraftServer server, ServerLevel level, CompoundTag ship, UUID requester) {
    String shipId = ship.getStringOr("id", "");
    if (jobs(server).values().stream()
        .anyMatch(j -> j.ship.equals(shipId) && !j.player.equals(requester)))
      return "This ship is already being stored or recalled by another player";
    if (TravelService.physicalBusy(server, ship)
        || NavigationService.pending(server, shipId)
        || com.nullandkale.nullspace.station.DockingService.boarding(server, shipId))
      return "This ship is already travelling or boarding";
    if (ship.contains("dock") || !ship.getCompoundOrEmpty("dockLinks").isEmpty())
      return "Undock this ship before storing it";
    var bounds = ShipStore.bounds(ship);
    for (var p : server.getPlayerList().getPlayers())
      if (p.isAlive() && p.level() == level && bounds.box().intersects(p.getBoundingBox()))
        return "Crew aboard: "
            + p.getPlainTextName()
            + ". Everyone must leave the ship before it is stored";
    for (var item : ship.getListOrEmpty("crew")) {
      String id = ((CompoundTag) item).getStringOr("id", "");
      if (server.getPlayerList().getPlayers().stream()
          .noneMatch(p -> p.getUUID().toString().equals(id)))
        return "Offline crew aboard: " + id + ". They must log in and leave first";
    }
    return "";
  }

  private static String access(ServerPlayer p, BlockPos at) {
    if (!p.isAlive()
        || p.level().dimension().equals(TravelService.SPACE)
        || !p.level().hasChunkAt(at)
        || !p.level().getBlockState(at).is(PadComputers.BLOCK)
        || p.distanceToSqr(at.getX() + .5, at.getY() + .5, at.getZ() + .5) > 64
        || !p.mayInteract(p.level(), at))
      return "Use an accessible surface Pad Computer within 8 blocks";
    return "";
  }

  static void markStored(ServerPlayer p, CompoundTag ship) {
    NavigationService.closeShip(p.level().getServer(), ship.getStringOr("id", ""));
    NavigationService.stop(ship);
    ship.putBoolean("stored", true);
    // Only the owner is issued a title: a crew member storing the ship must not void the owner's.
    if (ShipTitles.owner(p, ship)) ShipTitles.ensureHeld(p, ship);
  }

  /**
   * Store: a ship on this pad moves into its own private space slot through the uncrewed transfer
   * journal (no fuel, working engines or controller needed); a ship already in space is marked
   * stored where it is. Either way the owner keeps or receives the ship's title.
   */
  public static String store(ServerPlayer p, BlockPos at, String selection, UUID session) {
    String access = access(p, at);
    if (!access.isEmpty()) return access;
    if (active(p) || TravelService.busy(p))
      return "Finish or cancel your current travel or recall first";
    var server = p.level().getServer();
    var pad = pad(p, at);
    if (pad == null) return "Register this landing pad first";
    var ship = find(server, selection);
    if (ship == null) return "Choose one of your ships from the list";
    String name = ship.getStringOr("home", "This ship");
    if (!ShipTitles.owner(p, ship)) return "Only the owner can store " + name;
    if (ShipTitles.stored(ship)) return name + " is already stored";
    if (onPad(ship, pad)) {
      String problem = surfaceProblem(server, p.level(), ship, p.getUUID());
      if (!problem.isEmpty()) return problem;
      jobs(server).put(p.getUUID(), new Job(p, at, ship, pad, session, true));
      return "Storing " + name + ". Stay beside this computer until the pad is clear";
    }
    if (ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString())) {
      String problem = unavailable(server, ship, p.getUUID());
      if (!problem.isEmpty()) return problem;
      markStored(p, ship);
      ShipStore.get(server).save();
      return name + " stored where it was. Its title is in your inventory; deploy it from My ships";
    }
    return "Bring " + name + " to this pad first";
  }

  /** Issues a replacement title; every earlier copy stops working. */
  public static String title(ServerPlayer p, BlockPos at, String selection) {
    String access = access(p, at);
    if (!access.isEmpty()) return access;
    var server = p.level().getServer();
    var ship = find(server, selection);
    if (ship == null) return "Choose one of your ships from the list";
    String name = ship.getStringOr("home", "This ship");
    if (!ShipTitles.owner(p, ship)) return "Only the owner can get " + name + "'s title";
    if (ShipTitles.holds(p, ship)) return "You already carry " + name + "'s title";
    p.getInventory()
        .placeItemBackInInventory(ShipTitles.issue(ship), net.minecraft.util.Prediction.SERVER_ONLY);
    ShipStore.get(server).save();
    return "New title for " + name + " issued. Older copies no longer work";
  }

  static TravelService.Result storeMove(ServerPlayer p, BlockPos at, CompoundTag ship) {
    var server = p.level().getServer();
    var pad = pad(p, at);
    if (pad == null || !ShipTitles.owner(p, ship) || !onPad(ship, pad))
      return TravelService.Result.rejected("The ship or pad changed; reopen My ships");
    var level = p.level();
    String problem = surfaceProblem(server, level, ship, p.getUUID());
    if (!problem.isEmpty()) return TravelService.Result.rejected(problem);
    var column = padColumn(pad);
    var saved = ShipStore.bounds(ship);
    // Capture only inside the pad column, never neighbouring terrain or builds.
    var bounds =
        new ShipScan.Bounds(
            new BlockPos(
                Math.max(column.min().getX(), saved.min().getX()),
                Math.max(column.min().getY(), saved.min().getY()),
                Math.max(column.min().getZ(), saved.min().getZ())),
            new BlockPos(
                Math.min(column.max().getX(), saved.max().getX()),
                Math.min(column.max().getY(), saved.max().getY()),
                Math.min(column.max().getZ(), saved.max().getZ())));
    if (!ShipEntities.loaded(level, bounds)) return TravelService.Result.waiting();
    var blocks = new HashSet<BlockPos>();
    int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
    for (var cursor : BlockPos.betweenClosed(bounds.min(), bounds.max()))
      if (!level.getBlockState(cursor).isAir()) {
        var pos = cursor.immutable();
        ShipBlockSupport.validate(level, pos);
        blocks.add(pos);
        x0 = Math.min(x0, pos.getX());
        y0 = Math.min(y0, pos.getY());
        z0 = Math.min(z0, pos.getZ());
        x1 = Math.max(x1, pos.getX());
        y1 = Math.max(y1, pos.getY());
        z1 = Math.max(z1, pos.getZ());
      }
    if (blocks.isEmpty()) return TravelService.Result.rejected("No ship blocks remain on this pad");
    ShipBlockSupport.validatePistons(level, blocks);
    var actual = new ShipScan.Bounds(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    var scan =
        new ShipScan.Result(
            Set.copyOf(blocks),
            actual,
            ShipScan.pressure(level, actual, blocks),
            ShipStore.pos(ship, "controller"));
    return TravelService.transferRecall(
        p,
        level,
        ShipStore.pos(ship, "controller"),
        "",
        new ShipService.Inspection(scan, null, ship, ShipStore.pos(ship, "base")));
  }


  private static void tick(MinecraftServer server) {
    for (var job : List.copyOf(jobs(server).values())) tick(server, job);
  }

  private static void tick(MinecraftServer server, Job job) {
    var p = server.getPlayerList().getPlayer(job.player);
    String notice = "";
    if (p == null
        || !p.isAlive()
        || !p.level().dimension().identifier().toString().equals(job.dimension)
        || p.distanceToSqr(
                job.computer.getX() + .5, job.computer.getY() + .5, job.computer.getZ() + .5)
            > 64
        || !p.level().getBlockState(job.computer).is(PadComputers.BLOCK)
        || !p.mayInteract(p.level(), job.computer))
      notice = "Recall cancelled: stay beside the accessible Pad Computer";
    else if (++job.ticks > 600)
      notice = "Recall timed out while loading. Reopen Recall ships and try again";
    else {
      var ship = ShipStore.get(server).repository().find(job.ship);
      if (ship == null) notice = "This ship no longer exists";
      else
        try {
          var result = job.store ? storeMove(p, job.computer, ship) : move(p, job.computer, ship);
          if (result.outcome() == TravelService.Outcome.WAITING) return;
          var moved = ShipStore.get(server).repository().find(job.ship);
          if (result.outcome() == TravelService.Outcome.COMPLETED && moved != null) {
            if (job.store) markStored(p, moved);
            else moved.remove("stored");
            ShipStore.get(server).save();
          }
          notice =
              result.outcome() != TravelService.Outcome.COMPLETED || moved == null
                  ? result.message()
                  : job.store
                      ? moved.getStringOr("home", "Ship")
                          + " stored. The pad is clear and the ship's title is in your inventory"
                      : "Ship recalled to this pad. Your cargo, fuel and upgrades are preserved;"
                          + " damage still needs repair";
        } catch (Exception failure) {
          TravelService.recover(server);
          if (!(failure instanceof IllegalArgumentException))
            com.nullandkale.nullspace.NullSpace.LOGGER.error("Ship recall failed", failure);
          notice = "Recall failed: " + ShipMessages.failure(failure);
        }
    }
    jobs(server).remove(job.player, job);
    if (p != null) {
      p.sendSystemMessage(Component.literal(notice));
      if (job.session != null && ComputerTerminal.current(p, job.session))
        open(p, job.computer, notice);
    }
  }

  private ShipRecall() {}
}
