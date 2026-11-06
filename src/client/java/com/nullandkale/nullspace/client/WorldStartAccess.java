package com.nullandkale.nullspace.client;

import com.nullandkale.nullspace.start.WorldStartSettings;

/** Selection belongs to one creation screen, including resize and child-screen navigation. */
public interface WorldStartAccess {
  WorldStartSettings nullspace$start();

  void nullspace$start(WorldStartSettings settings);
}
