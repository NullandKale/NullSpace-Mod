package com.nullandkale.nullspace.station;

import com.nullandkale.nullspace.terminal.EquipmentMenus.Entry;
import java.util.*;

/** Client/server menu contract, with a single editable argument for names and room selection. */
public final class StationMenus {
  public static boolean supports(String mode) {
    return Set.of("station_core", "docking_port", "door_connector", "docks").contains(mode);
  }

  public static List<Entry> entries(String mode) {
    return switch (mode) {
      case "docks" ->
          List.of(
              e("station_refresh", "Refresh contacts"),
              e("dock_next", "Select next berth"),
              e("dock_request", "Dock automatically"),
              e("dock_cancel", "Cancel docking"),
              e("dock_undock", "Undock automatically"),
              e("station_start", "Start station here"),
              e("helm", "Return to helm"),
              e("dock_emergency", "Emergency departure"),
              e("dock_accept", "Accept incoming request"));
      case "docking_port" ->
          List.of(
              e("station_refresh", "Inspect airlock"),
              e("dock_board", "Board connected vessel"),
              e("dock_inner", "Cycle inner door"),
              e("dock_outer", "Cycle exterior door"),
              e("dock_accept", "Accept docking request"),
              e("dock_construction", "Enter construction site"),
              e("dock_undock", "Undock automatically"),
              e("dock_emergency", "Emergency departure"));
      case "station_core" ->
          List.of(
              e("station_argument", "Name / room / player"),
              e("station_refresh", "Inspect station and graph"),
              e("station_systems", "Engineering"),
              e("station_rename", "Rename station"),
              e("station_access", "Change docking access"),
              e("station_invite", "Invite builder"),
              e("station_remove", "Remove builder"),
              e("station_module", "Build selected room"),
              e("station_generate", "Assemble generated layout"),
              e("station_expand", "Expand station build area"),
              e("dock_return", "Return to construction ship"),
              e("station_claim", "Claim and take command"));
      case "door_connector" ->
          List.of(
              e("station_refresh", "Inspect door and routes"), e("station_door", "Cycle bulkhead"));
      default -> throw new IllegalArgumentException("Unknown station terminal");
    };
  }

  private static Entry e(String verb, String label) {
    return new Entry(verb, label, "");
  }

  private StationMenus() {}
}
