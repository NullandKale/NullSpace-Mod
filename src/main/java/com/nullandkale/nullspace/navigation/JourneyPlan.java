package com.nullandkale.nullspace.navigation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A journey's length comes from its route; its signal (site, kind, contact time) comes from a
 * nonce. Real journeys roll a fresh nonce each time, so every trip meets a different signal.
 */
public record JourneyPlan(long seed, String site, int seconds, int contactSecond, int kind) {
  /** Signal kinds: growing station, damaged freighter, salvage ship, drifting cargo. */
  public static final int KINDS = 4;

  /** The route's own plan, with a signal fixed by the route (used for quotes and tests). */
  public static JourneyPlan between(
      long worldSeed, String from, String to, double lightYears, int tier) {
    return between(worldSeed, from, to, lightYears, tier, laneSeed(worldSeed, from, to));
  }

  /** Local trips take 45-90 s; trips between chart places grow with distance (P9). */
  public static JourneyPlan between(
      long worldSeed, String from, String to, double lightYears, int tier, long nonce) {
    long lane = laneSeed(worldSeed, from, to);
    int base =
        lightYears > 0
            ? 60 + (int) Math.round(lightYears * 4) + (int) Math.floorMod(lane, 21)
            : 45 + (int) Math.floorMod(lane, 46);
    int seconds = Math.max(20, base * 4 / (4 + Math.clamp(tier, 0, 3)));
    var id =
        UUID.nameUUIDFromBytes(
            ("nullspace:signal:v2:" + worldSeed + ":" + nonce).getBytes(StandardCharsets.UTF_8));
    // The signal comes in between a quarter and half of the way.
    int quarter = Math.max(1, seconds / 4);
    int contact = Math.max(5, quarter + (int) Math.floorMod(nonce >>> 32, quarter));
    return new JourneyPlan(
        nonce,
        "e_" + id.toString().replace("-", "").substring(0, 24),
        seconds,
        contact,
        (int) Math.floorMod(nonce >>> 16, KINDS));
  }

  static long laneSeed(long worldSeed, String from, String to) {
    String lane = from.compareTo(to) < 0 ? from + ":" + to : to + ":" + from;
    var id =
        UUID.nameUUIDFromBytes(
            ("nullspace:journey:v1:" + worldSeed + ":" + lane).getBytes(StandardCharsets.UTF_8));
    return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
  }

  public String name() {
    return name(kind);
  }

  public static String name(int kind) {
    return switch (kind) {
      case 0 -> "Abandoned growing station";
      case 1 -> "Damaged freighter";
      case 2 -> "Salvage ship";
      default -> "Drifting cargo";
    };
  }
}
