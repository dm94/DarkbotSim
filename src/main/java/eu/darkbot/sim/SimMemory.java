package eu.darkbot.sim;

import com.github.manolo8.darkbot.core.api.GameAPI;

public final class SimMemory implements GameAPI.Memory {
    final FakeMemory memory;

    SimMemory(FakeMemory memory) {
        this.memory = memory;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public int readInt(long a) {
        return memory.readInt(a);
    }

    @Override
    public long readLong(long a) {
        return memory.readLong(a);
    }

    @Override
    public double readDouble(long a) {
        return memory.readDouble(a);
    }

    @Override
    public boolean readBoolean(long a) {
        return memory.readBoolean(a);
    }

    @Override
    public byte[] readBytes(long a, int len) {
        return memory.readBytes(a, len);
    }

    @Override
    public void readBytes(long a, byte[] buf, int len) {
        memory.readBytes(a, buf, len);
    }

    @Override
    public void replaceInt(long a, int ov, int nv) {
        memory.replaceInt(a, ov, nv);
    }

    @Override
    public void replaceLong(long a, long ov, long nv) {
        memory.replaceLong(a, ov, nv);
    }

    @Override
    public void replaceDouble(long a, double ov, double nv) {
        memory.replaceDouble(a, ov, nv);
    }

    @Override
    public void replaceBoolean(long a, boolean ov, boolean nv) {
        memory.replaceBoolean(a, ov, nv);
    }

    @Override
    public void writeInt(long a, int v) {
        memory.writeInt(a, v);
    }

    @Override
    public void writeLong(long a, long v) {
        memory.writeLong(a, v);
    }

    @Override
    public void writeDouble(long a, double v) {
        memory.writeDouble(a, v);
    }

    @Override
    public void writeBoolean(long a, boolean v) {
        memory.writeBoolean(a, v);
    }

    @Override
    public void writeBytes(long a, byte... b) {
        memory.writeBytes(a, b);
    }

    @Override
    public long[] queryInt(int value, int maxSize) {
        return new long[0];
    }

    @Override
    public long[] queryLong(long value, int maxSize) {
        return new long[0];
    }

    @Override
    public long[] queryBytes(byte[] pattern, int maxSize) {
        long addr = memory.searchPattern(pattern);
        if (addr == 0 || maxSize <= 0) return new long[0];
        return new long[]{addr};
    }
}
