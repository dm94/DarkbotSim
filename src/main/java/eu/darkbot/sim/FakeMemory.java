package eu.darkbot.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import com.github.manolo8.darkbot.core.BotInstaller;

/**
 * In-JVM fake of the Flash process memory. Implements the byte layout that
 * {@link BotInstaller} expects and serves the offsets the managers read.
 *
 * <p>
 * Backed by a single little-endian {@link ByteBuffer} addressed by absolute
 * {@code long} addresses. Each {@link #tick()} mirrors the {@link SimWorld}
 * state into the layout.
 *
 * <h3>Bugs fixed in this version:</h3>
 * <ul>
 *   <li><b>Entity list linked to map:</b> {@code mapAddress + MAP_ENTITY_LIST}
 *       now points to the entity vector so {@code EntityList.update(address)}
 *       can discover entities.</li>
 *   <li><b>Hero sub-objects:</b> LocationInfo, Health, ShipInfo, traits, and
 *       entity flags are set up for the hero ship so DarkBot's managers
 *       can read position, hp, shield, speed.</li>
 *   <li><b>View bounds:</b> 2D view container chain initialized so
 *       {@code MapManager.updateBounds()} doesn't NPE on
 *       {@code readObjectName}.</li>
 *   <li><b>String object chain:</b> Matches the indirection that
 *       {@code Offsets.getTraitAssetId()} expects
 *       ({@code strObj +0x8 → A → A+0x10 → B → B+0x18 → rawStr}).</li>
 *   <li><b>Per-entity trait vectors:</b> Each entity gets its own traits
 *       vector so box type detection works correctly per-entity.</li>
 *   <li><b>Hero position sync:</b> {@code tick()} now writes hero position,
 *       health, and speed into memory so DarkBot sees movement.</li>
 * </ul>
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

  // Hero ship sub-object addresses (updated every tick).
  private final long heroShipAddress;
  private final long heroLocationAddress;
  private final long heroHealthAddress;
  private final long heroShipInfoAddress;

  // String storage: maps raw string object address -> Java string.
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
    this.screenAddress = alloc(2048); // larger: needs room for view bounds chain
    this.guiAddress = alloc(512);
    this.connMgrAddress = alloc(512);
    this.mapAddress = alloc(1024);
    this.settingsAddress = alloc(512); // larger: settings manager reads more fields
    this.heroClosureAddress = alloc(1024);
    this.vtableString = alloc(64); // larger: needs composite at +8

    // Entity vtable stubs — non-zero, distinct per kind.
    this.vtableShip = alloc(64);
    this.vtableNpc = alloc(64);
    this.vtableBox = alloc(64);

    // Entity list — initial capacity for 64 entities.
    this.entityListAddress = alloc(64);
    this.entityListTableAddress = alloc(64 * Offsets.VECTOR_STRIDE + Offsets.VECTOR_TABLE_SKIP);
    this.entityListTableCapacity = 64;
    writeInt(entityListAddress + Offsets.VECTOR_SIZE, 0);
    writeLong(entityListAddress + Offsets.VECTOR_TABLE, entityListTableAddress);

    // Pre-allocate hero sub-objects (stable addresses).
    this.heroLocationAddress = alloc(128);
    this.heroHealthAddress = alloc(256);
    this.heroShipInfoAddress = alloc(256);

    // Hero ship object (256 bytes for all entity + ship fields).
    this.heroShipAddress = alloc(512);
    writeLong(screenAddress + Offsets.SCREEN_HERO, heroShipAddress);

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

    // STRING_OBJECT_VTABLE chain: screen -> +0x10 -> +0x28 -> +0x8 -> +0x3e8 -> +0x0
    long s = chain(screenAddress, 0x10, 0x28, 0x8, 0x3e8);
    writeLong(s, vtableString);

    // -----------------------------------------------------------------------
    // Hero ship — full entity + ship layout so DarkBot managers can read it.
    // -----------------------------------------------------------------------
    long h = heroShipAddress;

    // Entity base fields
    writeLong(h + Offsets.ENTITY_VTABLE, vtableShip);
    writeInt(h + Offsets.ENTITY_ID, world.hero.id);
    writeLong(h + Offsets.ENTITY_CONTAINER, mapAddress); // needed for isInvalid() check
    writeInt(h + Offsets.ENTITY_IS_NPC, 0);              // hero is NOT an NPC
    writeInt(h + Offsets.ENTITY_VISIBLE, 1);
    writeInt(h + Offsets.ENTITY_FLAG_C, 1);
    writeInt(h + Offsets.ENTITY_FLAG_D, 0);

    // Traits — empty vector (hero doesn't need asset ID detection)
    long heroTraitsVec = alloc(64);
    writeLong(h + Offsets.ENTITY_TRAITS, heroTraitsVec);
    writeLong(heroTraitsVec + Offsets.VECTOR_SIZE, 0);
    writeLong(heroTraitsVec + Offsets.VECTOR_TABLE, alloc(16));

    // LocationInfo
    writeLong(h + Offsets.ENTITY_LOCATION, heroLocationAddress);
    writeDouble(heroLocationAddress + Offsets.LOC_X, world.hero.x);
    writeDouble(heroLocationAddress + Offsets.LOC_Y, world.hero.y);

    // Health
    writeLong(h + Offsets.SHIP_HEALTH, heroHealthAddress);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_HP, (int) world.hero.hp);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_MAX_HP, (int) world.hero.maxHp);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_SHIELD, (int) world.hero.shield);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_MAX_SHIELD, (int) world.hero.maxShield);

    // ShipInfo
    writeLong(h + Offsets.SHIP_INFO, heroShipInfoAddress);
    writeBindableInt(heroShipInfoAddress + Offsets.SHIP_INFO_SPEED, (int) world.hero.speed);

    // PlayerInfo (minimal)
    writeLong(h + Offsets.SHIP_PLAYER_INFO, alloc(128));

    // Pet
    writeLong(h + Offsets.SHIP_PET, 0);

    // -----------------------------------------------------------------------
    // screenManager sub-addresses
    // -----------------------------------------------------------------------
    writeLong(screenAddress + Offsets.SCREEN_EVENT, alloc(256));
    writeLong(screenAddress + Offsets.SCREEN_VIEW, alloc(1024));
    writeLong(screenAddress + Offsets.SCREEN_MINIMAP, alloc(256));
    writeLong(screenAddress + Offsets.SCREEN_MAP, mapAddress);

    // -----------------------------------------------------------------------
    // View bounds — 2D mode chain for MapManager.updateBounds()
    //
    // MapManager reads:
    //   viewAddressStatic = screenManager + 216
    //   viewAddress = readLong(viewAddressStatic)
    //   is3DView = !is2DForced() && readObjectName(readLong(viewAddr + 208)).contains("HUD")
    //   boundsAddress = readLong(viewAddress + 208)  // 2D path
    //   clientWidth = readInt(boundsAddress + 0xA8)
    //   clientHeight = readInt(boundsAddress + 0xAC)
    //   updated = readLong(readLong(boundsAddress + 280) + 112)
    //   boundX/Y/MaxX/MaxY = readDouble(updated + 80/88/112/120)
    //
    // readObjectName(obj) = readString(obj, 0x10, 0x28, 0x90)
    //   chain: obj+0x10 → A, A+0x28 → B, B+0x90 → stringObject
    //   then readString(stringObject) goes through SimExtraMemory
    // -----------------------------------------------------------------------
    setupViewBounds();

    // -----------------------------------------------------------------------
    // Map object
    // -----------------------------------------------------------------------
    writeInt(mapAddress + Offsets.MAP_WIDTH, world.mapWidth);
    writeInt(mapAddress + Offsets.MAP_HEIGHT, world.mapHeight);
    writeInt(mapAddress + Offsets.MAP_ID, world.mapId);

    // *** CRITICAL FIX: link entity list to map ***
    // EntityList.update(address) reads API.readLong(address + 40) for the vector.
    writeLong(mapAddress + Offsets.MAP_ENTITY_LIST, entityListAddress);

    long targetWrapper = alloc(64);
    writeLong(mapAddress + Offsets.MAP_TARGET_WRAPPER, targetWrapper);
    writeLong(targetWrapper + Offsets.TARGET_ENTITY, 0);

    // -----------------------------------------------------------------------
    // Settings closure (must match BotInstaller.settingsPattern)
    // -----------------------------------------------------------------------
    writeInt(settingsAddress + Offsets.SETTINGS_48, -1);
    writeInt(settingsAddress + Offsets.SETTINGS_52, 0);
    writeInt(settingsAddress + Offsets.SETTINGS_56, 2);
    writeInt(settingsAddress + Offsets.SETTINGS_60, 1);

    // -----------------------------------------------------------------------
    // Hero closure (must match BotInstaller.checkUserData predicate)
    // -----------------------------------------------------------------------
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_HERO_ID, world.hero.id);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_LEVEL, clamp(world.hero.level, 0, 100));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_BOOL, 1);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_VAL, 0);
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_CARGO, Math.max(0, world.hero.cargo));
    writeInt(heroClosureAddress + Offsets.HERO_CLOSURE_MAX_CARGO, clamp(world.hero.maxCargo, 100, 99_999));
  }

  /**
   * Set up the 2D view bounds chain so MapManager.updateBounds() works.
   *
   * <pre>
   * viewObj (screen + 216)
   *   +208 → view2dContainer
   *          +0x10 → nameChainA
   *                   +0x28 → nameChainB
   *                            +0x90 → rawString "2D"  (readObjectName)
   *          +0xA8 = clientWidth
   *          +0xAC = clientHeight
   *          +280 → viewCamera
   *                   +112 → viewBoundsData
   *                            +80  = boundX  (0.0)
   *                            +88  = boundY  (0.0)
   *                            +96  = rightTop.x (mapWidth)
   *                            +104 = rightTop.y (0.0)
   *                            +112 = boundMaxX (mapWidth)
   *                            +120 = boundMaxY (mapHeight)
   *                            +128 = leftBot.x (0.0)
   *                            +136 = leftBot.y (mapHeight)
   * </pre>
   */
  private void setupViewBounds() {
    long viewObj = readLong(screenAddress + Offsets.SCREEN_VIEW);
    if (viewObj == 0) return;

    // 2D view container
    long view2d = alloc(512);
    writeLong(viewObj + 208, view2d);

    // --- readObjectName(view2d) chain: view2d → A → B → rawStrObj ---
    // readString(view2d, 0x10, 0x28, 0x90)
    long nameChainA = alloc(128);
    writeLong(view2d + 0x10, nameChainA);

    long nameChainB = alloc(128);
    writeLong(nameChainA + 0x28, nameChainB);

    long nameRawStr = allocateRawString("2D");
    writeLong(nameChainB + 0x90, nameRawStr);

    // --- View2d container fields for bounds ---
    writeInt(view2d + 0xA8, 1920);  // clientWidth
    writeInt(view2d + 0xAC, 1080);  // clientHeight

    // viewCamera at view2d + 280
    long viewCamera = alloc(256);
    writeLong(view2d + 280, viewCamera);

    // viewBoundsData at viewCamera + 112
    long boundsData = alloc(256);
    writeLong(viewCamera + 112, boundsData);

    // Corner coordinates for the polygon (2D view bounds)
    writeDouble(boundsData + 80,  0.0);                    // leftTop.x
    writeDouble(boundsData + 88,  0.0);                    // leftTop.y
    writeDouble(boundsData + 96,  (double) world.mapWidth); // rightTop.x
    writeDouble(boundsData + 104, 0.0);                    // rightTop.y
    writeDouble(boundsData + 112, (double) world.mapWidth); // rightBot.x (= boundMaxX)
    writeDouble(boundsData + 120, (double) world.mapHeight);// rightBot.y (= boundMaxY)
    writeDouble(boundsData + 128, 0.0);                    // leftBot.x
    writeDouble(boundsData + 136, (double) world.mapHeight);// leftBot.y
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * Allocate {@code bytes} and return its address, 8-byte aligned.
   *
   * <p>AVM2 objects are always stored at addresses where the low 3 bits are
   * zero — those bits are used as atom type tags (e.g. {@code ATOM_OBJECT}).
   * When DarkBot strips the tag via {@code ptr & ATOM_MASK}, it must recover
   * the original pointer exactly. Non-aligned addresses would corrupt every
   * read after an atom-mask round-trip.
   */
  private long alloc(int bytes) {
    long addr = (nextAddr + 7) & ~7L;
    long end = addr + bytes;
    if (end - BASE > SIZE)
      throw new OutOfMemoryError("FakeMemory exhausted");
    nextAddr = end;
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
    // --- Map ---
    writeInt(mapAddress + Offsets.MAP_ID, world.mapId);
    writeInt(mapAddress + Offsets.MAP_WIDTH, world.mapWidth);
    writeInt(mapAddress + Offsets.MAP_HEIGHT, world.mapHeight);

    // --- Hero ship identity ---
    writeInt(heroShipAddress + Offsets.ENTITY_ID, world.hero.id);
    writeLong(heroShipAddress + Offsets.ENTITY_CONTAINER, mapAddress);

    // --- Hero position (LocationInfo) ---
    writeDouble(heroLocationAddress + Offsets.LOC_X, world.hero.x);
    writeDouble(heroLocationAddress + Offsets.LOC_Y, world.hero.y);

    // --- Hero health ---
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_HP, (int) world.hero.hp);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_MAX_HP, (int) world.hero.maxHp);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_SHIELD, (int) world.hero.shield);
    writeBindableInt(heroHealthAddress + Offsets.HEALTH_MAX_SHIELD, (int) world.hero.maxShield);

    // --- Hero speed ---
    writeBindableInt(heroShipInfoAddress + Offsets.SHIP_INFO_SPEED, (int) world.hero.speed);

    // --- Hero closure ---
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
    long loc = allocOrReuse(npc.id, 0, 128);
    writeLong(addr + Offsets.ENTITY_LOCATION, loc);
    writeDouble(loc + Offsets.LOC_X, npc.x);
    writeDouble(loc + Offsets.LOC_Y, npc.y);

    // Per-entity asset ID chain: entity -> traits -> trait -> ... -> string "npc-streuner"
    buildAssetIdChain(addr, "npc-" + npc.name.toLowerCase());

    // Health sub-object
    long health = allocOrReuse(npc.id, 1, 256);
    writeLong(addr + Offsets.SHIP_HEALTH, health);
    writeBindableInt(health + Offsets.HEALTH_HP, (int) npc.hp);
    writeBindableInt(health + Offsets.HEALTH_MAX_HP, (int) npc.maxHp);
    writeBindableInt(health + Offsets.HEALTH_SHIELD, 0);
    writeBindableInt(health + Offsets.HEALTH_MAX_SHIELD, 0);

    // ShipInfo (minimal)
    long shipInfo = allocOrReuse(npc.id, 2, 256);
    writeLong(addr + Offsets.SHIP_INFO, shipInfo);
    writeBindableInt(shipInfo + Offsets.SHIP_INFO_SPEED, (int) npc.speed);

    // Npc ID via ship pointer
    long npcShipPtr = allocOrReuse(npc.id, 3, 128);
    writeLong(addr + Offsets.NPC_SHIP_PTR, npcShipPtr);
    writeInt(npcShipPtr + Offsets.NPC_ID_OFFSET, npc.id);

    // No pet
    writeLong(addr + Offsets.SHIP_PET, 0);

    // PlayerInfo
    writeLong(addr + Offsets.SHIP_PLAYER_INFO, 0);
  }

  private void writeBoxEntity(long addr, SimWorld.SimBox box) {
    // Entity base
    writeLong(addr + Offsets.ENTITY_VTABLE, vtableBox);
    writeInt(addr + Offsets.ENTITY_ID, box.id);
    writeLong(addr + Offsets.ENTITY_CONTAINER, mapAddress);

    // Ship detection: isNpc=99 ensures isShip() check fails → classified as box
    writeInt(addr + Offsets.ENTITY_IS_NPC, 99);
    writeInt(addr + Offsets.ENTITY_VISIBLE, 1);
    writeInt(addr + Offsets.ENTITY_FLAG_C, 0);
    writeInt(addr + Offsets.ENTITY_FLAG_D, 0);

    // LocationInfo
    long loc = allocOrReuse(box.id, 0, 128);
    writeLong(addr + Offsets.ENTITY_LOCATION, loc);
    writeDouble(loc + Offsets.LOC_X, box.x);
    writeDouble(loc + Offsets.LOC_Y, box.y);

    // Per-entity asset ID chain: entity -> traits -> ... -> string "box-prometium"
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
   *
   * <p>Each entity gets its OWN trait vector (not shared) so that per-entity
   * type detection works correctly (e.g. differentiating "box-prometium" from
   * "box-endurium").
   *
   * <pre>
   * getEntityAssetId(entity):
   *   readLong(entity + 0x30)           → traitVector
   *   readLong(traitVector + 0x30)      → traitTable  (VECTOR_TABLE)
   *   readLong(traitTable + 0x10)       → traitAtom   (VECTOR_TABLE_SKIP)
   *   traitAtom &amp; ATOM_MASK            → trait
   *
   * getTraitAssetId(trait):
   *   readLong(trait + 0x40)            → inter1
   *   readLong(inter1 + 0x20)           → inter2
   *   readLong(inter2 + 0x18)           → wrapperStr
   *   readString(wrapperStr, 0x8, 0x10, 0x18):
   *     readLong(wrapperStr + 0x8)      → A
   *     readLong(A + 0x10)              → B
   *     readLong(B + 0x18)              → rawStrObj  (vtable + charData)
   *     readString(rawStrObj)           → "npc-streuner"
   * </pre>
   */
  private void buildAssetIdChain(long entityAddr, String assetId) {
    // Per-entity trait vector
    long traitVector = alloc(64);
    writeLong(entityAddr + Offsets.ENTITY_TRAITS, traitVector);

    // Trait vector: size=1, table → traitTable
    long traitTable = alloc(64);
    writeLong(traitVector + Offsets.VECTOR_TABLE, traitTable);
    writeLong(traitVector + Offsets.VECTOR_SIZE, 1);

    // The single trait element (atom-tagged)
    long traitObj = alloc(128);
    long traitAtom = traitObj | Offsets.ATOM_OBJECT;
    writeLong(traitTable + Offsets.VECTOR_TABLE_SKIP, traitAtom);

    // trait +0x40 → intermediate1
    long inter1 = alloc(64);
    writeLong(traitObj + 0x40, inter1);

    // inter1 +0x20 → intermediate2
    long inter2 = alloc(64);
    writeLong(inter1 + 0x20, inter2);

    // inter2 +0x18 → wrapper string (the chain entry point)
    long wrapperStr = allocateStringObject(assetId);
    writeLong(inter2 + 0x18, wrapperStr);
  }

  /**
   * Allocate a wrapper string object that {@code getTraitAssetId} can resolve.
   *
   * <p>The chain from {@code readString(wrapper, 0x8, 0x10, 0x18)} expects:
   * <pre>
   *   wrapper +0x08 → A
   *   A +0x10       → B
   *   B +0x18       → rawStrObj (vtable, charData, sizeAndFlags)
   * </pre>
   *
   * <p>{@code readString(rawStrObj)} goes through {@code SimExtraMemory}
   * which checks vtable == STRING_OBJECT_VTABLE and looks up stringStore.
   *
   * @return the wrapper string object address (write to inter2 +0x18)
   */
  private long allocateStringObject(String s) {
    long rawStr = allocateRawString(s);

    // Build the 3-level indirection chain
    long b = alloc(64);
    writeLong(b + 0x18, rawStr);

    long a = alloc(64);
    writeLong(a + 0x10, b);

    long wrapper = alloc(64);
    writeLong(wrapper + 0x08, a);

    return wrapper;
  }

  /**
   * Allocate a raw AVM2 string object readable by {@code SimExtraMemory.readString}.
   *
   * <pre>
   *   +0x00 = vtable (must match STRING_OBJECT_VTABLE)
   *   +0x08 = composite/refcount (> 0 for isScriptableObjectValid)
   *   +0x10 = charData pointer
   *   +0x20 = sizeAndFlags (lower32 = byte length, upper32 = 0 for ISO-8859-1)
   * </pre>
   *
   * @return the raw string object address
   */
  private long allocateRawString(String s) {
    byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
    long rawStr = alloc(64);
    long charData = alloc(bytes.length + 8);
    writeBytes(charData, bytes);

    writeLong(rawStr, vtableString);                                    // +0x00 vtable
    writeInt(rawStr + 8, 1);                                           // +0x08 composite > 0
    writeLong(rawStr + Offsets.STRING_DATA_PTR, charData);              // +0x10 char data
    writeLong(rawStr + Offsets.STRING_SIZE_FLAGS, (long) bytes.length); // +0x20 sizeAndFlags

    stringStore.put(rawStr, s);
    return rawStr;
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
   * Look up a string previously written via {@link #allocateRawString}.
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

  /** Returns the hero ship entity address. */
  public long heroShipAddress() {
    return heroShipAddress;
  }
}
