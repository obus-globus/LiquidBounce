package lbrt;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe weak-IDENTITY-keyed map backing the sidecar per-instance state store.
 *
 * <p>The retransform converter relocates a mixin's added {@code @Unique} instance fields off the target object into
 * an external {@code Target -> State} map (the target' bytecode can't gain fields). That map MUST NOT retain its
 * targets: a plain {@code IdentityHashMap} has strong keys, so every entity/chat-line/etc. that ever touched a
 * relocated field would be pinned for the whole injected session and the map would grow without bound (OOM). And a
 * plain {@link java.util.WeakHashMap} is unusable here because it keys on {@code equals()}/{@code hashCode()} — a
 * mixin-modified {@code hashCode()} that reads a relocated field would recurse back into {@code getState()}.
 *
 * <p>So keys are weak AND compared by reference identity ({@link System#identityHashCode}, never {@code equals()}):
 * relocated state dies with its target exactly as a real {@code @Unique} field would, without ever calling a target's
 * {@code hashCode()}/{@code equals()}. Backed by a {@link ConcurrentHashMap} + {@link ReferenceQueue} drain, so it is
 * safe even though the generated {@code getState} is already {@code synchronized}. Only {@link #get}/{@link #put}/
 * {@link #remove} are exercised by the generated accessors; the rest are best-effort or unsupported.
 */
public final class WeakIdentityHashMap implements Map<Object, Object> {
    private final ConcurrentHashMap<Key, Object> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /** Weak reference whose identity (not value-equality) defines the key; hash is frozen at construction so a dead
     *  referent still hashes to its original bucket for eviction/removal. */
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object referent, ReferenceQueue<Object> q) { super(referent, q); this.hash = System.identityHashCode(referent); }
        Key(Object referent) { super(referent); this.hash = System.identityHashCode(referent); }   // lookup key (unregistered)
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;                      // the exact stored Key (used by the ReferenceQueue drain)
            if (!(o instanceof Key)) return false;
            Object a = get();
            return a != null && a == ((Key) o).get();        // reference identity; dead refs (null) never match
        }
    }

    private void expunge() { for (Object dead; (dead = queue.poll()) != null; ) map.remove(dead); }

    @Override public Object get(Object key) { expunge(); return map.get(new Key(key)); }
    @Override public Object put(Object key, Object value) { expunge(); return map.put(new Key(key, queue), value); }
    @Override public Object remove(Object key) { expunge(); return map.remove(new Key(key)); }
    @Override public boolean containsKey(Object key) { expunge(); return map.containsKey(new Key(key)); }
    @Override public int size() { expunge(); return map.size(); }
    @Override public boolean isEmpty() { expunge(); return map.isEmpty(); }
    @Override public void clear() { map.clear(); while (queue.poll() != null) { /* drain */ } }
    @Override public boolean containsValue(Object value) { return map.containsValue(value); }
    @Override public Collection<Object> values() { return map.values(); }
    @Override public void putAll(Map<?, ?> m) { for (Entry<?, ?> e : m.entrySet()) put(e.getKey(), e.getValue()); }
    @Override public Set<Object> keySet() { throw new UnsupportedOperationException(); }
    @Override public Set<Entry<Object, Object>> entrySet() { throw new UnsupportedOperationException(); }
}
