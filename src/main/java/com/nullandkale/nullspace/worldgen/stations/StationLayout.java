package com.nullandkale.nullspace.worldgen.stations;

import com.nullandkale.nullspace.gameplay.station.StationSpace;
import com.nullandkale.nullspace.worldgen.shipgen.StationGenerator;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.Rotation;

/** Bounded room grammar. A room piece is smaller than the station's simulation section. */
public final class StationLayout
{
    public static final int TILE = 16, SIZE = 32;

    /** What a tile's piece is for. The first four are the furnished rooms. */
    public enum Role
    {
        ARRIVAL, WORKSHOP, HABITATION, ENGINEERING, CORRIDOR, GARDEN, OBSERVATORY
    }

    /**
     * One tile of a station: a piece from {@link StationPieces} turned {@code rotation} quarter turns
     * clockwise, at tile {@code x, z}.
     */
    public record Room(String id, String piece, int rotation, int x, int z)
    {
        public Room
        {
            StationPieces.get(piece);
            rotation = Math.floorMod(rotation, 4);
        }

        /** The plain furnished room for a role. */
        public Room(String id, Role role, int x, int z)
        {
            this(id, StationPieces.forRole(role).id(), 0, x, z);
        }

        public Role role()
        {
            return StationPieces.get(piece).role();
        }

        public Rotation turn()
        {
            return switch (rotation)
            {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        }

        /** The sides with a doorway, after turning. */
        public Set<Direction> doors()
        {
            var turned = EnumSet.noneOf(Direction.class);
            for (var door : StationPieces.get(piece).doors())
                turned.add(turn().rotate(door));
            return turned;
        }

        public BlockPos origin()
        {
            return new BlockPos(x * TILE, 0, z * TILE);
        }

        /** The saved form in {@code roomPieces}: the piece id, plus {@code @n} when turned. */
        public String encode()
        {
            return rotation == 0 ? piece : piece + "@" + rotation;
        }

        /** Reads a saved tile ({@code "x_z"} to {@link #encode()}); old saves store a role name. */
        public static Room decode(String key, String value)
        {
            var coords = key.split("_");
            int x = Integer.parseInt(coords[0]), z = Integer.parseInt(coords[1]);
            if (Arrays.stream(Role.values()).anyMatch(r -> r.name().equals(value)))
                return new Room(key, Role.valueOf(value), x, z);
            int at = value.indexOf('@');
            return at < 0 ? new Room(key, value, 0, x, z) : new Room(key, value.substring(0, at), Integer.parseInt(value.substring(at + 1)), x, z);
        }
    }

    /** True if {@code a} and {@code b} are neighbours with doorways facing each other. */
    public static boolean joined(Room a, Room b)
    {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1 && a.doors().contains(direction(a, b)) && b.doors().contains(direction(b, a));
    }

    /**
     * Places a piece by doorway: on tile {@code x, z} next to the existing rooms, turned so at least
     * one of its doorways meets an open doorway of a neighbour. With {@code rotation} null, the first
     * turn that connects is used. The first piece of a station goes on tile 0,0 unturned unless asked.
     */
    public static Room place(List<Room> existing, String piece, int x, int z, Integer rotation)
    {
        StationPieces.get(piece);
        if (x < 0 || z < 0 || x >= StationSpace.TILES || z >= StationSpace.TILES)
            throw new IllegalArgumentException("Room tiles run from 0 to " + (StationSpace.TILES - 1));
        if (existing.stream().anyMatch(r -> r.x() == x && r.z() == z))
            throw new IllegalArgumentException("That room tile is already occupied");
        if (existing.isEmpty())
        {
            if (x != 0 || z != 0)
                throw new IllegalArgumentException("Build the first tile at 0,0, then expand through its doorways");
            return new Room("piece", piece, rotation == null ? 0 : rotation, x, z);
        }
        if (existing.stream().noneMatch(r -> Math.abs(r.x() - x) + Math.abs(r.z() - z) == 1))
            throw new IllegalArgumentException("Choose a room tile adjoining an existing room");
        for (int turn = 0; turn < 4; turn++)
        {
            if (rotation != null && Math.floorMod(rotation, 4) != turn)
                continue;
            var room = new Room("piece", piece, turn, x, z);
            if (existing.stream().anyMatch(r -> joined(r, room)))
                return room;
        }
        throw new IllegalArgumentException(StationPieces.get(piece).name() + (rotation == null ? "" : " turned that way") + " has no doorway meeting an open doorway next to this tile");
    }

    /** The {@code roomPieces} tag for a layout's rooms. */
    public static net.minecraft.nbt.CompoundTag pieces(Layout layout)
    {
        var tag = new net.minecraft.nbt.CompoundTag();
        for (var room : layout.rooms())
            tag.putString(room.x() + "_" + room.z(), room.encode());
        return tag;
    }

    public record Joint(String a, String b)
    {}

