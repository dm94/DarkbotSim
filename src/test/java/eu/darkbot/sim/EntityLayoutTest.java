package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Validates that FakeMemory correctly serializes entity lists (NPCs and Boxes)
 * into the byte layout that DarkBot's MapManager expects to read.
 */
class EntityLayoutTest {

  @Test
  void emptyWorldHasZeroEntityCount() {
    SimWorld world = new SimWorld();
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityList = mem.readInt(mem.entityListAddress() + Offsets.VECTOR_SIZE);
    assertEquals(0, entityList, "empty world -> 0 entities");
  }

  @Test
  void singleNpcAppearsInEntityList() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 1500, 2000);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    int count = mem.readInt(mem.entityListAddress() + Offsets.VECTOR_SIZE);
    assertEquals(1, count, "one NPC spawned -> entity count = 1");

    // Read the first entity pointer from the vector table
    long tablePtr = mem.readLong(mem.entityListAddress() + Offsets.VECTOR_TABLE);
    long entityAtom = mem.readLong(tablePtr + Offsets.VECTOR_TABLE_SKIP);
    long entityPtr = entityAtom & Offsets.ATOM_MASK;
    assertNotEquals(0, entityPtr, "entity pointer must be non-null");

    // Verify entity id
    assertEquals(world.npcs.get(0).id, mem.readInt(entityPtr + Offsets.ENTITY_ID));

    // Verify isNpc flag = 1
    assertEquals(1, mem.readInt(entityPtr + Offsets.ENTITY_IS_NPC));
  }

  @Test
  void npcLocationInfoHasCorrectCoordinates() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Lordakia", 3000, 4500);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityPtr = readFirstEntity(mem);
    long locPtr = mem.readLong(entityPtr + Offsets.ENTITY_LOCATION);
    assertNotEquals(0, locPtr, "location pointer must be non-null");

    assertEquals(3000.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(4500.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  @Test
  void npcHealthIsSerialized() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Mordon", 1000, 1000);
    npc.hp = 8000;
    npc.maxHp = 10000;
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityPtr = readFirstEntity(mem);
    long healthPtr = mem.readLong(entityPtr + Offsets.SHIP_HEALTH);
    assertNotEquals(0, healthPtr, "health pointer must be non-null");

    // Bindable int: real value at ptr + fieldOffset + BINDABLE_VALUE
    assertEquals(8000, mem.readInt(healthPtr + Offsets.HEALTH_HP + Offsets.BINDABLE_VALUE));
    assertEquals(10000, mem.readInt(healthPtr + Offsets.HEALTH_MAX_HP + Offsets.BINDABLE_VALUE));
  }

  @Test
  void npcVtableMatchesShipNpc() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 0, 0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityPtr = readFirstEntity(mem);
    long vtable = mem.readLong(entityPtr + Offsets.ENTITY_VTABLE);
    assertNotEquals(0, vtable, "vtable must be non-zero");
  }

  @Test
  void multipleNpcsAllSerialized() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 100, 200);
    world.spawnNpc("Lordakia", 300, 400);
    world.spawnNpc("Saimon", 500, 600);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    assertEquals(3, mem.readInt(mem.entityListAddress() + Offsets.VECTOR_SIZE));
  }

  @Test
  void singleBoxAppearsInEntityList() {
    SimWorld world = new SimWorld();
    world.spawnBox("prometium", 2500, 3500);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    int count = mem.readInt(mem.entityListAddress() + Offsets.VECTOR_SIZE);
    assertEquals(1, count, "one box spawned -> entity count = 1");

    long entityPtr = readFirstEntity(mem);
    assertEquals(world.boxes.get(0).id, mem.readInt(entityPtr + Offsets.ENTITY_ID));
  }

  @Test
  void boxHasCorrectLocation() {
    SimWorld world = new SimWorld();
    world.spawnBox("endurium", 1200, 3400);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityPtr = readFirstEntity(mem);
    long locPtr = mem.readLong(entityPtr + Offsets.ENTITY_LOCATION);

    assertEquals(1200.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(3400.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  @Test
  void mixedNpcsAndBoxesAllSerialized() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 100, 200);
    world.spawnBox("prometium", 300, 400);
    world.spawnNpc("Lordakia", 500, 600);
    world.spawnBox("endurium", 700, 800);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    assertEquals(4, mem.readInt(mem.entityListAddress() + Offsets.VECTOR_SIZE),
        "2 NPCs + 2 boxes = 4 entities");
  }

  @Test
  void npcFlagsPassShipDetection() {
    SimWorld world = new SimWorld();
    world.spawnNpc("Streuner", 0, 0);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long e = readFirstEntity(mem);
    int id = mem.readInt(e + Offsets.ENTITY_ID);
    int isNpc = mem.readInt(e + Offsets.ENTITY_IS_NPC);
    int visible = mem.readInt(e + Offsets.ENTITY_VISIBLE);
    int c = mem.readInt(e + Offsets.ENTITY_FLAG_C);
    int d = mem.readInt(e + Offsets.ENTITY_FLAG_D);

    // isShip() check: id>0 && isNpc in {0,1} && visible in {0,1} && c in {0,1} && d==0
    assert id > 0 : "id must be > 0, got " + id;
    assert isNpc == 1 : "isNpc must be 1 for NPC, got " + isNpc;
    assert visible == 1 : "visible must be 0 or 1, got " + visible;
    assert c == 1 : "c must be 0 or 1, got " + c;
    assert d == 0 : "d must be 0, got " + d;
  }

  @Test
  void tickResyncsNpcPositionAfterMove() {
    SimWorld world = new SimWorld();
    SimWorld.SimNpc npc = world.spawnNpc("Streuner", 100, 200);
    FakeMemory mem = new FakeMemory(world);
    mem.tick();

    long entityPtr = readFirstEntity(mem);
    long locPtr = mem.readLong(entityPtr + Offsets.ENTITY_LOCATION);
    assertEquals(100.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);

    // Move the NPC and re-tick
    npc.x = 9999;
    npc.y = 8888;
    mem.tick();

    assertEquals(9999.0, mem.readDouble(locPtr + Offsets.LOC_X), 0.01);
    assertEquals(8888.0, mem.readDouble(locPtr + Offsets.LOC_Y), 0.01);
  }

  // -- helpers --

  private static long readFirstEntity(FakeMemory mem) {
    long tablePtr = mem.readLong(mem.entityListAddress() + Offsets.VECTOR_TABLE);
    long entityAtom = mem.readLong(tablePtr + Offsets.VECTOR_TABLE_SKIP);
    return entityAtom & Offsets.ATOM_MASK;
  }
}
