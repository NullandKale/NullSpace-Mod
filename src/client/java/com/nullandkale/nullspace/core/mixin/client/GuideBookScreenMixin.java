package com.nullandkale.nullspace.core.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.ui.guide.GuideRecipeRenderer;
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

  @Unique
  private final java.util.ArrayDeque<Integer> nullspace$history = new java.util.ArrayDeque<>();

  @Unique private boolean nullspace$goingBack;

  @Inject(method = "setPage", at = @At("HEAD"))
  private void nullspace$rememberPage(int page, CallbackInfoReturnable<Boolean> cir) {
    if (!nullspace$goingBack && page != currentPage && GuideRecipeRenderer.isGuide(bookAccess)) {
      if (nullspace$history.size() == 32) nullspace$history.removeFirst();
      nullspace$history.addLast(currentPage);
    }
  }

  @Shadow
  protected abstract boolean handleClickEvent(ClickEvent event);

  protected GuideBookScreenMixin() {
    super(Component.empty());
  }

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void nullspace$recipeIcons(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
    GuideRecipeRenderer.render(graphics, bookAccess, currentPage, width, mouseX, mouseY);
    if (!nullspace$history.isEmpty()) {
      int x = (width - 192) / 2 + 36;
      graphics.fill(x - 2, 145, x + 62, 157, 0xffded3b2);
      graphics.text(font, "< Back", x, 147, 0xff333333, false);
    }
  }

  @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
  private void nullspace$ingredientRecipe(
      MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
    if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && !nullspace$history.isEmpty()) {
      int x = (width - 192) / 2 + 36;
      if (event.x() >= x - 2 && event.x() < x + 62 && event.y() >= 145 && event.y() < 157) {
        nullspace$goingBack = true;
        try {
          cir.setReturnValue(
              handleClickEvent(new ClickEvent.ChangePage(nullspace$history.removeLast() + 1)));
        } finally {
          nullspace$goingBack = false;
        }
        return;
      }
    }
    if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
        || event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
      int page = GuideRecipeRenderer.target(bookAccess, currentPage, width, event.x(), event.y());
      if (page >= 0) cir.setReturnValue(handleClickEvent(new ClickEvent.ChangePage(page + 1)));
    }
  }
}
