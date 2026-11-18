package com.nullandkale.nullspace.gameplay.shipyard;

import com.nullandkale.nullspace.dev.ShipGallery;
import com.nullandkale.nullspace.gameplay.ship.ShipMessages;
import com.nullandkale.nullspace.worldgen.shipgen.Spec.Maker;
import com.nullandkale.nullspace.worldgen.stations.HabPlanner;
import com.nullandkale.nullspace.worldgen.stations.HabStructure;
import com.nullandkale.nullspace.worldgen.stations.StationSpec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Every kind of generated hab of one size, standing in the land for review: one per maker (Rivet, Halcyon, Bastion) side by side, each dug
 * into the ground with its entry hall at the surface, its door facing the walkway at the south edge and a sign beside it.
 * {@code /nullspace shipyard habs [small|medium|large]} builds it (small by default); each hab is planned and built exactly as a generated one
 * is. A large hab is up to 512 by 512 blocks, so the gallery is built a slice at a time over many ticks and tells you when it is ready.
 * A visit rebuilds the gallery of that size in the same place.
 */
public final class HabGallery
{
    public static final int MARGIN = 12, GAP = 24, WALK = 12;

    /** How many habs are planned and built ahead of the one being placed. */
    private static final int AHEAD = 2;

    /** Blocks a gallery places in one server tick. */
    public static final int PER_TICK = 60_000;

    public static final List<Maker> MAKERS = List.of(Maker.RIVET, Maker.HALCYON, Maker.BASTION);

    /** One hab of the gallery: who made it, the layout form it has, its size and the seed that gives that form (0 to search for one). */
    public record Entry(Maker maker, HabPlanner.Form form, StationSpec.Size size, long seed)
    {
        public Entry(Maker maker, HabPlanner.Form form, StationSpec.Size size)
        {
            this(maker, form, size, 0);
        }
    }

    private static Entry cell(StationSpec.Size size, HabPlanner.Form form, Maker maker, long seed)
    {
        return new Entry(maker, form, size, seed);
    }

    /**
     * What the plain command builds, with nothing to type: a grid of the settings, a row for each size (small, medium, large) and in it a hab for
     * each layout form (arms, beads, suites, stacked, ring; a small hab has no room for beads or a ring), with their crew in them. The seeds are
     * ones found by planning many habs ({@code HabTimingTest}) that really do give the form, so nothing is searched for when the gallery is built.
     */
    public static final List<Entry> EVERYTHING = List.of(cell(StationSpec.Size.COMPACT, HabPlanner.Form.ARMS, Maker.RIVET, 2), cell(StationSpec.Size.COMPACT, HabPlanner.Form.SUITES, Maker.BASTION, 1), cell(StationSpec.Size.COMPACT, HabPlanner.Form.STACKED, Maker.RIVET, 4), cell(StationSpec.Size.STANDARD, HabPlanner.Form.ARMS, Maker.HALCYON, 7), cell(StationSpec.Size.STANDARD, HabPlanner.Form.BEADS, Maker.BASTION, 4), cell(StationSpec.Size.STANDARD, HabPlanner.Form.SUITES, Maker.RIVET, 12), cell(StationSpec.Size.STANDARD, HabPlanner.Form.STACKED, Maker.HALCYON, 1), cell(StationSpec.Size.STANDARD, HabPlanner.Form.RING, Maker.BASTION, 5), cell(StationSpec.Size.LARGE, HabPlanner.Form.ARMS, Maker.BASTION, 7), cell(StationSpec.Size.LARGE, HabPlanner.Form.BEADS, Maker.RIVET, 2), cell(StationSpec.Size.LARGE, HabPlanner.Form.SUITES, Maker.HALCYON, 6), cell(StationSpec.Size.LARGE, HabPlanner.Form.STACKED, Maker.BASTION, 1), cell(StationSpec.Size.LARGE, HabPlanner.Form.RING, Maker.RIVET, 19));

    private static final List<StationSpec.Purpose> PURPOSES = List.of(StationSpec.Purpose.HABITATION, StationSpec.Purpose.AGRICULTURE, StationSpec.Purpose.INDUSTRY, StationSpec.Purpose.RESEARCH, StationSpec.Purpose.SECURITY);

