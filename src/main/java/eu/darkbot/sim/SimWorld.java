package eu.darkbot.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java model of the game world. The simulator mutates this; FakeMemory
 * mirrors it into the byte layout DarkBot expects.
 *
 * Combat model (v2):
 * - Hero auto-attacks its current targetId when in range (damage = hero.dps).
 * - Each NPC has its own dps and attackRange; if the hero is within range,
 * the NPC applies its dps to the hero's shield first, then hp.
 * - Dead NPCs are removed at the end of tick and the hero clears its target
 * if it points to a removed NPC.
 */
public final class SimWorld {

  // Tunables kept as constants so scenarios/tests can reason about timing.
  public static final double HERO_ATTACK_RANGE = 600;
  public static final double HERO_DPS = 5000;
  public static final double NPC_RESPAWN_RANGE = 5000;
  public static final double BOX_PICKUP_RANGE = 200;

  public final Hero hero = new Hero();
  public final List<SimNpc> npcs = new ArrayList<>();
  public final List<SimBox> boxes = new ArrayList<>();

  public int mapId = Offsets.DEFAULT_MAP_ID;
  public int mapWidth = Offsets.DEFAULT_MAP_WIDTH;
  public int mapHeight = Offsets.DEFAULT_MAP_HEIGHT;

  /** Tick counter, useful for triggers and deterministic tests. */
  public long tickCount = 0;

  /** Listeners invoked whenever an NPC dies. */
  public final List<NpcDeathListener> npcDeathListeners = new ArrayList<>();

  @FunctionalInterface
  public interface NpcDeathListener {
    void onNpcDeath(SimNpc npc);
  }

  private int nextId = 1;

  public int nextId() {
    return nextId++;
  }

  /** Advance the simulation by {@code dtMs} milliseconds. */
  public void tick(long dtMs) {
    tickCount++;
    hero.tick(dtMs);
    for (SimNpc npc : npcs) {
      npc.tick(dtMs, hero);
    }
    resolveCombat(dtMs);
    collectBoxes();
    removeDeadNpcs();
  }

  private void resolveCombat(long dtMs) {
    // Hero attacks the locked target if in range.
    SimNpc heroTarget = findNpcById(hero.targetId);
    if (heroTarget != null && !heroTarget.isDead()) {
      double dist = distance(hero.x, hero.y, heroTarget.x, heroTarget.y);
      if (dist <= HERO_ATTACK_RANGE) {
        heroTarget.hp -= HERO_DPS * dtMs / 1000.0;
        heroTarget.lastAttacker = hero.id;
        if (dist <= heroTarget.attackRange) {
          // NPC retaliates immediately when shot in its own range.
          applyDamageToHero(heroTarget.dps * dtMs / 1000.0);
        }
      }
    }
    // NPCs that roam and see the hero inside their aggro range attack.
    for (SimNpc npc : npcs) {
      if (npc.isDead() || Integer.valueOf(hero.id).equals(npc.lastAttacker))
        continue;
      double dist = distance(hero.x, hero.y, npc.x, npc.y);
      if (dist <= npc.aggroRange) {
        if (dist <= npc.attackRange) {
          applyDamageToHero(npc.dps * dtMs / 1000.0);
        } else {
          // Chase the hero.
          moveToward(npc, hero.x, hero.y, npc.speed * dtMs / 1000.0);
        }
      }
    }
  }

  private void applyDamageToHero(double rawDamage) {
    if (rawDamage <= 0)
      return;
    double remaining = hero.shield - rawDamage;
    if (remaining >= 0) {
      hero.shield = remaining;
      return;
    }
    hero.shield = 0;
    hero.hp += remaining; // remaining is negative here
    if (hero.hp < 0)
      hero.hp = 0;
  }

  private void collectBoxes() {
    if (!hero.shouldCollectBoxes)
      return;
    // Hero collects any box within pickup range; cargo adds up.
    List<SimBox> collected = new ArrayList<>();
    for (SimBox box : boxes) {
      if (distance(hero.x, hero.y, box.x, box.y) <= BOX_PICKUP_RANGE) {
        collected.add(box);
      }
    }
    for (SimBox box : collected) {
      hero.cargo = Math.min(hero.maxCargo, hero.cargo + box.cargoValue);
      boxes.remove(box);
    }
  }

