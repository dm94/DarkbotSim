package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Validates combat resolution, box pickup, npc presets and JSON loading.
 * Each test exercises one SimWorld mechanic in isolation.
 */
class SimWorldTest {

  @Test
  void heroAttacksAndKillsTargetNpc() {
    SimWorld w = new SimWorld();
    SimWorld.SimNpc npc = w.spawnNpc("target", 1000, 1000);
    npc.hp = npc.maxHp = 1_000; // 1000 damage to kill at 5000 dps => 0.2s
    w.hero.x = 1000;
    w.hero.y = 1000;
    w.hero.targetId = npc.id;

    // 250ms ticks => 1250 dmg per tick. 1 tick kills.
    w.tick(250);

    assertNull(w.findNpcById(npc.id), "npc should be removed after death");
    assertNull(w.hero.targetId, "hero target cleared when npc dies");
    assertEquals(100_000, w.hero.hp, 0.001, "npc had 0 dps so hero is untouched");
  }

  @Test
  void npcRetaliatesWhenAttackedInRange() {
    SimWorld w = new SimWorld();
    SimWorld.SimNpc npc = w.spawnNpc("saimon", 1000, 1000);
    npc.hp = npc.maxHp = 100_000;
    npc.dps = 1_000;
    npc.attackRange = 500;
    w.hero.x = 1000;
    w.hero.y = 1000; // distance 0, in range
    w.hero.targetId = npc.id;

    w.tick(1_000); // 1s

    // Hero: 5000 dmg. Npc: 1000 dmg, shield absorbs 10k first.
    assertEquals(95_000, npc.hp, 0.001);
    assertEquals(9_000, w.hero.shield, 0.001, "shield absorbs retaliation");
    assertEquals(100_000, w.hero.hp, 0.001);
  }

  @Test
  void npcChasesAndAttacksHeroOnAggro() {
    SimWorld w = new SimWorld();
    SimWorld.SimNpc npc = w.spawnNpc("stalker", 1000, 1000);
    npc.hp = npc.maxHp = 100_000;
    npc.dps = 500;
    npc.speed = 600;
    npc.attackRange = 300;
    npc.aggroRange = 2_000;
    w.hero.x = 1500;
    w.hero.y = 1000;

    // Tick 1: distance 500 > attackRange(300), npc chases and closes to 0.
    w.tick(1_000);
    assertEquals(100_000, npc.hp, 0.001, "hero has no target so does not shoot");
    assertTrue(Math.abs(1500 - npc.x) < 1.0, "npc reached hero x");
    assertEquals(10_000, w.hero.shield, 0.001, "no damage yet, npc was out of range this tick");

    // Tick 2: npc is now in range, applies dps.
    w.tick(1_000);
    assertEquals(9_500, w.hero.shield, 0.001, "npc dps applied after closing distance");
  }

  @Test
  void deadNpcFiresListenerAndClearsTarget() {
    SimWorld w = new SimWorld();
    SimWorld.SimNpc npc = w.spawnNpc("dies", 0, 0);
    npc.hp = npc.maxHp = 1;
    w.hero.x = 0;
    w.hero.y = 0;
    w.hero.targetId = npc.id;

    final int[] calls = { 0 };
    w.npcDeathListeners.add(n -> calls[0]++);

    w.tick(50);

    assertEquals(1, calls[0]);
    assertNull(w.hero.targetId);
  }

  @Test
  void heroCollectsBoxesInRange() {
    SimWorld w = new SimWorld();
    w.hero.x = 1000;
    w.hero.y = 1000;
    w.spawnBox("prometium", 1100, 1000).cargoValue = 50;
    w.spawnBox("endurium", 5_000, 5_000).cargoValue = 999;

    w.tick(50);

    assertEquals(1, w.boxes.size(), "only the in-range box is collected");
    assertEquals(50, w.hero.cargo);
  }

  @Test
  void heroCargoCapped() {
    SimWorld w = new SimWorld();
    w.hero.x = 0;
    w.hero.y = 0;
    w.hero.maxCargo = 10;
    w.spawnBox("x", 0, 0).cargoValue = 100;

    w.tick(50);

    assertEquals(10, w.hero.cargo);
  }

  @Test
  void tickCountAdvances() {
    SimWorld w = new SimWorld();
    assertEquals(0, w.tickCount);
    w.tick(10);
    w.tick(10);
    assertEquals(2, w.tickCount);
  }

  @Test
  void npcPresetApply() {
    SimWorld w = new SimWorld();
    ScenarioRunner.on(w).npcPreset("boss_kristallon", 0, 0).apply();

    assertEquals(1, w.npcs.size());
    SimWorld.SimNpc npc = w.npcs.get(0);
    assertEquals(500_000, npc.hp, 0.001);
    assertEquals(4_000, npc.dps, 0.001);
  }

  @Test
  void jsonScenarioLoadsAllSections() throws Exception {
    String json = "{"
        + "\"map\":{\"id\":3,\"width\":20000,\"height\":13000},"
        + "\"hero\":{\"x\":500,\"y\":500,\"speed\":300,\"hp\":50000,\"shield\":5000},"
        + "\"npcs\":["
        + "  {\"name\":\"x\",\"x\":600,\"y\":600,\"hp\":2000,\"dps\":100},"
        + "  {\"preset\":\"streuner\",\"x\":700,\"y\":700}"
        + "],"
        + "\"boxes\":[{\"type\":\"prometium\",\"x\":550,\"y\":550,\"cargoValue\":5}]"
        + "}";

    SimWorld w = new SimWorld();
    ScenarioRunner.loadFromJson(w, new BufferedReader(new StringReader(json)));

    assertEquals(3, w.mapId);
    assertEquals(20_000, w.mapWidth);
    assertEquals(500, w.hero.x, 0.001);
    assertEquals(50_000, w.hero.hp, 0.001);
    assertEquals(2, w.npcs.size());
    assertNotNull(w.findNpcById(w.npcs.get(0).id));
    // preset-applied npc has 4000 hp
    SimWorld.SimNpc presetNpc = w.npcs.get(1);
    assertEquals(4_000, presetNpc.hp, 0.001);
    assertEquals(1, w.boxes.size());
    assertEquals(5, w.boxes.get(0).cargoValue);
  }

  @Test
  void jsonScenarioFromFile() throws Exception {
    Path tmp = Files.createTempFile("scenario", ".json");
    Files.write(tmp, ("{"
        + "\"hero\":{\"x\":1,\"y\":2},"
        + "\"npcs\":[{\"preset\":\"lordakia\",\"x\":100,\"y\":200}]"
        + "}").getBytes());
    tmp.toFile().deleteOnExit();

    SimWorld w = new SimWorld();
    ScenarioRunner.loadFromJson(w, tmp);

    assertEquals(1, w.hero.x, 0.001);
    assertEquals(6_000, w.npcs.get(0).hp, 0.001);
  }

  @Test
  void clearResetsWorld() {
    SimWorld w = new SimWorld();
    w.spawnNpc("a", 0, 0);
    w.spawnBox("b", 0, 0);
    w.npcDeathListeners.add(n -> {
    });

    ScenarioRunner.on(w).clear().apply();

    assertEquals(0, w.npcs.size());
    assertEquals(0, w.boxes.size());
    assertEquals(0, w.npcDeathListeners.size());
    assertEquals(0, w.tickCount);
  }
}
