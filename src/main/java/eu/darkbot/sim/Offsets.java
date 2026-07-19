package eu.darkbot.sim;

import java.nio.charset.StandardCharsets;

/**
 * Memory layout contract that DarkBot's {@code BotInstaller} expects.
 *
 * <p>
 * Anchored to a specific DarkBot commit. If {@code BotInstaller} offsets
 * change,
 * update them here and bump {@link #LAYOUT_VERSION}.
 */
public final class Offsets {

  /**
   * Bump whenever the layout below is adjusted to match a new DarkBot version.
   */
  public static final String LAYOUT_VERSION = "darkbot 1.131.7 @ be4d4eab";

  private Offsets() {
  }

  // ----- BotInstaller seeds -------------------------------------------------

  /** Pattern searched by {@code BotInstaller.bytesToMainApplication}. */
  public static final byte[] BYTES_TO_MAIN_APPLICATION = new byte[] {
      1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0,
      0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0
  };

  /** Offset subtracted from the pattern hit to get mainApplicationAddress. */
  public static final int MAIN_APP_FROM_PATTERN = 228;

  /** mainApplicationAddress + 4 -> SEP (pointer separator). */
  public static final int MAIN_APP_SEP = 4;
  /** mainApplicationAddress + 1344 -> mainAddress. */
  public static final int MAIN_APP_MAIN = 1344;
  /** mainAddress + 504 -> screenManagerAddress. */
  public static final int MAIN_SCREEN_MANAGER = 504;
  /** mainAddress + 512 -> guiManagerAddress. */
  public static final int MAIN_GUI_MANAGER = 512;
  /** mainAddress + 560 -> connectionManagerAddress (lazy-filled at runtime). */
  public static final int MAIN_CONNECTION_MANAGER = 560;
  /**
   * mainApp + 1344 must equal mainAddress for the bot to consider itself valid.
   */
  public static final int MAIN_APP_MAIN_VERIFY = 1344;

  // ----- Screen manager derived addresses -----------------------------------
  // MapManager.install: screenManagerAddress + X
  public static final int SCREEN_EVENT = 200; // eventAddress
  public static final int SCREEN_VIEW = 216; // viewAddressStatic
  public static final int SCREEN_MINIMAP = 224; // minimapAddressStatic
  public static final int SCREEN_HERO = 240; // heroManager staticAddress
  public static final int SCREEN_MAP = 256; // mapAddressStatic

  // ----- Map object (read at mapAddressStatic) ------------------------------
  public static final int MAP_WIDTH = 76;
  public static final int MAP_HEIGHT = 80;
  public static final int MAP_ID = 84;
  /** mapAddress + 128 -> targetWrapper, targetWrapper + 40 -> target address. */
  public static final int MAP_TARGET_WRAPPER = 128;
  public static final int TARGET_ENTITY = 40;

  // ----- Hero object (read at screenManager + 240) --------------------------
  public static final int HERO_ID = 56;
  public static final int HERO_PET = 176;

  // ----- checkUserData closure predicate ------------------------------------
  public static final int HERO_CLOSURE_HERO_ID = 0x30;
  public static final int HERO_CLOSURE_LEVEL = 0x34;
  public static final int HERO_CLOSURE_BOOL = 0x3c;
  public static final int HERO_CLOSURE_VAL = 0x40;
  public static final int HERO_CLOSURE_CARGO = 0x148;
  public static final int HERO_CLOSURE_MAX_CARGO = 0x150;

  // ----- Settings closure predicate -----------------------------------------
  public static final int SETTINGS_48 = 48; // == -1
  public static final int SETTINGS_52 = 52; // == 0
  public static final int SETTINGS_56 = 56; // == 2
  public static final int SETTINGS_60 = 60; // == 1

  // ----- Entity list (Flash Vector at mapAddress + 40) -----------------------
  /** mapAddress + MAP_ENTITY_LIST -> entityListPtr (Flash Vector object). */
  public static final int MAP_ENTITY_LIST = 40;

  // ----- Flash Vector layout ------------------------------------------------
  /** Vector + VECTOR_SIZE -> int element count. */
  public static final int VECTOR_SIZE = 56;
  /** Vector + VECTOR_TABLE -> long pointer to element table. */
  public static final int VECTOR_TABLE = 48;
  /** Byte skip before first element in the table (AVM2 vtable header). */
  public static final int VECTOR_TABLE_SKIP = 16;
  /** Stride between consecutive elements. */
  public static final int VECTOR_STRIDE = 8;
  /** AVM2 atom mask: clears the 3-bit type tag. */
  public static final long ATOM_MASK = ~0b111L;

