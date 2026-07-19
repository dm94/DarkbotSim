package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import com.github.manolo8.darkbot.core.BotInstaller;
import com.github.manolo8.darkbot.core.api.GameAPI;

/**
 * Replicates exactly what {@link BotInstaller#tryInstall()} does,
 * using the real {@link GameAPI.Memory} and {@link GameAPI.ExtraMemoryReader}
 * method signatures (the multi-offset variants).
 *
 * This test fails with a clear message at the FIRST broken step,
 * so you know exactly which condition BotInstaller fails on.
 */
class BotInstallerContractTest {

    private FakeMemory mem;
    private SimMemory simMem;
    private SimExtraMemory simExtra;

    private void init() {
        SimWorld world = new SimWorld();
        mem = new FakeMemory(world);
        simMem = new SimMemory(mem);
        simExtra = new SimExtraMemory(mem);
    }

    /** Simulates GameAPI.Memory.readLong with offsets (no atom masking). */
    private long readLongChain(long base, int... offsets) {
        long addr = base;
        for (int off : offsets) {
            if (addr < FakeMemory.BASE) return 0;
            addr = mem.readLong(addr + off);
        }
        return addr;
    }

    /** Simulates GameAPI.Memory.readLong with offsets (the GameAPI.Memory default method). */
    private long readLongGM(long address, int... offsets) {
        for (int offset : offsets) {
            if (address < FakeMemory.BASE) return 0;
            address = mem.readLong(address + offset);
        }
        return address;
    }

    /** Simulates GameAPI.Memory.readInt with offsets. */
    private int readIntChain(long base, int... offsets) {
        long addr = base;
        for (int i = 0; i < offsets.length - 1; i++) {
            if (addr < FakeMemory.BASE) return 0;
            addr = mem.readLong(addr + offsets[i]);
        }
        if (addr < FakeMemory.BASE) return 0;
        return mem.readInt(addr + offsets[offsets.length - 1]);
    }

    @Test
    void step01_patternSearch() {
        init();
        long found = mem.searchPattern(Offsets.BYTES_TO_MAIN_APPLICATION);
        assertNotEquals(0, found, "FAIL step 01: pattern not found by searchPattern");
    }

    @Test
    void step02_patternSearchViaMemoryInterface() {
        init();
        // GameAPIImpl.searchPattern(byte...) calls memory.queryBytes(pattern, 1)
        long[] results = simMem.queryBytes(Offsets.BYTES_TO_MAIN_APPLICATION, 1);
        assertEquals(1, results.length, "FAIL step 02: queryBytes must return 1 result");
        long found = results[0];
        assertNotEquals(0, found);
    }

    @Test
    void step03_mainAppAssigned() {
        init();
        long found = mem.searchPattern(Offsets.BYTES_TO_MAIN_APPLICATION);
        long mainApp = found - 228;
        assertTrue(mainApp >= FakeMemory.BASE, "FAIL step 03: mainApp below BASE");
        assertEquals(mem.mainAppAddress(), mainApp);
    }

    @Test
    void step04_sepRead() {
        init();
        int sep = mem.readInt(mem.mainAppAddress() + 4);
        assertEquals(0x1000, sep, "FAIL step 04: SEP must be 0x1000 at mainApp+4");
    }

    @Test
    void step05_settingsPattern() {
        init();
        long s = mem.settingsAddress();
        LongPredicate settingsPred = addr ->
            mem.readInt(addr + 48) == -1 &&
            mem.readInt(addr + 52) == 0 &&
            mem.readInt(addr + 56) == 2 &&
            mem.readInt(addr + 60) == 1;

        boolean matches = settingsPred.test(s);
        assertTrue(matches, "FAIL step 05: settingsAddress must match settingsPattern");
    }

    @Test
    void step06_searchClassClosureSettings() {
        init();
        LongPredicate settingsPred = addr ->
            mem.readInt(addr + 48) == -1 &&
            mem.readInt(addr + 52) == 0 &&
            mem.readInt(addr + 56) == 2 &&
            mem.readInt(addr + 60) == 1;

        long found = simExtra.searchClassClosure(settingsPred);
        assertEquals(mem.settingsAddress(), found, "FAIL step 06: searchClassClosure must find settings");
    }

    @Test
    void step07_mainAddressRead() {
        init();
        // readLong(mainApp + 1344)
        long mainAddr = mem.readLong(mem.mainAppAddress() + 1344);
        assertEquals(mem.mainAddress(), mainAddr, "FAIL step 07: mainApp+1344 must point to mainAddress");
        assertNotEquals(0, mainAddr);
    }

    @Test
    void step08_screenManagerAddress() {
        init();
        // readLong(mainAddress + 504)
        long screen = mem.readLong(mem.mainAddress() + 504);
        assertEquals(mem.screenAddress(), screen, "FAIL step 08: main+504 must point to screenManager");
        assertNotEquals(0, screen);
    }

