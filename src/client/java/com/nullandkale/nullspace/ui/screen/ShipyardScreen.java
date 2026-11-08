package com.nullandkale.nullspace.ui.screen;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.ui.terminal.ComputerTerminal;
import com.nullandkale.nullspace.ui.terminal.TerminalPackets;
import com.nullandkale.nullspace.worldgen.ships.ShipBlueprints;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Select a design, inspect its specification/footprint and deliver it on the same screen. */
public final class ShipyardScreen extends Screen
{
    private TerminalPackets.Open state;
    private String selected;
    private String notice = "";
    private int readiness;
    private boolean pending;
    private Button deliver;
    private int page;
    private String planId = "";
    private java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> plan = java.util.Map.of();

    public ShipyardScreen(TerminalPackets.Open state)
    {
        super(Component.literal("Ship Beacon"));
        this.state = state;
        selected = state.value();
        for (int i = 0; i < ShipBlueprints.PRESETS.size(); i++)
            if (ShipBlueprints.PRESETS.get(i).id().equals(selected))
                page = i / 6;
        update(state);
    }

    public void update(TerminalPackets.Open state)
    {
        this.state = state;
        pending = false;
        notice = state.message().startsWith("DELIVERED") ? state.message().lines().findFirst().orElse("") : "";
        if (!state.value().isBlank())
            selected = state.value();
        readiness = state.destinations().stream().filter(d -> d.id().equals(selected)).mapToInt(d -> d.state().matches("[012]") ? Integer.parseInt(d.state()) : 0).findFirst().orElse(0);
        updateButton();
    }

    private void updateButton()
    {
        if (deliver != null)
        {
            deliver.active = readiness > 0 && !pending;
            deliver.setMessage(Component.literal(readiness == 2 ? "Resume delivery" : "Deliver ship"));
        }
    }

    public void update(TerminalPackets.Update next)
    {
        if (!state.session().equals(next.session()))
            return;
        state = new TerminalPackets.Open(state.position(), (notice.isEmpty() ? "" : notice + "\n") + next.message(), state.mode(), state.value(), state.session(), state.destinations());
        readiness = next.revision();
        updateButton();
    }

    public void rejected(TerminalPackets.Rejected next)
    {
        if (!state.session().equals(next.session()))
            return;
        pending = false;
        notice = next.reason();
        state = new TerminalPackets.Open(state.position(), next.reason(), state.mode(), state.value(), state.session(), state.destinations());
    }

    public String status()
    {
        return state.message();
    }

    public String selected()
    {
        return selected;
    }

    public int page()
    {
        return page;
    }

    public boolean ready()
    {
        return readiness > 0;
    }

    private void send(ComputerTerminal.Action action)
    {
        if (pending)
            return;
        pending = true;
        updateButton();
        ClientPlayNetworking.send(new TerminalPackets.Request(state.session(), action, selected, ""));
    }

    @Override
    protected void init()
    {
        int x = (width - 360) / 2, y = (height - 220) / 2;
        for (int i = page * 6; i < Math.min(ShipBlueprints.PRESETS.size(), page * 6 + 6); i++)
        {
            var preset = ShipBlueprints.PRESETS.get(i);
            addRenderableWidget(Button.builder(Component.literal(preset.name()), button ->
            {
                selected = preset.id();
                notice = "";
                readiness = 0;
                updateButton();
                send(ComputerTerminal.Action.SHIP_QUOTE);
            }).bounds(x + 8, y + 24 + (i % 6) * 23, 112, 20).build());
        }
        if (ShipBlueprints.PRESETS.size() > 6)
        {
            addRenderableWidget(Button.builder(Component.literal("<"), b ->
            {
                page = Math.max(0, page - 1);
                rebuildWidgets();
            }).bounds(x + 8, y + 168, 32, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b ->
            {
                page = Math.min((ShipBlueprints.PRESETS.size() - 1) / 6, page + 1);
                rebuildWidgets();
            }).bounds(x + 88, y + 168, 32, 20).build());
        }
        deliver = addRenderableWidget(Button.builder(Component.literal("Deliver ship"), button -> send(ComputerTerminal.Action.SHIP_BUY)).bounds(x + 136, y + 184, 144, 20).build());
        updateButton();
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose()).bounds(x + 288, y + 184, 64, 20).build());
    }

    @Override
    public void removed()
    {
        if (minecraft.getConnection() != null)
            ClientPlayNetworking.send(new TerminalPackets.Close(state.session()));
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta)
    {
        int x = (width - 360) / 2, y = (height - 220) / 2;
        g.fill(x, y, x + 360, y + 220, 0xff101a2a);
        g.text(font, "SHIP BEACON | FREE DELIVERY", x + 8, y + 8, 0xffb0ddff, false);
        if (ShipBlueprints.PRESETS.size() > 6)
            g.text(font, (page + 1) + "/" + ((ShipBlueprints.PRESETS.size() + 5) / 6), x + 51, y + 174, 0xffb0ddff, false);
        var preset = ShipBlueprints.PRESETS.stream().filter(p -> p.id().equals(selected)).findFirst().orElse(ShipBlueprints.PRESETS.getFirst());
        g.text(font, preset.name() + " | " + preset.size() + " | " + preset.decks() + " decks", x + 136, y + 24, 0xffe1edff, false);
        // Top-down hull footprint; colors distinguish engines, controls and docking machinery.
        g.text(font, preset.manufacturer() + " | " + preset.role(), x + 136, y + 36, 0xffb0ddff, false);
        if (!planId.equals(preset.id()))
        {
            planId = preset.id();
            plan = ShipBlueprints.blocks(preset);
        }
        for (var entry : plan.entrySet())
        {
            if (entry.getKey().getY() != 1)
                continue;
            int color = 0xff526b82;
            if (entry.getValue().is(ModBlocks.ENGINE))
                color = 0xffe0a05c;
            if (entry.getValue().is(ModBlocks.DOCKING_BASE))
                color = 0xff77dcbd;
            int bx = x + 140 + entry.getKey().getX() * 2, bz = y + 44 + entry.getKey().getZ() * 2;
            g.fill(bx, bz, bx + 2, bz + 2, color);
        }
        g.text(font, "Closing keeps delivery running", x + 8, y + 208, 0xffb0b0b0, false);
        int row = y + 108;
        for (var line : font.split(Component.literal(state.message()), 212))
        {
            if (row > y + 178)
                break;
            g.text(font, line, x + 136, row, 0xffdce8f5, false);
            row += 9;
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
