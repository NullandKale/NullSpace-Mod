package com.nullandkale.nullspace.mixin;

import com.nullandkale.nullspace.client.*;
import com.nullandkale.nullspace.start.WorldStartSettings;
import java.io.UncheckedIOException;
import java.util.Arrays;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(CreateWorldScreen.class)
public abstract class WorldStartMixin implements WorldStartAccess {
  @Unique private WorldStartSettings nullspace$selection = WorldStartSettings.DEFAULT;

  @Override
  public WorldStartSettings nullspace$start() {
    return nullspace$selection;
  }

  @Override
  public void nullspace$start(WorldStartSettings settings) {
    nullspace$selection = settings;
  }

  @ModifyArg(
      method = "init",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;"),
      index = 0)
  private Tab[] nullspace$startTab(Tab[] tabs) {
    var result = Arrays.copyOf(tabs, tabs.length + 1);
    result[tabs.length] = new WorldStartTab((CreateWorldScreen) (Object) this, this);
    return result;
  }

  @ModifyArg(
      method = "createNewWorld",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;createLevelFromExistingSettings"),
      index = 0)
  private LevelStorageAccess nullspace$saveStart(LevelStorageAccess access) {
    DevWorldCreation.remember((CreateWorldScreen) (Object) this, access);
    if (nullspace$selection.space()) {
      try {
        nullspace$selection.write(access.getLevelPath(LevelResource.ROOT));
      } catch (java.io.IOException failure) {
        throw new UncheckedIOException("Cannot save starting ship selection", failure);
      }
    }
    return access;
  }
}
