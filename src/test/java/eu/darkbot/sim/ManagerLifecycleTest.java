package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Integration tests that validate the complete read chains DarkBot's managers
 * perform against {@link FakeMemory}. These are NOT unit tests of individual
 * offsets — they walk the exact same pointer chains that MapManager, EntityList,
 * and HeroManager follow during a normal tick cycle.
 *
 * <p>No {@code Main} or {@code BotInstaller} instantiation required; we call
 * the same low-level {@code readInt/readLong/readDouble} calls that
 * {@code API.readInt(...)} would invoke.
 */
class ManagerLifecycleTest {

  // ---------------------------------------------------------------
  // MapManager read chain
  // ---------------------------------------------------------------

  /**
   * Simulates {@code MapManager.tick()}:
   * <pre>
   *   mapAddressStatic = screenManager + 256
   *   mapAddress = readLong(mapAddressStatic)
   *   width  = readInt(mapAddress + 76)
   *   height = readInt(mapAddress + 80)
   *   id     = readInt(mapAddress + 84)
   * </pre>
   */
  @Test
  void mapManagerReadChainReturnsCorrectMapData() {
    SimWorld world = new SimWorld();
    world.mapId = 17;
    world.mapWidth = 24_000;
    world.mapHeight = 16_000;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    // MapManager.install registers: mapAddressStatic = screenManager + 256
    long screenMgr = mem.screenAddress();
    long mapAddressStatic = screenMgr + Offsets.SCREEN_MAP;
    long mapAddress = mem.readLong(mapAddressStatic);

    assertEquals(mem.mapAddress(), mapAddress,
        "screen+256 must point to the map object");

    assertEquals(24_000, mem.readInt(mapAddress + Offsets.MAP_WIDTH));
    assertEquals(16_000, mem.readInt(mapAddress + Offsets.MAP_HEIGHT));
    assertEquals(17, mem.readInt(mapAddress + Offsets.MAP_ID));
  }

  @Test
  void mapIdSyncsAcrossTicks() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long mapAddr = mem.readLong(mem.screenAddress() + Offsets.SCREEN_MAP);

