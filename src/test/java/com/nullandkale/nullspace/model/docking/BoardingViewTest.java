package com.nullandkale.nullspace.model.docking;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoardingViewTest {
  private static ScenePose pose(double yaw, double pitch, double roll) {
    return new ScenePose(new Vec3(1e9, 100, -1e9), FlightDynamics.orientation(yaw, pitch, roll));
  }

  @Test
  void crossingOpposingDoorsFacesIntoTheDestinationInsteadOfBackAtTheDoor() {
    var view = BoardingView.between(pose(180, 0, 0), pose(0, 0, 0), 180, -20);
    assertEquals(0, view.yaw(), 1e-4);
    assertEquals(-20, view.pitch(), 1e-4);
  }

  @Test
  void viewIsContinuousForEveryPortHeadingAndTiltedShipInBothDirections() {
    for (int sourceYaw : new int[] {0, 90, 180, 270})
      for (int targetYaw : new int[] {0, 90, 180, 270})
        for (float pitch : new float[] {-90, -35, 0, 42, 90}) {
          var source = pose(sourceYaw, 7, -12);
          var target = pose(targetYaw, -9, 18);
          float yaw = 137;
          var view = BoardingView.between(source, target, yaw, pitch);
          var before =
              FlightDynamics.rotate(source.orientation(), Vec3.directionFromRotation(pitch, yaw));
          var after =
              FlightDynamics.rotate(
                  target.orientation(), Vec3.directionFromRotation(view.pitch(), view.yaw()));
          // Native view vectors use Minecraft's quantized sine table (about .006 degrees).
          assertTrue(
              before.distanceTo(after) < 3e-4, "World look direction survives the frame change");
          var back = BoardingView.between(target, source, view.yaw(), view.pitch());
          assertTrue(
              Vec3.directionFromRotation(back.pitch(), back.yaw())
                      .distanceTo(Vec3.directionFromRotation(pitch, yaw))
                  < 3e-4);
        }
  }
}