    /** A hab in the gallery: what was built, and where its plan's corner (0, 0, 0) is in the world. */
    public record Placed(HabStructure.Build build, BlockPos origin, String label, BlockPos door, Direction outside)
    {
        public Placed(HabStructure.Build build, BlockPos origin, String label)
        {
            this(build, origin, label, origin.offset(build.entrance()), build.outside());
        }

        /** The same hab with its (big) build let go: the door and the way it faces are all a finished gallery needs to take a visitor there. */
        Placed slim()
        {
            return new Placed(null, origin, label, door, outside);
        }
    }

    private static Job active;

    /**
     * Planning a hab and building its blocks is pure computation with no world access, and for a big hab takes tens of seconds, so it is done
     * on these threads, all the habs of a gallery at once, while the server goes on ticking and places what is ready a slice at a time.
     */
    private static final java.util.concurrent.ExecutorService BUILDERS = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), task ->
    {
        var thread = new Thread(task, "Hab builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** A hab planned and built away from the server thread, with how long each took. */
    private record Planned(HabPlanner.Hab hab, HabStructure.Build build, long planMs, long buildMs)
    {}

    private static Planned planAndBuild(Entry entry)
    {
        // A seed whose layout form kept failing falls back to the arms: the next seed that gives the form is tried, up to four times.
        HabPlanner.Hab hab = null;
        long started = System.nanoTime(), planned = started;
        for (int tries = 0; tries <= 4; tries++)
        {
            // A seed that is known to give the form is used as it is; otherwise (or if it fails) a seed is searched for.
            var brief = entry.seed() != 0 && tries == 0 ? new StationSpec(entry.maker(), StationSpec.Purpose.HABITATION, entry.size(), StationSpec.Condition.OPERATIONAL, entry.seed()) : spec(entry.maker(), entry.size(), entry.form(), tries);
            hab = HabPlanner.generate(brief);
            planned = System.nanoTime();
            if (entry.form() == null || hab.form() == entry.form())
                break;
        }
        var build = HabStructure.build(hab);
        return new Planned(hab, build, (planned - started) / 1_000_000, (System.nanoTime() - planned) / 1_000_000);
    }

    private static UUID requester;

    /** True while the command has paused the world's saving for the length of a build (as /save-off does), so it is turned back on when the build ends. */
    private static boolean savingPaused;

    /**
     * A gallery writes millions of blocks into thousands of chunks, and an autosave in the middle of that writes every one of them out on the server
     * thread (it once held the server for three minutes), so saving is paused while it builds and comes back, with the chunks written once by the
     * normal autosave afterwards, when it is done or has failed.
     */
    private static void pauseSaving(MinecraftServer server)
    {
        savingPaused = server.setAutoSave(false);
    }

    private static void resumeSaving(MinecraftServer server)
    {
        if (savingPaused)
            server.setAutoSave(true);
        savingPaused = false;
    }

    public static void initialize()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
        {
            var habs = Commands.literal("habs").executes(ctx -> run(ctx.getSource(), StationSpec.Size.STANDARD, null, true, EVERYTHING));
            habs.then(sized("small", StationSpec.Size.COMPACT));
            habs.then(sized("medium", StationSpec.Size.STANDARD));
            habs.then(sized("large", StationSpec.Size.LARGE));
            dispatcher.register(Commands.literal("nullspace").then(Commands.literal("shipyard").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(habs)));
        });
        ServerTickEvents.END_SERVER_TICK.register(HabGallery::tick);
    }

    /**
     * {@code habs <size>} builds the three habs of the size in their three layouts; {@code habs <size> <form>} builds all three in one layout (arms, beads,
     * suites, stacked or ring); and {@code live} after either also fills the habs with their crew, which the gallery otherwise leaves out so a
     * visit is a walk and not a fight.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> sized(String name, StationSpec.Size size)
    {
        var node = Commands.literal(name).executes(ctx -> run(ctx.getSource(), size, null, false, null));
        node.then(Commands.literal("live").executes(ctx -> run(ctx.getSource(), size, null, true, null)));
        for (var form : HabPlanner.Form.values())
            node.then(Commands.literal(form.name().toLowerCase(Locale.ROOT)).executes(ctx -> run(ctx.getSource(), size, form, false, null)).then(Commands.literal("live").executes(ctx -> run(ctx.getSource(), size, form, true, null))));
        return node;
    }

    private static int run(CommandSourceStack source, StationSpec.Size size, HabPlanner.Form form, boolean live, List<Entry> entries) throws com.mojang.brigadier.exceptions.CommandSyntaxException
    {
        var player = source.getPlayerOrException();
        if ((form == HabPlanner.Form.BEADS || form == HabPlanner.Form.RING) && size == StationSpec.Size.COMPACT)
        {
            player.sendSystemMessage(Component.literal("A small hab has no room for " + form.name().toLowerCase(Locale.ROOT) + ". Try medium or large."));
            return 0;
        }
        if (active != null)
        {
            player.sendSystemMessage(Component.literal("A hab gallery is still being built. Wait for it to finish."));
            return 0;
        }
        try
        {
            active = (entries != null ? start(player.level().getServer(), size, entries, live) : start(player.level().getServer(), size, form, live)).slim();
            requester = player.getUUID();
            pauseSaving(player.level().getServer());
            // The visitor is taken to the gallery first, to the unbuilt lane at its west end where the land stays as it is, and watches it go up.
            var server = player.level().getServer();
            var corner = corner(size);
            int x = corner.getX() + 8, z = corner.getZ() + 40;
            // Up in the air above the west end, to watch it go up: at least 120 high, and 20 above the land there (the chunk is generated first, so that
            // its height is real). Slow falling keeps a fall to the ground, when the gallery is done, from hurting.
            server.overworld().getChunk(x >> 4, z >> 4);
            int y = Math.max(120, server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 20);
            player.teleportTo(server.overworld(), x + .5, y, z + .5, Set.of(), Direction.EAST.toYRot(), 20, true);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 20 * 60 * 10, 0));
            player.sendSystemMessage(Component.literal("You are in the air above the west end of the hab gallery, which is going up to the east, " + (entries != null ? entries.size() : MAKERS.size()) + " habs in rows by size. World saving is paused until it is done (do not quit before then, or what you did since the last save is lost). It tells you when it is done."));
            return 1;
        }
        catch (Exception error)
        {
            com.nullandkale.nullspace.NullSpace.LOGGER.error("The hab gallery failed", error);
            player.sendSystemMessage(Component.literal("Could not build the hab gallery: " + ShipMessages.failure(error)));
            return 0;
        }
    }

    private static void tick(MinecraftServer server)
    {
        if (active == null)
            return;
        var job = active;
        try
        {
            if (!job.step(PER_TICK))
                return;
        }
        catch (Exception error)
        {
            com.nullandkale.nullspace.NullSpace.LOGGER.error("The hab gallery failed", error);
            job.release();
            active = null;
            resumeSaving(server);
            var player = server.getPlayerList().getPlayer(requester);
            if (player != null)
                player.sendSystemMessage(Component.literal("The hab gallery failed: " + ShipMessages.failure(error)));
            return;
        }
        active = null;
        resumeSaving(server);
        var player = server.getPlayerList().getPlayer(requester);
        if (player == null)
            return;
        var first = job.placed().getFirst();
        player.sendSystemMessage(Component.literal("Hab gallery done: " + job.placed().size() + " habs, each in its own layout (the sign by its door names it), with their crew in them. Each stands in the ground with its entry hall at the surface: walk in the iron door and go down the ladder. The first door is at " + first.door().getX() + " " + first.door().getY() + " " + first.door().getZ() + ". Door panels open only while a generator in the machine hall has coal."));
    }

    public static String name(StationSpec.Size size)
    {
        return switch (size)
        {
            case COMPACT -> "small";
            case STANDARD -> "medium";
            case LARGE -> "large";
        };
    }

    /** The brief of a hab of a maker and size: fixed seeds, so a visit shows the same habs, and mixed purposes so the rooms differ. */
    public static StationSpec spec(Maker maker, StationSpec.Size size)
    {
        return spec(maker, size, null);
    }

    /** As above, in a given layout form when one is asked for (a seed is searched for that has it), else the maker's own of three. */
    public static StationSpec spec(Maker maker, StationSpec.Size size, HabPlanner.Form form)
    {
        return spec(maker, size, form, 0);
    }

    /**
     * As above, the {@code skip}th seed that gives the form (the gallery asks for the next one when a seed's form keeps failing and falls back to
     * the arms). Only the cheap choice of a form is looked at here: planning a whole hab to check takes seconds, and the gallery does that one
     * hab to a tick.
     */
    public static StationSpec spec(Maker maker, StationSpec.Size size, HabPlanner.Form form, int skip)
    {
        int row = MAKERS.indexOf(maker), column = size.ordinal();
        // The three habs of a size show three layouts: the arms, the plazas (rooms that open into each other for a small hab, which has no room for plazas) and the suites.
        var want = form != null ? form : row == 0 ? HabPlanner.Form.ARMS : row == 1 && size != StationSpec.Size.COMPACT ? HabPlanner.Form.BEADS : HabPlanner.Form.SUITES;
        var purpose = PURPOSES.get((row * 2 + column) % PURPOSES.size());
        for (long seed = 4000L + row * 31 + column;; seed += 1000)
        {
            var brief = new StationSpec(maker, purpose, size, StationSpec.Condition.OPERATIONAL, seed);
            if (HabPlanner.formFor(new StationSpec(maker, HabPlanner.purposeFor(purpose), size, StationSpec.Condition.OPERATIONAL, seed)) != want && seed <= 4000L + 200_000)
                continue;
            if (skip-- <= 0 || seed > 4000L + 200_000)
                return brief;
        }
    }

    /** Where the gallery of a size stands: its own stretch of the world, clear of the others. */
    public static BlockPos corner(StationSpec.Size size)
    {
        return new BlockPos(1024 + 6000, 0, 2048 + size.ordinal() * 1400);
    }

    /** The small gallery, built at once (for tests and the picture test). */
    public static List<Placed> build(MinecraftServer server)
    {
        return build(server, StationSpec.Size.COMPACT);
    }

    /** Builds the gallery of a size at once, however long it takes: a large one is better built with {@link #start} over many ticks. */
    public static List<Placed> build(MinecraftServer server, StationSpec.Size size)
    {
        var job = start(server, size);
        while (!job.step(Integer.MAX_VALUE / 2))
        {
        }
        return job.placed();
    }

    public static Job start(MinecraftServer server, StationSpec.Size size)
    {
        return start(server, size, (HabPlanner.Form) null, false);
    }

    public static Job start(MinecraftServer server, StationSpec.Size size, HabPlanner.Form form, boolean live)
    {
        var entries = new java.util.ArrayList<Entry>();
        for (var maker : MAKERS)
            entries.add(new Entry(maker, form, size));
        return new Job(server, size, entries, live);
    }

    /** A gallery of the given habs, one after another going east. */
    public static Job start(MinecraftServer server, StationSpec.Size size, List<Entry> entries, boolean live)
    {
        return new Job(server, size, entries, live);
    }

    private enum Phase
    {
        LOAD, GROUND, PLAN, ISLAND, CARVE, BLOCKS, DONE
    }

    /** A gallery being built a slice at a time: {@link #step} places up to a budget of blocks and says when it is finished. */
    public static final class Job
    {
        final StationSpec.Size size;
        private final List<Entry> entries;
        private final boolean live;
        /** Whether the placed habs keep their blocks (a test looks at them) or let them go (the command does not need them). */
        private boolean keep = true;
        /** Called with each hab as soon as it is placed, before its blocks are let go. */
        private java.util.function.Consumer<Placed> each;
        private final ServerLevel level;
        private final int[] widths;
        private final List<java.util.concurrent.CompletableFuture<Planned>> futures;
        private final FastBlocks fast;
        private final List<ChunkPos> chunks = new ArrayList<>();
        private final List<Placed> placed = new ArrayList<>();
        private final BlockPos corner;
        /** Where each hab stands: the west and north edge of its plot, in rows by size. */
        private final int[] xs, zs;
        private Phase phase = Phase.LOAD;
        private int chunk, ground, hab, dx, dz;
        private HabPlanner.Hab current;
        private HabStructure.Build built;
        private BlockPos origin;
        private Iterator<BlockPos> carve;
        private Iterator<Map.Entry<BlockPos, BlockState>> blocks;

        Job(MinecraftServer server, StationSpec.Size size, List<Entry> entries, boolean live)
        {
            this.size = size;
            this.entries = entries;
            this.widths = new int[entries.size()];
            this.live = live;
            this.futures = new java.util.ArrayList<>();
            for (int i = 0; i < entries.size(); i++)
                futures.add(null);
            this.level = server.overworld();
            this.fast = new FastBlocks(level);
            this.corner = corner(size);
            this.xs = new int[entries.size()];
            this.zs = new int[entries.size()];
            int rowX = GAP, rowZ = 0, rowHeight = 0;
            StationSpec.Size previous = null;
            var held = new java.util.LinkedHashSet<ChunkPos>();
            for (int i = 0; i < widths.length; i++)
            {
                widths[i] = HabPlanner.windowFor(entries.get(i).size()) + 2 * MARGIN;
                // A new size starts a new row, with a lane to walk along between the rows.
                if (previous != null && entries.get(i).size() != previous)
                {
                    rowZ += rowHeight + WALK;
                    rowX = GAP;
                    rowHeight = 0;
                }
                previous = entries.get(i).size();
                xs[i] = corner.getX() + rowX;
                zs[i] = corner.getZ() + rowZ;
                rowX += widths[i] + GAP;
                rowHeight = Math.max(rowHeight, widths[i]);
                for (int cx = xs[i] >> 4; cx <= (xs[i] + widths[i]) >> 4; cx++)
                    for (int cz = zs[i] >> 4; cz <= (zs[i] + widths[i]) >> 4; cz++)
                        held.add(new ChunkPos(cx, cz));
            }
            chunks.addAll(held);
        }

        public List<Placed> placed()
        {
            return placed;
        }

        /** Looks at each hab as it is placed, and lets go of its blocks afterwards: for a gallery too big to hold in memory all at once. */
        public Job checking(java.util.function.Consumer<Placed> check)
        {
            this.each = check;
            this.keep = false;
            return this;
        }

        /** A gallery that lets go of each hab's blocks once placed, which is how the command builds one. */
        public Job slim()
        {
            this.keep = false;
            return this;
        }

        /** Where the build is, for a log line: the phase, how many chunks are held, and which hab is being built. */
        public String progress()
        {
            return phase + " chunks " + chunk + "/" + chunks.size() + " hab " + hab + "/" + entries.size();
        }

        /** Gives the gallery's chunks back when it is done or has failed. */
        void release()
        {
            for (var future : futures)
                if (future != null)
                    future.cancel(true);
            for (var c : chunks)
                level.setChunkForced(c.x(), c.z(), false);
        }

        /** Does up to {@code budget} blocks of work; true when the gallery is finished. */
        public boolean step(int budget)
        {
            while (budget > 0 && phase != Phase.DONE)
            {
                var was = phase;
                long started = System.nanoTime();
                budget -= switch (phase)
                {
                    case LOAD -> load();
                    case GROUND -> ground();
                    case PLAN -> plan(budget);
                    case ISLAND -> island(budget);
                    case CARVE -> carve(budget);
                    case BLOCKS -> blocks(budget);
                    default -> 1;
                };
                spent[was.ordinal()] += System.nanoTime() - started;
            }
            if (phase == Phase.DONE && !reported)
            {
                reported = true;
                var report = new StringBuilder();
                for (var p : Phase.values())
                    report.append(' ').append(p).append(' ').append(spent[p.ordinal()] / 1_000_000).append(" ms");
                com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB GALLERY time spent by phase:{}", report);
            }
            return phase == Phase.DONE;
        }

        /** Nanoseconds spent in each phase, for the log: where a gallery's time goes. */
        private final long[] spent = new long[Phase.values().length];
        private boolean reported;

        /** Loads and holds a few chunks. */
        private int load()
        {
            int cost = 0;
            for (int i = 0; i < 8 && chunk < chunks.size(); i++, chunk++)
            {
                var c = chunks.get(chunk);
                level.setChunkForced(c.x(), c.z(), true);
                level.getChunk(c.x(), c.z());
                cost += 300;
            }
            if (chunk >= chunks.size())
                phase = Phase.GROUND;
            return cost;
        }

        /** The ground is the highest land here, so the habs are dug in and the plates that level it are shallow (a plate high over the land would put all of it in shadow and swamp the light engine); and it is high enough above the bottom of the world for the deepest floor. */
        private int ground()
        {
            int top = level.getMinY();
            for (int i = 0; i < widths.length; i++)
                for (int ix = 0; ix < widths[i]; ix += 4)
                    for (int iz = 0; iz < widths[i]; iz += 4)
                        top = Math.max(top, level.getHeight(Heightmap.Types.OCEAN_FLOOR, xs[i] + ix, zs[i] + iz));
            ground = Math.max(top, level.getMinY() + HabPlanner.floorsFor(StationSpec.Size.LARGE) * com.nullandkale.nullspace.worldgen.stations.StationPlan.PITCH + 12 + HabStructure.CAVE_DEPTH);
            if (ground + 20 >= level.getMaxY())
                throw new IllegalArgumentException("No room above the terrain for a gallery.");
            phase = Phase.PLAN;
            return 1000;
        }

        /** Plans and builds the next hab (a lot of thinking, but no blocks yet). */
        private int plan(int budget)
        {
            if (hab >= entries.size())
            {
                release();
                phase = Phase.DONE;
                return 1;
            }
            // The hab is being planned and built on the builder threads, this one and the next (more at once would hold too many in memory):
            // until it is ready, this tick has nothing to do.
            for (int ahead = hab; ahead < Math.min(entries.size(), hab + AHEAD); ahead++)
                if (futures.get(ahead) == null)
                {
                    var entry = entries.get(ahead);
                    futures.set(ahead, java.util.concurrent.CompletableFuture.supplyAsync(() -> planAndBuild(entry), BUILDERS));
                }
            var future = futures.get(hab);
            if (!future.isDone())
                return budget;
            Planned ready;
            try
            {
                ready = future.join();
            }
            catch (java.util.concurrent.CompletionException failure)
            {
                throw failure.getCause() instanceof RuntimeException cause ? cause : failure;
            }
            current = ready.hab();
            built = ready.build();
            origin = new BlockPos(xs[hab] + MARGIN, ground - current.groundY(), zs[hab] + MARGIN);
            dx = -MARGIN;
            dz = -MARGIN;
            carve = built.carve().iterator();
            blocks = built.plan().blocks().entrySet().iterator();
            com.nullandkale.nullspace.NullSpace.LOGGER.info("HAB GALLERY hab {} {} {}: planned in {} ms, built {} blocks and {} cells to dig in {} ms (on a builder thread)", hab, current.form(), entries.get(hab).size(), ready.planMs(), built.plan().blocks().size(), built.carve().size(), ready.buildMs());
            phase = Phase.ISLAND;
            return 1;
        }

        /**
         * The ground a hab stands in: each column of the plate is filled from the land up to the surface with dirt and a grass top, and
         * whatever stands above the surface (trees, hills) is cleared, so the entry hall has level ground and nothing hangs over the land.
         */
        private int island(int budget)
        {
            int cost = 0;
            int width = current.plan().width(), depth = current.plan().depth();
            while (cost < budget && dx < width + MARGIN)
            {
                int bx = origin.getX() + dx, bz = origin.getZ() + dz;
                int land = level.getHeight(Heightmap.Types.OCEAN_FLOOR, bx, bz) - 1;
                int cells = 0;
                // The ground is written straight into the chunk sections (see FastBlocks); the chunks are finished off once, when the plate is done.
                for (int y = Math.min(land + 1, ground - 3); y <= ground; y++)
                {
                    var block = y == ground ? Blocks.GRASS_BLOCK : y >= ground - 3 ? Blocks.DIRT : Blocks.STONE;
                    fast.set(bx, y, bz, block.defaultBlockState());
                    cells++;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                for (int y = ground + 1; y <= top; y++)
                {
                    fast.set(bx, y, bz, Blocks.AIR.defaultBlockState());
                    cells++;
                }
                // A cell written this way costs a sixteenth of one placed with setBlock, which is what the tick's budget is counted in.
                cost += 1 + (cells >> 4);
                if (++dz >= depth + MARGIN)
                {
                    dz = -MARGIN;
                    dx++;
                }
            }
            if (dx >= width + MARGIN)
            {
                fast.flush();
                phase = Phase.CARVE;
            }
            return cost;
        }

        private int carve(int budget)
        {
            int cost = 0, cells = 0;
            var air = Blocks.AIR.defaultBlockState();
            while (cost < budget && carve.hasNext())
            {
                // The rooms are dug by writing air into the chunk sections (see FastBlocks); a cell costs a sixteenth of one placed with setBlock.
                var at = origin.offset(carve.next());
                fast.set(at.getX(), at.getY(), at.getZ(), air);
                cost = ++cells >> 4;
            }
            if (!carve.hasNext())
            {
                fast.flush();
                phase = Phase.BLOCKS;
            }
            return cost + 1;
        }

        /**
         * True for a block that can be written straight into a chunk section: it has no block entity, holds no fluid and is not a door. Chests,
         * generators, lecterns, water and lava, and doors go through {@code setBlock}, which does what they need when they are placed.
         */
        private static boolean plain(BlockState state)
        {
            return !state.hasBlockEntity() && state.getFluidState().isEmpty() && !(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock);
        }

        /** A sign in the entry hall beside each door to an arm, naming the wing it leads to and the floor. */
        private void signWings(Placed hall)
        {
            var plan = current.plan();
            var entry = current.entry();
            for (var door : plan.doors())
            {
                boolean hubFirst = door.a().equals(entry.id());
                if (!hubFirst && !door.b().equals(entry.id()))
                    continue;
                var wing = HabPlanner.wingOf(plan, plan.room(hubFirst ? door.b() : door.a()));
                if (wing == null)
                    continue;
                var toward = hubFirst ? door.through().getOpposite() : door.through();
                int floor = current.floors() - door.at().getY() / com.nullandkale.nullspace.worldgen.stations.StationPlan.PITCH;
                var at = origin.offset(door.at()).relative(toward).relative(door.through().getClockWise(), 2);
                ShipGallery.label(level, at, DyeColor.YELLOW, wing.label, "floor " + floor);
            }
        }

        private int blocks(int budget)
        {
            int cost = 0, quick = 0, slow = 0;
            while (cost < budget && blocks.hasNext())
            {
                var e = blocks.next();
                var at = origin.offset(e.getKey());
                if (plain(e.getValue()))
                {
                    fast.set(at.getX(), at.getY(), at.getZ(), e.getValue());
                    quick++;
                }
                else
                {
                    level.setBlock(at, e.getValue(), 18);
                    slow++;
                }
                cost = (quick >> 4) + slow;
            }
            if (!blocks.hasNext())
            {
                // The blocks written to the sections are finished off, and then every chunk the hab stands in gives its light sources out again, so its rooms are lit whatever order its blocks went in.
                fast.flush();
                // The hab becomes a registered station, so the Station Cores in it (the controller room's above all) work.
                var lit = new ArrayList<ChunkPos>();
                for (int cx = origin.getX() >> 4; cx <= (origin.getX() + current.plan().width()) >> 4; cx++)
                    for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + current.plan().depth()) >> 4; cz++)
                        lit.add(new ChunkPos(cx, cz));
                FastBlocks.relight(level, lit);
                FastBlocks.resend(level, lit);
                var spec = current.plan().spec();
                String label = spec.maker().name().toLowerCase(Locale.ROOT) + " " + current.form().name().toLowerCase(Locale.ROOT);
                var hall = new Placed(built, origin, label);
                var sign = hall.door().relative(built.outside(), 2).relative(built.outside().getClockWise(), 2);
                ShipGallery.label(level, sign, DyeColor.GREEN, label, spec.purpose().name().toLowerCase(Locale.ROOT) + ", " + current.form().name().toLowerCase(Locale.ROOT), current.floors() + " floors", current.plan().rooms().size() + " rooms");
                signWings(hall);
                // The entities the rooms mark (frames, suits, animals, the logs on the lecterns), the stock in the chests by the depth of their floor, the
                // generators the door panels draw on, and, in a live gallery, the crew.
                RoomEntities.spawn(level, origin, built.plan().points());
                com.nullandkale.nullspace.gameplay.habs.HabDepth.stock(level, origin, current, built.plan().points(), spec.seed(), label + " supplies");
                com.nullandkale.nullspace.gameplay.habs.HabDoors.link(level, origin, built);
                com.nullandkale.nullspace.gameplay.habs.HabDepth.seedTree(level, origin, built, spec.seed());
                com.nullandkale.nullspace.gameplay.habs.HabVessels.register(level, origin, built, label);
                if (live)
                    com.nullandkale.nullspace.gameplay.habs.HabDepth.populate(level, origin, current, spec.seed());
                if (each != null)
                    each.accept(hall);
                placed.add(keep ? hall : hall.slim());
                // The block data of a hab is big (hundreds of thousands of blocks): when nobody will look at it again it is let go at once.
                built = null;
                current = null;
                blocks = null;
                carve = null;
                futures.set(hab, null);
                hab++;
                phase = Phase.PLAN;
            }
            return cost;
        }
    }

    private HabGallery()
    {
    }
}
