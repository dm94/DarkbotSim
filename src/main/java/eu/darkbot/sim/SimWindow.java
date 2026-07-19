package eu.darkbot.sim;

import com.github.manolo8.darkbot.core.api.GameAPI;

public final class SimWindow implements GameAPI.Window {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void createWindow() {
        // no-op: background only
    }
}