  // ----- Entity base fields (offsets from entityPtr) -------------------------
  /** entity + ENTITY_VTABLE -> raw vtable/class-ptr (used for type caching). */
  public static final int ENTITY_VTABLE = 0x10;
  /** entity + ENTITY_TRAITS -> long pointer to traits FlashVector. */
  public static final int ENTITY_TRAITS = 0x30;
  /** entity + ENTITY_ID -> int entity id. */
  public static final int ENTITY_ID = 56;
  /** entity + ENTITY_LOCATION -> long pointer to LocationInfo. */
  public static final int ENTITY_LOCATION = 64;
  /** entity + ENTITY_CONTAINER -> long map address (validity check). */
  public static final int ENTITY_CONTAINER = 96;
  /** entity + ENTITY_IS_NPC -> int flag: 1=npc, 0=player. */
  public static final int ENTITY_IS_NPC = 112;
  /** entity + ENTITY_VISIBLE -> int flag: 0 or 1. */
  public static final int ENTITY_VISIBLE = 116;
  /** entity + ENTITY_FLAG_C -> int flag: 0 or 1. */
  public static final int ENTITY_FLAG_C = 120;
  /** entity + ENTITY_FLAG_D -> int flag: must be 0 for ships. */
  public static final int ENTITY_FLAG_D = 124;

  // ----- Ship sub-objects (offsets from entityPtr) ---------------------------
  /** entity + SHIP_HEALTH -> long pointer to Health. */
  public static final int SHIP_HEALTH = 184;
  /** entity + SHIP_INFO -> long pointer to ShipInfo. */
  public static final int SHIP_INFO = 232;
  /** entity + SHIP_PLAYER_INFO -> long pointer to PlayerInfo. */
  public static final int SHIP_PLAYER_INFO = 248;
  /** entity + SHIP_PET -> long pointer to pet (0 for non-hero). */
  public static final int SHIP_PET = 176;

  // ----- Npc extra fields ---------------------------------------------------
  /** entity + NPC_SHIP_PTR -> long -> +80 = npcId int. */
  public static final int NPC_SHIP_PTR = 192;
  public static final int NPC_ID_OFFSET = 80;

  // ----- LocationInfo -------------------------------------------------------
  /** LocationInfo + LOC_X -> double x. */
  public static final int LOC_X = 32;
  /** LocationInfo + LOC_Y -> double y. */
  public static final int LOC_Y = 40;

  // ----- Health (bindable ints, real value at ptr + offset + BINDABLE_VALUE) -
  public static final int HEALTH_HP = 48;
  public static final int HEALTH_MAX_HP = 56;
  public static final int HEALTH_SHIELD = 80;
  public static final int HEALTH_MAX_SHIELD = 88;
  /** Bindable value skip: the real int is at ptr + fieldOffset + BINDABLE_VALUE. */
  public static final int BINDABLE_VALUE = 40;

  // ----- ShipInfo -----------------------------------------------------------
  public static final int SHIP_INFO_SPEED = 80;

  // ----- Box ----------------------------------------------------------------
  /** entity + BOX_HASH -> pointer to hash string (unused in v1). */
  public static final int BOX_HASH = 160;

  // ----- AVM2 String object layout ------------------------------------------
  /** String + 0x10 -> pointer to raw char data. */
  public static final int STRING_DATA_PTR = 0x10;
  /** String + 0x20 -> sizeAndFlags (lower32=length, upper32=flags). */
  public static final int STRING_SIZE_FLAGS = 0x20;

  // ----- AVM2 Atom type tags ------------------------------------------------
  public static final long ATOM_OBJECT = 0b001;
  public static final long ATOM_STRING = 0b010;

  // ----- Defaults -----------------------------------------------------------
  public static final int DEFAULT_MAP_WIDTH = 21000;
  public static final int DEFAULT_MAP_HEIGHT = 13500;
  public static final int DEFAULT_MAP_ID = 1; // X-1 by default

  /** Marker string returned by readString for unknown addresses. */
  public static final byte[] UNKNOWN_STRING_BYTES = "?".getBytes(StandardCharsets.UTF_8);
}
