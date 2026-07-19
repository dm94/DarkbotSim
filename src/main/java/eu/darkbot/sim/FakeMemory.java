package eu.darkbot.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
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
}
