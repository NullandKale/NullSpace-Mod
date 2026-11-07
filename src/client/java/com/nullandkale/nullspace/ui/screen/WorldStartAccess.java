package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;

/** Selection belongs to one creation screen, including resize and child-screen navigation. */
public interface WorldStartAccess {
  WorldStartSettings nullspace$start();

  void nullspace$start(WorldStartSettings settings);
}
