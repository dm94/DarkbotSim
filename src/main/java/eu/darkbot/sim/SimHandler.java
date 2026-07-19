package eu.darkbot.sim;

import com.github.manolo8.darkbot.core.api.GameAPI;

public final class SimHandler implements GameAPI.Handler {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public long getMemoryUsage() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Override
    public void reload() {
        // no-op
    }

    @Override
    public void setSize(int width, int height) {
        // no-op
    }

    @Override
    public void setVisible(boolean visible) {
        // no-op
    }

    @Override
    public void setMinimized(boolean minimized) {
        // no-op
    }
}
