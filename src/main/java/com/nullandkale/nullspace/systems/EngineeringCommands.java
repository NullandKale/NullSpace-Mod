package com.nullandkale.nullspace.systems;

import java.util.*;

/** Shared command semantics for authorization and execution. Unknown commands are never reads. */
public final class EngineeringCommands {
  public record Profile(String name, int[] requests, int[] order) {
    public Profile {
      requests = requests.clone();
      order = order.clone();
      if (requests.length != 6
          || order.length != 6
          || Arrays.stream(requests).anyMatch(v -> v < 0 || v > 8)
          || Arrays.stream(order).anyMatch(v -> v < 0 || v >= 6)
          || Arrays.stream(order).distinct().count() != 6)
        throw new IllegalArgumentException("Invalid power profile");
    }

    @Override
    public int[] requests() {
      return requests.clone();
    }

    @Override
    public int[] order() {
      return order.clone();
    }
  }

  private static final List<Profile> PROFILES =
      List.of(
          new Profile("cruise", new int[] {1, 4, 1, 3, 1, 0}, new int[] {0, 1, 2, 3, 4, 5}),
          // Combat runs air at half output so one generator also feeds the weapon.
          new Profile("combat", new int[] {1, 2, 1, 1, 1, 2}, new int[] {0, 1, 2, 3, 4, 5}),
          new Profile("emergency", new int[] {1, 8, 1, 0, 0, 0}, new int[] {0, 2, 1, 3, 4, 5}));

  public static List<Profile> profiles() {
    return PROFILES;
  }

  public static Profile profile(String name) {
    return PROFILES.stream()
        .filter(p -> p.name.equals(name.toLowerCase(Locale.ROOT)))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Use profile cruise, combat, or emergency"));
  }

  public static boolean readOnly(String input) {
    try {
      return ShipCommand.parse(input).readOnly();
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private EngineeringCommands() {}
}
