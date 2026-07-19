package eu.darkbot.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import com.github.manolo8.darkbot.core.BotInstaller;

/**
 * In-JVM fake of the Flash process memory. Implements the byte layout that
 * {@link BotInstaller} searches for and serves the offsets the managers read.
 *
 * <p>
 * Backed by a single little-endian {@link ByteBuffer} addressed by absolute
 * {@code long} addresses. Each {@link #tick()} mirrors the {@link SimWorld}
 * state into the layout.
 */
public final class FakeMemory {

  /** First valid address handed out. Anything below is treated as null/0. */
  public static final long BASE = 0x2000_0000L;
  private static final int SIZE = 16 * 1024 * 1024; // 16 MiB is plenty

  private final ByteBuffer buf;
  private final SimWorld world;
  private long nextAddr = BASE;

  // Stable root addresses assigned once at construction.
  private final long patternAddress;
  private final long mainAppAddress;
  private final long mainAddress;
  private final long screenAddress;
  private final long guiAddress;
  private final long connMgrAddress;
  private final long mapAddress;
  private final long settingsAddress;
  private final long heroClosureAddress;
  private final long vtableString;

  // Entity list (Flash Vector) — allocated once, resized each tick.
  private long entityListAddress;
  private long entityListTableAddress;
  private int entityListTableCapacity;

  // Distinct vtable pointers per entity kind (EntityRegistry caches by this).
  private final long vtableShip;
  private final long vtableNpc;
  private final long vtableBox;

  // Shared traits vector used by all entities (first trait for asset ID chain).
  private final long sharedTraitVectorAddress;

  // String storage: maps string object address -> Java string.
  private final Map<Long, String> stringStore = new HashMap<>();
  // Track per-entity addresses so we can reuse across ticks.
  private final Map<Integer, Long> entityAddresses = new HashMap<>();
  // Track per-entity sub-object addresses: key = entityId * 10 + slot.
  private final Map<Long, Long> subObjectAddresses = new HashMap<>();

  public FakeMemory(SimWorld world) {
    this.world = world;
    this.buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);

    // Reserve padding so mainApp = pattern - 228 stays above BASE.
    alloc(Offsets.MAIN_APP_FROM_PATTERN + 64);
    this.patternAddress = alloc(Offsets.BYTES_TO_MAIN_APPLICATION.length + 32);
    this.mainAppAddress = patternAddress - Offsets.MAIN_APP_FROM_PATTERN;
    if (mainAppAddress < BASE)
      throw new IllegalStateException("mainApp below BASE");
    this.mainAddress = alloc(2048);
    this.screenAddress = alloc(1024);
    this.guiAddress = alloc(512);
    this.connMgrAddress = alloc(512);
    this.mapAddress = alloc(1024);
    this.settingsAddress = alloc(256);
    this.heroClosureAddress = alloc(1024);
    this.vtableString = alloc(8);

    // Entity vtable stubs — non-zero, distinct per kind.
    this.vtableShip = alloc(8);
    this.vtableNpc = alloc(8);
    this.vtableBox = alloc(8);

    // Shared traits vector for asset ID chain (a small Flash Vector).
    this.sharedTraitVectorAddress = alloc(64);

    // Entity list — initial capacity for 64 entities.
    this.entityListAddress = alloc(64);
    this.entityListTableAddress = alloc(64 * Offsets.VECTOR_STRIDE + Offsets.VECTOR_TABLE_SKIP);
    this.entityListTableCapacity = 64;
    writeInt(entityListAddress + Offsets.VECTOR_SIZE, 0);
    writeLong(entityListAddress + Offsets.VECTOR_TABLE, entityListTableAddress);

    seedStaticLayout();

