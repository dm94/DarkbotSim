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

  // ----- Defaults -----------------------------------------------------------
  public static final int DEFAULT_MAP_WIDTH = 21000;
  public static final int DEFAULT_MAP_HEIGHT = 13500;
  public static final int DEFAULT_MAP_ID = 1; // X-1 by default

  /** Marker string returned by readString for unknown addresses. */
  public static final byte[] UNKNOWN_STRING_BYTES = "?".getBytes(StandardCharsets.UTF_8);
}