  private void removeDeadNpcs() {
    List<SimNpc> dead = new ArrayList<>();
    for (SimNpc npc : npcs) {
      if (npc.isDead())
        dead.add(npc);
    }
    for (SimNpc npc : dead) {
      npcs.remove(npc);
      for (NpcDeathListener l : npcDeathListeners)
        l.onNpcDeath(npc);
    }
    // Clear hero target if it pointed at a now-dead npc.
    if (hero.targetId != null && findNpcById(hero.targetId) == null) {
      hero.targetId = null;
    }
  }

  private static double distance(double x1, double y1, double x2, double y2) {
    return Math.hypot(x1 - x2, y1 - y2);
  }

  private static void moveToward(SimNpc npc, double tx, double ty, double step) {
    double dx = tx - npc.x, dy = ty - npc.y;
    double dist = Math.hypot(dx, dy);
    if (dist < 0.5)
      return;
    step = Math.min(dist, step);
    npc.x += dx / dist * step;
    npc.y += dy / dist * step;
  }

  public SimNpc findNpcById(Integer id) {
    if (id == null)
      return null;
    for (SimNpc npc : npcs)
      if (npc.id == id)
        return npc;
    return null;
  }

  public SimNpc spawnNpc(String name, double x, double y) {
    SimNpc npc = new SimNpc(nextId(), name, x, y);
    npcs.add(npc);
    return npc;
  }

  public SimBox spawnBox(String type, double x, double y) {
    SimBox box = new SimBox(nextId(), type, x, y);
    boxes.add(box);
    return box;
  }

  public void removeBox(SimBox box) {
    boxes.remove(box);
  }

  public void removeNpc(SimNpc npc) {
    npcs.remove(npc);
  }

  /** The hero ship. */
  public static final class Hero {
    public int id = 1000;
    public double x, y;
    public double speed = 400;
    public double hp = 100_000;
    public double maxHp = 100_000;
    public double shield = 10_000;
    public double maxShield = 10_000;
    public int cargo = 0;
    public int maxCargo = 2_000;
    public int level = 30;
    public int config = 1;

    public Double destX, destY;
    public Integer targetId;
    /** When true, the hero auto-collects boxes inside BOX_PICKUP_RANGE. */
    public boolean shouldCollectBoxes = true;

    public void setDestination(double x, double y) {
      this.destX = x;
      this.destY = y;
    }

    public void tick(long dtMs) {
      if (destX == null || destY == null)
        return;
      double dx = destX - x, dy = destY - y;
      double dist = Math.hypot(dx, dy);
      if (dist < 0.5) {
        destX = destY = null;
        return;
      }
      double step = Math.min(dist, speed * dtMs / 1000.0);
      x += dx / dist * step;
      y += dy / dist * step;
    }
  }

  public static final class SimNpc {
    public final int id;
    public String name;
    public double x, y;
    public double hp = 1_000;
    public double maxHp = 1_000;
    public double speed = 0;
    public double dps = 0;
    public double attackRange = 500;
    public double aggroRange = 1_000;
    /**
     * Id of the last entity that damaged this npc; used to short-circuit
     * retaliation.
     */
    public Integer lastAttacker;

    public SimNpc(int id, String name, double x, double y) {
      this.id = id;
      this.name = name;
      this.x = x;
      this.y = y;
    }

    public void tick(long dtMs, Hero hero) {
      // Per-npc custom behavior (waypoints) can be added here later.
    }

    public boolean isDead() {
      return hp <= 0;
    }
  }

  public static final class SimBox {
    public final int id;
    public String type;
    public double x, y;
    public int cargoValue = 1;

    public SimBox(int id, String type, double x, double y) {
      this.id = id;
      this.type = type;
      this.x = x;
      this.y = y;
    }
  }
}
