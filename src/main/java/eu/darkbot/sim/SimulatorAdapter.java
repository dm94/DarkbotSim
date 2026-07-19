package eu.darkbot.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.manolo8.darkbot.core.api.Capability;
import com.github.manolo8.darkbot.core.api.GameAPIImpl;
import com.github.manolo8.darkbot.utils.StartupParams;
import eu.darkbot.api.utils.Inject;

/**
 * DarkBot API adapter backed by a pure-Java {@link SimWorld}.
 *
 * <p>
 * By default it auto-loads the bundled {@code scenarios/basic_farm.json}
 * scenario. Override with {@code -Dsim.scenario=path/to/scenario.json}
 * (file on disk) or {@code -Dsim.scenario=none} for an empty world.
 */
public final class SimulatorAdapter extends
        GameAPIImpl<SimWindow, SimHandler, SimMemory, SimExtraMemory, SimInteraction, SimulatorDirectInteraction> {

    private static final long TICK_MS = 50L;
    private static final String SCENARIO_PROPERTY = "sim.scenario";
    private static final String BUNDLED_SCENARIO = "/scenarios/basic_farm.json";

    private final SimWorld world;
    private final FakeMemory memory;
    private long tickCount;
    private long lastReport = System.currentTimeMillis();

    @Inject
    public SimulatorAdapter(StartupParams params) {
        this(params, new SimWorld());
    }

    public SimulatorAdapter(StartupParams params, SimWorld world) {
        this(params, world, new FakeMemory(world));
    }

    private SimulatorAdapter(StartupParams params, SimWorld world, FakeMemory mem) {
        super(params,
                new SimWindow(), new SimHandler(),
                new SimMemory(mem), new SimExtraMemory(mem), new SimInteraction(),
                new SimulatorDirectInteraction(world),
                Capability.DIRECT_MOVE_SHIP,
                Capability.DIRECT_ENTITY_SELECT,
                Capability.DIRECT_ENTITY_LOCK,
                Capability.DIRECT_COLLECT_BOX,
                Capability.DIRECT_CALL_METHOD);
        this.world = world;
        this.memory = mem;
        loadDefaultScenario();
        System.out.println("[SIM] SimulatorAdapter ready: map=" + world.mapId
                + " hero=(" + (int) world.hero.x + "," + (int) world.hero.y + ")"
                + " npcs=" + world.npcs.size() + " boxes=" + world.boxes.size());
    }

    private void loadDefaultScenario() {
        String scenario = System.getProperty(SCENARIO_PROPERTY);
        if (scenario != null) {
            if (scenario.equalsIgnoreCase("none")) {
                System.out.println("[SIM] sim.scenario=none, starting empty world");
                return;
            }
            Path file = Paths.get(scenario);
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    ScenarioRunner.loadFromJson(world, r);
                    System.out.println("[SIM] Loaded scenario from " + file);
                    return;
                } catch (IOException e) {
                    System.err.println("[SIM] Failed to load " + file + ": " + e.getMessage());
                }
            } else {
                System.err.println("[SIM] sim.scenario file not found: " + file + ", falling back to bundled default");
            }
        }
        try (InputStream is = SimulatorAdapter.class.getResourceAsStream(BUNDLED_SCENARIO);
                Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            ScenarioRunner.loadFromJson(world, r);
            System.out.println("[SIM] Loaded bundled default scenario");
        } catch (IOException | NullPointerException e) {
            System.err.println("[SIM] Could not load bundled scenario: " + e.getMessage());
        }
    }

    @Override
    public void tick() {
        world.tick(TICK_MS);
        memory.tick();
        super.tick();
        tickCount++;
        long now = System.currentTimeMillis();
        if (now - lastReport >= 2000) {
            Integer targetId = world.hero.targetId;
            System.out.println("[SIM] ticks=" + tickCount + " hero=(" + (int) world.hero.x + "," + (int) world.hero.y
                    + ") hp=" + (int) world.hero.hp + "/" + (int) world.hero.maxHp
                    + " target=" + (targetId == null ? "-" : targetId)
                    + " npcs=" + world.npcs.size() + " boxes=" + world.boxes.size());
            lastReport = now;
        }
    }

    public SimWorld world() {
        return world;
    }

    public FakeMemory memory() {
        return memory;
    }
}
