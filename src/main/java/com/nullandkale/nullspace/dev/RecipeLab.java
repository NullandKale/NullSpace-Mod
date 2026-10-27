package com.nullandkale.nullspace.dev;

import com.nullandkale.nullspace.pad.*;
import com.nullandkale.nullspace.ship.PadRegistry;
import com.nullandkale.nullspace.ship.ShipStore;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** A saved, editable display of real loaded recipes, never a second recipe implementation. */
public final class RecipeLab {
  public static final List<String> RECIPES = com.nullandkale.nullspace.guide.GuideContent.RECIPES;
  public static final int[] GRID_SLOTS = {1, 2, 3, 10, 11, 12, 19, 20, 21};
  public static final int OUTPUT_SLOT = 15;
  public static final int WIDTH = 33, DEPTH = 35;

  public record Gallery(BlockPos origin) {
    public BlockPos chest(int index) {
      if (index < 0 || index >= RECIPES.size())
        throw new IllegalArgumentException("Unknown recipe display");
      return origin.offset(3 + (index % 5) * 6, 1, 5 + (index / 5) * 6);
    }

    public BlockPos spawn() {
      return origin.offset(16, 1, 1);
    }
  }

  public static Gallery ensure(MinecraftServer server) throws java.io.IOException {
    var marker = server.getWorldPath(LevelResource.ROOT).resolve("nullspace-recipe-lab-v1.nbt");
    if (Files.exists(marker)) {
      var saved = ShipStore.read(marker);
      if (saved.getIntOr("version", 0) != 1 || !saved.getBooleanOr("complete", false))
        throw new IllegalStateException(
            "Recipe gallery creation was interrupted or has an unknown version; existing blocks"
                + " preserved");
      return new Gallery(ShipStore.pos(saved, "origin"));
    }
    // Resolve everything before reserving or placing the gallery. Broken data must
    // not leave half-filled chests or a completed marker.
    var recipes = RECIPES.stream().map(name -> recipe(server, name)).toList();
    var reference = ShipGallery.ensure(server);
    var level = server.overworld();
    Gallery gallery = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      int x = reference.origin().getX() + attempt * (WIDTH + 8),
          z = reference.origin().getZ() + ShipGallery.DEPTH + 8;
      var proposed = new PadScanner.Pad(x, x + WIDTH - 1, 0, z, z + DEPTH - 1);
      if (PadRegistry.read(server).stream()
          .anyMatch(
              p ->
                  p.dimension().equals(level.dimension().identifier().toString())
                      && proposed.overlaps(p.bounds()))) continue;
      for (int cx = x >> 4; cx <= (x + WIDTH - 1) >> 4; cx++)
        for (int cz = z >> 4; cz <= (z + DEPTH - 1) >> 4; cz++) level.getChunk(cx, cz);
      int y = reference.origin().getY();
      for (int dx = 0; dx < WIDTH; dx++)
        for (int dz = 0; dz < DEPTH; dz++)
          y = Math.max(y, level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) + 4);
      if (y + 5 < level.getMaxY()) {
        gallery = new Gallery(new BlockPos(x, y, z));
        break;
      }
    }
    if (gallery == null) throw new IllegalStateException("No clear recipe gallery site");
    var saved = new CompoundTag();
    saved.putInt("version", 1);
    saved.putLong("origin", gallery.origin().asLong());
    saved.putBoolean("complete", false);
    ShipStore.write(marker, saved);
    for (int x = 0; x < WIDTH; x++)
      for (int z = 0; z < DEPTH; z++) {
        var floor = gallery.origin().offset(x, 0, z);
        level.setBlock(
            floor,
            ((x % 6 == 0 && z % 6 == 0) ? Blocks.SEA_LANTERN : Blocks.CONCRETE.lightGray())
                .defaultBlockState(),
            18);
        if ((x == 0 || x == WIDTH - 1 || z == DEPTH - 1) && z > 2)
          level.setBlock(floor.above(), Blocks.GLASS.defaultBlockState(), 18);
      }
    ShipGallery.label(
        level,
        gallery.origin().offset(3, 1, 1),
        DyeColor.CYAN,
        "RECIPE GALLERY",
        "3x3 grid on left",
        "Result on right",
        RECIPES.size() + " shaped recipes");
    ShipGallery.label(
        level,
        gallery.origin().offset(9, 1, 1),
        DyeColor.GREEN,
        "Take ingredients",
        "Use nearby table",
        "Visits preserve",
        "your changes");
    for (int index = 0; index < recipes.size(); index++)
      display(level, gallery.chest(index), index, recipes.get(index));
    server.saveEverything(true, true, true);
    saved.putBoolean("complete", true);
    ShipStore.write(marker, saved);
    return gallery;
  }

  public static ShapedRecipe recipe(MinecraftServer server, String name) {
    var key =
        ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("nullspace", name));
    var holder =
        server
            .getRecipeManager()
            .byKey(key)
            .orElseThrow(() -> new IllegalStateException("Missing ship recipe: " + name));
    if (!(holder.value() instanceof ShapedRecipe shaped))
      throw new IllegalStateException("Recipe must be shaped: " + name);
    return shaped;
  }

  private static void display(ServerLevel level, BlockPos at, int index, ShapedRecipe recipe) {
    var result = recipe.assemble(CraftingInput.EMPTY);
    level.setBlock(
        at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 18);
    var chest = (ChestBlockEntity) level.getBlockEntity(at);
    var namedChest = new ItemStack(Items.CHEST);
    namedChest.set(
        DataComponents.CUSTOM_NAME,
        Component.literal(String.format(Locale.ROOT, "%02d ", index + 1))
            .append(result.getHoverName())
            .append(" x" + result.getCount()));
    chest.applyComponentsFromItemStack(namedChest);
    Set<Integer> inputSlots = new HashSet<>();
    for (int slot : GRID_SLOTS) inputSlots.add(slot);
    for (int slot = 0; slot < 27; slot++)
      if (!inputSlots.contains(slot) && slot != OUTPUT_SLOT) {
        var border = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        border.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        chest.setItem(slot, border);
      }
    for (int y = 0; y < recipe.getHeight(); y++)
      for (int x = 0; x < recipe.getWidth(); x++) {
        var ingredient = recipe.getIngredients().get(y * recipe.getWidth() + x);
        if (ingredient.isPresent())
          chest.setItem(GRID_SLOTS[y * 3 + x], representative(ingredient.get()));
      }
    var arrow = new ItemStack(Items.PAPER);
    arrow.set(DataComponents.CUSTOM_NAME, Component.literal("Crafts ->"));
    chest.setItem(14, arrow);
    chest.setItem(OUTPUT_SLOT, result);
    chest.setChanged();
    level.setBlock(at.offset(2, 0, 0), Blocks.CRAFTING_TABLE.defaultBlockState(), 18);
    ShipGallery.label(
        level,
        at.south(),
        DyeColor.CYAN,
        String.format(Locale.ROOT, "%02d RECIPE", index + 1),
        RECIPES.get(index).replace('_', ' '),
        "3x3 -> result",
        "Makes " + result.getCount());
  }

  private static ItemStack representative(Ingredient ingredient) {
    for (var item : List.of(Items.OAK_PLANKS, Items.COBBLESTONE)) {
      var stack = new ItemStack(item);
      if (ingredient.test(stack)) return stack;
    }
    return new ItemStack(
        ingredient
            .items()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Recipe contains an empty ingredient")));
  }

  private RecipeLab() {}
}
