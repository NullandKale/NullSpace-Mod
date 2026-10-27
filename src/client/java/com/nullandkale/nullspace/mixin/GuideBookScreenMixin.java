package com.nullandkale.nullspace.mixin;

import com.nullandkale.nullspace.client.guide.GuideRecipeRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(BookViewScreen.class)
public abstract class GuideBookScreenMixin extends Screen {
  @Shadow private BookViewScreen.BookAccess bookAccess;
  @Shadow private int currentPage;

  @Shadow
  protected abstract boolean handleClickEvent(ClickEvent event);

  protected GuideBookScreenMixin() {
    super(Component.empty());
  }

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void nullspace$recipeIcons(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
    GuideRecipeRenderer.render(graphics, bookAccess, currentPage, width, mouseX, mouseY);
  }

  @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
  private void nullspace$ingredientRecipe(
      MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
    if (event.button() == 1) {
      int page = GuideRecipeRenderer.target(bookAccess, currentPage, width, event.x(), event.y());
      if (page >= 0) cir.setReturnValue(handleClickEvent(new ClickEvent.ChangePage(page + 1)));
    }
  }
}
