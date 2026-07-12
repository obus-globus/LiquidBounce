package lbrt;

import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime dispatcher for duck interfaces which cannot be added to already-loaded targets. */
public final class DuckDispatch {
    private static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    private static final Map<String,List<Impl>> IMPLEMENTATIONS = new ConcurrentHashMap<>();
    private static final Map<String,Class<?>> TARGETS = new ConcurrentHashMap<>();
    private static final Map<Key,Method> METHODS = new ConcurrentHashMap<>();

    private record Impl(String targetName, String sidecarName) {}
    private record Key(Class<?> receiver, String iface, String name, String desc) {}

    private DuckDispatch() {}

    public static synchronized void register(String iface, String target, String sidecar) {
        List<Impl> list = new ArrayList<>(IMPLEMENTATIONS.getOrDefault(iface, List.of()));
        for (Impl i : list) if (i.targetName.equals(target)) return;
        // Registration must not load the target: doing so before the CFT sees it would bypass conversion/AW.
        list.add(new Impl(target, sidecar));
        list.sort(Comparator.comparing(Impl::targetName));
        IMPLEMENTATIONS.put(iface, List.copyOf(list));
        METHODS.clear();
    }

    public static boolean isInstance(Object receiver, String iface) {
        if (receiver == null) return false;
        for (Impl i : IMPLEMENTATIONS.getOrDefault(iface, List.of())) if (target(i).isInstance(receiver)) return true;
        return false;
    }

    public static Object cast(Object receiver, String iface) {
        if (receiver == null || isInstance(receiver, iface)) return receiver;
        throw new ClassCastException(receiver.getClass().getName() + " cannot be cast to " + iface.replace('/','.'));
    }

    public static Object invoke(Object receiver, String iface, String name, String desc, Object[] args) {
        if (receiver == null) throw new NullPointerException("duck receiver for " + iface + "." + name);
        try {
            Key key = new Key(receiver.getClass(), iface, name, desc);
            Method m = METHODS.computeIfAbsent(key, DuckDispatch::resolve);
            Object[] actual = new Object[args.length + 1];
            actual[0] = receiver; System.arraycopy(args, 0, actual, 1, args.length);
            return m.invoke(null, actual);
        } catch (InvocationTargetException e) {
            throw re(e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            throw re(t);
        }
    }

    private static Method resolve(Key key) {
        try {
            Impl best = null;
            for (Impl i : IMPLEMENTATIONS.getOrDefault(key.iface, List.of())) {
                Class<?> it = target(i);
                if (!it.isAssignableFrom(key.receiver)) continue;
                if (best == null || target(best).isAssignableFrom(it)) best = i;
                else if (!it.isAssignableFrom(target(best)))
                    throw new IncompatibleClassChangeError("Ambiguous duck interface " + key.iface + " for " + key.receiver.getName());
            }
            if (best == null) throw new IncompatibleClassChangeError("No duck implementation " + key.iface + " for " + key.receiver.getName());
            MethodType mt = MethodType.fromMethodDescriptorString(key.desc, SYS);
            Class<?>[] original = mt.parameterArray();
            Class<?>[] params = new Class<?>[original.length + 1];
            params[0] = target(best); System.arraycopy(original, 0, params, 1, original.length);
            Class<?> sidecar = Class.forName(best.sidecarName.replace('/','.'), false, SYS);
            Method m = sidecar.getMethod("h$" + key.name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            throw re(t);
        }
    }

    private static Class<?> target(Impl impl) {
        return TARGETS.computeIfAbsent(impl.targetName, n -> {
            try { return Class.forName(n.replace('/','.'), false, SYS); }
            catch (Throwable t) { throw re(t); }
        });
    }

    private static RuntimeException re(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
