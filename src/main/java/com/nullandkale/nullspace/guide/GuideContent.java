package com.nullandkale.nullspace.guide;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.*;

/** Native book pages, clickable indexes, and recipes read from the same data as crafting. */
public final class GuideContent {
  public static final List<String> RECIPES =
      com.nullandkale.nullspace.content.ContentCatalog.EQUIPMENT.stream()
          .map(com.nullandkale.nullspace.content.ContentCatalog.EquipmentDefinition::id)
          .toList();

  private record Page(String id, String title, Component body) {}

  /** Nine row-major slots; each slot contains every ingredient accepted by the server. */
  public record Recipe(String name, String output, int count, List<List<String>> cells) {
    public Recipe {
      if (cells.size() != 9) throw new IllegalArgumentException("Recipes need nine slots");
      cells = cells.stream().map(List::copyOf).toList();
    }
  }

  public static List<Recipe> recipes(MinecraftServer server) {
    return RECIPES.stream()
        .map(name -> server == null ? bundled(name) : loaded(server, name))
        .toList();
  }

  public static WrittenBookContent builtin() {
    return build(null);
  }

  public static WrittenBookContent forServer(MinecraftServer server) {
    return build(server);
  }

  private static WrittenBookContent build(MinecraftServer server) {
    var chapters = read("/data/nullspace/guide/chapters.json").getAsJsonArray("chapters");
    var pages = new ArrayList<Page>();
    pages.add(
        new Page(
            "cover",
            "NullSpace",
            Component.literal(
                "Field Guide\n\n"
                    + "Build a ship.\n"
                    + "Keep its crew alive.\n"
                    + "Explore space.\n\n"
                    + "Click Contents or\n"
                    + "Recipes below.")));
    int indexes = (chapters.size() + 7) / 8;
    for (int i = 0; i < indexes; i++)
      pages.add(new Page("index" + i, "Contents " + (i + 1), Component.empty()));
    for (var element : chapters) {
      var chapter = element.getAsJsonObject();
      String id = chapter.get("id").getAsString(), title = chapter.get("title").getAsString();
      var texts = chapter.getAsJsonArray("pages");
      for (int i = 0; i < texts.size(); i++)
        pages.add(
            new Page(
                id + (i == 0 ? "" : "_" + i),
                title,
                Component.literal(texts.get(i).getAsString())));
    }
    int recipeIndexes = (RECIPES.size() + 7) / 8;
    for (int i = 0; i < recipeIndexes; i++)
      pages.add(new Page("recipes" + i, "Recipes " + (i + 1), Component.empty()));
    for (var recipe : recipes(server)) {
      // Leave room for native item rendering. Text remains useful to narration clients.
      var text =
          Component.literal(
              recipe.count == 0
                  ? "Recipe unavailable"
                  : "Makes " + recipe.count + "\n\n\n\n\n\n\n\n\nHover for items");
      pages.add(new Page("recipe_" + recipe.name, label(recipe.name), text));
    }
    var positions = new LinkedHashMap<String, Integer>();
    for (int i = 0; i < pages.size(); i++)
      if (positions.put(pages.get(i).id, i + 1) != null)
        throw new IllegalStateException("Duplicate guide page");
    for (int i = 0; i < indexes; i++) {
      var body = Component.empty();
      for (int c = i * 8; c < Math.min(chapters.size(), (i + 1) * 8); c++) {
        var chapter = chapters.get(c).getAsJsonObject();
        body.append(
                link(
                    chapter.get("title").getAsString(),
                    positions.get(chapter.get("id").getAsString())))
            .append("\n");
      }
      if (i + 1 < indexes) body.append(link("More contents >", positions.get("index" + (i + 1))));
      pages.set(i + 1, new Page("index" + i, "Contents " + (i + 1), body));
    }
    for (int i = 0; i < recipeIndexes; i++) {
      var body = Component.empty();
      for (int c = i * 8; c < Math.min(RECIPES.size(), (i + 1) * 8); c++) {
        String name = RECIPES.get(c);
        body.append(link(label(name), positions.get("recipe_" + name))).append("\n");
      }
      if (i + 1 < recipeIndexes)
        body.append(link("More recipes >", positions.get("recipes" + (i + 1))));
      int at = positions.get("recipes" + i) - 1;
      pages.set(at, new Page("recipes" + i, "Recipes " + (i + 1), body));
    }
    var output = new ArrayList<Filterable<Component>>();
    for (var page : pages) {
      var text =
          Component.empty()
              .withStyle(ChatFormatting.BLACK)
              .append(
                  Component.literal(page.title)
                      .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_AQUA))
              .append("\n")
              .append(page.body)
              .append("\n")
              .append(link("Contents", positions.get("index0")))
              .append(" | ")
              .append(link("Recipes", positions.get("recipes0")));
      output.add(Filterable.passThrough(text));
    }
    if (output.size() > 100)
      throw new IllegalStateException("Guide exceeds native book page budget");
    return new WrittenBookContent(
        Filterable.passThrough("NullSpace Field Guide"), "NullSpace", 0, List.copyOf(output), true);
  }

  private static MutableComponent link(String text, int page) {
    return Component.literal(text)
        .withStyle(
            s ->
                s.withColor(ChatFormatting.DARK_BLUE)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.ChangePage(page)));
  }

  private static Recipe bundled(String name) {
    var json = read("/data/nullspace/recipe/" + name + ".json");
    var pattern = json.getAsJsonArray("pattern");
    var keys = json.getAsJsonObject("key");
    var cells = new ArrayList<List<String>>();
    for (int y = 0; y < 3; y++)
      for (int x = 0; x < 3; x++) {
        String row = y < pattern.size() ? pattern.get(y).getAsString() : "";
        char symbol = x < row.length() ? row.charAt(x) : ' ';
        String id = symbol == ' ' ? "" : keys.get(Character.toString(symbol)).getAsString();
        if (id.equals("#minecraft:planks"))
          cells.add(
              BuiltInRegistries.ITEM.keySet().stream()
                  .map(Object::toString)
                  .filter(k -> k.startsWith("minecraft:") && k.endsWith("_planks"))
                  .sorted()
                  .toList());
        else if (id.equals("#minecraft:wooden_doors"))
          cells.add(
              BuiltInRegistries.ITEM.keySet().stream()
                  .filter(k -> k.getNamespace().equals("minecraft"))
                  .map(Object::toString)
                  .filter(
                      k ->
                          k.endsWith("_door") && !k.contains("copper") && !k.endsWith(":iron_door"))
                  .sorted()
                  .toList());
        else if (id.equals("#nullspace:rough_stone"))
          cells.add(List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate"));
        else cells.add(id.isEmpty() ? List.of() : List.of(id));
      }
    var result = json.getAsJsonObject("result");
    return new Recipe(
        name,
        result.get("id").getAsString(),
        result.has("count") ? result.get("count").getAsInt() : 1,
        cells);
  }

  private static Recipe loaded(MinecraftServer server, String name) {
    var key =
        ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("nullspace", name));
    var holder = server.getRecipeManager().byKey(key);
    if (holder.isEmpty() || !(holder.get().value() instanceof ShapedRecipe shaped))
      return new Recipe(name, "minecraft:air", 0, Collections.nCopies(9, List.of()));
    var cells = new ArrayList<List<String>>();
    for (int y = 0; y < 3; y++)
      for (int x = 0; x < 3; x++) {
        var ingredient =
            y < shaped.getHeight() && x < shaped.getWidth()
                ? shaped.getIngredients().get(y * shaped.getWidth() + x)
                : Optional.<Ingredient>empty();
        cells.add(
            ingredient
                .map(
                    i ->
                        i.items()
                            .map(h -> BuiltInRegistries.ITEM.getKey(h.value()).toString())
                            .toList())
                .orElse(List.of()));
      }
    var output = shaped.assemble(CraftingInput.EMPTY);
    return new Recipe(
        name, BuiltInRegistries.ITEM.getKey(output.getItem()).toString(), output.getCount(), cells);
  }

  public static String label(String name) {
    return switch (name) {
      case "computer_terminal" -> "Terminal";
      case "space_chestplate" -> "Suit chestplate";
      case "space_leggings" -> "Suit leggings";
      case "space_helmet" -> "Space helmet";
      case "space_boots" -> "Space boots";
      case "field_guide" -> "Field guide";
      default -> {
        String s = name.replace('_', ' ');
        yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
      }
    };
  }

  private static JsonObject read(String path) {
    try (var stream = GuideContent.class.getResourceAsStream(path)) {
      if (stream == null) throw new IllegalStateException("Missing guide data " + path);
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private GuideContent() {}
}
