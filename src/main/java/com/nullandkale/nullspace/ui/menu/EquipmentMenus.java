package com.nullandkale.nullspace.ui.menu;

import com.nullandkale.nullspace.content.ContentCatalog;

import java.util.*;

/** Shared menu contract: the server authorizes precisely the actions offered by each device. */
public final class EquipmentMenus
{
    public record Entry(String verb, String label, String description)
    {}

    public static final Set<String> MODES = ContentCatalog.EQUIPMENT.stream().filter(d -> d.has("equipment_menu")).map(ContentCatalog.EquipmentDefinition::id).collect(java.util.stream.Collectors.toUnmodifiableSet());

    public static boolean supports(String mode)
    {
        return MODES.contains(mode);
    }

    public static List<Entry> entries(String mode)
    {
        if (!supports(mode))
            throw new IllegalArgumentException("Unknown equipment");
        var menu = new ArrayList<Entry>();
        menu.add(new Entry("device_refresh", "refresh", ""));
        menu.add(new Entry("device_enable", "enable", ""));
        menu.add(new Entry("device_disable", "disable", ""));
        if (mode.equals("generator") || mode.equals("battery"))
            menu.add(new Entry("device_reset", "manual restart", "Recover supply + controls"));
        if (ContentCatalog.equipment(mode).orElseThrow().has("power_allocation"))
            menu.add(new Entry("device_allocate", "allocate power", "Ship-wide request 0..8"));
        if (mode.equals("monitor"))
            menu.add(new Entry("device_channel", "next camera", ""));
        if (mode.equals("monitor") || mode.equals("camera"))
            menu.add(new Entry("device_view", "view feed", "Fullscreen"));
        return List.copyOf(menu);
    }

    private EquipmentMenus()
    {
    }
}
