package com.nullandkale.nullspace.ship;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * A physical Ship Title authorizes one existing vessel. The server keeps the vessel; the paper
 * carries its id and an authorization number. Issuing a new title advances the number, so a copied
 * or older paper can never deploy the ship or outlive a replacement.
 */
public final class ShipTitles {
  private static final String SHIP = "nullspaceShip", AUTH = "nullspaceTitle";

  public static String registration(CompoundTag ship) {
    String id = ship.getStringOr("id", "").replace("-", "");
    return "NS-" + id.substring(0, Math.min(6, id.length())).toUpperCase(Locale.ROOT);
  }

  public static boolean owner(ServerPlayer player, CompoundTag ship) {
    return ship.getStringOr("owner", "").equals(player.getUUID().toString());
  }

  public static boolean stored(CompoundTag ship) {
    return ship.getBooleanOr("stored", false);
  }

  /** Whether this stack is the current title of this ship. */
  public static boolean valid(ItemStack stack, CompoundTag ship) {
    if (!stack.is(ShipBlocks.SHIP_TITLE)) return false;
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    return data.getStringOr(SHIP, "").equals(ship.getStringOr("id", "-"))
        && data.getIntOr(AUTH, -1) == ship.getIntOr("titleAuth", 0)
        && ship.getIntOr("titleAuth", 0) > 0;
  }

  public static boolean holds(ServerPlayer player, CompoundTag ship) {
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++)
      if (valid(inventory.getItem(i), ship)) return true;
    return false;
  }

  /** Issues a new title for an owned ship and invalidates every earlier copy. */
  public static ItemStack issue(CompoundTag ship) {
    int auth = ship.getIntOr("titleAuth", 0) + 1;
    ship.putInt("titleAuth", auth);
    var stack = new ItemStack(ShipBlocks.SHIP_TITLE);
    var data = new CompoundTag();
    data.putString(SHIP, ship.getStringOr("id", ""));
    data.putInt(AUTH, auth);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    stack.set(
        DataComponents.CUSTOM_NAME,
        Component.literal("Ship Title - " + ship.getStringOr("home", "Ship")));
    stack.set(
        DataComponents.LORE,
        new ItemLore(
            List.of(
                Component.literal("Registration " + registration(ship)),
                Component.literal("Deploy at a Pad Computer: My ships"))));
    return stack;
  }

  /** Gives the player a valid title unless they already carry one. */
  public static boolean ensureHeld(ServerPlayer player, CompoundTag ship) {
    if (holds(player, ship)) return false;
    var stack = issue(ship);
    // Adds to the inventory, or drops it at the player if the inventory is full.
    player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    return true;
  }

  private static CompoundTag shipFor(net.minecraft.server.MinecraftServer server, ItemStack stack) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    return ShipStore.get(server).repository().find(data.getStringOr(SHIP, ""));
  }

  /**
   * Handing a ship over, conservatively: the owner first signs the title over, then whoever holds
   * that signed title takes ownership. A found or stolen unsigned title transfers nothing.
   */
  public static String use(ServerPlayer player, ItemStack stack) {
    var server = player.level().getServer();
    var ship = shipFor(server, stack);
    if (ship == null || !valid(stack, ship))
      return "This title is no longer valid. Its owner has a newer one";
    String name = ship.getStringOr("home", "This ship");
    int auth = ship.getIntOr("titleAuth", 0);
    boolean signed = ship.getIntOr("titleSigned", -1) == auth;
    if (owner(player, ship)) {
      if (signed) ship.remove("titleSigned");
      else ship.putInt("titleSigned", auth);
      ShipStore.get(server).save();
      return signed
          ? "Signature cancelled. You still own " + name
          : "Title signed over. Whoever holds it can now take ownership of "
              + name
              + ". Right-click again to cancel";
    }
    if (!signed)
      return "This title is not signed over. Only " + name + "'s owner can sign it";
    if (TravelService.busy(server, ship) || ShipRecall.active(server, ship.getStringOr("id", "")))
      return name + " is moving. Take ownership once it has arrived";
    String previous = ship.getStringOr("owner", "");
    ship.putString("owner", player.getUUID().toString());
    var users = new CompoundTag();
    users.putBoolean(player.getUUID().toString(), true);
    ship.put("recallUsers", users);
    ship.remove("titleSigned");
    // The same paper now belongs to the new owner; every older copy stops working.
    var fresh = issue(ship);
    stack.set(DataComponents.CUSTOM_DATA, fresh.get(DataComponents.CUSTOM_DATA));
    ShipStore.get(server).save();
    if (!previous.isEmpty()) {
      var old = server.getPlayerList().getPlayer(java.util.UUID.fromString(previous));
      if (old != null)
        old.sendSystemMessage(
            Component.literal(player.getPlainTextName() + " now owns " + name + "."));
    }
    return "You now own " + name + ". Its cargo and crew access stay as they were";
  }

  public static void initialize() {
    net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
        (player, world, hand) -> {
          var stack = player.getItemInHand(hand);
          if (!stack.is(ShipBlocks.SHIP_TITLE)) return net.minecraft.world.InteractionResult.PASS;
          if (player instanceof ServerPlayer p) p.sendSystemMessage(Component.literal(use(p, stack)));
          return net.minecraft.world.InteractionResult.SUCCESS;
        });
  }

  private ShipTitles() {}
}
