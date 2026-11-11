package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.content.machine.MachineMenu;
import com.nullandkale.nullspace.content.machine.MachineMenus;
import com.nullandkale.nullspace.gameplay.scene.ScenePackets;
import com.nullandkale.nullspace.gameplay.systems.FuelSystem;
import com.nullandkale.nullspace.render.SpaceSettings;
import com.nullandkale.nullspace.render.scene.SceneViewport;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Ordinary Minecraft slots plus the controls for the block being inspected. */
public final class MachineScreen extends AbstractContainerScreen<MachineMenu> implements SceneScreen
{
    private record Draft(Object level, Object block, net.minecraft.core.BlockPos at, String mode, String name, int page, String selected, boolean nearby, boolean editing, boolean details)
    {}

    private static Draft returning;

    public static void clearReturn()
    {
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

    private java.util.List<CompoundTag> rows()
    {
        return menu.reading.getListOrEmpty(mode.equals("monitor") ? "cameras" : "destinations").stream().map(t -> (CompoundTag) t).filter(t -> !mode.contains("scanner") || !nearby || t.getStringOr("region", "").equals(menu.reading.getStringOr("region", "sol_terra"))).toList();
    }

    private int ticks;
    private ScenePackets.Frame frame;
    private SceneViewport viewport;

    public long previewFrames()
    {
        return viewport == null ? 0 : viewport.renderedFrames();
    }

    public String previewStatus()
    {
        return frame == null ? "Waiting for camera ticks=" + ticks + " mode=" + mode : frame.scene().getStringOr("status", "");
    }

    public void preview(ScenePackets.Frame next)
    {
        if (next.scene().getLongOr("console", 0) == menu.at.asLong())
            frame = next;
    }

    @Override
    public void renderFrame()
    {
        if (frame != null)
        {
            int w = SpaceSettings.cameraWidth() / 2, h = w * 9 / 16;
            if (viewport == null || viewport.target.width != w || viewport.target.height != h)
            {
                if (viewport != null)
                    viewport.close();
                viewport = new SceneViewport(w, h);
            }
            viewport.render(frame.scene(), 0, 0, 70);
        }
    }

    @Override
    public void removed()
    {
        if (frame != null && minecraft.getConnection() != null)
            ClientPlayNetworking.send(new ScenePackets.KeepAlive(frame.lease(), true, frame.scene().getIntOr("selection", 0)));
        if (viewport != null)
            viewport.close();
        super.removed();
    }

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title)
    {
        super(menu, inventory, title, 320, 240);
        inventoryLabelX = 8;
        inventoryLabelY = 154;
    }

    public void readingChanged()
    {
        if (!mode.equals(menu.reading.getStringOr("mode", "")))
            rebuildWidgets();
        updateControls();
    }

    private void send(int action, String value)
    {
        if (action == 1 || action == 6)
            returning = new Draft(minecraft.level, minecraft.level.getBlockEntity(menu.at), menu.at, mode, name.getValue(), page, selected, nearby, editingName, details);
        if (action != 9 && action != 1 && action != 6)
        {
            if (pending)
                return;
            pending = true;
            pendingResponse = menu.reading.getIntOr("response", 0);
        }
        ClientPlayNetworking.send(new MachineMenus.Edit(menu.containerId, action, value));
    }

    private Button button(String title, int x, int y, int width, Runnable run)
    {
        return addRenderableWidget(Button.builder(Component.literal(title), b -> run.run()).bounds(leftPos + x, topPos + y, width, 18).build());
    }

    private boolean videoMode()
    {
        return mode.equals("camera") || mode.equals("monitor");
    }

    private void changeMonitorCamera(int step)
    {
        var cameras = rows();
        if (cameras.isEmpty())
            return;
        long source = frame == null ? Long.MIN_VALUE : frame.scene().getLongOr("source", Long.MIN_VALUE);
        int current = -1;
        for (int i = 0; i < cameras.size(); i++)
            if (cameras.get(i).getLongOr("at", 0) == source || Long.toString(cameras.get(i).getLongOr("at", 0)).equals(menu.reading.getStringOr("feed", "")))
                current = i;
        int chosen = current < 0 ? step < 0 ? cameras.size() - 1 : 0 : Math.floorMod(current + step, cameras.size());
        send(7, Long.toString(cameras.get(chosen).getLongOr("at", 0)));
        refreshPreview = true;
    }

