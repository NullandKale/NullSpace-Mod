package com.nullandkale.nullspace.content.guide;

import com.nullandkale.nullspace.dev.RecipeLab;

import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

public final class GuideGameTest
{
    @GameTest
    public void guideUsesLiveRecipesAndSafePageLinks(GameTestHelper ctx)
    {
        var live = GuideContent.forServer(ctx.getLevel().getServer());
        ctx.assertTrue(live.equals(GuideContent.builtin()), "Bundled guide patterns must match the actual loaded recipes");
        ctx.assertTrue(live.pages().size() <= 100 && live.pages().size() > GuideContent.RECIPES.size(), "Guide must contain features and all recipes within native book limits");
        for (var page : live.pages())
            page.raw().visit((style, text) ->
            {
                var click = style.getClickEvent();
                ctx.assertTrue(click == null || click instanceof ClickEvent.ChangePage change && change.page() >= 1 && change.page() <= live.pages().size(), "Guide links must only navigate to existing pages");
                return Optional.<String>empty();
            }, Style.EMPTY);
        var recipe = RecipeLab.recipe(ctx.getLevel().getServer(), "field_guide");
        var result = recipe.assemble(CraftingInput.EMPTY);
        ctx.assertTrue(result.is(FieldGuide.ITEM) && result.has(DataComponents.WRITTEN_BOOK_CONTENT), "Crafting must produce a readable written guide");
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), ctx.getLevel().registryAccess());
        try
        {
            ItemStack.STREAM_CODEC.encode(buffer, result);
            ctx.assertTrue(ItemStack.matches(result, ItemStack.STREAM_CODEC.decode(buffer)), "Guide and links must survive native inventory packets");
        }
        finally
        {
            buffer.release();
        }
        ctx.succeed();
    }

    @GameTest
    public void graphicalRecipesMatchEveryLoadedIngredientAndOutput(GameTestHelper ctx)
    {
        var server = ctx.getLevel().getServer();
        var stack = new ItemStack(FieldGuide.ITEM);
        FieldGuide.refresh(stack, ctx.getLevel());
        var graphics = stack.get(GuideRecipes.COMPONENT);
        ctx.assertTrue(graphics.recipes().size() == GuideContent.RECIPES.size(), "All recipes need images");
        for (var page : graphics.recipes())
        {
            var actual = RecipeLab.recipe(server, page.name());
            var result = actual.assemble(CraftingInput.EMPTY);
            ctx.assertTrue(page.output().equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem()).toString()) && page.count() == result.getCount(), "Exact displayed result for " + page.name());
            var cells = new java.util.ArrayList<ItemStack>();
            for (int slot = 0; slot < 9; slot++)
            {
                var choices = page.cells().get(slot);
                int x = slot % 3, y = slot / 3;
                var ingredient = x < actual.getWidth() && y < actual.getHeight() ? actual.getIngredients().get(y * actual.getWidth() + x) : Optional.<net.minecraft.world.item.crafting.Ingredient>empty();
                var expected = ingredient.map(i -> i.items().map(h -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString()).collect(java.util.stream.Collectors.toSet())).orElse(java.util.Set.of());
                ctx.assertTrue(new java.util.HashSet<>(choices).equals(expected), "Every accepted tag alternative and empty slot must match: " + page.name() + " slot " + slot);
                cells.add(choices.isEmpty() ? ItemStack.EMPTY : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(choices.getFirst()))));
            }
            ctx.assertTrue(actual.matches(CraftingInput.of(3, 3, cells), ctx.getLevel()), "The displayed grid must actually craft " + page.name());
        }
        var encoded = GuideRecipes.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, graphics).getOrThrow();
        ctx.assertTrue(graphics.equals(GuideRecipes.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow()), "Graphical pages must survive saved-book serialization");
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), ctx.getLevel().registryAccess());
        try
        {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            ctx.assertTrue(ItemStack.matches(stack, ItemStack.STREAM_CODEC.decode(buffer)), "Graphical data must survive actual inventory/lectern packets");
        }
        finally
        {
            buffer.release();
        }
        ctx.succeed();
    }

    @GameTest
    public void guideCommandIsAvailableAndDoesNotDuplicateHeldCopies(GameTestHelper ctx)
    {
        var p = ctx.makeMockServerPlayerInLevel();
        try
        {
            var server = ctx.getLevel().getServer();
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "nullspace guide");
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "nullspace guide");
            ctx.assertTrue(p.getInventory().countItem(FieldGuide.ITEM) == 1, "Requesting the guide twice must retain one inventory copy");
            ctx.succeed();
        }
        finally
        {
            ctx.getLevel().getServer().getPlayerList().remove(p);
        }
    }

    @GameTest
    public void guideCanBePlacedOnANativeLectern(GameTestHelper ctx)
    {
        var p = ctx.makeMockServerPlayerInLevel();
        try
        {
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var pos = ctx.absolutePos(new BlockPos(2, 2, 2));
            var level = ctx.getLevel();
            level.setBlock(pos, Blocks.LECTERN.defaultBlockState(), 18);
            var stack = new ItemStack(FieldGuide.ITEM);
            ctx.assertTrue(LecternBlock.tryPlaceBook(p, level, pos, level.getBlockState(pos), stack), "The guide must be accepted as a lectern book");
            var lectern = (LecternBlockEntity) level.getBlockEntity(pos);
            ctx.assertTrue(stack.isEmpty() && lectern.getBook().is(FieldGuide.ITEM) && lectern.getBook().has(DataComponents.WRITTEN_BOOK_CONTENT), "Lectern placement retains content and consumes exactly one guide");
            ctx.succeed();
        }
        finally
        {
            ctx.getLevel().getServer().getPlayerList().remove(p);
        }
    }
}
