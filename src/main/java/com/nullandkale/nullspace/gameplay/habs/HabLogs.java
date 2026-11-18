package com.nullandkale.nullspace.gameplay.habs;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

/**
 * The written logs of a hab, which tell the outpost's story a page at a time (see {@code docs/design/hab-rooms.md}): the crew came to mine the
 * planet, dug to a warm wet cavern and planted the grove first, built upward as the outpost grew, took ships at a lobby they added last, and
 * lost it when the seam ran thin and what lay under it broke through. Each lectern in a family's quarters, the chapel, the machine hall and the
 * controller room holds one, chosen by the number its room was marked with, so two habs tell it in different parts and voices.
 */
public final class HabLogs
{
    private static final List<String[]> FAMILY = List.of(new String[]{"We came down in the third year. The grove was green already, and the children slept better under the rock. Tomas says a deep place keeps its warmth.", "Mine is the third door on the left. The bunks are for the shift crews; we have a real bed and a shelf. I wrote our names on the wall of the chapel."}, new String[]{"A ship called today, the first since the lobby opened. They bought ore and left us flour. Mara cried at the smell of bread.", "The lobby is the newest room in the hab, and the coldest. We keep the suits by the door and the door shut."}, new String[]{"The seam is thinner this month. Nobody says it. The foundry runs a shift less, and the long tables in the commons are not full.", "I keep the keepsake box on the wall: a clock that no longer runs, a book with the end torn out."}, new String[]{"Something is wrong at the bottom. The grove is fine, the trees are fine, but the old shaft sweats and the rail is wet. The chief says it is the water table.", "I moved the children's cots to the upper floor. It is not far. It is not far enough."}, new String[]{"They sealed the ward below the commons. Nobody will say why. Ana went in with the doctor and the door was closed behind them.", "If you are reading this: the bread is in the kitchen, the seeds are in the vault, and the grove must not go dark."}, new String[]{"We are leaving by the lobby at first light, as many as the ship will take. I have left the bed made. I have left the door unlocked.", "Someone should come back for the grove. It is the best thing we ever built."});
    private static final List<String[]> CHAPEL = List.of(new String[]{"These are the names. There were more of us when we dug the first shaft than there are on this wall, and more on this wall than came out.", "Say them if you stay. Say them if you leave."}, new String[]{"A candle was kept lit here for every shift that came up safe. It was lit for a long time.", "The soul lantern is for the ones we did not find."});
    private static final List<String[]> MACHINE = List.of(new String[]{"Shift log. Generators one to four fed and running. The air supplies draw too much; the doors draw more. Keep the coal dry.", "Reminder to whoever relieves me: the command wing's doors do not open without the plant. Feed the plant first."}, new String[]{"Shift log. The plant is the first room we built after the grove. It is the last I will leave.", "If the lights fail, the gardens fail. If the gardens fail, nothing else matters."});
    private static final List<String[]> CONTROLLER = List.of(new String[]{"Final entry. The hab is on its own power, its own air and its own tree. A crew could live here indefinitely if it could be got to stay.", "Whoever claims this room claims the rest: the doors, the lamps and the turrets. Be kind to it."}, new String[]{"Controller log. Seam exhausted, supply ship overdue, leak below the grove. Evacuation ordered.", "I am staying until the last of them is out. Then I will turn the lights down, not off."});

    /** The text of the log a lectern marked {@code "<kind> <number>"} holds. */
    public static ItemStack book(String argument)
    {
        var parts = argument.split(" ");
        int number = parts.length > 1 ? Math.floorMod(Integer.parseInt(parts[1]), 1000) : 0;
        var set = switch (parts[0])
        {
            case "chapel" -> CHAPEL;
            case "machine" -> MACHINE;
            case "controller" -> CONTROLLER;
            default -> FAMILY;
        };
        var entry = set.get(number % set.size());
        var pages = new ArrayList<Filterable<Component>>();
        for (var page : entry)
            pages.add(Filterable.passThrough(Component.literal(page)));
        var title = switch (parts[0])
        {
            case "chapel" -> "The Names";
            case "machine" -> "Shift Log";
            case "controller" -> "Final Log";
            default -> "Family Log";
        };
        var stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), "The crew", 0, List.copyOf(pages), true));
        return stack;
    }

    /** Puts the log in the lectern at {@code at}; false when there is no lectern. */
    public static boolean place(ServerLevel level, BlockPos at, String argument)
    {
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof LecternBlock) || !(level.getBlockEntity(at) instanceof LecternBlockEntity lectern))
            return false;
        lectern.setBook(book(argument));
        level.setBlock(at, state.setValue(LecternBlock.HAS_BOOK, true), 3);
        return true;
    }

    private HabLogs()
    {
    }
}
