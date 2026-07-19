package eu.darkbot.sim;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;

/**
 * Builds initial state on top of a {@link SimWorld}.
 *
 * <p>Two entry points:
 * <ul>
 * <li>Fluent Java API via {@link #on(SimWorld)} for tests and inline
 * scenarios.</li>
 * <li>JSON loader via {@link #loadFromJson(SimWorld, Path)} for files.</li>
 * </ul>
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 * "map": { "id": 1, "width": 21000, "height": 13500 },
 * "hero": { "x": 1000, "y": 2000, "speed": 400, "hp": 100000, "shield": 10000
 * },
 * "npcs": [
 * { "name": "Streuner", "x": 1500, "y": 2000, "hp": 1000, "dps": 200 },
 * { "name": "Boss", "preset": "boss_saimon", "x": 3000, "y": 2500 }
 * ],
 * "boxes": [
 * { "type": "prometium", "x": 1200, "y": 2050 }
 * ]
 * }
 * }</pre>
 */
public final class ScenarioRunner {

  private static final Gson GSON = new Gson();

  private final SimWorld world;

  private ScenarioRunner(SimWorld world) {
    this.world = world;
  }

  public static ScenarioRunner on(SimWorld world) {
    return new ScenarioRunner(world);
  }

  // ----- Fluent API ---------------------------------------------------------

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
    return hero(x, y, 400, 100_000, 10_000);
  }

  public ScenarioRunner hero(double x, double y, double speed) {
    return hero(x, y, speed, 100_000, 10_000);
  }

  public ScenarioRunner hero(double x, double y, double speed, double hp, double shield) {
    world.hero.x = x;
    world.hero.y = y;
    world.hero.speed = speed;
    world.hero.hp = world.hero.maxHp = hp;
    world.hero.shield = world.hero.maxShield = shield;
    world.hero.destX = null;
    world.hero.destY = null;
    return this;
  }

  public ScenarioRunner npc(String name, double x, double y) {
    return npc(name, x, y, 1_000, 0);
  }

  public ScenarioRunner npc(String name, double x, double y, double hp) {
    return npc(name, x, y, hp, 0);
  }

  public ScenarioRunner npc(String name, double x, double y, double hp, double dps) {
    SimWorld.SimNpc npc = world.spawnNpc(name, x, y);
    npc.hp = npc.maxHp = hp;
    npc.dps = dps;
    return this;
  }

  /** Apply a preset instead of specifying every stat. */
  public ScenarioRunner npcPreset(String preset, double x, double y) {
    NpcPreset p = NpcPreset.BY_NAME.get(preset);
    if (p == null)
      throw new IllegalArgumentException("unknown preset: " + preset);
    SimWorld.SimNpc npc = world.spawnNpc(p.name, x, y);
    npc.hp = npc.maxHp = p.hp;
    npc.dps = p.dps;
    npc.speed = p.speed;
    npc.attackRange = p.attackRange;
    npc.aggroRange = p.aggroRange;
    return this;
  }

  public ScenarioRunner box(String type, double x, double y) {
    return box(type, x, y, 1);
  }

  public ScenarioRunner box(String type, double x, double y, int cargoValue) {
    SimWorld.SimBox box = world.spawnBox(type, x, y);
    box.cargoValue = cargoValue;
    return this;
  }

  /** Clear everything before reapplying. */
  public ScenarioRunner clear() {
    world.npcs.clear();
    world.boxes.clear();
    world.hero.destX = world.hero.destY = null;
    world.hero.targetId = null;
    world.tickCount = 0;
    world.npcDeathListeners.clear();
    return this;
  }

  /**
   * Commit. No-op so far; kept for future side-effects (event triggering,
   * logging).
   */
  public void apply() {
    // state already mutated
  }

  // ----- JSON loader --------------------------------------------------------

  /** Replace the world's state with the scenario described in {@code json}. */
  public static void loadFromJson(SimWorld world, Path json) throws IOException {
    try (Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
      loadFromJson(world, reader);
    }
  }

  public static void loadFromJson(SimWorld world, Reader reader) {
    ScenarioDoc doc = GSON.fromJson(reader, ScenarioDoc.class);
    ScenarioRunner r = on(world).clear();
    if (doc.map != null) {
      r.map(doc.map.id, doc.map.width, doc.map.height);
    }
    if (doc.hero != null) {
      HeroDoc h = doc.hero;
      r.hero(h.x, h.y, h.speed, h.hp, h.shield);
    }
    if (doc.npcs != null) {
      for (NpcDoc n : doc.npcs) {
        if (n.preset != null) {
          r.npcPreset(n.preset, n.x, n.y);
        } else {
          r.npc(n.name, n.x, n.y,
              n.hp != null ? n.hp : 1_000,
              n.dps != null ? n.dps : 0);
        }
      }
    }
    if (doc.boxes != null) {
      for (BoxDoc b : doc.boxes) {
        r.box(b.type, b.x, b.y, b.cargoValue != null ? b.cargoValue : 1);
      }
    }
    r.apply();
  }

  // ----- JSON DTOs ----------------------------------------------------------

  private static final class ScenarioDoc {
    MapDoc map;
    HeroDoc hero;
    List<NpcDoc> npcs;
    List<BoxDoc> boxes;
  }

  private static final class MapDoc {
    int id;
    int width = Offsets.DEFAULT_MAP_WIDTH;
    int height = Offsets.DEFAULT_MAP_HEIGHT;
  }

  private static final class HeroDoc {
    double x, y;
    double speed = 400;
    double hp = 100_000;
    double shield = 10_000;
  }

  private static final class NpcDoc {
    String name;
    String preset;
    double x, y;
    Double hp;
    Double dps;
  }

  private static final class BoxDoc {
    String type;
    double x, y;
    Integer cargoValue;
  }

  // ----- Built-in NPC presets ----------------------------------------------

  /** Catalogue of NPCs approximating DarkOrbit values. Extend as needed. */
  public static final class NpcPreset {
    public final String name;
    public final double hp;
    public final double dps;
    public final double speed;
    public final double attackRange;
    public final double aggroRange;

    public NpcPreset(String name, double hp, double dps, double speed,
        double attackRange, double aggroRange) {
      this.name = name;
      this.hp = hp;
      this.dps = dps;
      this.speed = speed;
      this.attackRange = attackRange;
      this.aggroRange = aggroRange;
    }

    public static final java.util.Map<String, NpcPreset> BY_NAME = new java.util.HashMap<>();
    static {
      register(new NpcPreset("streuner", 4_000, 80, 0, 500, 800));
      register(new NpcPreset("lordakia", 6_000, 220, 0, 500, 1_000));
      register(new NpcPreset("mordon", 10_000, 350, 0, 500, 1_000));
      register(new NpcPreset("saimon", 12_000, 400, 0, 500, 1_000));
      register(new NpcPreset("devolarium", 32_000, 900, 0, 550, 1_200));
      register(new NpcPreset("sibelonit", 16_000, 500, 0, 500, 1_000));
      register(new NpcPreset("boss_saimon", 120_000, 800, 0, 550, 1_200));
      register(new NpcPreset("boss_kristallon", 500_000, 4_000, 0, 600, 1_500));
    }

    private static void register(NpcPreset p) {
      BY_NAME.put(p.name.toLowerCase(), p);
    }
  }
}
