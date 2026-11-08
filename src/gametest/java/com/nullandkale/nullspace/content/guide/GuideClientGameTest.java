package com.nullandkale.nullspace.content.guide;

import com.nullandkale.nullspace.testing.ClientTestEvidence;
import com.nullandkale.nullspace.ui.guide.GuideRecipeRenderer;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.input.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import com.mojang.blaze3d.platform.InputConstants;

public final class GuideClientGameTest implements FabricClientGameTest
{
    @Override
    public void runTest(ClientGameTestContext c)
    {
        ClientTestEvidence.started(getClass());
        if (System.getProperty("nullspace.local.role") != null)
            return;
        try (var world = c.worldBuilder().create())
        {
            world.getConnection().waitForChunksRender();
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FieldGuide.ITEM));
                p.inventoryMenu.broadcastChanges();
            });
            c.waitFor(mc -> mc.player.getMainHandItem().is(FieldGuide.ITEM), 100);
            c.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
            c.waitFor(mc -> mc.gui.screen() instanceof BookViewScreen, 100);
            c.runOnClient(mc ->
            {
                var content = mc.player.getMainHandItem().get(DataComponents.WRITTEN_BOOK_CONTENT);
                for (int i = 0; i < content.pages().size(); i++)
                {
                    var text = content.pages().get(i).raw();
                    int height = mc.font.split(text, 114).size() * mc.font.lineHeight;
                    if (height > 128)
                        throw new AssertionError("Guide page " + (i + 1) + " exceeds the native book height (" + height + "): " + text.getString());
                }
            });
            c.takeScreenshot("nullspace-field-guide-cover");
            // Inspect the native contents page and its narration.
            c.runOnClient(mc ->
            {
                var screen = (BookViewScreen) mc.gui.screen();
                if (!screen.setPage(1) || !screen.getNarrationMessage().getString().contains("Build my first ship") || !screen.getNarrationMessage().getString().contains("Already on a ship"))
                    throw new AssertionError("Native guide contents must navigate to feature chapters");
            });
            c.takeScreenshot("nullspace-field-guide-contents");
            c.runOnClient(mc ->
            {
                var screen = (BookViewScreen) mc.gui.screen();
                var pages = mc.player.getMainHandItem().get(DataComponents.WRITTEN_BOOK_CONTENT).pages();
                for (int i = 0; i < pages.size(); i++)
                    if (pages.get(i).raw().getString().startsWith("Terminal\nMakes"))
                    {
                        screen.setPage(i);
                        return;
                    }
                throw new AssertionError("Terminal recipe must be present in the real book");
            });
            c.runOnClient(mc ->
            {
                var stack = mc.player.getMainHandItem();
                var access = BookViewScreen.BookAccess.fromItem(stack);
                var data = stack.get(GuideRecipes.COMPONENT);
                if (data == null || data.recipes().size() != GuideContent.RECIPES.size())
                    throw new AssertionError("Server must synchronize graphical recipes");
                int first = access.getPageCount() - data.recipes().size();
                for (int i = 0; i < data.recipes().size(); i++)
                {
                    var recipe = GuideRecipeRenderer.recipe(access, first + i);
                    if (recipe == null || !recipe.equals(data.recipes().get(i)))
                        throw new AssertionError("Missing graphical page " + i);
                }
                if (GuideRecipeRenderer.recipe(access, first - 1) != null)
                    throw new AssertionError("Icons must stay off chapter/index pages");
                var ordinary = new ItemStack(Items.WRITTEN_BOOK);
                ordinary.set(DataComponents.WRITTEN_BOOK_CONTENT, stack.get(DataComponents.WRITTEN_BOOK_CONTENT));
                if (GuideRecipeRenderer.recipe(BookViewScreen.BookAccess.fromItem(ordinary), first) != null)
                    throw new AssertionError("Ordinary books must remain unchanged");
                int terminalPage = first + GuideContent.RECIPES.indexOf("computer_terminal");
                int left = (mc.gui.screen().width - 192) / 2 + 36;
                int basePage = first + GuideContent.RECIPES.indexOf("computer_base");
                if (GuideRecipeRenderer.target(access, terminalPage, mc.gui.screen().width, left + 27, 82) != basePage)
                    throw new AssertionError("Computer base icon must link to its recipe");
                mc.gui.screen().mouseClicked(new MouseButtonEvent(left + 27, 82, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
                if (!mc.gui.screen().getNarrationMessage().getString().contains("Computer base"))
                    throw new AssertionError("Clicking the actual ingredient must turn the page");
                mc.gui.screen().mouseClicked(new MouseButtonEvent(left + 5, 150, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
                if (!mc.gui.screen().getNarrationMessage().getString().contains("Terminal"))
                    throw new AssertionError("Back must restore the recipe that was being followed; got: " + mc.gui.screen().getNarrationMessage().getString());
                mc.gui.screen().mouseClicked(new MouseButtonEvent(left + 27, 82, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
                if (!mc.gui.screen().getNarrationMessage().getString().contains("Computer base"))
                    throw new AssertionError("Right-click recipe shortcut must remain supported");
                ((BookViewScreen) mc.gui.screen()).setPage(terminalPage);
            });
            c.takeScreenshot("nullspace-field-guide-recipe");
            c.runOnClient(mc -> mc.gui.screen().onClose());
            // Exercise the same renderer through native lectern inventory synchronization.
            world.getServer().runOnServer(server ->
            {
                var p = world.getConnection().getServerPlayer();
                var level = p.level();
                var pos = p.blockPosition().offset(2, 0, 0);
                level.setBlock(pos, Blocks.LECTERN.defaultBlockState(), 18);
                var guide = new ItemStack(FieldGuide.ITEM);
                FieldGuide.refresh(guide, level);
                LecternBlock.tryPlaceBook(p, level, pos, level.getBlockState(pos), guide);
                p.openMenu((LecternBlockEntity) level.getBlockEntity(pos));
            });
            c.waitFor(mc -> mc.gui.screen() instanceof LecternScreen, 100);
            c.runOnClient(mc ->
            {
                var screen = (LecternScreen) mc.gui.screen();
                var access = BookViewScreen.BookAccess.fromItem(screen.getMenu().getBook());
                int page = access.getPageCount() - GuideContent.RECIPES.size() + GuideContent.RECIPES.indexOf("circuit_board");
                if (GuideRecipeRenderer.recipe(access, page).count() != 4)
                    throw new AssertionError("Lecterns must retain ingredient data and output quantities");
                screen.setPage(page);
            });
            c.takeScreenshot("nullspace-field-guide-lectern");
            c.runOnClient(mc -> mc.gui.screen().onClose());
        }
        ClientTestEvidence.passed(getClass());
    }
}
