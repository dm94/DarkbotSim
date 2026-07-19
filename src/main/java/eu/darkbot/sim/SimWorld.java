package eu.darkbot.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java model of the game world. The simulator mutates this;
 * {@link FakeMemory}
 * mirrors it into the byte layout DarkBot expects.
 *
 * <p>
 * Kept intentionally small: only fields the bot actually reads are modelled.
 * Add fields as scenarios demand them.
 */
public final class SimWorld {

  public final Hero hero = new Hero();
  public final List<SimNpc> npcs = new ArrayList<>();
  public final List<SimBox> boxes = new ArrayList<>();

  public int mapId = Offsets.DEFAULT_MAP_ID;
  public int mapWidth = Offsets.DEFAULT_MAP_WIDTH;
  public int mapHeight = Offsets.DEFAULT_MAP_HEIGHT;

  /**
   * Last assigned entity id; incrementing ids keeps them unique across spawns.
   */
  private int nextId = 1;

  public int nextId() {
    return nextId++;
  }

  /** Advance the simulation by {@code dtMs} milliseconds. */
  public void tick(long dtMs) {
    hero.tick(dtMs);
    for (SimNpc npc : npcs)
      npc.tick(dtMs);
    // Boxes are static in v1.
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

  /** The hero ship. Coordinates and stats read directly by DarkBot. */
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

    public SimNpc(int id, String name, double x, double y) {
      this.id = id;
      this.name = name;
      this.x = x;
      this.y = y;
    }

    public void tick(long dtMs) {
      /* static in v1; waypoints come later */ }

    public boolean isDead() {
      return hp <= 0;
    }
  }

  public static final class SimBox {
    public final int id;
    public String type;
    public double x, y;

    public SimBox(int id, String type, double x, double y) {
      this.id = id;
      this.type = type;
      this.x = x;
      this.y = y;
    }
  }
}
