package com.nullandkale.nullspace.model.computer;

import java.util.*;

/** Rotates the first claimant and never rounds a nonzero remaining allowance down to zero. */
public final class FairScheduler<T> {
  private int cursor;

  public List<T> order(Collection<T> values) {
    var ordered = new ArrayList<>(values);
    if (!ordered.isEmpty()) Collections.rotate(ordered, -Math.floorMod(cursor++, ordered.size()));
    return ordered;
  }

  public static int quantum(int budget, int remaining, int maximum) {
    if (budget <= 0) return 0;
    return Math.min(maximum, Math.min(budget, Math.max(1, budget / Math.max(1, remaining))));
  }
}
