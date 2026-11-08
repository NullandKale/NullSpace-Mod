package com.nullandkale.nullspace.gameplay.ownership;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TransferLocks;
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
    if (!stack.is(ModBlocks.SHIP_TITLE)) return false;
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
    var stack = new ItemStack(ModBlocks.SHIP_TITLE);
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
    player
        .getInventory()
        .placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
    return true;
  }

  private static CompoundTag shipFor(net.minecraft.server.MinecraftServer server, ItemStack stack) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    return WorldStore.get(server).repository().find(data.getStringOr(SHIP, ""));
  }

  /**
   * Handing a ship over, conservatively: the owner first signs the title over, then whoever holds
   * that signed title takes ownership. A found or stolen unsigned title transfers nothing.
   */
  public static String use(ServerPlayer player, ItemStack stack) {
    var server = player.level().getServer();
    var ship = shipFor(server, stack);
    if (ship == null || !valid(stack, ship)) {
      markIfVoid(server, stack);
      return ship == null
          ? "This title is void: its ship no longer exists"
          : "This title is void. Its owner has a newer one";
    }
    String name = ship.getStringOr("home", "This ship");
    int auth = ship.getIntOr("titleAuth", 0);
    boolean signed = ship.getIntOr("titleSigned", -1) == auth;
    if (owner(player, ship)) {
      if (signed) ship.remove("titleSigned");
      else ship.putInt("titleSigned", auth);
      WorldStore.get(server).save();
      return signed
          ? "Signature cancelled. You still own " + name
          : "Title signed over. Whoever holds it can now take ownership of "
              + name
              + ". Right-click again to cancel";
    }
    if (!signed) return "This title is not signed over. Only " + name + "'s owner can sign it";
    if (TransferLocks.busy(server, ship) || ShipStorage.active(server, ship.getStringOr("id", "")))
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
    WorldStore.get(server).save();
    if (!previous.isEmpty()) {
      var old = server.getPlayerList().getPlayer(java.util.UUID.fromString(previous));
      if (old != null)
        old.sendSystemMessage(
            Component.literal(player.getPlainTextName() + " now owns " + name + "."));
    }
    return "You now own " + name + ". Its cargo and crew access stay as they were";
  }

  private static final String VOID = "nullspaceVoid";

  /**
   * A title replaced by a newer one (or for a ship that no longer exists) is visibly marked void,
   * so a stale copy cannot be mistaken for the real one. Returns true if the stack changed.
   */
  static boolean markIfVoid(net.minecraft.server.MinecraftServer server, ItemStack stack) {
    if (!stack.is(ModBlocks.SHIP_TITLE)) return false;
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (data.getBooleanOr(VOID, false) || data.getStringOr(SHIP, "").isEmpty()) return false;
    var ship = shipFor(server, stack);
    if (ship != null && valid(stack, ship)) return false;
    data.putBoolean(VOID, true);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    String name = ship == null ? "a scrapped ship" : ship.getStringOr("home", "Ship");
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Void Ship Title - " + name));
    stack.set(
        DataComponents.LORE,
        new ItemLore(
            List.of(
                Component.literal(
                    ship == null ? "This ship no longer exists" : "Replaced by a newer title"),
                Component.literal("No longer grants anything"))));
    return true;
  }

  public static void initialize() {
    // Every two seconds, mark stale titles void in online players' inventories.
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          if (server.getTickCount() % 40 != 0) return;
          for (var player : server.getPlayerList().getPlayers()) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++)
              markIfVoid(server, inventory.getItem(i));
          }
        });
    net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
        (player, world, hand) -> {
          var stack = player.getItemInHand(hand);
          if (!stack.is(ModBlocks.SHIP_TITLE)) return net.minecraft.world.InteractionResult.PASS;
          if (player instanceof ServerPlayer p)
            p.sendSystemMessage(Component.literal(use(p, stack)));
          return net.minecraft.world.InteractionResult.SUCCESS;
        });
  }

  private ShipTitles() {}
}
