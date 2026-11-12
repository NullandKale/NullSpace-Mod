package com.nullandkale.nullspace.worldgen.shipgen;

import java.util.*;

import net.minecraft.core.*;

/**
 * Builds a whole ship from a {@link Spec}: lay out rooms, wall them, fill from the rooms outward
 * with cheap hull so no pocket of air is left that nobody walks to, open the doorways, punch the
 * windows, furnish each room and fit the docking door. Pure geometry: it never touches a block registry.
 */
public final class ShipGenerator
{
    /** A walking spot and container in a room, for supplies and walking checks. */
    public record Landmark(String name, RoomUse use, BlockPos walk, BlockPos supplies)
    {}

    /** A generated ship, shifted so its lowest corner is the origin. {@code interior} is every cell a person can occupy. */
    public record Ship(Spec spec, Map<BlockPos, Voxel> voxels, Set<BlockPos> interior, int width, int height, int depth, int decks, BlockPos helm, List<Landmark> rooms, BlockPos dock)
    {
        public Kind kind(BlockPos at)
        {
            var v = voxels.get(at);
            return v == null ? null : v.kind();
        }
    }

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public static Ship build(Spec spec)
    {
        return assemble(spec, Shapes.layout(spec), true);
    }

    /**
     * One room on its own, for auditing: furnished as it would be on a ship of {@code tier}, behind a
     * short corridor with an open doorway to the outside. Rooms that do not fit the tier return null.
     */
    public static Ship room(RoomUse use, Spec.Tier tier, int windows)
    {
        int w = tier.length, d = tier.cell, h = tier.height;
        if (!Program.fits(use, w, d))
            return null;
        var layout = new Layout();
        int cx = 20, floorY = 1, row0 = floorY + 1, minZ = 12;
        layout.cx = cx;
        layout.add(Space.box("corridor", RoomUse.CORRIDOR, 0, floorY, h, cx - 2, cx + 2, minZ - 5, minZ - 2, Direction.SOUTH, 0));
        layout.add(Space.box("room", use, 0, floorY, h, cx - w / 2, cx + w / 2, minZ, minZ + d - 1, Direction.SOUTH, windows));
        layout.links.add(new Link(new BlockPos(cx, row0, minZ - 1), Direction.SOUTH, 3, Math.min(3, h)));
        layout.links.add(new Link(new BlockPos(cx, row0, minZ - 6), Direction.NORTH, 3, Math.min(3, h)));
        layout.dockWall = new BlockPos(cx, row0, minZ - 6);
        return assemble(new Spec(Spec.Maker.RIVET, Spec.Shape.SPINE, tier, Spec.Role.SCOUT, 0), layout, false);
    }

