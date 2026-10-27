package com.nullandkale.nullspace.guide;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Persistent recipe snapshots travel with the book, including on a lectern. */
public record GuideRecipes(List<GuideContent.Recipe> recipes) {
  private static final Codec<GuideContent.Recipe> RECIPE_CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.STRING.fieldOf("name").forGetter(GuideContent.Recipe::name),
                      Codec.STRING.fieldOf("output").forGetter(GuideContent.Recipe::output),
                      Codec.intRange(0, 9999)
                          .fieldOf("count")
                          .forGetter(GuideContent.Recipe::count),
                      Codec.STRING
                          .listOf(0, 256)
                          .listOf(9, 9)
                          .fieldOf("cells")
                          .forGetter(GuideContent.Recipe::cells))
                  .apply(i, GuideContent.Recipe::new));
  public static final Codec<GuideRecipes> CODEC =
      RECIPE_CODEC.listOf(0, 100).xmap(GuideRecipes::new, GuideRecipes::recipes);
  public static final DataComponentType<GuideRecipes> COMPONENT =
      Registry.register(
          BuiltInRegistries.DATA_COMPONENT_TYPE,
          Identifier.parse("nullspace:guide_recipes"),
          DataComponentType.<GuideRecipes>builder().persistent(CODEC).cacheEncoding().build());

  public GuideRecipes {
    recipes = List.copyOf(recipes);
  }
}
