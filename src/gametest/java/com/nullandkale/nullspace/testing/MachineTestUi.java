package com.nullandkale.nullspace.testing;

import com.mojang.blaze3d.platform.InputConstants;
import com.nullandkale.nullspace.ui.screen.MachineScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

public final class MachineTestUi {
  public static void clickLabel(ClientGameTestContext context, String label) {
    context.waitFor(
        mc ->
            mc.gui.screen() instanceof MachineScreen s
                && s.children().stream()
                    .anyMatch(
                        w ->
                            w instanceof net.minecraft.client.gui.components.Button b
                                && b.visible
                                && b.active
                                && b.getMessage().getString().equals(label)),
        200);
    context.runOnClient(
        mc -> {
          var screen = (MachineScreen) mc.gui.screen();
          var button =
              screen.children().stream()
                  .filter(
                      w ->
                          w instanceof net.minecraft.client.gui.components.Button b
                              && b.visible
                              && b.active
                              && b.getMessage().getString().equals(label))
                  .map(w -> (net.minecraft.client.gui.components.Button) w)
                  .findFirst()
                  .orElseThrow();
          screen.mouseClicked(
              new MouseButtonEvent(
                  button.getX() + button.getWidth() / 2.0,
                  button.getY() + button.getHeight() / 2.0,
                  new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
              false);
        });
  }

  public static void click(ClientGameTestContext context, int x, int y) {
    context.runOnClient(
        mc -> {
          var screen = (MachineScreen) mc.gui.screen();
          screen.mouseClicked(
              new MouseButtonEvent(
                  (screen.width - screen.panelWidth()) / 2.0 + x,
                  (screen.height - screen.panelHeight()) / 2.0 + y,
                  new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
              false);
        });
  }

  public static void shiftItem(ClientGameTestContext context, Item item) {
    context.runOnClient(
        mc -> {
          var menu = ((MachineScreen) mc.gui.screen()).getMenu();
          int source = -1;
          for (int i = 10; i < menu.slots.size(); i++)
            if (menu.getSlot(i).getItem().is(item)) {
              source = i;
              break;
            }
          if (source < 0) throw new AssertionError("Missing inventory item " + item);
          mc.gameMode.handleContainerInput(
              menu.containerId, source, 0, ContainerInput.QUICK_MOVE, mc.player);
        });
    context.waitTicks(4);
  }

  private MachineTestUi() {}
}
