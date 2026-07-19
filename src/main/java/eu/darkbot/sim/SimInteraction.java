package eu.darkbot.sim;

import com.github.manolo8.darkbot.core.api.GameAPI;

public final class SimInteraction implements GameAPI.Interaction {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void keyClick(int keyCode) {
        // no-op
    }

    @Override
    public void sendText(String text) {
        // no-op
    }

    @Override
    public void mouseMove(int x, int y) {
        // no-op
    }

    @Override
    public void mouseDown(int x, int y) {
        // no-op
    }

    @Override
    public void mouseUp(int x, int y) {
        // no-op
    }

    @Override
    public void mouseClick(int x, int y) {
        // no-op
    }
}
