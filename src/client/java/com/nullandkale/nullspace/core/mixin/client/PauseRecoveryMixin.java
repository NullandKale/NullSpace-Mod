package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.ui.screen.RescueScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseRecoveryMixin extends Screen
{
    protected PauseRecoveryMixin()
    {
        super(Component.literal("Rescue"));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void nullspace$rescue(CallbackInfo ci)
    {
        if (minecraft.player != null && ((PauseScreen) (Object) this).showsPauseMenu())
            addRenderableWidget(Button.builder(Component.literal("Rescue"), b -> minecraft.gui.setScreen(new RescueScreen(this))).bounds(width - 96, 8, 88, 20).build());
    }
}
