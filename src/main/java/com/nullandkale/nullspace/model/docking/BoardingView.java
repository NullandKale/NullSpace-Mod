package com.nullandkale.nullspace.model.docking;

import com.nullandkale.nullspace.model.flight.FlightDynamics;
import com.nullandkale.nullspace.model.space.ScenePose;
import net.minecraft.world.phys.Vec3;

/** Keep the view continuous when crossing between ships' physical storage frames. */
public record BoardingView(float yaw, float pitch) {
  public static BoardingView between(ScenePose source, ScenePose target, float yaw, float pitch) {
    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
    var direction = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    var universe = FlightDynamics.rotate(source.orientation(), direction);
    var local = target.directionToLocal(universe);
    double horizontal = Math.hypot(local.x, local.z);
    return new BoardingView(
        horizontal < 1e-7 ? yaw : (float) Math.toDegrees(Math.atan2(-local.x, local.z)),
        (float) Math.toDegrees(Math.atan2(-local.y, horizontal)));
  }
}
