package com.nullandkale.nullspace.dev;

import java.nio.file.Files;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Opt-in development launcher. Never opens or seeds an ordinary player save. */
public final class PlaytestLauncher {
  private static boolean launched;

  public static void initialize() {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()
        || !Boolean.getBoolean("nullspace.playtest")) return;
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (launched || !(client.gui.screen() instanceof TitleScreen)) return;
          launched = true;
          String world = Boolean.getBoolean("nullspace.lab") ? "NullSpace-Lab" : "NullSpace-Dev";
          var flows = client.createWorldOpenFlows();
          if (Files.exists(
              client.gameDirectory.toPath().resolve("saves/" + world + "/level.dat"))) {
            flows.openWorld(world, () -> {});
          } else {
            flows.createFreshLevel(
                world,
                new LevelSettings(
                    "NullSpace Development",
                    GameType.CREATIVE,
                    LevelSettings.DifficultySettings.DEFAULT,
                    true,
                    WorldDataConfiguration.DEFAULT),
                new WorldOptions(42L, false, false),
                WorldPresets::createTestWorldDimensions,
                client.gui.screen());
          }
        });
  }

  private PlaytestLauncher() {}
}
