package com.nullandkale.nullspace.core.mixin.client;

import com.nullandkale.nullspace.content.guide.FieldGuide;
import com.nullandkale.nullspace.content.guide.GuideRecipes;
import com.nullandkale.nullspace.ui.guide.RecipeBookAccess;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BookViewScreen.BookAccess.class)
public abstract class GuideBookAccessMixin implements RecipeBookAccess {
  @Unique private GuideRecipes nullspace$recipeData;

  public GuideRecipes nullspace$recipes() {
    return nullspace$recipeData;
  }

  public void nullspace$recipes(GuideRecipes recipes) {
    nullspace$recipeData = recipes;
  }

  @Inject(method = "fromItem", at = @At("RETURN"))
  private static void nullspace$captureRecipes(
      ItemStack stack, CallbackInfoReturnable<BookViewScreen.BookAccess> result) {
    if (result.getReturnValue() != null && stack.is(FieldGuide.ITEM))
      ((RecipeBookAccess) (Object) result.getReturnValue())
          .nullspace$recipes(stack.get(GuideRecipes.COMPONENT));
  }
}
