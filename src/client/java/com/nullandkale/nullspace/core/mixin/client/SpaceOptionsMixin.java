package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.ui.screen.SpaceSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class SpaceOptionsMixin extends OptionsSubScreen {
  protected SpaceOptionsMixin(Screen parent, Options options, Component title) {
    super(parent, options, title);
  }

  @Inject(method = "addOptions", at = @At("TAIL"))
  private void nullspace$spaceOptions(CallbackInfo ci) {
    list.addBig(
        Button.builder(
                Component.literal("Space Graphics..."),
                b -> minecraft.gui.setScreen(new SpaceSettingsScreen(this)))
            .width(310)
            .build());
  }
}
