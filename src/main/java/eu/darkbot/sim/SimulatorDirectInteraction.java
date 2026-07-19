package eu.darkbot.sim;

import java.util.function.LongPredicate;

/**
 * Bridges DarkBot's direct interactions to {@link SimWorld}.
 * Each method maps to a world mutation; no real game calls involved.
 */
public final class SimulatorDirectInteraction
    implements com.github.manolo8.darkbot.core.api.GameAPI.DirectInteraction {

  private final SimWorld world;

  public SimulatorDirectInteraction(SimWorld world) {
    this.world = world;
  }

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  public void moveShip(eu.darkbot.api.game.other.Locatable destination) {
    if (destination == null) {
      world.hero.setDestination(world.hero.x, world.hero.y);
      return;
    }
    world.hero.setDestination(destination.getX(), destination.getY());
  }

  @Override
  public void lockEntity(int id) {
    world.hero.targetId = id;
  }

  @Override
  public void selectEntity(com.github.manolo8.darkbot.core.entities.Entity entity) {
    // Real game selection also implies locking; mirror that behavior.
    world.hero.targetId = entity == null ? null : entity.id;
  }

  @Override
  public void collectBox(com.github.manolo8.darkbot.core.entities.Box box) {
    if (box == null)
      return;
    world.hero.setDestination(box.locationInfo.getX(), box.locationInfo.getY());
  }

  @Override
  public void refine(long refineUtilAddress, eu.darkbot.api.managers.OreAPI.Ore oreType, int amount) {
    // Refining is a no-op in v1; can be extended to consume ore.
  }

  @Override
  public long callMethod(int index, long... arguments) {
    // Index 10 = goto (used by TanosAdapter.moveShip). Keep parity.
    if (index == 10 && arguments.length >= 3) {
      long x = arguments[1], y = arguments[2];
      world.hero.setDestination((double) x, (double) y);
    }
    return 0;
  }

  @Override
  public boolean callMethodChecked(boolean checkName, String signature, int index, long... arguments) {
    callMethod(index, arguments);
    return true;
  }

  @Override
  public boolean callMethodAsync(int index, long... arguments) {
    callMethod(index, arguments);
    return true;
  }

  @Override
  public int checkMethodSignature(long obj, int methodIdx, boolean includeMethodName, String signature) {
    // 0 = unknown / not checked; DarkBot accepts non-negative values.
    return 0;
  }

  @Override
  public void setMaxFps(int maxFps) {
    /* irrelevant in sim */ }

  // Helper kept package-private for tests.
  LongPredicate noopPredicate() {
    return addr -> true;
  }
}