    public record Layout(List<Room> rooms, List<Joint> joints)
    {
        public Layout
        {
            rooms = List.copyOf(rooms);
            joints = List.copyOf(joints);
            var ids = new HashMap<String, Room>();
            var cells = new HashSet<Integer>();
            for (var room : rooms)
            {
                if (room.id().isBlank() || ids.put(room.id(), room) != null || room.x() < 0 || room.x() >= StationSpace.TILES || room.z() < 0 || room.z() >= StationSpace.TILES || !cells.add(room.x() + room.z() * StationSpace.TILES))
                    throw new IllegalArgumentException("Invalid or overlapping station room");
            }
            if (rooms.isEmpty() || rooms.size() > StationSpace.TILES * StationSpace.TILES)
                throw new IllegalArgumentException("Station needs 1..64 room pieces");
            var edges = new HashSet<String>();
            for (var edge : joints)
            {
                var a = ids.get(edge.a());
                var b = ids.get(edge.b());
                if (a == null || b == null || Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) != 1 || !edges.add(a.id().compareTo(b.id()) < 0 ? a.id() + ":" + b.id() : b.id() + ":" + a.id()))
                    throw new IllegalArgumentException("Invalid station joint");
                if (!a.doors().contains(direction(a, b)) || !b.doors().contains(direction(b, a)))
                    throw new IllegalArgumentException("Station joint " + a.id() + "-" + b.id() + " needs a doorway on both sides");
            }
            var reached = new HashSet<String>();
            reached.add(rooms.getFirst().id());
            for (int n = 0; n < rooms.size(); n++)
                for (var e : joints)
                {
                    if (reached.contains(e.a()))
                        reached.add(e.b());
                    if (reached.contains(e.b()))
                        reached.add(e.a());
                }
            if (reached.size() != rooms.size())
                throw new IllegalArgumentException("Station graph is disconnected");
        }

        public Room room(String id)
        {
            return rooms.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
        }
    }

    public static Layout waypoint()
    {
        return new Layout(List.of(new Room("arrival", Role.ARRIVAL, 0, 0), new Room("workshop", Role.WORKSHOP, 1, 0), new Room("habitation", Role.HABITATION, 0, 1), new Room("engineering", Role.ENGINEERING, 1, 1)), List.of(new Joint("arrival", "workshop"), new Joint("arrival", "habitation"), new Joint("workshop", "engineering"), new Joint("habitation", "engineering")));
    }

    /**
     * Bounded generation varies room roles and a redundant connection, never retries indefinitely.
     */
    public static Layout generate(long seed)
    {
        var random = new Random(seed);
        var roles = new ArrayList<>(List.of(Role.WORKSHOP, Role.HABITATION, Role.ENGINEERING));
        Collections.shuffle(roles, random);
        var rooms = new ArrayList<Room>();
        // Each room is a few-windows or a many-windows version, by the seed.
        rooms.add(new Room("room0", windows(Role.ARRIVAL, random), 0, 0, 0));
        rooms.add(new Room("room1", windows(roles.get(0), random), 0, 1, 0));
        rooms.add(new Room("room2", windows(roles.get(1), random), 0, 0, 1));
        rooms.add(new Room("room3", windows(roles.get(2), random), 0, 1, 1));
        var edges = new ArrayList<>(List.of(new Joint("room0", "room1"), new Joint("room0", "room2"), new Joint("room1", "room3"), new Joint("room2", "room3")));
        if (random.nextBoolean())
            edges.remove(random.nextInt(edges.size()));
        return new Layout(rooms, edges);
    }

    private static String windows(Role role, Random random)
    {
        var plain = StationPieces.forRole(role).id();
        return random.nextBoolean() ? plain + "_bright" : plain;
    }

    /**
     * How many closed loops the rooms' graph holds: its joints minus rooms plus parts. A station
     * may hold one ring, such as the harbor's perimeter, and no more.
     */
    public static int loops(Layout layout)
    {
        var parent = new HashMap<String, String>();
        for (var room : layout.rooms())
            parent.put(room.id(), room.id());
        int loops = 0;
        for (var joint : layout.joints())
        {
            var a = root(parent, joint.a());
            var b = root(parent, joint.b());
            if (a.equals(b))
                loops++;
            else
                parent.put(a, b);
        }
        return loops;
    }

    private static String root(Map<String, String> parent, String id)
    {
        while (!parent.get(id).equals(id))
            id = parent.get(id);
        return id;
    }

    /** Eight widely separated berths on a walkable 128-block perimeter. */
    public static Layout harbor()
    {
        var rooms = new ArrayList<Room>();
        for (int z = 0; z < 8; z++)
            for (int x = 0; x < 8; x++)
            {
                if (x != 0 && x != 7 && z != 0 && z != 7)
                    continue;
                var role = x == 0 && z == 0 ? Role.ARRIVAL : (x + z) % 4 == 0 ? Role.ENGINEERING : (x + z) % 2 == 0 ? Role.WORKSHOP : Role.HABITATION;
                rooms.add(new Room(x + "_" + z, role, x, z));
            }
        var edges = new ArrayList<Joint>();
        for (var a : rooms)
            for (var b : rooms)
                if (a.id().compareTo(b.id()) < 0 && Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1)
                    edges.add(new Joint(a.id(), b.id()));
        return new Layout(rooms, edges);
    }

    public static Direction direction(Room a, Room b)
    {
        if (a.x() < b.x())
            return Direction.EAST;
        if (a.x() > b.x())
            return Direction.WEST;
        return a.z() < b.z() ? Direction.SOUTH : Direction.NORTH;
    }

    /** The first doorway on a side of a room: slot 0, on the lower story. Berths and the old single-door code use this one. */
    public static BlockPos doorway(Room room, Direction face)
    {
        return room.origin().offset(StationGenerator.doorway(face, 0, StationGenerator.DOOR_ROWS[0]));
    }

    /** Every doorway on a side of a room: two slots on each of the two stories, where a neighbour's door is cut. */
    public static List<BlockPos> doorways(Room room, Direction face)
    {
        var out = new ArrayList<BlockPos>();
        for (int story = 0; story < 2; story++)
            for (int slot = 0; slot < StationGenerator.SLOTS.length; slot++)
                out.add(room.origin().offset(StationGenerator.doorway(face, slot, StationGenerator.DOOR_ROWS[story])));
        return out;
    }

    private StationLayout()
    {
    }
}
