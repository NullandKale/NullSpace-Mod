package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.gameplay.start.WorldStartSettings;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Choose a named design directly. No world, player or network connection is needed. */
public final class StartingShipScreen extends Screen {
  private final Screen parent;
  private final WorldStartAccess selection;
  private final Runnable changed;
  private int page;
  private int rows;

  public StartingShipScreen(Screen parent, WorldStartAccess selection, Runnable changed) {
    super(Component.literal("Choose your starting ship"));
    this.parent = parent;
    this.selection = selection;
    this.changed = changed;
  }

  @Override
  protected void init() {
    rows = Math.clamp((height - 110) / 24, 1, 8);
    int pages = (ShipBlueprints.PRESETS.size() + rows - 1) / rows;
    page = Math.min(page, pages - 1);
    int w = Math.min(310, width - 20), x = (width - w) / 2;
    for (int row = 0; row < rows; row++) {
      int index = page * rows + row;
      if (index >= ShipBlueprints.PRESETS.size()) break;
      var preset = ShipBlueprints.PRESETS.get(index);
      var button =
          Button.builder(
                  Component.literal(
                      (preset.id().equals(selection.nullspace$start().ship()) ? "> " : "")
                          + preset.name()
                          + " | "
                          + preset.role()),
                  b -> {
                    selection.nullspace$start(
                        new WorldStartSettings(selection.nullspace$start().space(), preset.id()));
                    changed.run();
                    onClose();
                  })
              .bounds(x, 46 + row * 24, w, 20)
              .build();
      button.setTooltip(
          Tooltip.create(Component.literal(preset.size() + "\n" + preset.description())));
      addRenderableWidget(button);
    }
    var previous =
        addRenderableWidget(
            Button.builder(
                    Component.literal("Previous"),
                    b -> {
                      page--;
                      rebuildWidgets();
                    })
                .bounds(x, height - 56, (w - 8) / 2, 20)
                .build());
    previous.active = page > 0;
    var next =
        addRenderableWidget(
            Button.builder(
                    Component.literal("Next"),
                    b -> {
                      page++;
                      rebuildWidgets();
                    })
                .bounds(x + (w + 8) / 2, height - 56, (w - 8) / 2, 20)
                .build());
    next.active = page < pages - 1;
    addRenderableWidget(
        Button.builder(Component.literal("Back"), b -> onClose())
            .bounds(x, height - 30, w, 20)
            .build());
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    super.extractRenderState(g, mouseX, mouseY, delta);
    g.text(font, title, (width - font.width(title)) / 2, 14, 0xffffffff, true);
    String label =
        "Page " + (page + 1) + " / " + ((ShipBlueprints.PRESETS.size() + rows - 1) / rows);
    g.text(font, label, (width - font.width(label)) / 2, 29, 0xffb0b0b0, false);
  }
}