    static Ship assemble(Spec spec, Layout layout, boolean whole)
    {
        var interior = new HashSet<BlockPos>(layout.extra);
        for (var space : layout.spaces)
            interior.addAll(space.cells());
        for (var link : layout.links)
            interior.addAll(openingCells(link));

        // Walls: every cell touching the interior that is not interior itself.
        var walls = new HashSet<BlockPos>();
        for (var at : interior)
            for (var d : Direction.values())
            {
                var n = at.relative(d);
                if (!interior.contains(n))
                    walls.add(n);
            }
        var solids = new HashSet<BlockPos>();
        for (var box : layout.solids)
            for (int x = box.x0(); x <= box.x1(); x++)
                for (int y = box.y0(); y <= box.y1(); y++)
                    for (int z = box.z0(); z <= box.z1(); z++)
                        solids.add(new BlockPos(x, y, z));

        var core = new HashSet<BlockPos>(interior);
        core.addAll(walls);
        core.addAll(solids);
        var envelope = envelope(core, layout.hullH, layout.hullV);

        var grid = new Grid();
        for (var at : envelope)
        {
            if (interior.contains(at))
                continue;
            if (walls.contains(at))
                grid.set(at, interior.contains(at.above()) ? Kind.FLOOR : interior.contains(at.below()) ? Kind.CEILING : Kind.WALL);
            else
            {
                boolean exposed = false;
                for (var d : Direction.values())
                    exposed |= !envelope.contains(at.relative(d));
                grid.set(at, exposed ? Kind.HULL : Kind.FRAME);
            }
        }
        for (var link : layout.links)
            frame(grid, link);
        windows(layout, grid, envelope, interior);
        for (var flight : layout.flights)
            for (int k = 0; k < flight.steps(); k++)
            {
                var step = flight.foot().relative(flight.up(), k).above(k);
                grid.set(step, Kind.STAIR, flight.up());
                // A one-block stringer joins the diagonal risers without closing the headroom
                // over the flight below. Station tiles retain their filled stair foundations.
                if (layout.station != null)
                    for (int under = flight.foot().getY(); under < step.getY(); under++)
                        grid.set(new BlockPos(step.getX(), under, step.getZ()), Kind.WALL);
                else if (k > 0)
                    grid.set(step.below(), Kind.WALL);
            }
        for (var space : layout.spaces)
            if (layout.station != null)
                StationFurnishings.furnish(layout.station, space, grid, layout.linksOf(space));
            else
                Furnishings.furnish(space, grid, layout.linksOf(space), space == layout.dockSpace, layout.flights);
        for (var link : layout.links)
            if (!link.at().equals(layout.dockWall))
                door(grid, link);
        var dock = whole ? dock(layout, grid, envelope) : null;
        if (whole)
        {
            exteriors(grid, envelope, layout);
            exhausts(grid, envelope);
        }
        else
            // The doorway to the outside goes through the hull layer too.
            for (var link : layout.links)
                if (link.across() == Direction.NORTH)
                    for (int depth = 1; depth <= 2; depth++)
                        for (var cell : openingCells(new Link(link.at().relative(Direction.NORTH, depth), link.across(), link.width(), link.height())))
                            grid.clear(cell);
        prune(layout, grid, interior);
        if (layout.station == null)
            stripe(grid, layout);
        if (whole)
            ramp(grid, envelope, dock);

        return finish(spec, layout, grid, interior, envelope, dock);
    }

    /**
     * Engine pods behind each engine: the outside shows what the inside does. They are mirrored,
     * because the engines are.
     */
    private static void exteriors(Grid grid, Set<BlockPos> envelope, Layout layout)
    {
        BlockPos lo = null, hi = null;
        for (var at : envelope)
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        int room = Shapes.LIMIT - Shapes.RAMP - (hi.getZ() - lo.getZ() + 1);
        var added = new HashSet<BlockPos>();
        for (var e : new ArrayList<>(grid.cells().entrySet()))
            if (e.getValue().kind() == Kind.ENGINE && room > 0)
                for (int back = 1; back <= Math.min(3, room) + layout.hullH; back++)
                    for (int side = -1; side <= 1; side++)
                        for (int up = -1; up <= 1; up++)
                            added.add(e.getKey().relative(e.getValue().facing(), back).offset(side, up, 0));
        for (var at : added)
            if (envelope.add(at))
                grid.set(at, Kind.HULL);
    }

    /** Opens the hull behind every engine so its exhaust has a clear run to space. */
    private static void exhausts(Grid grid, Set<BlockPos> envelope)
    {
        for (var e : new ArrayList<>(grid.cells().entrySet()))
            if (e.getValue().kind() == Kind.ENGINE)
                for (var at = e.getKey().relative(e.getValue().facing()); envelope.contains(at); at = at.relative(e.getValue().facing()))
                    grid.clear(at);
    }

    /**
     * Takes out any fixture nobody can reach (boxed in by furniture around it) and re-fills the
     * nooks that leaves, until every fixture can be used.
     */
    private static void prune(Layout layout, Grid grid, Set<BlockPos> interior)
    {
        var view = new Walk.Cells()
        {
            @Override
            public Kind kind(BlockPos at)
            {
                return grid.kind(at);
            }

            @Override
            public boolean interior(BlockPos at)
            {
                return interior.contains(at);
            }
        };
        var start = layout.dockWall.relative(Direction.SOUTH);
        for (int pass = 0; pass < 6; pass++)
        {
            var reach = Walk.reachable(view, start);
            boolean removed = false;
            for (var e : new ArrayList<>(grid.cells().entrySet()))
            {
                var kind = e.getValue().kind();
                if (kind.fixture() && kind != Kind.CAMERA && kind != Kind.BED && kind != Kind.BED_FOOT && kind != Kind.FARMLAND && !Walk.usable(reach, e.getKey()))
                {
                    grid.clear(e.getKey());
                    removed = true;
                }
            }
            if (!removed)
                return;
        }
    }

