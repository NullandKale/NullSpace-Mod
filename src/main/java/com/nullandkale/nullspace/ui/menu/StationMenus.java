package com.nullandkale.nullspace.ui.menu;

import com.nullandkale.nullspace.ui.menu.EquipmentMenus.Entry;

import java.util.*;

/** Client/server menu contract, with a single editable argument for names and room selection. */
public final class StationMenus
{
    public static boolean supports(String mode)
    {
        return Set.of("station_core", "docking_port", "docks").contains(mode);
    }

    public static List<Entry> entries(String mode)
    {
        var menu = new ArrayList<Entry>();
        switch (mode)
        {
            case "docks" ->
            {
                menu.add(e("station_refresh", "Refresh contacts"));
                menu.add(e("dock_next", "Select next berth"));
                menu.add(e("dock_request", "Dock automatically"));
                menu.add(e("dock_cancel", "Cancel docking"));
                menu.add(e("dock_undock", "Undock automatically"));
                menu.add(e("station_start", "Start station here"));
                menu.add(e("helm", "Return to helm"));
                menu.add(e("dock_emergency", "Emergency departure"));
                menu.add(e("dock_accept", "Accept incoming request"));
            }
            case "docking_port" ->
            {
                menu.add(e("station_refresh", "Inspect airlock"));
                menu.add(e("dock_board", "Board connected vessel"));
                menu.add(e("dock_inner", "Cycle inner door"));
                menu.add(e("dock_outer", "Cycle exterior door"));
                menu.add(e("dock_accept", "Accept docking request"));
                menu.add(e("dock_construction", "Enter construction site"));
                menu.add(e("dock_undock", "Undock automatically"));
                menu.add(e("dock_emergency", "Emergency departure"));
            }
            case "station_core" ->
            {
                menu.add(e("station_argument", "Name / room / player"));
                menu.add(e("station_refresh", "Inspect station and graph"));
                menu.add(e("station_systems", "Engineering"));
                menu.add(e("station_rename", "Rename station"));
                menu.add(e("station_access", "Change docking access"));
                menu.add(e("station_invite", "Invite builder"));
                menu.add(e("station_remove", "Remove builder"));
                menu.add(e("station_module", "Build selected room"));
                menu.add(e("station_generate", "Assemble generated layout"));
                menu.add(e("station_expand", "Expand station build area"));
                menu.add(e("dock_return", "Return to construction ship"));
                menu.add(e("station_claim", "Claim and take command"));
            }
            default -> throw new IllegalArgumentException("Unknown station terminal");
        }
        return List.copyOf(menu);
    }

    private static Entry e(String verb, String label)
    {
        return new Entry(verb, label, "");
    }

    private StationMenus()
    {
    }
}
