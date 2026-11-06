package com.nullandkale.nullspace.client;

import com.nullandkale.nullspace.systems.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Ordinary Minecraft slots plus the controls for the block being inspected. */
public final class MachineScreen extends AbstractContainerScreen<MachineMenu>
    implements com.nullandkale.nullspace.client.scene.SceneScreen {
  private record Draft(
      Object level,
      Object block,
      net.minecraft.core.BlockPos at,
      String mode,
      String name,
      int page,
      String selected,
      boolean nearby,
      boolean editing,
      boolean details) {}

  private static Draft returning;

  public static void clearReturn() {
    returning = null;
  }

  private EditBox name;
  private String mode = "";
  private int page;
  private String selected = "";
  private boolean editingName, details, pending;
  private int pendingResponse;
  private Button setDestination, previous, next, restart;
  private boolean nearby = true;
  private final java.util.List<Button> resultButtons = new java.util.ArrayList<>();
  private Button enabledButton, saveName, editName;
  private Button accept, decline, cancel, undock;
  private Button lessPower, morePower;
  private boolean refreshPreview;

  private java.util.List<CompoundTag> rows() {
    return menu.reading.getListOrEmpty(mode.equals("monitor") ? "cameras" : "destinations").stream()
        .map(t -> (CompoundTag) t)
        .filter(
            t ->
                !mode.contains("scanner")
                    || !nearby
                    || t.getStringOr("region", "")
                        .equals(menu.reading.getStringOr("region", "sol_terra")))
        .toList();
  }

  private int ticks;
  private com.nullandkale.nullspace.scene.SceneService.Frame frame;
  private com.nullandkale.nullspace.client.scene.SceneViewport viewport;

  public long previewFrames() {
    return viewport == null ? 0 : viewport.renderedFrames();
  }

  public String previewStatus() {
    return frame == null
        ? "Waiting for camera ticks=" + ticks + " mode=" + mode
        : frame.scene().getStringOr("status", "");
  }

  public void preview(com.nullandkale.nullspace.scene.SceneService.Frame next) {
    if (next.scene().getLongOr("console", 0) == menu.at.asLong()) frame = next;
  }

  @Override
  public void renderFrame() {
    if (frame != null) {
      if (viewport == null)
        viewport = new com.nullandkale.nullspace.client.scene.SceneViewport(320, 180);
      viewport.render(frame.scene(), 0, 0, 70);
    }
  }

  @Override
  public void removed() {
    if (frame != null && minecraft.getConnection() != null)
      ClientPlayNetworking.send(
          new com.nullandkale.nullspace.scene.SceneService.KeepAlive(
              frame.lease(), true, frame.scene().getIntOr("selection", 0)));
    if (viewport != null) viewport.close();
    super.removed();
  }

  public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title, 320, 240);
    inventoryLabelX = 8;
    inventoryLabelY = 154;
  }

  public void readingChanged() {
    if (!mode.equals(menu.reading.getStringOr("mode", ""))) rebuildWidgets();
    updateControls();
  }

  private void send(int action, String value) {
    if (action == 1 || action == 6)
      returning =
          new Draft(
              minecraft.level,
              minecraft.level.getBlockEntity(menu.at),
              menu.at,
              mode,
              name.getValue(),
              page,
              selected,
              nearby,
              editingName,
              details);
    if (action != 9 && action != 1 && action != 6) {
      if (pending) return;
      pending = true;
      pendingResponse = menu.reading.getIntOr("response", 0);
    }
    ClientPlayNetworking.send(new MachineMenus.Edit(menu.containerId, action, value));
  }

  private Button button(String title, int x, int y, int width, Runnable run) {
    return addRenderableWidget(
        Button.builder(Component.literal(title), b -> run.run())
            .bounds(leftPos + x, topPos + y, width, 18)
            .build());
  }

  @Override
  protected void init() {
    super.init();
    resultButtons.clear();
    mode = menu.reading.getStringOr("mode", "");
    name =
        addRenderableWidget(
            new EditBox(font, leftPos + 108, topPos + 16, 134, 18, Component.literal("Name")));
    name.setMaxLength(32);
    name.setValue(menu.reading.getStringOr("name", ""));
    editingName = mode.equals("camera") || mode.equals("monitor") || mode.equals("docking_base");
    saveName = button("Save name", 246, 16, 70, () -> send(2, name.getValue()));
    editName =
        button(
            "Name",
            108,
            16,
            134,
            () -> {
              editingName = true;
              updateControls();
              setFocused(name);
            });
    editName.visible = !editingName;
    button("Details", 8, 184, 96, () -> details = !details);
    button("Close", 8, 158, 96, this::onClose);
    enabledButton =
        button(
            menu.reading.getBooleanOr("enabled", true) ? "On" : "Off",
            8,
            16,
            96,
            () -> send(0, ""));
    lessPower = button("-", 8, 220, 24, () -> send(15, "-1"));
    morePower = button("+", 80, 220, 24, () -> send(15, "1"));
    if (mode.contains("scanner")) {
      button(
          "Nearby",
          8,
          40,
          53,
          () -> {
            page = 0;
            selected = "";
            nearby = true;
            updateControls();
          });
      button(
          "Destinations",
          65,
          40,
          66,
          () -> {
            page = 0;
            selected = "";
            nearby = false;
            updateControls();
          });
      for (int i = 0; i < 4; i++) {
        final int row = i;
        resultButtons.add(
            button(
                "Select result " + (i + 1),
                8,
                61 + i * 15,
                120,
                () -> {
                  var entries = rows();
                  if (page * 4 + row < entries.size())
                    selected = entries.get(page * 4 + row).getStringOr("id", "");
                  updateControls();
                }));
      }
      setDestination =
          button(
              "Set destination",
              8,
              126,
              96,
              () -> {
                if (!selected.isBlank()
                    && rows().stream()
                        .skip(page * 4L)
                        .limit(4)
                        .anyMatch(r -> selected.equals(r.getStringOr("id", "")))) send(8, selected);
              });
      previous =
          button(
              "<",
              108,
              126,
              20,
              () -> {
                page = Math.max(0, page - 1);
                selected = "";
                updateControls();
              });
      next =
          button(
              ">",
              132,
              126,
              20,
              () -> {
                page++;
                selected = "";
                updateControls();
              });
    } else if (mode.equals("monitor")) {
      for (int i = 0; i < 4; i++) {
        final int row = i;
        resultButtons.add(
            button(
                "Camera " + (i + 1),
                8,
                56 + i * 16,
                120,
                () -> {
                  var cameras = menu.reading.getListOrEmpty("cameras");
                  if (page * 4 + row < cameras.size())
                    send(
                        7,
                        Long.toString(
                            ((CompoundTag) cameras.get(page * 4 + row)).getLongOr("at", 0)));
                  refreshPreview = true;
                }));
      }
      previous = button("<", 8, 126, 30, () -> page = Math.max(0, page - 1));
      next = button(">", 42, 126, 30, () -> page++);
      button("View fullscreen", 156, 126, 120, () -> send(6, ""));
    } else if (mode.equals("camera")) button("View fullscreen", 8, 126, 120, () -> send(6, ""));
    else if (mode.equals("air_supply")) {
      button("Show air opening", 8, 126, 108, () -> send(3, ""));
      button("Show leak", 120, 126, 88, () -> send(4, ""));
    } else if (mode.equals("engine") || mode.equals("weapon"))
      button(
          mode.equals("engine") ? "Show exhaust" : "Show muzzle", 8, 126, 120, () -> send(3, ""));
    else if (mode.equals("docking_base")) {
      accept = button("Accept", 8, 126, 65, () -> send(10, ""));
      decline = button("Decline", 77, 126, 65, () -> send(11, ""));
      cancel = button("Cancel", 146, 126, 65, () -> send(12, ""));
      undock = button("Undock", 215, 126, 65, () -> send(13, ""));
    }
    if (mode.equals("generator") || mode.equals("battery"))
      restart = button("Restart ship supply", 8, 126, 124, () -> send(14, ""));
    for (var row : resultButtons) row.setHeight(14);
    if (returning != null && !mode.isBlank()) {
      if (returning.level == minecraft.level
          && returning.block == minecraft.level.getBlockEntity(menu.at)
          && returning.at.equals(menu.at)
          && returning.mode.equals(mode)) {
        name.setValue(returning.name);
        page = returning.page;
        selected = returning.selected;
        nearby = returning.nearby;
        editingName = returning.editing;
        details = returning.details;
      }
      returning = null;
    }
    updateControls();
  }

  @Override
  public boolean mouseClicked(
      net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
    boolean wasEditing = editingName;
    boolean handled = super.mouseClicked(event, doubleClick);
    if (!wasEditing && editingName) setFocused(name);
    return handled;
  }

  @Override
  public void containerTick() {
    super.containerTick();
    ticks++;
    if ((mode.equals("camera") || mode.equals("monitor")) && ticks % 20 == 1) {
      if (frame == null || refreshPreview) {
        send(9, "");
        refreshPreview = false;
      } else
        ClientPlayNetworking.send(
            new com.nullandkale.nullspace.scene.SceneService.KeepAlive(
                frame.lease(), false, frame.scene().getIntOr("selection", 0)));
    }
    if (!mode.equals(menu.reading.getStringOr("mode", ""))) rebuildWidgets();
    updateControls();
  }

  private void updateControls() {
    if (pending && menu.reading.getIntOr("response", 0) > pendingResponse) pending = false;
    boolean canConfigure = menu.reading.getBooleanOr("canConfigure", true);
    if (lessPower != null) {
      int allocation = menu.reading.getIntOr("allocation", -1);
      lessPower.visible = morePower.visible = allocation >= 0;
      lessPower.active =
          canConfigure
              && !pending
              && allocation > 0
              && menu.reading.getBooleanOr("connected", false);
      morePower.active =
          canConfigure
              && !pending
              && allocation < 8
              && menu.reading.getBooleanOr("connected", false);
    }
    if (editName != null) {
      editName.visible = !editingName;
      String saved = menu.reading.getStringOr("name", "");
      editName.setMessage(
          Component.literal(
              font.plainSubstrByWidth("Name: " + (saved.isBlank() ? "-" : saved), 110) + " ✎"));
    }
    if (saveName != null) {
      saveName.active =
          canConfigure && !pending && !name.getValue().equals(menu.reading.getStringOr("name", ""));
      saveName.visible = editingName;
    }
    if (name != null) {
      name.active = canConfigure;
      name.visible = editingName;
    }
    if (enabledButton != null) {
      enabledButton.active =
          canConfigure && !pending && menu.reading.getBooleanOr("connected", false);
      enabledButton.setMessage(
          Component.literal(menu.reading.getBooleanOr("enabled", true) ? "On" : "Off"));
    }
    if (restart != null) {
      restart.visible = menu.reading.getBooleanOr("restart", false);
      restart.active = canConfigure && !pending;
    }
    if (mode.equals("docking_base") && accept != null) {
      accept.visible =
          decline.visible = canConfigure && menu.reading.getBooleanOr("incoming", false);
      cancel.visible = canConfigure && menu.reading.getBooleanOr("maneuver", false);
      undock.active = canConfigure && menu.reading.getBooleanOr("clamped", false);
    }
    var entries = rows();
    page = Math.clamp(page, 0, Math.max(0, (entries.size() - 1) / 4));
    if (mode.contains("scanner")
        && entries.stream()
            .skip(page * 4L)
            .limit(4)
            .noneMatch(r -> selected.equals(r.getStringOr("id", "")))) selected = "";
    if (setDestination != null)
      setDestination.active =
          !pending && !selected.isBlank() && menu.reading.getBooleanOr("selectable", false);
    if (previous != null) previous.active = page > 0;
    if (next != null) next.active = (page + 1) * 4 < entries.size();
    for (int i = 0; i < resultButtons.size(); i++) {
      int index = page * 4 + i;
      var button = resultButtons.get(i);
      button.active = index < entries.size();
      String label =
          index < entries.size() ? entries.get(index).getStringOr("name", "") : "No result";
      button.setMessage(
          Component.literal(
              ((index < entries.size()
                          && (mode.equals("monitor")
                                  ? entries.get(index).getLongOr("at", 0) + ""
                                  : entries.get(index).getStringOr("id", ""))
                              .equals(
                                  mode.equals("monitor")
                                      ? menu.reading.getStringOr("feed", "")
                                      : selected))
                      ? "> "
                      : "")
                  + label.substring(0, Math.min(18, label.length()))));
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
    g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, 0xff343434);
    g.fill(leftPos + 4, topPos + 38, leftPos + 316, topPos + 146, 0xff262626);
    g.fill(leftPos + 4, topPos + 150, leftPos + 108, topPos + 240, 0xff292929);
    if (menu.machineHasSlots())
      g.fill(leftPos + 122, topPos + 150, leftPos + 292, topPos + 234, 0xff292929);
    for (var slot : menu.slots)
      if (slot.isActive()) {
        g.fill(
            leftPos + slot.x - 1,
            topPos + slot.y - 1,
            leftPos + slot.x + 17,
            topPos + slot.y + 17,
            0xff777777);
        g.fill(
            leftPos + slot.x,
            topPos + slot.y,
            leftPos + slot.x + 16,
            topPos + slot.y + 16,
            0xff373737);
      }
    g.nextStratum();
    super.extractRenderState(g, mx, my, delta);
    if (mx >= leftPos + 248
        && mx < leftPos + 308
        && my >= topPos + 32
        && my < topPos + 52
        && !menu.reading.getStringOr("upgradeBenefit", "").isBlank()) {
      g.setComponentTooltipForNextFrame(
          font,
          java.util.List.of(
              Component.literal("System Upgrades: " + menu.reading.getIntOr("tier", 0) + "/3"),
              Component.literal("Now: " + menu.reading.getStringOr("upgradeBenefit", "")),
              Component.literal("Next: " + menu.reading.getStringOr("nextUpgradeBenefit", ""))),
          mx,
          my);
    }
  }

  @Override
  protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
    g.text(font, title, 8, 4, 0xffe0e0e0, false);
    var data = menu.reading;
    int allocation = data.getIntOr("allocation", -1);
    if (allocation >= 0) {
      text(g, "Power", 8, 208, 100);
      text(g, allocation + " / 8", 38, 224, 40);
    }
    if (java.util.Set.of("generator", "engine", "battery", "local_scanner", "long_range_scanner")
        .contains(mode)) {
      text(g, "Upgrades", 250, 4, 66);
      text(g, data.getIntOr("tier", 0) + "/3", 250, 16, 66);
    }
    String status =
        pending ? "Waiting for server..." : data.getStringOr("status", "Getting machine status...");
    if (frame != null && (mode.equals("camera") || mode.equals("monitor")))
      status = frame.scene().getStringOr("status", status);
    int sy = 68;
    for (var line :
        font.split(
            Component.literal(status.replace('\n', ' ')), mode.equals("monitor") ? 86 : 176)) {
      if (sy > 92) break;
      g.text(font, line, mode.equals("monitor") ? 226 : 140, sy, 0xffeeeecc, false);
      sy += 9;
    }
    if (java.util.Set.of("generator", "engine", "battery", "local_scanner").contains(mode)) {
      String benefit =
          switch (mode) {
            case "generator" -> "Max " + data.getIntOr("outputMax", 8) + "/tick";
            case "engine" -> "Thrust " + data.getIntOr("thrustMax", 2);
            case "battery" -> "Holds " + data.getIntOr("capacity", 1200);
            default -> "Range " + data.getIntOr("range", 1024);
          };
      text(g, benefit, 250, 54, 66);
    }
    if (mode.equals("generator")) {
      int generated = data.getIntOr("output", 0), demand = data.getIntOr("powerRequested", 0);
      int scale = Math.max(1, Math.max(data.getIntOr("powerCapacity", 0), demand));
      gauge(
          g,
          "Generating",
          generated,
          scale,
          8,
          44,
          124,
          generated >= demand ? 0xff69b75e : 0xffdbad55);
      gauge(g, "Ship demand", demand, scale, 8, 66, 124, 0xffce9656);
      gauge(g, "To batteries", data.getIntOr("charging", 0), scale, 8, 88, 124, 0xff76a8cc);
      text(g, "Any furnace fuel", 8, 112, 124);
      // The meters already show a working generator; only call out the problem.
      if (data.getIntOr("fuelHere", 0) == 0)
        g.text(font, "No fuel stored", 140, 48, 0xffdbad55, false);
      text(g, "Fuel", 140, 94, 112);
      text(g, "Empty", 260, 84, 56);
      text(g, "containers", 260, 94, 56);
    } else if (mode.equals("battery")) {
      int energy = data.getIntOr("energy", 0), cap = Math.max(1, data.getIntOr("capacity", 1200));
      if (data.getBooleanOr("connected", false)) bar(g, "Power stored", energy, cap);
      else text(g, "Charge: not connected", 140, 108, 176);
      if (data.getBooleanOr("removalBlocked", false)) {
        text(g, "Cannot remove upgrade", 8, 48, 124);
        text(g, "Discharge below " + (1200 * Math.max(1, data.getIntOr("tier", 0))), 8, 60, 124);
      }
    } else if (mode.equals("engine") || mode.equals("air_supply")) {
      boolean engine = mode.equals("engine");
      if (data.getBooleanOr("connected", false))
        bar(
            g,
            engine ? "Thrust" : "Room air",
            data.getIntOr(engine ? "thrust" : "oxygen", 0),
            engine ? data.getIntOr("thrustMax", 2) : 100);
      if (engine) {
        int y = 46;
        for (var line : font.split(Component.literal("Uses fuel stored in generators."), 124)) {
          g.text(font, line, 8, y, 0xffdddddd, false);
          y += 10;
        }
      } else {
        // The Room air bar shows the level; do not repeat it as text.
        text(g, "This vent's room", 8, 46, 124);
        text(g, "Leaks: " + data.getIntOr("leaks", 0), 8, 62, 124);
      }
    }
    if (mode.equals("camera") || mode.equals("monitor")) {
      if (viewport != null && viewport.target != null) {
        var sampler =
            com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                .getSampler(
                    com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                    false);
        if (mode.equals("camera"))
          g.blit(viewport.target.getColorTextureView(), sampler, 8, 48, 128, 116, 0, 1, 1, 0);
        else g.blit(viewport.target.getColorTextureView(), sampler, 140, 44, 220, 89, 0, 1, 1, 0);
      }
      text(
          g,
          mode.equals("camera")
              ? "Facing: " + data.getStringOr("facing", "")
              : "Wall: " + data.getIntOr("tiles", 1) + " tiles",
          140,
          108,
          220);
    }
    if (mode.equals("docking_base")) {
      text(g, "Connected to: " + data.getStringOr("partner", "None"), 8, 48, 304);
      text(
          g,
          data.getBooleanOr("clamped", false)
              ? "Docked: open the door and walk through"
              : "Dock at a port using your Ship Controller",
          8,
          108,
          304);
    }
    if (mode.contains("scanner")) {
      text(g, "Page " + (page + 1) + "/" + Math.max(1, (rows().size() + 3) / 4), 8, 112, 120);
      var candidate =
          rows().stream().filter(r -> selected.equals(r.getStringOr("id", ""))).findFirst();
      int nameY = 44;
      for (var line :
          font.split(
              Component.literal(
                  candidate.map(r -> r.getStringOr("name", "")).orElse("Choose a place")),
              104)) {
        if (nameY > 62) break;
        g.text(font, line, 140, nameY, 0xffdddddd, false);
        nameY += 9;
      }
      candidate.ifPresent(
          r ->
              text(
                  g,
                  r.getStringOr("kind", "").toLowerCase(java.util.Locale.ROOT)
                      + " | "
                      + r.getStringOr("state", ""),
                  140,
                  99,
                  176));
      text(g, "Destination: " + data.getStringOr("destination", "None"), 140, 108, 176);
    }
    if (details && !mode.contains("scanner")) {
      String values =
          mode.equals("generator")
              ? "Fuel aboard: "
                  + data.getIntOr("fuel", 0)
                  + " | Unit limit: "
                  + data.getIntOr("outputMax", 8)
                  + "/tick"
              : mode.equals("battery")
                  ? "Charge/discharge limit: " + data.getIntOr("rate", 4) + "/tick"
                  : "Fuel aboard: "
                      + data.getIntOr("fuel", 0)
                      + " | "
                      + data.getStringOr("facing", "");
      text(g, values, 8, 36, 232);
    }
    String actionNotice = data.getStringOr("notice", "");
    if (!actionNotice.isBlank()) text(g, actionNotice, 8, 145, 304);
    // Full status is available without clipping, including slot-removal and permission failures.
    if (mx >= leftPos + 140 && mx < leftPos + 316 && my >= topPos + 64 && my < topPos + 99)
      g.setComponentTooltipForNextFrame(font, java.util.List.of(Component.literal(status)), mx, my);
  }

  public int panelWidth() {
    return imageWidth;
  }

  public int panelHeight() {
    return imageHeight;
  }

  private void text(GuiGraphicsExtractor g, String text, int x, int y, int width) {
    g.text(font, font.plainSubstrByWidth(text, width), x, y, 0xffdddddd, false);
  }

  private void bar(GuiGraphicsExtractor g, String label, int current, int max) {
    max = Math.max(1, max);
    g.fill(140, 106, 312, 118, 0xff303030);
    int color =
        mode.equals("air_supply") && current < 25
            ? 0xffc4544c
            : mode.equals("air_supply") && current < 60 ? 0xffcba64b : 0xff5ca54d;
    g.fill(140, 106, 140 + (int) (172L * Math.clamp(current, 0, max) / max), 118, color);
    if (mode.equals("air_supply")) g.fill(183, 106, 184, 118, 0xffeeeeee);
    text(g, label + ": " + Math.clamp(current, 0, max) + " / " + max, 145, 108, 164);
  }

  private void gauge(
      GuiGraphicsExtractor g,
      String label,
      int value,
      int max,
      int x,
      int y,
      int width,
      int color) {
    text(g, label + ": " + value, x, y, width);
    g.fill(x, y + 11, x + width, y + 15, 0xff454545);
    g.fill(
        x,
        y + 11,
        x + (int) ((long) width * Math.clamp(value, 0, Math.max(1, max)) / Math.max(1, max)),
        y + 15,
        color);
  }
}
