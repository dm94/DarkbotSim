package eu.darkbot.sim;

import java.util.function.LongPredicate;

import com.github.manolo8.darkbot.core.api.GameAPI;

public final class SimExtraMemory implements GameAPI.ExtraMemoryReader {
    private final FakeMemory memory;

    SimExtraMemory(FakeMemory memory) {
        this.memory = memory;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public String readString(long address) {
        return null;
    }

    @Override
    public void resetCache() {
        // no cache
    }

    @Override
    public long searchClassClosure(LongPredicate pattern) {
        return memory.searchClassClosure(pattern);
    }
}
