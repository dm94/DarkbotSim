package eu.darkbot.sim;

/**
 * Builds initial state on top of a {@link SimWorld}. Fluent one-liners describe
 * the scenario; {@link #apply()} commits it.
 *
 * <pre>{@code
 * ScenarioRunner.on(world)
 *     .map(1, 21000, 13500)
 *     .hero(1000, 2000, 400)
 *     .npc("Streuner", 1500, 2000)
 *     .npc("Lordakia", 3000, 2500)
 *     .box("prometium", 1200, 2050)
 *     .apply();
 * }</pre>
 */
public final class ScenarioRunner {

  private final SimWorld world;

  private ScenarioRunner(SimWorld world) {
    this.world = world;
  }

  public static ScenarioRunner on(SimWorld world) {
    return new ScenarioRunner(world);
  }

  public ScenarioRunner map(int id) {
    return map(id, Offsets.DEFAULT_MAP_WIDTH, Offsets.DEFAULT_MAP_HEIGHT);
  }

  public ScenarioRunner map(int id, int width, int height) {
    world.mapId = id;
    world.mapWidth = width;
    world.mapHeight = height;
    return this;
  }

  public ScenarioRunner hero(double x, double y) {
    return hero(x, y, 400);
  }

  public ScenarioRunner hero(double x, double y, double speed) {
    world.hero.x = x;
    world.hero.y = y;
    world.hero.speed = speed;
    world.hero.destX = null;
    world.hero.destY = null;
    return this;
  }

  public ScenarioRunner npc(String name, double x, double y) {
    return npc(name, x, y, 1_000);
  }

  public ScenarioRunner npc(String name, double x, double y, double hp) {
    SimWorld.SimNpc npc = world.spawnNpc(name, x, y);
    npc.hp = npc.maxHp = hp;
    return this;
  }

  public ScenarioRunner box(String type, double x, double y) {
    world.spawnBox(type, x, y);
    return this;
  }

  /** Clear everything before reapplying. */
  public ScenarioRunner clear() {
    world.npcs.clear();
    world.boxes.clear();
    world.hero.destX = world.hero.destY = null;
    world.hero.targetId = null;
    return this;
  }

  /** Commit the scenario. Currently a no-op; kept for future hooks. */
  public void apply() {
    /* state already mutated; placeholder for events */ }
}