    world.mapId = 42;
    mem.tick();
    assertEquals(42, mem.readInt(mapAddr + Offsets.MAP_ID));
  }

  // ---------------------------------------------------------------
  // EntityList read chain
  // ---------------------------------------------------------------

  /**
   * Simulates {@code EntityList.update(address)}:
   * <pre>
   *   vectorAddr = readLong(mapAddress + 40)
   *   // then in refreshEntities():
   *   count = readInt(vectorAddr + 56)
   *   table = readLong(vectorAddr + 48)
   *   for i in 0..count:
   *     atom = readLong(table + 16 + i*8)
   *     entityPtr = atom & ~7
   *     id = readInt(entityPtr + 56)
   * </pre>
   */
  @Test
  void entityListReadChainFindsAllNpcs() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 100, 200);
    world.spawnNpc("Lordakia", 300, 400);
    world.spawnNpc("Saimon", 500, 600);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    // 1. Map → entity vector
    long mapAddr = mem.readLong(mem.screenAddress() + Offsets.SCREEN_MAP);
    long vectorAddr = mem.readLong(mapAddr + Offsets.MAP_ENTITY_LIST);

    // 2. Vector count
    int count = mem.readInt(vectorAddr + Offsets.VECTOR_SIZE);
    assertEquals(3, count);

    // 3. Iterate entities (exactly as EntityList.refreshEntities does)
    long table = mem.readLong(vectorAddr + Offsets.VECTOR_TABLE);
    for (int i = 0; i < count; i++) {
      long atom = mem.readLong(table + Offsets.VECTOR_TABLE_SKIP + (long) i * Offsets.VECTOR_STRIDE);
      long entityPtr = atom & Offsets.ATOM_MASK;
      int id = mem.readInt(entityPtr + Offsets.ENTITY_ID);
      assertTrue(id > 0, "entity[" + i + "] id must be > 0, got " + id);
    }
  }

  /**
   * Simulates entity type detection chain:
   * <pre>
   *   isNpc  = readInt(e + 112) in {0,1}
   *   visible = readInt(e + 116) in {0,1}
   *   flagC  = readInt(e + 120) in {0,1}
   *   flagD  = readInt(e + 124) == 0
   * </pre>
   * isShip() requires all of: id>0, isNpc∈{0,1}, visible∈{0,1}, c∈{0,1}, d==0
   */
  @Test
  void shipDetectionFlagsAreCorrectForNpcs() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 0, 0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);

    int isNpc = mem.readInt(e + Offsets.ENTITY_IS_NPC);
    int visible = mem.readInt(e + Offsets.ENTITY_VISIBLE);
    int c = mem.readInt(e + Offsets.ENTITY_FLAG_C);
    int d = mem.readInt(e + Offsets.ENTITY_FLAG_D);

    assertEquals(1, isNpc, "NPC must have isNpc=1");
    assertEquals(1, visible, "NPC must be visible");
    assertTrue(c == 0 || c == 1, "flagC must be 0 or 1");
    assertEquals(0, d, "flagD must be 0");
  }

  @Test
  void boxDetectionFlagsAreCorrectForBoxes() {
    SimWorld world = new SimWorld();
    world.spawnBox("prometium", 0, 0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);

    // Box: isNpc != 0 and isNpc != 1 → isShip() returns false → classified as box
    int isNpc = mem.readInt(e + Offsets.ENTITY_IS_NPC);
    assertTrue(isNpc != 0 && isNpc != 1,
        "box must have isNpc ∉ {0,1}, got " + isNpc);
  }

  // ---------------------------------------------------------------
  // Entity property read chains (LocationInfo, Health, ShipInfo)
  // ---------------------------------------------------------------

  /**
   * Simulates {@code LocationInfo} read:
   * <pre>
   *   locPtr = readLong(entity + 64)
   *   x = readDouble(locPtr + 32)
   *   y = readDouble(locPtr + 40)
   * </pre>
   */
  @Test
  void locationInfoReadChainMatches() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Lordakia", 7500.5, 3200.0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);
    long locPtr = mem.readLong(e + Offsets.ENTITY_LOCATION);
    assertNotEquals(0, locPtr);

    assertEquals(7500.5, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(3200.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  /**
   * Simulates {@code Health} read (bindable int pattern):
   * <pre>
   *   healthPtr = readLong(entity + 184)
   *   hp      = readInt(healthPtr + 48 + 40)   // field + BINDABLE_VALUE
   *   maxHp   = readInt(healthPtr + 56 + 40)
   *   shield  = readInt(healthPtr + 80 + 40)
   *   maxShield = readInt(healthPtr + 88 + 40)
   * </pre>
   */
  @Test
  void healthReadChainMatches() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Mordon", 0, 0);
    npc.hp = 42_500;
    npc.maxHp = 50_000;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);
    long healthPtr = mem.readLong(e + Offsets.SHIP_HEALTH);
    assertNotEquals(0, healthPtr);

    assertEquals(42_500, mem.readInt(healthPtr + Offsets.HEALTH_HP + Offsets.BINDABLE_VALUE));
    assertEquals(50_000, mem.readInt(healthPtr + Offsets.HEALTH_MAX_HP + Offsets.BINDABLE_VALUE));
  }

  /**
   * Simulates {@code ShipInfo.speed} read:
   * <pre>
   *   shipInfoPtr = readLong(entity + 232)
   *   speed = readInt(shipInfoPtr + 80 + 40)
   * </pre>
   */
  @Test
  void shipInfoSpeedReadChainMatches() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Streuner", 0, 0);
    npc.speed = 450;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);
    long shipInfoPtr = mem.readLong(e + Offsets.SHIP_INFO);
    assertNotEquals(0, shipInfoPtr);

    assertEquals(450, mem.readInt(shipInfoPtr + Offsets.SHIP_INFO_SPEED + Offsets.BINDABLE_VALUE));
  }

  // ---------------------------------------------------------------
  // Hero read chain (HeroManager)
  // ---------------------------------------------------------------

  /**
   * Simulates {@code HeroManager.tick()}:
   * <pre>
   *   staticAddress = screenManager + 240
   *   heroAddress = readLong(staticAddress)
   *   heroId = readInt(heroAddress + 56)
   * </pre>
   */
  @Test
  void heroReadChainMatches() {
    SimWorld world = new SimWorld();
    world.hero.id = 9999;
    world.hero.x = 1234.5;
    world.hero.y = 6789.0;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long screenMgr = mem.screenAddress();
    long heroAddress = mem.readLong(screenMgr + Offsets.SCREEN_HERO);
    assertNotEquals(0, heroAddress);

    assertEquals(9999, mem.readInt(heroAddress + Offsets.ENTITY_ID));

    long locPtr = mem.readLong(heroAddress + Offsets.ENTITY_LOCATION);
    assertEquals(1234.5, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(6789.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);

    long healthPtr = mem.readLong(heroAddress + Offsets.SHIP_HEALTH);
    assertEquals(100_000, mem.readInt(healthPtr + Offsets.HEALTH_HP + Offsets.BINDABLE_VALUE));
    assertEquals(100_000, mem.readInt(healthPtr + Offsets.HEALTH_MAX_HP + Offsets.BINDABLE_VALUE));
    assertEquals(10_000, mem.readInt(healthPtr + Offsets.HEALTH_SHIELD + Offsets.BINDABLE_VALUE));
  }

  @Test
  void heroPositionSyncsAcrossTicks() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long heroAddr = mem.readLong(mem.screenAddress() + Offsets.SCREEN_HERO);
    long locPtr = mem.readLong(heroAddr + Offsets.ENTITY_LOCATION);

    // Move hero
    world.hero.x = 5000;
    world.hero.y = 6000;
    mem.tick();

    assertEquals(5000.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(6000.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  // ---------------------------------------------------------------
  // View bounds chain (MapManager.updateBounds)
  // ---------------------------------------------------------------

  /**
   * Simulates the 2D view bounds read chain:
   * <pre>
   *   viewAddressStatic = screenManager + 216
   *   viewAddress = readLong(viewAddressStatic)
   *   view2d = readLong(viewAddress + 208)
   *   clientWidth  = readInt(view2d + 0xA8)
   *   clientHeight = readInt(view2d + 0xAC)
   *   viewCamera = readLong(view2d + 280)
   *   boundsData = readLong(viewCamera + 112)
   *   boundMaxX = readDouble(boundsData + 112)
   *   boundMaxY = readDouble(boundsData + 120)
   * </pre>
   */
  @Test
  void viewBoundsReadChainMatches() {
    SimWorld world = new SimWorld();
    world.mapWidth = 21_000;
    world.mapHeight = 13_500;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long screenMgr = mem.screenAddress();
    long viewAddrStatic = screenMgr + Offsets.SCREEN_VIEW;
    long viewAddress = mem.readLong(viewAddrStatic);
    assertNotEquals(0, viewAddress, "viewAddress must be non-null");

    long view2d = mem.readLong(viewAddress + 208);
    assertNotEquals(0, view2d, "view2d container must be non-null");

    assertEquals(1920, mem.readInt(view2d + 0xA8), "clientWidth");
    assertEquals(1080, mem.readInt(view2d + 0xAC), "clientHeight");

    long viewCamera = mem.readLong(view2d + 280);
    assertNotEquals(0, viewCamera);

    long boundsData = mem.readLong(viewCamera + 112);
    assertNotEquals(0, boundsData);

    assertEquals(21_000.0, mem.readDouble(boundsData + 112), 0.1, "boundMaxX");
    assertEquals(13_500.0, mem.readDouble(boundsData + 120), 0.1, "boundMaxY");
  }

  // ---------------------------------------------------------------
  // Multi-tick entity lifecycle
  // ---------------------------------------------------------------

  @Test
  void addAndRemoveEntitiesAcrossTicks() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);

    // Tick 1: no entities
    mem.tick();
    assertEquals(0, entityCount(mem));

    // Tick 2: add NPC
    SimWorld.SimNpc npc1 = world.spawnNpc("Streuner", 100, 200);
    mem.tick();
    assertEquals(1, entityCount(mem));
    long id1 = readFirstEntityId(mem);
    assertEquals(npc1.id, id1);

    // Tick 3: add box
    world.spawnBox("prometium", 300, 400);
    mem.tick();
    assertEquals(2, entityCount(mem));

    // Tick 4: remove NPC
    world.removeNpc(npc1);
    mem.tick();
    assertEquals(1, entityCount(mem));
    long remainingId = readFirstEntityId(mem);
    assertNotEquals(npc1.id, remainingId, "removed NPC should not appear");
  }

  @Test
  void npcPositionUpdatesAcrossMultipleTicks() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Lordakia", 1000, 2000);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityAddr = readFirstEntityId_addr(mem);
    long locPtr = mem.readLong(entityAddr + Offsets.ENTITY_LOCATION);
    assertEquals(1000.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);

    // Move NPC
    npc.x = 5555;
    npc.y = 6666;
    mem.tick();

    assertEquals(5555.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(6666.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  @Test
  void npcHealthUpdatesAcrossTicks() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Mordon", 0, 0);
    npc.hp = 10_000;
    npc.maxHp = 10_000;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);
    long healthPtr = mem.readLong(e + Offsets.SHIP_HEALTH);

    assertEquals(10_000, mem.readInt(healthPtr + Offsets.HEALTH_HP + Offsets.BINDABLE_VALUE));

    // Damage NPC
    npc.hp = 3_333;
    mem.tick();

    assertEquals(3_333, mem.readInt(healthPtr + Offsets.HEALTH_HP + Offsets.BINDABLE_VALUE));
  }

  // ---------------------------------------------------------------
  // Mixed entity type detection
  // ---------------------------------------------------------------

  @Test
  void mixedEntitiesHaveCorrectTypeFlags() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 100, 200);
    world.spawnBox("prometium", 300, 400);
    world.spawnNpc("Lordakia", 500, 600);
    world.spawnBox("endurium", 700, 800);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    assertEquals(4, entityCount(mem));

    long table = mem.readLong(
        mem.readLong(mem.readLong(mem.screenAddress() + Offsets.SCREEN_MAP)
            + Offsets.MAP_ENTITY_LIST) + Offsets.VECTOR_TABLE);

    int npcCount = 0, boxCount = 0;
    for (int i = 0; i < 4; i++) {
      long atom = mem.readLong(table + Offsets.VECTOR_TABLE_SKIP + (long) i * Offsets.VECTOR_STRIDE);
      long e = atom & Offsets.ATOM_MASK;
      int isNpc = mem.readInt(e + Offsets.ENTITY_IS_NPC);
      if (isNpc == 1) npcCount++;
      else boxCount++; // isNpc=99 for boxes
    }

    assertEquals(2, npcCount, "must have 2 NPCs");
    assertEquals(2, boxCount, "must have 2 boxes");
  }

  // ---------------------------------------------------------------
  // Container validity check
  // ---------------------------------------------------------------

  @Test
  void entityContainerPointsToMapAddress() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 0, 0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = firstEntity(mem);
    long container = mem.readLong(e + Offsets.ENTITY_CONTAINER);
    assertEquals(mem.mapAddress(), container,
        "entity container must point to the map address for isInvalid() to pass");
  }

  // ---------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------

  private static long firstEntity(FakeMemory mem) {
    long mapAddr = mem.readLong(mem.screenAddress() + Offsets.SCREEN_MAP);
    long vectorAddr = mem.readLong(mapAddr + Offsets.MAP_ENTITY_LIST);
    long table = mem.readLong(vectorAddr + Offsets.VECTOR_TABLE);
    long atom = mem.readLong(table + Offsets.VECTOR_TABLE_SKIP);
    return atom & Offsets.ATOM_MASK;
  }

  private static long readFirstEntityId_addr(FakeMemory mem) {
    return firstEntity(mem);
  }

  private static int entityCount(FakeMemory mem) {
    long mapAddr = mem.readLong(mem.screenAddress() + Offsets.SCREEN_MAP);
    long vectorAddr = mem.readLong(mapAddr + Offsets.MAP_ENTITY_LIST);
    return mem.readInt(vectorAddr + Offsets.VECTOR_SIZE);
  }

  private static long readFirstEntityId(FakeMemory mem) {
    long e = firstEntity(mem);
    return mem.readInt(e + Offsets.ENTITY_ID);
  }
}
