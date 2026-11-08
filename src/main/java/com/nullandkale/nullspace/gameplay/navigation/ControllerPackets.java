package com.nullandkale.nullspace.gameplay.navigation;

import com.nullandkale.nullspace.gameplay.navigation.ShipControl.Action;

import java.util.*;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What the Ship Controller screen sends and receives: the view, its entries and buttons, and player
 * input.
 */
public final class ControllerPackets
{

    public record Entry(String id, ShipDestination.Kind kind, String name, String region, String state)
    {}

    /**
     * One place on the star map (P9). {@code x}/{@code y} are chart thousandths; {@code zone} is the
     * destination to select for it; fuel and range are from the ship's current place.
     */
    public record ChartPlace(String id, String name, String kind, int x, int y, String zone, double lightYears, double fuel, boolean inRange, boolean here, String summary, String route)
    {}

    public record Button(Action action, String reason)
    {
        public boolean enabled()
        {
            return reason.isEmpty();
        }
    }

    public record Input(UUID session, Action action, String target) implements CustomPacketPayload
    {

        public static final Type<Input> TYPE = new Type<>(Identifier.parse("nullspace:ship_control_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeEnum(p.action);
            b.writeUtf(p.target, 36);
        }, b -> new Input(b.readUUID(), b.readEnum(Action.class), b.readUtf(36)));

        @Override
        public Type<Input> type()
        {
            return TYPE;
        }
    }

    public record View(UUID session, BlockPos position, boolean scanner, boolean opening, String ship, String summary, String selected, String destination, String location, String message, List<Entry> entries, List<Button> buttons, int response, String here, String connection, String details, List<ChartPlace> chart, double range) implements CustomPacketPayload
    {
        public View(UUID session, BlockPos position, boolean scanner, boolean opening, String ship, String summary, String selected, String destination, String location, String message, List<Entry> entries, List<Button> buttons, int response, String here, String connection, String details)
        {
            this(session, position, scanner, opening, ship, summary, selected, destination, location, message, entries, buttons, response, here, connection, details, List.of(), 0);
        }

        public View(UUID session, BlockPos position, boolean scanner, boolean opening, String ship, String summary, String selected, String destination, String location, String message, List<Entry> entries, List<Button> buttons)
        {
            this(session, position, scanner, opening, ship, summary, selected, destination, location, message, entries, buttons, 0, "", "", "");
        }

        public View(UUID session, BlockPos position, boolean scanner, boolean opening, String ship, String summary, String selected, String destination, String location, String message, List<Entry> entries, List<Button> buttons, int response)
        {
            this(session, position, scanner, opening, ship, summary, selected, destination, location, message, entries, buttons, response, "", "", "");
        }

        public static final Type<View> TYPE = new Type<>(Identifier.parse("nullspace:ship_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((b, p) ->
        {
            b.writeUUID(p.session);
            b.writeInt(p.response);
            b.writeUtf(p.here, 160);
            b.writeUtf(p.connection, 160);
            b.writeUtf(p.details, 2048);
            b.writeBlockPos(p.position);
            b.writeBoolean(p.scanner);
            b.writeBoolean(p.opening);
            b.writeUtf(p.ship, 160);
            b.writeUtf(p.summary, 160);
            b.writeUtf(p.selected, 36);
            b.writeUtf(p.destination, 160);
            b.writeUtf(p.location, 160);
            b.writeUtf(p.message, 2048);
            b.writeVarInt(p.entries.size());
            for (var e : p.entries)
            {
                b.writeUtf(e.id, 36);
                b.writeEnum(e.kind);
                b.writeUtf(e.name, 160);
                b.writeUtf(e.region, 32);
                b.writeUtf(e.state, 32);
            }
            b.writeVarInt(p.buttons.size());
            for (var a : p.buttons)
            {
                b.writeEnum(a.action);
                b.writeUtf(a.reason, 256);
            }
            b.writeDouble(p.range);
            b.writeVarInt(p.chart.size());
            for (var c : p.chart)
            {
                b.writeUtf(c.id, 32);
                b.writeUtf(c.name, 64);
                b.writeUtf(c.kind, 32);
                b.writeVarInt(c.x);
                b.writeVarInt(c.y);
                b.writeUtf(c.zone, 32);
                b.writeDouble(c.lightYears);
                b.writeDouble(c.fuel);
                b.writeBoolean(c.inRange);
                b.writeBoolean(c.here);
                b.writeUtf(c.summary, 160);
                b.writeUtf(c.route, 160);
            }
        }, b ->
        {
            var session = b.readUUID();
            int response = b.readInt();
            String here = b.readUtf(160), connection = b.readUtf(160), details = b.readUtf(2048);
            var position = b.readBlockPos();
            boolean scanner = b.readBoolean(), opening = b.readBoolean();
            String ship = b.readUtf(160), summary = b.readUtf(160), selected = b.readUtf(36), destination = b.readUtf(160), location = b.readUtf(160), message = b.readUtf(2048);
            int count = b.readVarInt();
            if (count < 0 || count > 512)
                throw new IllegalArgumentException("Invalid destination count");
            var entries = new ArrayList<Entry>();
            for (int i = 0; i < count; i++)
                entries.add(new Entry(b.readUtf(36), b.readEnum(ShipDestination.Kind.class), b.readUtf(160), b.readUtf(32), b.readUtf(32)));
            count = b.readVarInt();
            if (count < 0 || count > Action.values().length)
                throw new IllegalArgumentException("Invalid action count");
            var buttons = new ArrayList<Button>();
            for (int i = 0; i < count; i++)
                buttons.add(new Button(b.readEnum(Action.class), b.readUtf(256)));
            double range = b.readDouble();
            count = b.readVarInt();
            if (count < 0 || count > 64)
                throw new IllegalArgumentException("Invalid chart");
            var chart = new ArrayList<ChartPlace>();
            for (int i = 0; i < count; i++)
                chart.add(new ChartPlace(b.readUtf(32), b.readUtf(64), b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readUtf(32), b.readDouble(), b.readDouble(), b.readBoolean(), b.readBoolean(), b.readUtf(160), b.readUtf(160)));
            return new View(session, position, scanner, opening, ship, summary, selected, destination, location, message, List.copyOf(entries), List.copyOf(buttons), response, here, connection, details, List.copyOf(chart), range);
        });

        @Override
        public Type<View> type()
        {
            return TYPE;
        }
    }

    private ControllerPackets()
    {
    }
}
