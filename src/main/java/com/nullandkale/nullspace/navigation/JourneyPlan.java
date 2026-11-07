package com.nullandkale.nullspace.navigation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A route's discoveries are independent of travel speed, vessel and visit count. */
public record JourneyPlan(long seed, String site, int seconds, int contactSecond, int kind) {
  /** Local trips take 45-90 s; trips between chart places grow with distance (P9). */
  public static JourneyPlan between(
      long worldSeed, String from, String to, double lightYears, int tier) {
    boolean interstellar = lightYears > 0;
    String lane = from.compareTo(to) < 0 ? from + ":" + to : to + ":" + from;
    var id =
        UUID.nameUUIDFromBytes(
            ("nullspace:journey:v1:" + worldSeed + ":" + lane).getBytes(StandardCharsets.UTF_8));
    long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    int base =
        interstellar
            ? 60 + (int) Math.round(lightYears * 4) + (int) Math.floorMod(seed, 21)
            : 45 + (int) Math.floorMod(seed, 46);
    int seconds = Math.max(20, base * 4 / (4 + Math.clamp(tier, 0, 3)));
    return new JourneyPlan(
        seed,
        "e_" + id.toString().replace("-", "").substring(0, 24),
        seconds,
        Math.max(5, seconds / 3),
        (int) Math.floorMod(seed >>> 16, 3));
  }

  public String name() {
    return switch (kind) {
      case 0 -> "Abandoned growing station";
      case 1 -> "Damaged freighter";
      default -> "Salvage ship";
    };
  }
}