    @Override
    protected void init()
    {
        super.init();
        resultButtons.clear();
        mode = menu.reading.getStringOr("mode", "");
        name = addRenderableWidget(new EditBox(font, leftPos + 108, topPos + 16, 134, 18, Component.literal("Name")));
        name.setMaxLength(32);
        name.setValue(menu.reading.getStringOr("name", ""));
        // Every machine names the same way: a Name button that opens the field.
        editingName = false;
        saveName = button("Save name", 246, 16, 70, () -> send(2, name.getValue()));
        editName = button("Name", 108, 16, 134, () ->
        {
            editingName = true;
            updateControls();
            setFocused(name);
        });
        editName.visible = !editingName;
        button("Details", videoMode() ? 80 : 8, videoMode() ? 220 : 184, videoMode() ? 64 : 96, () -> details = !details);
        button("Close", 8, videoMode() ? 220 : 158, videoMode() ? 64 : 96, this::onClose);
        enabledButton = button(menu.reading.getBooleanOr("enabled", true) ? "On" : "Off", 8, 16, 96, () -> send(0, ""));
        lessPower = button("-", videoMode() ? 220 : 8, 220, 24, () -> send(15, "-1"));
        morePower = button("+", videoMode() ? 288 : 80, 220, 24, () -> send(15, "1"));
        if (mode.contains("scanner"))
            button("Open scanner", 8, 44, 120, () -> send(8, ""));
        else if (mode.equals("monitor"))
        {
            previous = button("Previous camera", 64, 160, 90, () -> changeMonitorCamera(-1));
            next = button("Next camera", 166, 160, 90, () -> changeMonitorCamera(1));
            button("View fullscreen", 100, 184, 120, () -> send(6, ""));
        }
        else if (mode.equals("camera"))
            button("View fullscreen", 100, 184, 120, () -> send(6, ""));
        else if (mode.equals("air_supply"))
        {
            button("Show air opening", 8, 126, 108, () -> send(3, ""));
            button("Show leak", 120, 126, 88, () -> send(4, ""));
        }
        else if (mode.equals("engine") || mode.equals("weapon"))
            button(mode.equals("engine") ? "Show exhaust" : "Show muzzle", 8, 126, 120, () -> send(3, ""));
        else if (mode.equals("docking_base"))
        {
            accept = button("Accept", 8, 126, 65, () -> send(10, ""));
            decline = button("Decline", 77, 126, 65, () -> send(11, ""));
            cancel = button("Cancel", 146, 126, 65, () -> send(12, ""));
            undock = button("Undock", 215, 126, 65, () -> send(13, ""));
        }
        if (mode.equals("generator") || mode.equals("battery"))
            restart = button("Restart ship supply", 8, 126, 124, () -> send(14, ""));
        for (var row : resultButtons)
            row.setHeight(14);
        if (returning != null && !mode.isBlank())
        {
            if (returning.level == minecraft.level && returning.block == minecraft.level.getBlockEntity(menu.at) && returning.at.equals(menu.at) && returning.mode.equals(mode))
            {
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
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick)
    {
        boolean wasEditing = editingName;
        boolean handled = super.mouseClicked(event, doubleClick);
        if (!wasEditing && editingName)
            setFocused(name);
        return handled;
    }

    @Override
    public void containerTick()
    {
        super.containerTick();
        ticks++;
        if ((mode.equals("camera") || mode.equals("monitor")) && ticks % 20 == 1)
        {
            if (frame == null || refreshPreview)
            {
                send(9, "");
                refreshPreview = false;
            }
            else
                ClientPlayNetworking.send(new ScenePackets.KeepAlive(frame.lease(), false, frame.scene().getIntOr("selection", 0)));
        }
        if (!mode.equals(menu.reading.getStringOr("mode", "")))
            rebuildWidgets();
        updateControls();
    }

    private void updateControls()
    {
        if (pending && menu.reading.getIntOr("response", 0) > pendingResponse)
            pending = false;
        boolean canConfigure = menu.reading.getBooleanOr("canConfigure", true);
        if (lessPower != null)
        {
            int allocation = menu.reading.getIntOr("allocation", -1);
            lessPower.visible = morePower.visible = allocation >= 0;
            lessPower.active = canConfigure && !pending && allocation > 0 && menu.reading.getBooleanOr("connected", false);
            morePower.active = canConfigure && !pending && allocation < 8 && menu.reading.getBooleanOr("connected", false);
        }
        if (editName != null)
        {
            editName.visible = !editingName;
            String saved = menu.reading.getStringOr("name", "");
            editName.setMessage(Component.literal(font.plainSubstrByWidth("Name: " + (saved.isBlank() ? "-" : saved), 110) + " ✎"));
        }
        if (saveName != null)
        {
            saveName.active = canConfigure && !pending && !name.getValue().equals(menu.reading.getStringOr("name", ""));
            saveName.visible = editingName;
        }
        if (name != null)
        {
            name.active = canConfigure;
            name.visible = editingName;
        }
        if (enabledButton != null)
        {
            enabledButton.active = canConfigure && !pending && menu.reading.getBooleanOr("connected", false);
            enabledButton.setMessage(Component.literal(menu.reading.getBooleanOr("enabled", true) ? "On" : "Off"));
        }
        if (restart != null)
        {
            restart.visible = menu.reading.getBooleanOr("restart", false);
            restart.active = canConfigure && !pending;
        }
        if (mode.equals("docking_base") && accept != null)
        {
            accept.visible = decline.visible = canConfigure && menu.reading.getBooleanOr("incoming", false);
            cancel.visible = canConfigure && menu.reading.getBooleanOr("maneuver", false);
            undock.active = canConfigure && menu.reading.getBooleanOr("clamped", false);
        }
        var entries = rows();
        page = Math.clamp(page, 0, Math.max(0, (entries.size() - 1) / 4));
        if (mode.contains("scanner") && entries.stream().skip(page * 4L).limit(4).noneMatch(r -> selected.equals(r.getStringOr("id", ""))))
            selected = "";
        if (setDestination != null)
            setDestination.active = !pending && !selected.isBlank() && menu.reading.getBooleanOr("selectable", false);
        if (previous != null)
            previous.active = mode.equals("monitor") ? !pending && entries.size() > 1 : page > 0;
        if (next != null)
            next.active = mode.equals("monitor") ? !pending && entries.size() > 1 : (page + 1) * 4 < entries.size();
        for (int i = 0; i < resultButtons.size(); i++)
        {
            int index = page * 4 + i;
            var button = resultButtons.get(i);
            button.active = index < entries.size();
            String label = index < entries.size() ? entries.get(index).getStringOr("name", "") : "No result";
            button.setMessage(Component.literal(((index < entries.size() && (mode.equals("monitor") ? entries.get(index).getLongOr("at", 0) + "" : entries.get(index).getStringOr("id", "")).equals(mode.equals("monitor") ? menu.reading.getStringOr("feed", "") : selected)) ? "> " : "") + label.substring(0, Math.min(18, label.length()))));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta)
    {
        g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, 0xff343434);
        g.fill(leftPos + 4, topPos + 38, leftPos + 316, topPos + (videoMode() ? 216 : 146), 0xff262626);
        g.fill(leftPos + 4, topPos + (videoMode() ? 216 : 150), leftPos + (videoMode() ? 316 : 108), topPos + 240, 0xff292929);
        if (menu.machineHasSlots())
            g.fill(leftPos + 122, topPos + 150, leftPos + 292, topPos + 234, 0xff292929);
        // The upgrade column gets its own panel instead of straddling the readings panel's edge.
        if (java.util.Set.of("generator", "engine", "battery", "local_scanner").contains(mode))
        {
            g.fill(leftPos + 244, topPos + 1, leftPos + 318, topPos + 66, 0xff4a4a4a);
            g.fill(leftPos + 245, topPos + 2, leftPos + 317, topPos + 65, 0xff2c2c2c);
        }
        for (var slot : menu.slots)
            if (slot.isActive())
            {
                g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xff777777);
                g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xff373737);
            }
        g.nextStratum();
        super.extractRenderState(g, mx, my, delta);
        if (mx >= leftPos + 248 && mx < leftPos + 308 && my >= topPos + 32 && my < topPos + 52 && !menu.reading.getStringOr("upgradeBenefit", "").isBlank())
        {
            g.setComponentTooltipForNextFrame(font, java.util.List.of(Component.literal("System Upgrades: " + menu.reading.getIntOr("tier", 0) + "/3"), Component.literal("Now: " + menu.reading.getStringOr("upgradeBenefit", "")), Component.literal("Next: " + menu.reading.getStringOr("nextUpgradeBenefit", ""))), mx, my);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my)
    {
        g.text(font, title, 8, 4, 0xffe0e0e0, false);
        var data = menu.reading;
        int allocation = data.getIntOr("allocation", -1);
        if (allocation >= 0)
        {
            text(g, "Power", videoMode() ? 220 : 8, 208, 92);
            text(g, allocation + " / 8", videoMode() ? 250 : 38, 224, 36);
        }
        if (java.util.Set.of("generator", "engine", "battery", "local_scanner").contains(mode))
        {
            text(g, "Upgrades", 250, 4, 66);
            text(g, data.getIntOr("tier", 0) + "/3", 250, 16, 66);
        }
        String status = pending ? "Waiting for server..." : data.getStringOr("status", "Getting machine status...");
        if (videoMode() && frame == null && !pending)
            status = "Loading camera...";
        if (frame != null && videoMode() && !pending)
            status = frame.scene().getStringOr("status", status);
        if (videoMode())
            text(g, status.replace('\n', ' '), 8, 208, mode.equals("camera") ? 204 : 304);
        else
        {
            int sy = 68;
            for (var line : font.split(Component.literal(status.replace('\n', ' ')), 176))
            {
                if (sy > 92)
                    break;
                g.text(font, line, 140, sy, 0xffeeeecc, false);
                sy += 9;
            }
        }
        if (java.util.Set.of("generator", "engine", "battery", "local_scanner").contains(mode))
        {
            String benefit = switch (mode)
            {
                case "generator" -> "Max " + data.getIntOr("outputMax", 8) + "/tick";
                case "engine" -> "Thrust " + data.getIntOr("thrustMax", 2);
                case "battery" -> "Holds " + data.getIntOr("capacity", 1200);
                default -> "Range " + data.getIntOr("range", 1024);
            };
            text(g, benefit, 250, 54, 66);
        }
        if (mode.equals("generator"))
        {
            int generated = data.getIntOr("output", 0), demand = data.getIntOr("powerRequested", 0);
            int scale = Math.max(1, Math.max(data.getIntOr("powerCapacity", 0), demand));
            gauge(g, "Generating", generated, scale, 8, 44, 124, generated >= demand ? 0xff69b75e : 0xffdbad55);
            gauge(g, "Ship demand", demand, scale, 8, 66, 124, 0xffce9656);
            gauge(g, "To batteries", data.getIntOr("charging", 0), scale, 8, 88, 124, 0xff76a8cc);
            text(g, "Any furnace fuel", 8, 112, 124);
            // The meters already show a working generator; only call out the problem.
            if (data.getIntOr("fuelHere", 0) == 0)
                g.text(font, "No fuel stored", 140, 48, 0xffdbad55, false);
            text(g, "Fuel", 140, 94, 112);
            text(g, "Empty", 260, 84, 56);
            text(g, "containers", 260, 94, 56);
        }
        else if (mode.equals("battery"))
        {
            int energy = data.getIntOr("energy", 0), cap = Math.max(1, data.getIntOr("capacity", 1200));
            if (data.getBooleanOr("connected", false))
                bar(g, "Power stored", energy, cap);
            else
                text(g, "Charge: not connected", 140, 108, 176);
            if (data.getBooleanOr("removalBlocked", false))
            {
                text(g, "Cannot remove upgrade", 8, 48, 124);
                text(g, "Discharge below " + (1200 * Math.max(1, data.getIntOr("tier", 0))), 8, 60, 124);
            }
        }
        else if (mode.equals("engine") || mode.equals("air_supply"))
        {
            boolean engine = mode.equals("engine");
            if (data.getBooleanOr("connected", false))
                bar(g, engine ? "Thrust" : "Room air", data.getIntOr(engine ? "thrust" : "oxygen", 0), engine ? data.getIntOr("thrustMax", 2) : 100);
            if (engine)
            {
                int y = 46;
                for (var line : font.split(Component.literal("Uses fuel stored in generators."), 124))
                {
                    g.text(font, line, 8, y, 0xffdddddd, false);
                    y += 10;
                }
            }
            else
            {
                // The Room air bar shows the level; do not repeat it as text.
                // Floors joined by an open stairwell are one room, so one vent fills them all.
                int floors = data.getIntOr("roomFloors", 0);
                text(g, "Room: " + data.getIntOr("roomCells", 0) + " blocks" + (floors > 1 ? " on " + floors + " levels" : ""), 8, 46, 124);
                text(g, "Leaks: " + data.getIntOr("leaks", 0), 8, 60, 124);
                // What this pump gives and whether the room's pumps keep up with its leaks.
                text(g, "This pump: " + data.getIntOr("supplyOutput", 0) + "% output", 8, 74, 124);
                int covered = data.getIntOr("leakCovered", -1);
                if (covered >= 0)
                    g.text(font, font.plainSubstrByWidth(covered >= 100 ? "Pumps hold the leak" : "Leak covered: " + covered + "%", 124), 8, 88, covered >= 100 ? 0xff8fd18a : 0xffdbad55, false);
            }
        }
        if (mode.equals("camera") || mode.equals("monitor"))
        {
            g.fill(63, 47, 257, 157, 0xff777777);
            g.fill(64, 48, 256, 156, 0xff080b10);
            if (viewport != null && viewport.target != null)
            {
                var sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getSampler(com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.renderpearl.api.textures.FilterMode.LINEAR, com.mojang.renderpearl.api.textures.FilterMode.LINEAR, false);
                g.blit(viewport.target.getColorTextureView(), sampler, 64, 48, 256, 156, 0, 1, 1, 0);
            }
            text(g, mode.equals("camera") ? "Facing: " + data.getStringOr("facing", "") : "Wall: " + data.getIntOr("tiles", 1) + " tiles", 8, 36, 304);
        }
        if (mode.equals("docking_base"))
        {
            text(g, "Connected to: " + data.getStringOr("partner", "None"), 8, 48, 304);
            text(g, data.getBooleanOr("clamped", false) ? "Docked: open the door and walk through" : "Dock at a port using your Ship Controller", 8, 108, 304);
        }
        if (mode.contains("scanner"))
        {
            int y = 108;
            for (var line : font.split(Component.literal("Browse destinations with Open scanner."), 176))
            {
                g.text(font, line, 140, y, 0xffdddddd, false);
                y += 9;
            }
        }
        if (details && !mode.contains("scanner"))
        {
            String values = mode.equals("generator") ? "Fuel aboard: " + FuelSystem.display(data.getIntOr("fuel", 0)) + " | Unit limit: " + data.getIntOr("outputMax", 8) + "/tick" : mode.equals("battery") ? "Charge/discharge limit: " + data.getIntOr("rate", 4) + "/tick" : "Fuel aboard: " + FuelSystem.display(data.getIntOr("fuel", 0)) + " | " + data.getStringOr("facing", "");
            text(g, values, 8, 36, 232);
        }
        String actionNotice = data.getStringOr("notice", "");
        if (!actionNotice.isBlank())
            text(g, actionNotice, 8, 145, 304);
        // Full status is available without clipping, including slot-removal and permission failures.
        if (videoMode() ? mx >= leftPos + 8 && mx < leftPos + 212 && my >= topPos + 202 && my < topPos + 216 : mx >= leftPos + 140 && mx < leftPos + 316 && my >= topPos + 64 && my < topPos + 99)
            g.setComponentTooltipForNextFrame(font, java.util.List.of(Component.literal(status)), mx, my);
    }

    public int panelWidth()
    {
        return imageWidth;
    }

    public int panelHeight()
    {
        return imageHeight;
    }

    private void text(GuiGraphicsExtractor g, String text, int x, int y, int width)
    {
        g.text(font, font.plainSubstrByWidth(text, width), x, y, 0xffdddddd, false);
    }

    private void bar(GuiGraphicsExtractor g, String label, int current, int max)
    {
        max = Math.max(1, max);
        g.fill(140, 106, 312, 118, 0xff303030);
        int color = mode.equals("air_supply") && current < 25 ? 0xffc4544c : mode.equals("air_supply") && current < 60 ? 0xffcba64b : 0xff5ca54d;
        g.fill(140, 106, 140 + (int) (172L * Math.clamp(current, 0, max) / max), 118, color);
        // The label sits above the bar, so the breathing-threshold tick never cuts through it.
        if (mode.equals("air_supply"))
            g.fill(183, 104, 184, 120, 0xffeeeeee);
        text(g, label + ": " + Math.clamp(current, 0, max) + (mode.equals("air_supply") ? "/" + max + ", need 25" : " / " + max), 140, 96, 172);
    }

    private void gauge(GuiGraphicsExtractor g, String label, int value, int max, int x, int y, int width, int color)
    {
        text(g, label + ": " + value, x, y, width);
        g.fill(x, y + 11, x + width, y + 15, 0xff454545);
        g.fill(x, y + 11, x + (int) ((long) width * Math.clamp(value, 0, Math.max(1, max)) / Math.max(1, max)), y + 15, color);
    }
}