    /** The cells an opening clears: a doorway {@code width} wide and {@code height} high in the wall. */
    private static List<BlockPos> openingCells(Link link)
    {
        var side = link.across().getClockWise();
        var cells = new ArrayList<BlockPos>();
        for (int k = -link.width() / 2; k <= link.width() / 2; k++)
            for (int row = 0; row < link.height(); row++)
                cells.add(link.at().relative(side, k).above(row));
        return cells;
    }

    /**
     * A closed, framed door in the middle of a doorway, like the dock door: trim fills the rest of
     * the opening, so the framing rules for a sealed door hold.
     */
    private static void door(Grid grid, Link link)
    {
        var side = link.across().getClockWise();
        for (int k = -link.width() / 2; k <= link.width() / 2; k++)
            for (int row = 0; row < link.height(); row++)
            {
                var at = link.at().relative(side, k).above(row);
                if (k == 0 && row < 2)
                    grid.set(at, row == 0 ? Kind.DOOR_LOWER : Kind.DOOR_UPPER, link.across());
                else
                    grid.set(at, Kind.TRIM);
            }
    }

    /** Trim around an opening: both sides and the lintel. */
    private static void frame(Grid grid, Link link)
    {
        var side = link.across().getClockWise();
        for (int k = -link.width() / 2 - 1; k <= link.width() / 2 + 1; k++)
            for (int row = 0; row <= link.height(); row++)
            {
                var at = link.at().relative(side, k).above(row);
                if (grid.kind(at) == Kind.WALL && (row == link.height() || Math.abs(k) == link.width() / 2 + 1))
                    grid.set(at, Kind.TRIM);
            }
    }

    /**
     * Everything within the hull thickness of a core cell, bevelled: the horizontal reach is a disc
     * (so vertical edges are cut), and the layers above and below sit one block in from the rim (so
     * the roof edge is cut too). Any pocket left sealed inside is hull as well.
     */
    static Set<BlockPos> envelope(Set<BlockPos> core, int horizontal, int vertical)
    {
        var out = new HashSet<BlockPos>(core);
        int inset = Math.max(0, horizontal - 1);
        for (var at : core)
        {
            for (int dx = -horizontal; dx <= horizontal; dx++)
                for (int dz = -horizontal; dz <= horizontal; dz++)
                {
                    int d2 = dx * dx + dz * dz;
                    if (d2 <= horizontal * horizontal + (horizontal >= 2 ? 1 : 0))
                        out.add(at.offset(dx, 0, dz));
                    if (d2 <= inset * inset + (inset >= 2 ? 1 : 0))
                        for (int dy = 1; dy <= vertical; dy++)
                        {
                            out.add(at.offset(dx, dy, dz));
                            out.add(at.offset(dx, -dy, dz));
                        }
                }
        }
        // Fill pockets: whatever outside air cannot reach through open space is hull too.
        BlockPos lo = null, hi = null;
        for (var at : out)
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        lo = lo.offset(-1, -1, -1);
        hi = hi.offset(1, 1, 1);
        var outside = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(lo);
        outside.add(lo);
        while (!queue.isEmpty())
        {
            var at = queue.poll();
            for (var d : Direction.values())
            {
                var n = at.relative(d);
                if (n.getX() < lo.getX() || n.getY() < lo.getY() || n.getZ() < lo.getZ() || n.getX() > hi.getX() || n.getY() > hi.getY() || n.getZ() > hi.getZ() || out.contains(n) || !outside.add(n))
                    continue;
                queue.add(n);
            }
        }
        for (int x = lo.getX(); x <= hi.getX(); x++)
            for (int y = lo.getY(); y <= hi.getY(); y++)
                for (int z = lo.getZ(); z <= hi.getZ(); z++)
                {
                    var at = new BlockPos(x, y, z);
                    if (!outside.contains(at))
                        out.add(at);
                }
        return out;
    }