    @Test
    void step09_guiManagerAddress() {
        init();
        // readLong(mainAddress + 512)
        long gui = mem.readLong(mem.mainAddress() + 512);
        assertNotEquals(0, gui, "FAIL step 09: main+512 (guiManager) must be non-zero");
    }

    @Test
    void step10_scriptObjectVtable() {
        init();
        // readLong(screenManagerAddress)
        long vtable = mem.readLong(mem.screenAddress());
        assertNotEquals(0, vtable, "FAIL step 10: screenManager+0 (SCRIPT_OBJECT_VTABLE) must be non-zero");
    }

    @Test
    void step11_stringObjectVtable() {
        init();
        // readLong(screenManager, 0x10, 0x28, 0x8, 0x3e8, 0x0)
        // Uses GameAPI.Memory.readLong(long, int...) default method
        long strVtable = readLongGM(mem.screenAddress(), 0x10, 0x28, 0x8, 0x3e8, 0x0);
        assertNotEquals(0, strVtable, "FAIL step 11: STRING_OBJECT_VTABLE chain must resolve to non-zero");
        assertEquals(mem.stringVtable(), strVtable, "FAIL step 11: must match our string vtable");
    }

    @Test
    void step12_fullTryInstallSequence() {
        init();
        // This simulates the FULL BotInstaller.tryInstall() method
        
        // 1. API.isValid() -> simHandler.isValid() = true, but after 5s timer.
        //    We skip the timer check and just assume valid for this test.

        // 2. searchPattern(bytesToMainApplication)
        long temp = mem.searchPattern(Offsets.BYTES_TO_MAIN_APPLICATION);
        assertNotEquals(0, temp, "FAIL: searchPattern returned 0");
        long mainAppAddr = temp - 228;
        assertTrue(mainAppAddr >= FakeMemory.BASE);

        // 3. readInt(mainApp + 4) = SEP
        int sep = mem.readInt(mainAppAddr + 4);
        assertEquals(0x1000, sep);

        // 4. searchClassClosure(settingsPattern)
        LongPredicate settingsPred = addr ->
            mem.readInt(addr + 48) == -1 &&
            mem.readInt(addr + 52) == 0 &&
            mem.readInt(addr + 56) == 2 &&
            mem.readInt(addr + 60) == 1;
        temp = simExtra.searchClassClosure(settingsPred);
        assertNotEquals(0, temp, "FAIL: settingsPattern not found");

        // 5. readLong(mainApp + 1344) -> mainAddress
        temp = mem.readLong(mainAppAddr + 1344);
        assertNotEquals(0, temp, "FAIL: mainAddress is null");
        assertEquals(mem.mainAddress(), temp);

        // 6. readLong(main + 504) -> screenManager
        temp = mem.readLong(temp + 504);
        assertNotEquals(0, temp, "FAIL: screenManager is null");
        assertEquals(mem.screenAddress(), temp);

        // 7. readLong(main + 512) -> guiManager
        temp = mem.readLong(mem.mainAddress() + 512);
        assertNotEquals(0, temp, "FAIL: guiManager is null");

        // 8. SCRIPT_OBJECT_VTABLE = readLong(screen)
        long scriptVtable = mem.readLong(mem.screenAddress());
        assertNotEquals(0, scriptVtable, "FAIL: SCRIPT_OBJECT_VTABLE is 0");

        // 9. STRING_OBJECT_VTABLE = readLong(screen, 0x10, 0x28, 0x8, 0x3e8, 0x0)
        long strVtable = readLongGM(mem.screenAddress(), 0x10, 0x28, 0x8, 0x3e8, 0x0);
        assertNotEquals(0, strVtable, "FAIL: STRING_OBJECT_VTABLE chain returned 0");
        assertEquals(mem.stringVtable(), strVtable, "FAIL: STRING_OBJECT_VTABLE mismatch");
    }

    @Test
    void step13_isInvalidStableCheck() {
        init();
        // After tryInstall completes, isInvalid() checks:
        // if (API.isValid() && readLong(mainApp + 1344) == mainAddress)
        long mainApp = mem.mainAppAddress();
        long mainAddr = mem.readLong(mainApp + 1344);
        assertEquals(mem.mainAddress(), mainAddr, "FAIL: mainApp+1344 must equal mainAddress for stable check");
    }

    @Test
    void step14_heroCheckUserData() {
        init();
        // Simulates checkUserData(): readInt(readLong(screen+240) + 56)
        SimWorld world = new SimWorld();
        world.hero.id = 9999;
        // Re-init with this world
        mem = new FakeMemory(world);
        simMem = new SimMemory(mem);
        simExtra = new SimExtraMemory(mem);

        long screen = mem.screenAddress();
        long heroAddr = mem.readLong(screen + 240);
        assertNotEquals(0, heroAddr, "FAIL: screen+240 (heroShip) must be non-zero");
        int heroId = mem.readInt(heroAddr + 56);
        assertEquals(9999, heroId, "FAIL: heroShip+56 must be hero id");
    }
}
