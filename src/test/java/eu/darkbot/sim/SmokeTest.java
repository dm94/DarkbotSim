package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import com.github.manolo8.darkbot.core.BotInstaller;

// Same package as the classes under test (eu.darkbot.sim); no explicit imports needed.

/**
 * Validates the {@link FakeMemory} byte-layout contract against what
 * {@link BotInstaller} expects. This is the "smallest thing that fails if the
 * logic breaks" required by the lazy-senior rule: no bot launch needed.
 */
class SmokeTest {

  @Test
  void patternIsFoundAndLeadsToMainApp() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);

    long found = mem.searchPattern(Offsets.BYTES_TO_MAIN_APPLICATION);
    assertEquals(mem.patternAddress(), found, "pattern must be found at the seeded address");

    long mainApp = found - Offsets.MAIN_APP_FROM_PATTERN;
    assertEquals(mem.mainAppAddress(), mainApp, "mainApp = pattern - 228");
  }

  @Test
  void mainApplicationPointsToScreenManagerAndGui() {
    FakeMemory mem = new FakeMemory(new SimWorld());

    long mainApp = mem.mainAppAddress();
    long main = mem.readLong(mainApp + Offsets.MAIN_APP_MAIN);
    long screen = mem.readLong(main + Offsets.MAIN_SCREEN_MANAGER);
    long gui = mem.readLong(main + Offsets.MAIN_GUI_MANAGER);
    long conn = mem.readLong(main + Offsets.MAIN_CONNECTION_MANAGER);

    assertEquals(mem.mainAddress(), main, "mainApp+1344 must equal mainAddress");
    assertEquals(mem.screenAddress(), screen, "main+504 -> screenManager");
    assertNotEquals(0, gui, "main+512 -> guiManager must be non-null");
    assertNotEquals(0, conn, "main+560 -> connectionManager must be non-null");
  }

  @Test
  void screenManagerExposesHeroAndMapRoots() {
    SimWorld world = new SimWorld();
    world.hero.id = 4242;
    FakeMemory mem = new FakeMemory(world);

    long screen = mem.screenAddress();
    long heroShip = mem.readLong(screen + Offsets.SCREEN_HERO);
    assertNotEquals(0, heroShip, "screen+240 -> heroShip");
    assertEquals(4242, mem.readInt(heroShip + Offsets.HERO_ID), "heroShip+56 = hero id");

    long mapPtr = mem.readLong(screen + Offsets.SCREEN_MAP);
    assertEquals(mem.mapAddress(), mapPtr, "screen+256 -> map object");
    assertEquals(world.mapId, mem.readInt(mapPtr + Offsets.MAP_ID), "map+84 = map id");
  }

  @Test
  void settingsClosureMatchesBotInstallerPattern() {
    FakeMemory mem = new FakeMemory(new SimWorld());
    long s = mem.settingsAddress();

    // Mirrors BotInstaller.settingsPattern exactly.
    assertEquals(-1, mem.readInt(s + Offsets.SETTINGS_48));
    assertEquals(0, mem.readInt(s + Offsets.SETTINGS_52));
    assertEquals(2, mem.readInt(s + Offsets.SETTINGS_56));
    assertEquals(1, mem.readInt(s + Offsets.SETTINGS_60));
  }

  @Test
  void searchClassClosureReturnsHeroWhenPredicateMatches() {
    SimWorld world = new SimWorld();
    world.hero.id = 7777;
    world.hero.level = 25;
    world.hero.cargo = 100;
    world.hero.maxCargo = 5_000;
    FakeMemory mem = new FakeMemory(world);

    // Same predicate BotInstaller.checkUserData uses (subset).
    int heroId = world.hero.id;
    LongPredicate heroPredicate = c -> heroId == mem.readInt(c + Offsets.HERO_CLOSURE_HERO_ID)
        && inRange(mem.readInt(c + Offsets.HERO_CLOSURE_LEVEL), 0, 100)
        && (mem.readInt(c + Offsets.HERO_CLOSURE_BOOL) == 1
            || mem.readInt(c + Offsets.HERO_CLOSURE_BOOL) == 2)
        && mem.readInt(c + Offsets.HERO_CLOSURE_VAL) == 0
        && mem.readInt(c + Offsets.HERO_CLOSURE_CARGO) >= 0
        && inRange(mem.readInt(c + Offsets.HERO_CLOSURE_MAX_CARGO), 100, 100_000);

    long found = mem.searchClassClosure(heroPredicate);
    assertEquals(mem.heroClosureAddress(), found, "hero closure must satisfy the predicate");
    assertEquals(7777, mem.readInt(found + Offsets.HERO_CLOSURE_HERO_ID));
  }

  @Test
  void writeReadRoundtrip() {
    FakeMemory mem = new FakeMemory(new SimWorld());
    long addr = mem.mainAddress() + 0x400; // unused region

    mem.writeInt(addr, 123456);
    assertEquals(123456, mem.readInt(addr));
    mem.writeDouble(addr + 8, 3.14);
    assertEquals(3.14, mem.readDouble(addr + 8), 1e-9);
    mem.writeBoolean(addr + 16, true);
    assertTrue(mem.readBoolean(addr + 16));
  }

  @Test
  void tickSyncsMapIdChanges() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);

    world.mapId = 5;
    mem.tick();
    assertEquals(5, mem.readInt(mem.mapAddress() + Offsets.MAP_ID));
  }

  private static boolean inRange(int v, int lo, int hi) {
    return v >= lo && v < hi;
  }
}
