package com.nullandkale.nullspace.model.galaxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class LocalSpacesTest {
  @Test
  void homeIsARealDestination() {
    assertEquals(LocalSpaces.HOME, LocalSpaces.get(LocalSpaces.HOME).id());
  }

  @Test
  void destinationIdsAndNamesAreUnique() {
    var ids = LocalSpaces.DESTINATIONS.stream().map(LocalSpaces.Destination::id).toList();
    assertEquals(ids.size(), new HashSet<>(ids).size());
    var labels =
        LocalSpaces.DESTINATIONS.stream()
            .map(d -> d.systemName() + " / " + d.name())
            .collect(Collectors.toList());
    assertEquals(labels.size(), new HashSet<>(labels).size(), "Labels must be distinguishable");
    assertFalse(LocalSpaces.DESTINATIONS.isEmpty());
  }

  @Test
  void everyDestinationCanBeLookedUpAndHasUsableFields() {
    for (var d : LocalSpaces.DESTINATIONS) {
      assertSame(d, LocalSpaces.get(d.id()));
      assertFalse(d.name().isBlank(), d.id());
      assertFalse(d.systemName().isBlank(), d.id());
      assertFalse(d.system().isBlank(), d.id());
    }
  }

  @Test
  void unknownDestinationsAreRejected() {
    for (String bad : new String[] {"", "nowhere", "SOL_TERRA", " sol_terra"})
      assertThrows(IllegalArgumentException.class, () -> LocalSpaces.get(bad), bad);
  }

  @Test
  void addressesDefaultToHomeForMissingShipsAndFields() {
    assertEquals(LocalSpaces.HOME, LocalSpaces.address(null));
    assertEquals(LocalSpaces.HOME, LocalSpaces.address(new CompoundTag()));
    var ship = new CompoundTag();
    ship.putString("localSpace", "elsewhere");
    assertEquals("elsewhere", LocalSpaces.address(ship));
  }

  @Test
  void shipsAreTogetherOnlyInTheSameSpace() {
    var a = new CompoundTag();
    var b = new CompoundTag();
    assertTrue(LocalSpaces.together(a, b), "Two unset ships share home");
    assertTrue(LocalSpaces.together(a, null));
    var other =
        LocalSpaces.DESTINATIONS.stream().filter(d -> !d.id().equals(LocalSpaces.HOME)).findFirst();
    if (other.isPresent()) {
      b.putString("localSpace", other.get().id());
      assertFalse(LocalSpaces.together(a, b));
      a.putString("localSpace", other.get().id());
      assertTrue(LocalSpaces.together(a, b));
    }
  }

  @Test
  void labelsNameTheSystemAndBody() {
    var home = LocalSpaces.get(LocalSpaces.HOME);
    assertEquals(home.systemName() + " / " + home.name(), LocalSpaces.label(new CompoundTag()));
    var stale = new CompoundTag();
    stale.putString("localSpace", "removed_planet");
    assertThrows(IllegalArgumentException.class, () -> LocalSpaces.label(stale));
  }

  @Test
  void theDestinationListCannotBeModified() {
    assertThrows(UnsupportedOperationException.class, () -> LocalSpaces.DESTINATIONS.add(null));
  }
}