    /** Glass through the hull wherever a room's wall is close to space, in a pattern that mirrors across the ship. */
    private static void windows(Layout layout, Grid grid, Set<BlockPos> envelope, Set<BlockPos> interior)
    {
        for (var space : layout.spaces)
        {
            if (space.windows == 0)
                continue;
            int dense = space.windows;
            // A dome's top deck is roofed in glass.
            if ((dense == 3 || layout.station != null && layout.station.dome()) && space.level == layout.spaces.stream().mapToInt(sp -> sp.level).max().orElse(0))
                for (var at : space.cells())
                    if (at.getY() - space.floorY - 1 == space.height - 1 && grid.kind(at.above()) == Kind.CEILING)
                        grid.set(at.above(), Kind.GLASS);
            for (var at : space.cells())
            {
                int row = at.getY() - space.floorY - 1;
                boolean sideBand = row >= 1 && row <= space.height - (dense >= 2 ? 1 : 2);
                for (var d : SIDES)
                {
                    if (!sideBand)
                        continue;
                    var first = at.relative(d);
                    if (grid.kind(first) != Kind.WALL)
                        continue;
                    // A pillar at every corner, so a window never wraps a room's edge.
                    var along = d.getClockWise();
                    if (!space.cells().contains(at.relative(along)) || !space.cells().contains(at.relative(along.getOpposite())))
                        continue;
                    // A station tile is even, so its pattern counts in from the nearer end and mirrors; and no window cuts a doorway.
                    if (layout.station != null && StationGenerator.doorBand(first))
                        continue;
                    int coord = d.getAxis() == Direction.Axis.Z ? at.getX() : at.getZ();
                    int key = layout.station != null ? Math.min(coord - 1, StationGenerator.TILE - 2 - coord) : d.getAxis() == Direction.Axis.Z ? Math.abs(at.getX() - layout.cx) : at.getZ();
                    boolean pane = dense == 3 || (dense == 2 ? Math.floorMod(key, 3) != 0 : Math.floorMod(key, 6) == 1 || Math.floorMod(key, 6) == 2);
                    if (pane)
                        punch(grid, envelope, first, d);
                }
                // The dome: glass in the roof of the commons.
                if (space.use == RoomUse.HUB && !space.cells().contains(at.above()) && grid.kind(at.above()) == Kind.CEILING && (Math.abs(at.getX() - layout.cx) + Math.abs(at.getZ() - layout.cx)) % 2 == 0)
                    punch(grid, envelope, at.above(), Direction.UP);
            }
        }
    }

    /** Turns a straight run of wall and hull into glass, if it reaches open space without crossing anything else. */
    private static void punch(Grid grid, Set<BlockPos> envelope, BlockPos from, Direction d)
    {
        var run = new ArrayList<BlockPos>();
        var at = from;
        while (envelope.contains(at))
        {
            var kind = grid.kind(at);
            if (kind != Kind.WALL && kind != Kind.CEILING && kind != Kind.FRAME && kind != Kind.HULL && kind != Kind.ACCENT)
                return;
            run.add(at);
            at = at.relative(d);
            if (run.size() > 6)
                return;
        }
        for (var cell : run)
            grid.set(cell, Kind.GLASS);
    }

    /** The docking door, its base, the recess in the hull in front of it and the camera over it. Returns the door position. */
    private static BlockPos dock(Layout layout, Grid grid, Set<BlockPos> envelope)
    {
        var wall = layout.dockWall;
        for (int k = -1; k <= 1; k++)
            for (int row = 0; row <= 2; row++)
                if (k != 0 || row == 2)
                    grid.set(wall.offset(k, row, 0), Kind.TRIM);
        grid.set(wall, Kind.DOOR_LOWER, Direction.NORTH);
        grid.set(wall.above(), Kind.DOOR_UPPER, Direction.NORTH);
        grid.set(wall.below(), Kind.DOCK_BASE, Direction.NORTH);
        // Open the hull in front of the door so boarding is flush with the outside.
        for (int depth = 1; envelope.contains(wall.relative(Direction.NORTH, depth)) || depth == 1; depth++)
            for (int k = -1; k <= 1; k++)
                for (int row = 0; row <= 3; row++)
                    grid.clear(wall.offset(k, row, -depth));
        grid.set(wall.offset(0, 2, -1), Kind.CAMERA, Direction.NORTH);
        return wall;
    }

