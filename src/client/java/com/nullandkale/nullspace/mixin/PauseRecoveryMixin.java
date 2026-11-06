package com.nullandkale.nullspace.mixin;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PauseScreen.class)
public abstract class PauseRecoveryMixin extends Screen {
  protected PauseRecoveryMixin(){super(Component.literal("Rescue"));}
  @Inject(method="init",at=@At("TAIL"))
  private void nullspace$rescue(CallbackInfo ci){
    if(minecraft.player!=null && ((PauseScreen)(Object)this).showsPauseMenu())
      addRenderableWidget(Button.builder(Component.literal("Rescue"),b->minecraft.gui.setScreen(new com.nullandkale.nullspace.client.RescueScreen(this))).bounds(width-96,8,88,20).build());
  }
}
