package com.nullandkale.nullspace.gameplay.systems;

import com.nullandkale.nullspace.content.ModBlocks;
import com.nullandkale.nullspace.core.ServerContext;
import com.nullandkale.nullspace.core.save.WorldStore;
import com.nullandkale.nullspace.gameplay.travel.TravelService;
import com.nullandkale.nullspace.model.power.PowerBus;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The glow and smoke of a ship's engines while it flies in space. A ship thrusts when its pilot (or the autopilot or a docking
 * manoeuvre) asks for it and its engines have power; then every connected engine throws a plume of large glowing puffs out of
 * its exhaust, in the direction the engine faces. Nothing is drawn for an idle ship or one without engine power.
 */
public final class EngineEffects
{
    /** An engine's exhaust: the centre of the cell it fires into, the way it points, and the engine's upgrade tier (0 to 3). */
    public record Exhaust(Vec3 origin, Direction direction, int tier)
    {}

    /**
     * The colours of an exhaust: {@code core} at the nozzle, then two stretches of flame each fading from one colour to the next,
     * and the smoke. An engine's upgrades set it: plain engines burn amber, a first upgrade burns blue, an advanced one violet and an
     * exotic one green; a creative one burns white-gold.
     */
    public record Palette(int core, int hotFrom, int hotTo, int warmFrom, int warmTo, int smokeFrom, int smokeTo)
    {}

    public static Palette palette(int tier)
    {
        return switch (tier)
        {
            case 0 -> new Palette(0xFFF2C8, 0xFFE08A, 0xFF9A30, 0xFF9A30, 0xFF4A10, 0xE0601A, 0x555555);
            case 1 -> new Palette(0xE6F6FF, 0xBFE6FF, 0x5FA8FF, 0x5FA8FF, 0x2060E0, 0x4070D0, 0x445566);
            case 2 -> new Palette(0xF6E6FF, 0xE0B8FF, 0xB060FF, 0xB060FF, 0x7A20E0, 0x8040C0, 0x444455);
            case 3 -> new Palette(0xE6FFF4, 0xB8FFE0, 0x40FFB0, 0x40FFB0, 0x10C080, 0x30B080, 0x445555);
            default -> new Palette(0xFFFFFF, 0xFFFFFF, 0xFFE070, 0xFFE070, 0xFFB800, 0xFFD060, 0x666655);
        };
    }

    public static void initialize()
    {
        ServerContext.tick(ServerContext.Phase.SESSIONS, server ->
        {
            var space = server.getLevel(TravelService.SPACE);
            if (space == null || space.players().isEmpty())
                return;
            for (var tag : WorldStore.get(server).ships())
            {
                var ship = (CompoundTag) tag;
                if (!ship.getStringOr("dimension", "").equals(TravelService.SPACE.identifier().toString()) || ship.getIntOr("thrustDemand", 0) <= 0)
                    continue;
                emit(space, ship);
            }
        });
    }

    /** The exhausts of a ship's connected engines, when it is thrusting and they have power; empty otherwise. */
    public static List<Exhaust> exhausts(ServerLevel level, CompoundTag ship)
    {
        var out = new ArrayList<Exhaust>();
        if (ship.getIntOr("thrustDemand", 0) <= 0 || !ShipSystems.enabled(ship) || ShipSystems.power(level, ship, PowerBus.System.ENGINES) <= 0)
            return out;
        for (var at : ShipSystems.connected(level, ship))
        {
            var state = level.getBlockState(at);
            if (!state.is(ModBlocks.ENGINE))
                continue;
            var facing = state.getValue(HorizontalDirectionalBlock.FACING);
            out.add(new Exhaust(Vec3.atCenterOf(at).add(facing.getStepX() * .6, 0, facing.getStepZ() * .6), facing, FuelSystem.tier(level, at)));
        }
        return out;
    }

    /** Dust puffs can be four times the size of an ordinary particle; a plume of them reads as a real exhaust. */
    private static final float WIDE = 4f;

    /** How big an exhaust is: its length in blocks, the size of its puffs, and how many puffs lie in each stretch of it. */
    public record Plume(double length, float size, int density)
    {}

    /**
     * The plume for how hard the engines are working ({@code thrust}, 0 to 1 of their full push) and how fast the ship is going
     * for its power ({@code speed}, 0 to 1 of its cruising speed). Both make it bigger: more thrust burns harder, and a ship that
     * is already moving fast trails a longer flame. Even at a crawl it is a large flame; at full thrust and speed it is the full plume.
     */
    public static Plume plume(double thrust, double speed)
    {
        double energy = .65 * Math.clamp(thrust, 0, 1) + .35 * Math.clamp(speed, 0, 1);
        return new Plume(4 + 8 * energy, (float) Math.min(WIDE, 2.2 + 1.8 * energy), 1 + (int) Math.round(2 * energy));
    }

    /** Blue-white at the nozzle, then amber, fading to orange smoke: puffs laid along the exhaust, bigger as they spread. */
    private static void emit(ServerLevel level, CompoundTag ship)
    {
        var random = level.getRandom();
        var plume = plume(ship.getDoubleOr("thrustShare", Math.min(1, ship.getIntOr("thrustDemand", 0) / 8.0)), ship.getDoubleOr("speedShare", 0));
        int segments = Math.max(2, (int) Math.ceil(plume.length() / 1.1));
        for (var exhaust : exhausts(level, ship))
        {
            var d = exhaust.direction();
            var o = exhaust.origin();
            var colours = palette(exhaust.tier());
            // The hot core: a bright blue-white blaze right at the nozzle.
            level.sendParticles(new DustParticleOptions(colours.core(), Math.max(1.6f, plume.size() * .9f)), o.x + d.getStepX() * .3, o.y, o.z + d.getStepZ() * .3, plume.density(), .12, .12, .12, 0);
            for (int k = 0; k < segments; k++)
            {
                double along = .8 + k * 1.1;
                double spread = .15 + k * .12;
                float size = (float) Math.min(WIDE, plume.size() + k * .3);
                boolean hot = k < segments / 3;
                level.sendParticles(new DustColorTransitionOptions(hot ? colours.hotFrom() : colours.warmFrom(), hot ? colours.hotTo() : colours.warmTo(), size), o.x + d.getStepX() * along, o.y, o.z + d.getStepZ() * along, plume.density(), spread, spread, spread, 0);
            }
            // Embers thrown off the end, and a heavy smoke trail beyond the flame, longer the harder the burn.
            level.sendParticles(ParticleTypes.FLAME, o.x + d.getStepX() * 1.5, o.y, o.z + d.getStepZ() * 1.5, 0, d.getStepX(), 0, d.getStepZ(), .2 + plume.length() * .03);
            double tail = plume.length() * .7 + random.nextDouble() * 2;
            level.sendParticles(new DustColorTransitionOptions(colours.smokeFrom(), colours.smokeTo(), WIDE), o.x + d.getStepX() * tail, o.y, o.z + d.getStepZ() * tail, plume.density(), .5, .5, .5, 0);
        }
    }

    private EngineEffects()
    {
    }
}
