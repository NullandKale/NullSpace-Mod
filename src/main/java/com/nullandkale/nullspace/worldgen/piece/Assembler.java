package com.nullandkale.nullspace.worldgen.piece;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.worldgen.blueprint.BlueprintOrientation;
import com.nullandkale.nullspace.worldgen.stations.StationBlueprint;

import java.util.*;

import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Joins kit pieces at matching connectors into one build. Deterministic for a seed, bounded by the
 * archetype's size and a fixed number of attempts, and pieces never overlap.
 * <ol>
 * <li>Roll a target count for each role between its min and max.</li>
 * <li>Place a start piece at the origin.</li>
 * <li>For each open connector, in order, take the first role still short of its target with a piece
 * that fits: a connector of the type it targets, turned to face back, not overlapping, inside the size limit.</li>
 * <li>Close what stays open with cap pieces; settle facing (BlueprintOrientation).</li>
 * </ol>
 * If a try leaves a minimum unmet, a connector open or a required machine missing, the next try uses
 * a derived seed; after {@link #ATTEMPTS} the last try is returned with its problems listed.
 */
public final class Assembler
{
    public static final int ATTEMPTS = 8;

    /** One piece in the build: which, its role, how it was turned, where it starts and its blocks. */
    public record Placed(String piece, String role, Rotation rotation, BlockPos offset, Set<BlockPos> cells)
    {
        public Placed
        {
            cells = Set.copyOf(cells);
        }

        Placed move(Vec3i shift)
        {
            var moved = new HashSet<BlockPos>();
            cells.forEach(c -> moved.add(c.offset(shift)));
            return new Placed(piece, role, rotation, offset.offset(shift), moved);
        }
    }

    /**
     * A finished assembly as a placeable plan: loot points become barrels, the core is the Station
     * Core or computer, and the docks are its docking bases.
     */
    public static StationBlueprint.Plan plan(Assembly assembly)
    {
        var blocks = new LinkedHashMap<>(assembly.blocks());
        for (var loot : assembly.points("loot"))
            if (blocks.getOrDefault(loot.pos(), Blocks.AIR.defaultBlockState()).isAir())
                blocks.put(loot.pos(), Blocks.BARREL.defaultBlockState());
        var core = blocks.entrySet().stream().filter(e -> e.getValue().is(ModBlocks.STATION_CORE) || e.getValue().is(ModBlocks.COMPUTER)).map(Map.Entry::getKey).findFirst().orElse(assembly.points("core").stream().map(Piece.Point::pos).findFirst().orElse(BlockPos.ZERO));
        var docks = blocks.entrySet().stream().filter(e -> e.getValue().is(ModBlocks.DOCKING_BASE)).map(Map.Entry::getKey).toList();
        return new StationBlueprint.Plan(blocks, core, docks, assembly.points());
    }

    /**
     * The finished build in local coordinates from the origin. Air entries mean "clear this block".
     * {@code problems} is empty for a complete build.
     */
    public record Assembly(Map<BlockPos, BlockState> blocks, List<Piece.Point> points, List<Placed> placed, List<Piece.Connector> open, List<String> problems)
    {
        public List<Piece.Point> points(String name)
        {
            return points.stream().filter(p -> p.name().equals(name)).toList();
        }

        public Vec3i size()
        {
            int x = 0, y = 0, z = 0;
            for (var at : blocks.keySet())
            {
                x = Math.max(x, at.getX() + 1);
                y = Math.max(y, at.getY() + 1);
                z = Math.max(z, at.getZ() + 1);
            }
            return new Vec3i(x, y, z);
        }
    }

    public static Assembly assemble(Kit kit, Archetype archetype, long seed)
    {
        Assembly last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++)
        {
            last = new Build(kit, archetype, new Random(attempt == 0 ? seed : seed * 0x9E3779B97F4A7C15L + attempt)).run();
            if (last.problems().isEmpty())
                return last;
        }
        return last;
    }

    /** One assembly try. */
    private static final class Build
    {
        final Kit kit;
        final Archetype archetype;
        final Random random;
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final List<Piece.Point> points = new ArrayList<>();
        final List<Placed> placed = new ArrayList<>();
        final List<Piece.Connector> open = new ArrayList<>();
        final Map<String, Integer> counts = new HashMap<>();
        BlockPos min, max;

        Build(Kit kit, Archetype archetype, Random random)
        {
            this.kit = kit;
            this.archetype = archetype;
            this.random = random;
        }

        Assembly run()
        {
            var targets = new LinkedHashMap<String, Integer>();
            for (var count : archetype.roles())
                targets.put(count.role(), count.min() + random.nextInt(count.max() - count.min() + 1));
            var starts = weighted(kit.role(archetype.start()));
            if (starts.isEmpty())
                return new Assembly(Map.of(), List.of(), List.of(), List.of(), List.of("Kit " + kit.id() + " has no " + archetype.start() + " piece to start from"));
            var start = starts.getFirst();
            place(start.piece(), start.role(), Rotation.NONE, BlockPos.ZERO, null);
            boolean progress = true;
            while (progress)
            {
                progress = false;
                for (var connector : List.copyOf(open))
                {
                    if (!open.contains(connector))
                        continue;
                    for (var target : targets.entrySet())
                        if (counts.getOrDefault(target.getKey(), 0) < target.getValue() && attach(connector, target.getKey()))
                        {
                            progress = true;
                            break;
                        }
                }
            }
            for (var connector : List.copyOf(open))
                attach(connector, archetype.cap());
            return finish(targets);
        }

        /** Tries the role's pieces, weighted, at {@code connector}; true if one was placed. */
        boolean attach(Piece.Connector connector, String role)
        {
            for (var entry : weighted(kit.role(role)))
            {
                var piece = entry.piece();
                for (int i = 0; i < piece.connectors().size(); i++)
                {
                    var own = piece.connectors().get(i);
                    if (!connector.accepts(own))
                        continue;
                    for (var rotation : rotations(own.facing(), connector.facing().getOpposite()))
                    {
                        var turned = piece.rotate(rotation);
                        var joining = turned.connectors().get(i);
                        var offset = connector.pos().relative(connector.facing()).subtract(joining.pos());
                        if (fits(turned, offset))
                        {
                            open.remove(connector);
                            place(turned, role, rotation, offset, joining.pos().offset(offset));
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Rotations turning {@code from} into {@code to}: one for walls, all four for floors. */
        List<Rotation> rotations(Direction from, Direction to)
        {
            if (from.getAxis().isHorizontal() && to.getAxis().isHorizontal())
                return List.of(Piece.turning(from, to));
            if (from != to)
                return List.of();
            var all = new ArrayList<>(List.of(Rotation.values()));
            Collections.shuffle(all, random);
            return all;
        }

        boolean fits(Piece turned, Vec3i offset)
        {
            var lo = min;
            var hi = max;
            for (var at : turned.blocks().keySet())
            {
                var p = at.offset(offset);
                if (blocks.containsKey(p))
                    return false;
                lo = lo == null ? p : BlockPos.min(lo, p);
                hi = hi == null ? p : BlockPos.max(hi, p);
            }
            var limit = archetype.max();
            return hi.getX() - lo.getX() < limit.getX() && hi.getY() - lo.getY() < limit.getY() && hi.getZ() - lo.getZ() < limit.getZ();
        }

        void place(Piece turned, String role, Rotation rotation, Vec3i offset, BlockPos joined)
        {
            var moved = turned.move(offset);
            moved.blocks().forEach((at, state) ->
            {
                blocks.put(at, kit.material(state));
                min = min == null ? at : BlockPos.min(min, at);
                max = max == null ? at : BlockPos.max(max, at);
            });
            points.addAll(moved.points());
            for (var c : moved.connectors())
                if (!c.pos().equals(joined))
                    open.add(c);
            placed.add(new Placed(turned.id(), role, rotation, BlockPos.ZERO.offset(offset), moved.blocks().keySet()));
            counts.merge(role, 1, Integer::sum);
        }

        Assembly finish(Map<String, Integer> targets)
        {
            var problems = new ArrayList<String>();
            for (var count : archetype.roles())
                if (counts.getOrDefault(count.role(), 0) < count.min())
                    problems.add("Only " + counts.getOrDefault(count.role(), 0) + " " + count.role() + " of at least " + count.min());
            if (!open.isEmpty())
                problems.add(open.size() + " connectors left open (no " + archetype.cap() + " piece fits): " + open.stream().limit(3).map(c -> c.target() + " at " + c.pos().toShortString()).toList());
            var shift = new BlockPos(-min.getX(), -min.getY(), -min.getZ());
            var out = new LinkedHashMap<BlockPos, BlockState>();
            blocks.forEach((at, state) -> out.put(at.offset(shift), state));
            BlueprintOrientation.fix(out);
            problems.addAll(missingMachines(archetype.kind(), out));
            return new Assembly(Collections.unmodifiableMap(out), points.stream().map(p -> p.move(shift)).toList(), placed.stream().map(p -> p.move(shift)).toList(), open.stream().map(c -> c.move(shift)).toList(), List.copyOf(problems));
        }

        /** Weighted order without replacement, so heavier pieces tend to be tried first. */
        List<Kit.Entry> weighted(List<Kit.Entry> entries)
        {
            var pool = new ArrayList<>(entries);
            var order = new ArrayList<Kit.Entry>();
            while (!pool.isEmpty())
            {
                int total = pool.stream().mapToInt(e -> Math.max(1, e.weight())).sum();
                int pick = random.nextInt(total);
                for (int i = 0; i < pool.size(); i++)
                {
                    pick -= Math.max(1, pool.get(i).weight());
                    if (pick < 0)
                    {
                        order.add(pool.remove(i));
                        break;
                    }
                }
            }
            return order;
        }
    }

    /** What a build of this kind is missing to work: a ship's flight machines, a station's core. */
    public static List<String> missingMachines(Archetype.Kind kind, Map<BlockPos, BlockState> blocks)
    {
        var problems = new ArrayList<String>();
        var required = switch (kind)
        {
            case SHIP -> List.of(ModBlocks.COMPUTER, ModBlocks.GENERATOR, ModBlocks.ENGINE, ModBlocks.AIR_SUPPLY);
            case STATION -> List.of(ModBlocks.STATION_CORE);
            case SITE -> List.<Block>of();
        };
        for (var block : required)
        {
            long count = blocks.values().stream().filter(s -> s.is(block)).count();
            if (count == 0 || (block == ModBlocks.COMPUTER || block == ModBlocks.STATION_CORE) && count > 1)
                problems.add("Needs " + (block == ModBlocks.COMPUTER || block == ModBlocks.STATION_CORE ? "exactly one " : "a ") + block.getName().getString() + ", has " + count);
        }
        return problems;
    }

    private Assembler()
    {
    }
}
