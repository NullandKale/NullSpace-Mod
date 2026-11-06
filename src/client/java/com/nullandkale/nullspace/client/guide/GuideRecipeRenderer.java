package com.nullandkale.nullspace.client.guide;

import com.nullandkale.nullspace.guide.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Uses the item renderer: resource packs, models, counts and hover names work normally. */
public final class GuideRecipeRenderer {
  private static BookViewScreen.BookAccess hoverBook;
  private static int hoverPage = -1, hoverCell = -1;
  private static String hoverItem;

  public static boolean isGuide(BookViewScreen.BookAccess access) {
    return ((RecipeBookAccess) (Object) access).nullspace$recipes() != null;
  }

  public static GuideContent.Recipe recipe(BookViewScreen.BookAccess access, int page) {
    var data = ((RecipeBookAccess) (Object) access).nullspace$recipes();
    if (data == null) return null;
    int index = page - (access.getPageCount() - data.recipes().size());
    return index >= 0 && index < data.recipes().size() ? data.recipes().get(index) : null;
  }

  public static int target(
      BookViewScreen.BookAccess access, int page, int width, double mouseX, double mouseY) {
    var recipe = recipe(access, page);
    if (recipe == null || recipe.count() == 0) return -1;
    int cell = cellAt(width, mouseX, mouseY);
    if (cell < 0) return -1;
    return target(
        access,
        access == hoverBook && page == hoverPage && cell == hoverCell
            ? hoverItem
            : shown(recipe.cells().get(cell)));
  }

  private static int target(BookViewScreen.BookAccess access, String item) {
    if (item == null) return -1;
    var recipes = ((RecipeBookAccess) (Object) access).nullspace$recipes().recipes();
    for (int i = 0; i < recipes.size(); i++)
      if (recipes.get(i).count() > 0 && recipes.get(i).output().equals(item))
        return access.getPageCount() - recipes.size() + i;
    return -1;
  }

  public static int cellAt(int width, double mouseX, double mouseY) {
    int x = (int) Math.floor(mouseX) - ((width - 192) / 2 + 36), y = (int) Math.floor(mouseY) - 55;
    return x >= 0 && x < 54 && y >= 0 && y < 54 ? x / 18 + y / 18 * 3 : -1;
  }

  private static String shown(List<String> choices) {
    return choices.isEmpty()
        ? null
        : choices.get((int) ((net.minecraft.util.Util.getMillis() / 1500) % choices.size()));
  }

  private static ItemStack stack(String id, int count) {
    if (id == null) return ItemStack.EMPTY;
    var identifier = Identifier.tryParse(id);
    return identifier == null
        ? ItemStack.EMPTY
        : BuiltInRegistries.ITEM
            .getOptional(identifier)
            .map(item -> new ItemStack(item, count))
            .orElse(ItemStack.EMPTY);
  }

  public static void render(
      GuiGraphicsExtractor g,
      BookViewScreen.BookAccess access,
      int page,
      int width,
      int mx,
      int my) {
    var recipe = recipe(access, page);
    if (recipe == null || recipe.count() == 0) {
      hoverBook = null;
      return;
    }
    int hovered = cellAt(width, mx, my);
    if (access != hoverBook || page != hoverPage || hovered != hoverCell) {
      hoverBook = access;
      hoverPage = page;
      hoverCell = hovered;
      hoverItem = hovered < 0 ? null : shown(recipe.cells().get(hovered));
    }
    var mc = Minecraft.getInstance();
    int left = (width - 192) / 2 + 36;
    for (int cell = 0; cell < 9; cell++) {
      var choices = recipe.cells().get(cell);
      String displayed = cell == hovered ? hoverItem : shown(choices);
      var item = stack(displayed, 1);
      int x = left + cell % 3 * 18, y = 55 + cell / 3 * 18;
      slot(g, item, x, y);
      if (cellAt(width, mx, my) == cell && !item.isEmpty()) {
        var tooltip = new ArrayList<Component>();
        tooltip.add(item.getHoverName());
        if (choices.size() > 1)
          tooltip.add(
              Component.literal("Any of " + choices.size() + " ingredients (cycles)")
                  .withStyle(ChatFormatting.GRAY));
        if (target(access, displayed) >= 0)
          tooltip.add(Component.literal("Click for recipe").withStyle(ChatFormatting.AQUA));
        g.setComponentTooltipForNextFrame(mc.font, tooltip, mx, my);
      }
    }
    g.text(mc.font, ">", left + 67, 78, 0xFF585448, false);
    var output = stack(recipe.output(), recipe.count());
    slot(g, output, left + 90, 73);
    g.itemDecorations(mc.font, output, left + 91, 74);
    if (mx >= left + 90 && mx < left + 108 && my >= 73 && my < 91)
      g.setTooltipForNextFrame(mc.font, output, mx, my);
  }

  private static void slot(GuiGraphicsExtractor g, ItemStack item, int x, int y) {
    g.fill(x, y, x + 18, y + 18, 0xFF6B6558);
    g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFF3E8CC);
    g.fill(x + 1, y + 1, x + 17, y + 17, 0xFFB7AC95);
    g.item(item, x + 1, y + 1);
  }

  private GuideRecipeRenderer() {}
}
