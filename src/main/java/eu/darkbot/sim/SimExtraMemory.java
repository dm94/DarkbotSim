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
        if (address < FakeMemory.BASE)
            return null;
        // Check vtable matches our STRING_OBJECT_VTABLE
        long vtable = memory.readLong(address);
        if (vtable != memory.stringVtable())
            return null;
        return memory.readStringByAddress(address);
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
