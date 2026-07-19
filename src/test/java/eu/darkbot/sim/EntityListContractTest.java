package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifies that the FakeMemory exposes the entity list correctly so DarkBot's
 * EntityList can discover NPCs and boxes via the FlashVector at map+40.
 *
 * Replicates the read pattern of:
 * EntityList.update(mapAddress) -> readLong(mapAddress + 40) -> FlashVector
 * FlashVector.update() reads size at +56, table at +48, table_skip=16
 */
class EntityListContractTest {

    @Test
    void emptyWorldHasNoEntities() {
        FakeMemory mem = new FakeMemory(new SimWorld());
        mem.tick(); // entity list is populated on tick(), not at construction
        long vec = mem.readLong(mem.mapAddress() + 40);
        assertNotEquals(0, vec, "entity vector must exist at map+40");
        int size = mem.readInt(vec + 56);
        assertEquals(0, size, "empty world should expose 0 entities");
    }

    @Test
    void npcsAndBoxesAreListedAsEntities() {
        SimWorld world = new SimWorld();
        world.spawnNpc("streuner", 1500, 2000);
        world.spawnNpc("lordakia", 2000, 1800);
        world.spawnBox("prometium", 1200, 2050);

        FakeMemory mem = new FakeMemory(world);
        mem.tick(); // entity list is populated on tick(), not at construction

        long vec = mem.readLong(mem.mapAddress() + 40);
        int size = mem.readInt(vec + 56);
        assertEquals(3, size, "expected 3 entities (2 npcs + 1 box)");

        long table = mem.readLong(vec + 48);
        for (int i = 0; i < size; i++) {
            long atom = mem.readLong(table + 16 + (long) i * 8);
            long entityPtr = atom & 0xFFFFFFFFFFFFFFF8L; // ATOM_MASK
            assertNotEquals(0, entityPtr, "entity " + i + " pointer is 0");
            int id = mem.readInt(entityPtr + 56);
            assertTrue(id > 0, "entity " + i + " id must be positive, got " + id);
        }
    }

    @Test
    void bundledScenarioLoads() {
        // Sanity check that the bundled scenario is loadable via the classloader.
        try (var is = SimulatorAdapter.class.getResourceAsStream("/scenarios/basic_farm.json")) {
            assertNotNull(is, "bundled scenario must be on the classpath");
        } catch (Exception e) {
            fail("could not read bundled scenario: " + e.getMessage());
        }
    }
}
