package dev.starbridge.compat.computer;

import java.util.List;
import java.util.TreeMap;

/** Live peripheral connections, held only for the lifetime of this helm. One computer may attach on several sides. */
public final class AttachedComputers {
    public static final int PAGE_SIZE = 3;
    public record Page(int index, int count, List<Integer> ids) {}
    private final TreeMap<Integer, Integer> connections = new TreeMap<>();

    // CC may call attach and detach on a computer thread while a ship owner is viewing the helm.
    public synchronized void attach(int id) {
        connections.merge(id, 1, Integer::sum);
    }

    public synchronized void detach(int id) {
        connections.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
    }

    public synchronized boolean contains(int id) {
        return connections.containsKey(id);
    }

    public synchronized Page page(int requested) {
        int count = Math.max(1, (connections.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int index = Math.max(0, Math.min(requested, count - 1));
        return new Page(index, count, connections.keySet().stream().skip((long) index * PAGE_SIZE).limit(PAGE_SIZE).toList());
    }

    public synchronized int pageOf(int id) {
        return connections.headMap(id).size() / PAGE_SIZE;
    }
}
