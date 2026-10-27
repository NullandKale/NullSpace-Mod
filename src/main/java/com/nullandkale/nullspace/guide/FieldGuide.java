package com.nullandkale.nullspace.guide;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

/** A normal written book, usable in survival and on vanilla lecterns. */
public final class FieldGuide {
  private static final Identifier ID = Identifier.parse("nullspace:field_guide");
  public static final Item ITEM =
      Registry.register(
          BuiltInRegistries.ITEM,
          ID,
          new WrittenBookItem(
              new Item.Properties()
                  .setId(ResourceKey.create(Registries.ITEM, ID))
                  .stacksTo(1)
                  .component(GuideRecipes.COMPONENT, new GuideRecipes(GuideContent.recipes(null)))
                  .component(DataComponents.WRITTEN_BOOK_CONTENT, GuideContent.builtin())) {
            @Override
            public InteractionResult use(Level level, Player player, InteractionHand hand) {
              if (player instanceof ServerPlayer serverPlayer) {
                refresh(player.getItemInHand(hand), serverPlayer.level());
                serverPlayer.containerMenu.broadcastChanges();
              }
              return super.use(level, player, hand);
            }

            @Override
            public void onCraftedPostProcess(ItemStack stack, Level level) {
              if (level instanceof ServerLevel serverLevel) refresh(stack, serverLevel);
            }
          });

  public static void refresh(ItemStack stack, ServerLevel level) {
    stack.set(DataComponents.WRITTEN_BOOK_CONTENT, GuideContent.forServer(level.getServer()));
    stack.set(GuideRecipes.COMPONENT, new GuideRecipes(GuideContent.recipes(level.getServer())));
  }

  public static void supply(ServerPlayer player) {
    if (player.getInventory().countItem(ITEM) > 0) return;
    var stack = new ItemStack(ITEM);
    refresh(stack, player.level());
    player.getInventory().add(stack);
    if (!stack.isEmpty())
      player
          .level()
          .addFreshEntity(
              new net.minecraft.world.entity.item.ItemEntity(
                  player.level(), player.getX(), player.getY(), player.getZ(), stack));
  }

  public static void initialize() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, env) ->
            dispatcher.register(
                Commands.literal("nullspace")
                    .then(
                        Commands.literal("guide")
                            .executes(
                                ctx -> {
                                  var player = ctx.getSource().getPlayerOrException();
                                  supply(player);
                                  player.sendSystemMessage(
                                      Component.literal(
                                          "NullSpace Field Guide: right-click the book. Contents"
                                              + " and recipe links jump between pages."));
                                  return 1;
                                }))));
  }

  private FieldGuide() {}
}
