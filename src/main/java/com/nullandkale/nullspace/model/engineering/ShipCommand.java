package com.nullandkale.nullspace.model.engineering;

import java.util.*;

/** The same parsed command controls authorization and execution for consoles and scripts. */
public record ShipCommand(Kind kind, List<String> words) {
  public enum Kind {
    HELP(true),
    PROFILES(true),
    ROOMS(true),
    ROOM_HERE(true),
    AIR_AUDIT(true),
    DEVICES(true),
    DOOR_LIST(true),
    GROUPS(true),
    PROFILE(false),
    POWER(false),
    ORDER(false),
    SUPPLY(false),
    DEVICE(false),
    GROUP_DELETE(false),
    GROUP_EDIT(false),
    DOORS(false);
    final boolean query;

    Kind(boolean query) {
      this.query = query;
    }
  }

  public ShipCommand {
    words = List.copyOf(words);
  }

  public boolean readOnly() {
    return kind.query;
  }

  public static ShipCommand parse(String input) {
    if (input.length() > 128) throw new IllegalArgumentException("Device command is too long");
    var w = List.of(input.toLowerCase(Locale.ROOT).strip().split(" +"));
    int n = w.size();
    String verb = w.getFirst();
    Kind kind =
        switch (verb) {
          case "help" -> n == 1 ? Kind.HELP : null;
          case "profiles" -> n == 1 ? Kind.PROFILES : null;
          case "rooms" -> n <= 2 ? Kind.ROOMS : null;
          case "room" -> n == 2 && w.get(1).equals("here") ? Kind.ROOM_HERE : null;
          case "air" -> n == 2 && w.get(1).equals("audit") ? Kind.AIR_AUDIT : null;
          case "devices" -> n <= 2 ? Kind.DEVICES : null;
          case "groups" -> n == 1 ? Kind.GROUPS : null;
          case "profile" -> n == 2 ? Kind.PROFILE : null;
          case "power" -> n == 3 ? Kind.POWER : null;
          case "order" -> n == 7 ? Kind.ORDER : null;
          case "supply" -> n == 2 && Set.of("on", "off").contains(w.get(1)) ? Kind.SUPPLY : null;
          case "device" -> n == 3 && Set.of("on", "off").contains(w.get(2)) ? Kind.DEVICE : null;
          case "group" ->
              n == 3 && w.get(2).equals("delete")
                  ? Kind.GROUP_DELETE
                  : n == 4
                          && w.get(1).matches("[a-z0-9_-]{1,12}")
                          && Set.of("add", "remove").contains(w.get(2))
                      ? Kind.GROUP_EDIT
                      : null;
          case "doors" ->
              n == 2 && w.get(1).equals("list")
                  ? Kind.DOOR_LIST
                  : n == 3 && Set.of("open", "close").contains(w.get(2)) ? Kind.DOORS : null;
          default -> null;
        };
    if (kind == null) throw new IllegalArgumentException("Unknown engineering command. Type help");
    return new ShipCommand(kind, w);
  }
}
