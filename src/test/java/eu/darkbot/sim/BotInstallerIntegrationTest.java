package eu.darkbot.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.core.BotInstaller;
import com.github.manolo8.darkbot.core.IDarkBotAPI;
import com.github.manolo8.darkbot.utils.StartupParams;

/**
 * End-to-end integration: drives the REAL {@link BotInstaller} against the
 * simulator's API to find out whether the bot reaches the "valid" state,
 * and which step breaks if not.
 *
 * This is the closest unit test to what DarkBot does on every tick.
 */
class BotInstallerIntegrationTest {

  private IDarkBotAPI previousApi;
  private SimulatorAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    previousApi = Main.API;
    StartupParams params = new StartupParams(new String[0]);
    adapter = new SimulatorAdapter(params);
    Main.API = adapter;
  }

  @AfterEach
  void tearDown() {
    Main.API = previousApi;
  }

  @Test
  void apiIsValid() {
    // The 5-second grace timer in SimHandler only kicks in when called fresh.
    assertTrue(Main.API.isValid(),
        "Simulator API must report isValid()=true so DarkBot treats it as attached");
  }

  @Test
  void botInstallerReachesValidState() {
    BotInstaller installer = new BotInstaller();
    // invalid starts true. Run a few ticks and observe the transition.
    boolean invalid1 = installer.isInvalid();
    System.out.println("[tick 1] invalid=" + invalid1);
    assertTrue(invalid1, "First tick is always invalid (still installing)");

    boolean invalid2 = installer.isInvalid();
    System.out.println("[tick 2] invalid=" + invalid2);
    assertFalse(invalid2,
        "Bot must be valid on the second tick once tryInstall() has wired all addresses");
  }

  @Test
  void mainApplicationAddressResolved() {
    BotInstaller installer = new BotInstaller();
    installer.isInvalid(); // tick 1: installs
    installer.isInvalid(); // tick 2: validates
    long mainApp = installer.mainApplicationAddress.get();
    long main = installer.mainAddress.get();
    long screen = installer.screenManagerAddress.get();
    long gui = installer.guiManagerAddress.get();
    long settings = installer.settingsAddress.get();
    System.out.printf("mainApp=%x main=%x screen=%x gui=%x settings=%x%n",
        mainApp, main, screen, gui, settings);
    assertNotEquals(0, mainApp, "mainApplicationAddress must be set");
    assertNotEquals(0, main, "mainAddress must be set");
    assertNotEquals(0, screen, "screenManagerAddress must be set");
    assertNotEquals(0, gui, "guiManagerAddress must be set");
    assertNotEquals(0, settings, "settingsAddress must be set");
  }
}
