package eu.darkbot.sim;

import com.github.manolo8.darkbot.core.api.Capability;
import com.github.manolo8.darkbot.core.api.GameAPIImpl;
import com.github.manolo8.darkbot.utils.StartupParams;
import eu.darkbot.api.utils.Inject;

public final class SimulatorAdapter extends GameAPIImpl<
        SimWindow, SimHandler, SimMemory,
        SimExtraMemory, SimInteraction,
        SimulatorDirectInteraction> {

    private static final long TICK_MS = 50L;

    private final SimWorld world;
    private final FakeMemory memory;

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
              Capability.BACKGROUND_ONLY,
              Capability.DIRECT_MOVE_SHIP,
              Capability.DIRECT_ENTITY_SELECT,
              Capability.DIRECT_ENTITY_LOCK,
              Capability.DIRECT_COLLECT_BOX,
              Capability.DIRECT_CALL_METHOD);
        this.world = world;
        this.memory = mem;
    }

    @Override
    public void tick() {
        world.tick(TICK_MS);
        memory.tick();
        super.tick();
    }

    public SimWorld world() { return world; }
    public FakeMemory memory() { return memory; }
}