    // Register closure candidates AFTER roots are assigned.
    closureCandidates.add(heroClosureAddress);
    closureCandidates.add(settingsAddress);
  }

  // ----- Static layout (only depends on root addresses) ---------------------

  private void seedStaticLayout() {
    writeBytes(patternAddress, Offsets.BYTES_TO_MAIN_APPLICATION);

    writeInt(mainAppAddress + Offsets.MAIN_APP_SEP, 0x1000);
    writeLong(mainAppAddress + Offsets.MAIN_APP_MAIN, mainAddress);
    writeLong(mainAddress + Offsets.MAIN_SCREEN_MANAGER, screenAddress);
    writeLong(mainAddress + Offsets.MAIN_GUI_MANAGER, guiAddress);
    writeLong(mainAddress + Offsets.MAIN_CONNECTION_MANAGER, connMgrAddress);

    // screenManager + 0 -> SCRIPT_OBJECT_VTABLE (any non-zero)
    writeLong(screenAddress, alloc(8));

    // STRING_OBJECT_VTABLE chain: screen -> +0x10 -> +0x28 -> +0x8 -> +0x3e8 ->
    // +0x0
    long s = chain(screenAddress, 0x10, 0x28, 0x8, 0x3e8);
    writeLong(s, vtableString);

    // screen -> +240 -> hero ship; hero ship + 56 = heroId
    long heroShip = alloc(256);
    writeLong(screenAddress + Offsets.SCREEN_HERO, heroShip);
    writeInt(heroShip + Offsets.HERO_ID, world.hero.id);
    writeLong(heroShip + Offsets.HERO_PET, 0); // no pet in v1

    writeLong(screenAddress + Offsets.SCREEN_EVENT, alloc(64));
    writeLong(screenAddress + Offsets.SCREEN_VIEW, alloc(512));
    writeLong(screenAddress + Offsets.SCREEN_MINIMAP, alloc(64));
    writeLong(screenAddress + Offsets.SCREEN_MAP, mapAddress);

    // map object
    writeInt(mapAddress + Offsets.MAP_WIDTH, world.mapWidth);
    writeInt(mapAddress + Offsets.MAP_HEIGHT, world.mapHeight);
    writeInt(mapAddress + Offsets.MAP_ID, world.mapId);
    long targetWrapper = alloc(64);
    writeLong(mapAddress + Offsets.MAP_TARGET_WRAPPER, targetWrapper);
    writeLong(targetWrapper + Offsets.TARGET_ENTITY, 0);

    // settings closure
    writeInt(settingsAddress + Offsets.SETTINGS_48, -1);
    writeInt(settingsAddress + Offsets.SETTINGS_52, 0);
    writeInt(settingsAddress + Offsets.SETTINGS_56, 2);
    writeInt(settingsAddress + Offsets.SETTINGS_60, 1);

    // hero closure
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_HERO_ID, world.hero.id);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_LEVEL, clamp(world.hero.level, 0, 100));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_BOOL, 1);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_VAL, 0);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_CARGO, Math.max(0, world.hero.cargo));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_MAX_CARGO, clamp(world.hero.maxCargo, 100, 99_999));
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /** Allocate {@code bytes} and return its address. */
  private long alloc(int bytes) {
    long addr = nextAddr;
    nextAddr += bytes;
    if (nextAddr - BASE > SIZE)
      throw new OutOfMemoryError("FakeMemory exhausted");
    return addr;
  }

  /**
   * Build a pointer chain writing a fresh pointer at {@code root + offset} each
   * step.
   */
  private long chain(long root, int... offsets) {
    long addr = root;
    for (int off : offsets) {
      long next = alloc(8);
      writeLong(addr + off, next);
      addr = next;
    }
    return addr;
  }

  // ----- Per-tick mirror ----------------------------------------------------

  /** Repopulate the dynamic fields from {@link SimWorld}. */
  public void tick() {
    writeInt(mapAddress + Offsets.MAP_ID, world.mapId);
    writeInt(mapAddress + Offsets.MAP_WIDTH, world.mapWidth);
    writeInt(mapAddress + Offsets.MAP_HEIGHT, world.mapHeight);

    long heroShip = readLong(screenAddress + Offsets.SCREEN_HERO);
    if (heroShip != 0)
      writeInt(heroShip + Offsets.HERO_ID, world.hero.id);

    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_HERO_ID, world.hero.id);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_LEVEL, clamp(world.hero.level, 0, 100));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_CARGO, Math.max(0, world.hero.cargo));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_MAX_CARGO, clamp(world.hero.maxCargo, 100, 99_999));

    // --- Entity list ---
    int totalEntities = world.npcs.size() + world.boxes.size();
    ensureEntityListCapacity(totalEntities);

    stringStore.clear();

    int idx = 0;
    for (SimWorld.SimNpc npc : world.npcs) {
      long entityAddr = getOrCreateEntityAddress(npc.id);
      writeNpcEntity(entityAddr, npc);
      long atomPtr = entityAddr | Offsets.ATOM_OBJECT;
      writeLong(entityListTableAddress + Offsets.VECTOR_TABLE_SKIP + (long) idx * Offsets.VECTOR_STRIDE, atomPtr);
      idx++;
    }
    for (SimWorld.SimBox box : world.boxes) {
      long entityAddr = getOrCreateEntityAddress(box.id);
      writeBoxEntity(entityAddr, box);
      long atomPtr = entityAddr | Offsets.ATOM_OBJECT;
      writeLong(entityListTableAddress + Offsets.VECTOR_TABLE_SKIP + (long) idx * Offsets.VECTOR_STRIDE, atomPtr);
      idx++;
    }
    writeInt(entityListAddress + Offsets.VECTOR_SIZE, totalEntities);
  }

  private void writeNpcEntity(long addr, SimWorld.SimNpc npc) {
    // Entity base
    writeLong(addr + Offsets.ENTITY_VTABLE, vtableNpc);
    writeInt(addr + Offsets.ENTITY_ID, npc.id);
    writeLong(addr + Offsets.ENTITY_CONTAINER, mapAddress);

    // Ship detection flags: id>0, isNpc=1, visible=1, c=1, d=0
    writeInt(addr + Offsets.ENTITY_IS_NPC, 1);
    writeInt(addr + Offsets.ENTITY_VISIBLE, 1);
    writeInt(addr + Offsets.ENTITY_FLAG_C, 1);
    writeInt(addr + Offsets.ENTITY_FLAG_D, 0);

    // LocationInfo
    long loc = allocOrReuse(npc.id, 0, 64);
    writeLong(addr + Offsets.ENTITY_LOCATION, loc);
    writeDouble(loc + Offsets.LOC_X, npc.x);
    writeDouble(loc + Offsets.LOC_Y, npc.y);

    // Asset ID chain for NPC: entity -> traits -> trait -> ... -> string "npc-streuner"
    buildAssetIdChain(addr, "npc-" + npc.name.toLowerCase());

    // Health sub-object
    long health = allocOrReuse(npc.id, 1, 128);
    writeLong(addr + Offsets.SHIP_HEALTH, health);
    writeBindableInt(health + Offsets.HEALTH_HP, (int) npc.hp);
    writeBindableInt(health + Offsets.HEALTH_MAX_HP, (int) npc.maxHp);
    writeBindableInt(health + Offsets.HEALTH_SHIELD, 0);
    writeBindableInt(health + Offsets.HEALTH_MAX_SHIELD, 0);

    // ShipInfo (minimal)
    long shipInfo = allocOrReuse(npc.id, 2, 128);
    writeLong(addr + Offsets.SHIP_INFO, shipInfo);
    writeBindableInt(shipInfo + Offsets.SHIP_INFO_SPEED, (int) npc.speed);

    // Npc ID via ship pointer
    long npcShipPtr = allocOrReuse(npc.id, 3, 128);
    writeLong(addr + Offsets.NPC_SHIP_PTR, npcShipPtr);
    writeInt(npcShipPtr + Offsets.NPC_ID_OFFSET, npc.id);

    // No pet
    writeLong(addr + Offsets.SHIP_PET, 0);
  }

  private void writeBoxEntity(long addr, SimWorld.SimBox box) {
    // Entity base
    writeLong(addr + Offsets.ENTITY_VTABLE, vtableBox);
    writeInt(addr + Offsets.ENTITY_ID, box.id);
    writeLong(addr + Offsets.ENTITY_CONTAINER, mapAddress);

    // Ship detection flags: id>0, isNpc=anything, visible=1
    // Boxes have isNpc that fails isShip check (could be any value not 0/1)
    writeInt(addr + Offsets.ENTITY_IS_NPC, 99);
    writeInt(addr + Offsets.ENTITY_VISIBLE, 1);
    writeInt(addr + Offsets.ENTITY_FLAG_C, 0);
    writeInt(addr + Offsets.ENTITY_FLAG_D, 0);

    // LocationInfo
    long loc = allocOrReuse(box.id, 0, 64);
    writeLong(addr + Offsets.ENTITY_LOCATION, loc);
    writeDouble(loc + Offsets.LOC_X, box.x);
    writeDouble(loc + Offsets.LOC_Y, box.y);

    // Asset ID chain: entity -> traits -> ... -> string "box-prometium"
    buildAssetIdChain(addr, "box-" + box.type.toLowerCase());

    // Box doesn't need Health/ShipInfo/Pet
    writeLong(addr + Offsets.SHIP_HEALTH, 0);
    writeLong(addr + Offsets.SHIP_INFO, 0);
    writeLong(addr + Offsets.SHIP_PLAYER_INFO, 0);
    writeLong(addr + Offsets.SHIP_PET, 0);
    writeLong(addr + Offsets.NPC_SHIP_PTR, 0);
  }

  /**
   * Build the AVM2 asset-ID chain so {@code Offsets.getEntityAssetId()} resolves.
   * <pre>
   * entity +0x30 -> traitVector (FlashVector, first element = trait atom)
   * trait  +0x40 -> intermediate1
   * intermediate1 +0x20 -> intermediate2
   * intermediate2 +0x18 -> stringObject
   * </pre>
   */
  private void buildAssetIdChain(long entityAddr, String assetId) {
    // Write the shared trait vector at entity +0x30
    writeLong(entityAddr + Offsets.ENTITY_TRAITS, sharedTraitVectorAddress);

    // The first trait element in the vector (at table + TABLE_SKIP + 0)
    long traitAtom = alloc(64) | Offsets.ATOM_OBJECT;
    writeLong(sharedTraitVectorAddress + Offsets.VECTOR_SIZE, 1);
    writeLong(entityListTableAddress, traitAtom); // reuse temp; trait vector has its own table
    // Actually, write the trait vector's own table
    long traitTable = alloc(32);
    writeLong(sharedTraitVectorAddress + Offsets.VECTOR_TABLE, traitTable);
    writeLong(sharedTraitVectorAddress + Offsets.VECTOR_SIZE, 1);
    writeLong(traitTable + Offsets.VECTOR_TABLE_SKIP, traitAtom);

    long trait = traitAtom & Offsets.ATOM_MASK;

    // trait +0x40 -> intermediate1
    long inter1 = alloc(64);
    writeLong(trait + 0x40, inter1);

    // inter1 +0x20 -> intermediate2
    long inter2 = alloc(64);
    writeLong(inter1 + 0x20, inter2);

    // inter2 +0x18 -> stringObject (the asset ID string)
    long strObj = allocateStringObject(assetId);
    writeLong(inter2 + 0x18, strObj);
  }

  /**
   * Allocate an AVM2-compatible StringObject in memory.
   * Layout: +0x00=vtable, +0x10=charDataPtr, +0x20=sizeAndFlags.
   */
  private long allocateStringObject(String s) {
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    long strObj = alloc(64);
    long charData = alloc(bytes.length + 8);
    writeBytes(charData, bytes);

    writeLong(strObj, vtableString); // vtable must match STRING_OBJECT_VTABLE
    writeLong(strObj + Offsets.STRING_DATA_PTR, charData);
    // sizeAndFlags: lower32 = length, upper32 = 0 (8-bit static)
    writeLong(strObj + Offsets.STRING_SIZE_FLAGS, (long) bytes.length);

    stringStore.put(strObj, s);
    return strObj;
  }

  /** Write a bindable int: the real value lives at {@code ptr + BINDABLE_VALUE}. */
  private void writeBindableInt(long ptr, int value) {
    writeInt(ptr + Offsets.BINDABLE_VALUE, value);
  }

  // ----- Entity address management ------------------------------------------

  private long getOrCreateEntityAddress(int entityId) {
    return entityAddresses.computeIfAbsent(entityId, id -> alloc(512));
  }

  /**
   * Allocate or reuse a per-entity sub-object. Slot indices: 0=location,
   * 1=health, 2=shipInfo, 3=npcShipPtr.
   */
  private long allocOrReuse(int entityId, int slot, int size) {
    long key = (long) entityId * 10 + slot;
    return subObjectAddresses.computeIfAbsent(key, k -> alloc(size));
  }

  private void ensureEntityListCapacity(int count) {
    if (count <= entityListTableCapacity)
      return;
    int newCap = Math.max(count, entityListTableCapacity * 2);
    long newTable = alloc(newCap * Offsets.VECTOR_STRIDE + Offsets.VECTOR_TABLE_SKIP);
    writeLong(entityListAddress + Offsets.VECTOR_TABLE, newTable);
    entityListTableAddress = newTable;
    entityListTableCapacity = newCap;
  }

  // ----- String store -------------------------------------------------------

  /**
   * Look up a string previously written via {@link #allocateStringObject}.
   * Returns {@code null} if the address is unknown.
   */
  public String readStringByAddress(long address) {
    return stringStore.get(address);
  }

  // ----- Candidates for searchClassClosure ----------------------------------

  private final List<Long> closureCandidates = new ArrayList<>();

  public long searchClassClosure(LongPredicate predicate) {
    if (predicate == null)
      return 0;
    for (long candidate : closureCandidates) {
      if (predicate.test(candidate))
        return candidate;
    }
    return 0;
  }

  // ----- Pattern search -----------------------------------------------------

  public long searchPattern(byte[] pattern) {
    if (pattern == null || pattern.length == 0)
      return 0;
    if (matchesAt(patternAddress, pattern))
      return patternAddress;
    return 0;
  }

  private boolean matchesAt(long addr, byte[] pattern) {
    int start = (int) (addr - BASE);
    if (start < 0 || start + pattern.length > buf.capacity())
      return false;
    for (int i = 0; i < pattern.length; i++) {
      if (buf.get(start + i) != pattern[i])
        return false;
    }
    return true;
  }

  // ----- Read / write primitives --------------------------------------------

  public int readInt(long addr) {
    int i = idx(addr, 4);
    return i < 0 ? 0 : buf.getInt(i);
  }

  public long readLong(long addr) {
    int i = idx(addr, 8);
    return i < 0 ? 0 : buf.getLong(i);
  }

  public double readDouble(long addr) {
    int i = idx(addr, 8);
    return i < 0 ? 0 : buf.getDouble(i);
  }

  public boolean readBoolean(long addr) {
    int i = idx(addr, 1);
    return i >= 0 && buf.get(i) != 0;
  }

  public byte[] readBytes(long addr, int length) {
    byte[] out = new byte[length];
    int i = idx(addr, length);
    if (i >= 0)
      buf.get(i, out);
    return out;
  }

  public void readBytes(long addr, byte[] dst, int length) {
    int i = idx(addr, length);
    if (i >= 0)
      buf.get(i, dst, 0, length);
  }

  public void writeInt(long addr, int v) {
    int i = idx(addr, 4);
    if (i >= 0)
      buf.putInt(i, v);
  }

  public void writeLong(long addr, long v) {
    int i = idx(addr, 8);
    if (i >= 0)
      buf.putLong(i, v);
  }

  public void writeDouble(long addr, double v) {
    int i = idx(addr, 8);
    if (i >= 0)
      buf.putDouble(i, v);
  }

  public void writeBoolean(long addr, boolean v) {
    int i = idx(addr, 1);
    if (i >= 0)
      buf.put(i, v ? (byte) 1 : 0);
  }

  public void writeBytes(long addr, byte... bytes) {
    int i = idx(addr, bytes.length);
    if (i >= 0)
      buf.put(i, bytes);
  }

  public void replaceInt(long addr, int oldV, int newV) {
    if (readInt(addr) == oldV)
      writeInt(addr, newV);
  }

  public void replaceLong(long addr, long oldV, long newV) {
    if (readLong(addr) == oldV)
      writeLong(addr, newV);
  }

  public void replaceDouble(long addr, double oldV, double newV) {
    if (readDouble(addr) == oldV)
      writeDouble(addr, newV);
  }

  public void replaceBoolean(long addr, boolean oldV, boolean newV) {
    if (readBoolean(addr) == oldV)
      writeBoolean(addr, newV);
  }

  private int idx(long addr, int size) {
    if (addr < BASE)
      return -1;
    int i = (int) (addr - BASE);
    return (i + size > buf.capacity()) ? -1 : i;
  }

  // ----- Accessors for tests / adapter --------------------------------------

  public long patternAddress() {
    return patternAddress;
  }

  public long mainAppAddress() {
    return mainAppAddress;
  }

  public long mainAddress() {
    return mainAddress;
  }

  public long screenAddress() {
    return screenAddress;
  }

  public long mapAddress() {
    return mapAddress;
  }

  public long settingsAddress() {
    return settingsAddress;
  }

  public long heroClosureAddress() {
    return heroClosureAddress;
  }

  /** Returns the vtable address used for AVM2 String objects. */
  public long stringVtable() {
    return vtableString;
  }

  /** Returns the entity list (Flash Vector) root address. */
  public long entityListAddress() {
    return entityListAddress;
  }
}
