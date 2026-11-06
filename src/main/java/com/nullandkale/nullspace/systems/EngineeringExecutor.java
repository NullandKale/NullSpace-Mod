package com.nullandkale.nullspace.systems;

import static com.nullandkale.nullspace.systems.ShipSystems.*;

import com.nullandkale.nullspace.ship.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;

final class EngineeringExecutor {
  static String execute(
      ServerPlayer player,
      ServerLevel level,
      CompoundTag ship,
      ShipRuntime r,
      ShipCommand command) {
    var words = command.words().toArray(String[]::new);
    var s = state(ship);
    if (command.kind() == ShipCommand.Kind.HELP)
      return "rooms <page>; room here; air audit; profiles; profile <cruise/combat/emergency>;"
          + " power <controls air doors engines cameras weapons> <0-8>; order <six numbers"
          + " 0-5>; doors list; doors <all/group/id> <open/close>; group <name> <add/remove>"
          + " <door id>; devices <page>; device <id> <on/off>; groups; group <name> delete;"
          + " supply <on/off>. IDs use first 8 characters. Readings stay available during a"
          + " control blackout.";
    if (command.kind() == ShipCommand.Kind.PROFILES)
      return "CRUISE: travel + air + camera. COMBAT: trade engine power for weapons. EMERGENCY:"
          + " controls and doors first, up to six air power, engines/cameras/weapons off."
          + " Apply: profile emergency. Profiles request power; hardware and stored energy"
          + " still limit delivery.";
    if (command.kind() == ShipCommand.Kind.PROFILE) {
      var profile = EngineeringCommands.profile(words[1]);
      s.putIntArray("requests", profile.requests());
      s.putIntArray("order", profile.order());
      s.putString("profile", profile.name());
      return "Power profile "
          + profile.name().toUpperCase(Locale.ROOT)
          + " applied. Requests "
          + Arrays.toString(profile.requests())
          + ". Existing device switches are preserved.";
    }
    if (command.kind() == ShipCommand.Kind.ROOMS) {
      int page = 0;
      try {
        if (words.length == 2) page = Integer.parseInt(words[1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Use rooms 0 (eight rooms per page)");
      }
      var readings = rooms(level, ship);
      int last = Math.max(0, (readings.size() - 1) / 8);
      if (page < 0 || page > last)
        throw new IllegalArgumentException("Room page out of range 0.." + last);
      var text = new StringJoiner(" | ", "ROOMS page " + page + "/" + last + " | ", "");
      for (var room :
          readings.subList(
              Math.min(page * 8, readings.size()), Math.min((page + 1) * 8, readings.size())))
        text.add(room.description());
      return text.toString();
    }
    if (command.kind() == ShipCommand.Kind.ROOM_HERE) {
      var pos = BlockPos.containing(player.getEyePosition());
      var bounds = ShipStore.bounds(ship);
      if (!bounds.contains(pos)) return "OUTSIDE SHIP | No room at your position";
      int room = r.atmosphere.roomAt()[local(ship, pos)];
      return room < 0
          ? "EXTERIOR / NO ROOM | No stored cabin air"
          : rooms(level, ship).get(room).description()
              + " | Your air: "
              + (breathable(level, ship, pos) ? "BREATHABLE" : "NOT BREATHABLE");
    }
    if (command.kind() == ShipCommand.Kind.AIR_AUDIT)
      return "AIR AUDIT | Stored "
          + rooms(level, ship).stream().mapToLong(RoomReading::quantity).sum()
          + " units | Last edit displaced "
          + r.atmosphere.displaced()
          + " | Construction loss "
          + s.getLongOr("constructionVented", 0)
          + " units. Filling an entire room removes its remaining gas; reopening does not refill"
          + " it.";
    if (command.kind() == ShipCommand.Kind.POWER) {
      PowerBus.System system;
      int value;
      try {
        system = PowerBus.System.valueOf(words[1].toUpperCase(Locale.ROOT));
        value = Integer.parseInt(words[2]);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Use power cameras 2 (or help)");
      }
      if (value < 0 || value > 8) throw new IllegalArgumentException("Allocation must be 0..8");
      var requests = Arrays.copyOf(s.getIntArray("requests").orElse(new int[0]), 6);
      requests[system.ordinal()] = value;
      s.putIntArray("requests", requests);
      s.putString("profile", "custom");
      return system + " requested " + value;
    }
    if (command.kind() == ShipCommand.Kind.ORDER) {
      var order = new int[6];
      try {
        for (int i = 0; i < 6; i++) order[i] = Integer.parseInt(words[i + 1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("order 0 1 2 3 4 5");
      }
      if (Arrays.stream(order).anyMatch(n -> n < 0 || n > 5)
          || Arrays.stream(order).distinct().count() != 6)
        throw new IllegalArgumentException("Use each subsystem 0..5 exactly once");
      s.putIntArray("order", order);
      s.putString("profile", "custom");
      return "Priority saved: " + Arrays.toString(order);
    }
    if (command.kind() == ShipCommand.Kind.SUPPLY) {
      s.putBoolean("supplyDisabled", words[1].equals("off"));
      return "Supply " + words[1];
    }
    if (command.kind() == ShipCommand.Kind.DEVICES) {
      var devices =
          s.getListOrEmpty("devices").stream()
              .map(t -> (CompoundTag) t)
              .sorted(Comparator.comparing(d -> d.getStringOr("id", "")))
              .toList();
      int page = 0;
      try {
        if (words.length == 2) page = Integer.parseInt(words[1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("devices 0 (12 devices per page)");
      }
      int last = Math.max(0, (devices.size() - 1) / 12);
      if (page < 0 || page > last)
        throw new IllegalArgumentException("Device page out of range 0.." + last);
      return "DEVICES page "
          + page
          + "/"
          + last
          + " | "
          + String.join(
              " | ",
              devices.stream()
                  .skip(page * 12L)
                  .limit(12)
                  .map(
                      d -> {
                        var pos = BlockPos.of(d.getLongOr("position", 0));
                        return d.getStringOr("id", "").substring(0, 8)
                            + " "
                            + d.getStringOr("type", "").replace("nullspace:", "")
                            + " "
                            + pos.toShortString()
                            + " "
                            + (!r.blocks.contains(ShipStore.pos(ship, "base").offset(pos))
                                ? "DETACHED"
                                : d.getBooleanOr("enabled", true) ? "ON" : "OFF")
                            + " E="
                            + d.getIntOr("energy", 0);
                      })
                  .toList());
    }
    if (command.kind() == ShipCommand.Kind.DEVICE) {
      var matches =
          s.getListOrEmpty("devices").stream()
              .map(t -> (CompoundTag) t)
              .filter(d -> d.getStringOr("id", "").startsWith(words[1]))
              .toList();
      if (words[1].length() < 8 || matches.size() != 1)
        throw new IllegalArgumentException("Choose one device ID from devices");
      matches.getFirst().putBoolean("enabled", words[2].equals("on"));
      return "Device " + words[1] + " " + words[2];
    }
    var doors = doors(ship, r);
    var base = ShipStore.pos(ship, "base");
    if (command.kind() == ShipCommand.Kind.DOOR_LIST)
      return String.join(
          " | ",
          doors.stream()
              .map(
                  d -> {
                    var p = base.offset(BlockPos.of(d.getLongOr("position", 0)));
                    var block = level.getBlockState(p);
                    return d.getStringOr("id", "").substring(0, 8)
                        + " "
                        + p.subtract(base).toShortString()
                        + " "
                        + (block.hasProperty(DoorBlock.OPEN) && block.getValue(DoorBlock.OPEN)
                            ? "OPEN"
                            : "CLOSED")
                        + " groups="
                        + s.getCompoundOrEmpty("groups").keySet().stream()
                            .filter(
                                g ->
                                    s.getCompoundOrEmpty("groups").getListOrEmpty(g).stream()
                                        .anyMatch(
                                            t ->
                                                t.asString()
                                                    .orElse("")
                                                    .equals(d.getStringOr("id", ""))))
                            .toList();
                  })
              .toList());
    if (command.kind() == ShipCommand.Kind.GROUPS)
      return "GROUPS " + s.getCompoundOrEmpty("groups").keySet();
    if (command.kind() == ShipCommand.Kind.GROUP_DELETE) {
      s.getCompoundOrEmpty("groups").remove(words[1]);
      return "Deleted group " + words[1];
    }
    if (command.kind() == ShipCommand.Kind.GROUP_EDIT) {
      var matches =
          doors.stream().filter(d -> d.getStringOr("id", "").startsWith(words[3])).toList();
      if (words[3].length() < 8 || matches.size() != 1)
        throw new IllegalArgumentException("Choose one door ID from doors list");
      if (!s.contains("groups")) s.put("groups", new CompoundTag());
      var groups = s.getCompoundOrEmpty("groups");
      if (!groups.contains(words[1])) {
        if (groups.size() >= 16) throw new IllegalArgumentException("Maximum 16 door groups");
        groups.put(words[1], new ListTag());
      }
      var members = groups.getListOrEmpty(words[1]);
      String id = matches.getFirst().getStringOr("id", "");
      members.removeIf(t -> t.asString().orElse("").equals(id));
      if (words[2].equals("add")) members.add(StringTag.valueOf(id));
      return "Group " + words[1] + " now has " + members.size() + " doors";
    }
    if (command.kind() == ShipCommand.Kind.DOORS) {
      var members = s.getCompoundOrEmpty("groups").getListOrEmpty(words[1]);
      var targets =
          doors.stream()
              .filter(
                  d ->
                      words[1].equals("all")
                          || words[1].length() >= 8 && d.getStringOr("id", "").startsWith(words[1])
                          || members.stream()
                              .anyMatch(
                                  t -> t.asString().orElse("").equals(d.getStringOr("id", ""))))
              .toList();
      if (targets.isEmpty())
        throw new IllegalArgumentException("No current doors match that group or ID");
      r.doors.clear();
      r.doorResults.clear();
      r.open = words[2].equals("open");
      for (var d : targets) r.doors.add(base.offset(BlockPos.of(d.getLongOr("position", 0))));
      return "Queued " + targets.size() + " doors to " + words[2];
    }
    throw new IllegalArgumentException("Unknown engineering command. Type help");
  }
}