    /**
     * The docking door sits above the ground, because the hull has a floor layer under it, so a
     * three-wide flight of stairs climbs to it from the ground: part of the ship, so it works on any pad.
     */
    private static void ramp(Grid grid, Set<BlockPos> envelope, BlockPos dock)
    {
        int floor = Integer.MAX_VALUE;
        for (var at : envelope)
            floor = Math.min(floor, at.getY());
        int rise = dock.getY() - floor;
        for (int step = 0; step < rise; step++)
            for (int k = -1; k <= 1; k++)
            {
                var at = new BlockPos(dock.getX() + k, floor + step, dock.getZ() - rise + step);
                grid.set(at, Kind.STAIR, Direction.SOUTH);
                envelope.add(at);
                // Solid under the step, so the flight is ground, not a bridge, and joins the hull.
                for (int under = floor; under < floor + step; under++)
                {
                    var below = new BlockPos(at.getX(), under, at.getZ());
                    if (!grid.has(below))
                        grid.set(below, Kind.HULL);
                    envelope.add(below);
                }
            }
    }

    /** A line of accent blocks along the hull's flanks, the same on both sides. */
    private static void stripe(Grid grid, Layout layout)
    {
        int y = 2;
        for (var e : new ArrayList<>(grid.cells().entrySet()))
        {
            var at = e.getKey();
            if (e.getValue().kind() == Kind.HULL && at.getY() == y && Math.floorMod(at.getZ(), 6) < 3)
                grid.set(at, Kind.ACCENT);
        }
    }

    private static Ship finish(Spec spec, Layout layout, Grid grid, Set<BlockPos> interior, Set<BlockPos> envelope, BlockPos dock)
    {
        BlockPos lo = null, hi = null;
        for (var at : envelope)
        {
            lo = lo == null ? at : BlockPos.min(lo, at);
            hi = hi == null ? at : BlockPos.max(hi, at);
        }
        var shift = new Vec3i(-lo.getX(), -lo.getY(), -lo.getZ());
        var voxels = new LinkedHashMap<BlockPos, Voxel>();
        BlockPos helm = null;
        for (var e : grid.cells().entrySet())
        {
            var at = e.getKey().offset(shift);
            voxels.put(at, e.getValue());
            if (e.getValue().kind() == Kind.COMPUTER)
                helm = at;
        }
        var shifted = new HashSet<BlockPos>();
        for (var at : interior)
            shifted.add(at.offset(shift));
        var rooms = new ArrayList<Landmark>();
        for (var space : layout.spaces)
        {
            if (!space.use.destination())
                continue;
            BlockPos supplies = null;
            for (var at : space.cells())
            {
                var k = grid.kind(at);
                if ((k == Kind.BARREL || k == Kind.CHEST || k == Kind.CHEST_LEFT || k == Kind.CHEST_RIGHT) && (supplies == null || at.asLong() < supplies.asLong()))
                    supplies = at;
            }
            rooms.add(new Landmark(space.use.label, space.use, space.walk().offset(shift), supplies == null ? null : supplies.offset(shift)));
        }
        return new Ship(spec, Collections.unmodifiableMap(voxels), Collections.unmodifiableSet(shifted), hi.getX() - lo.getX() + 1, hi.getY() - lo.getY() + 1, hi.getZ() - lo.getZ() + 1, layout.spaces.stream().mapToInt(sp -> sp.level + 1).max().orElse(1), helm, rooms, dock == null ? null : dock.offset(shift));
    }

    private ShipGenerator()
    {
    }
}
