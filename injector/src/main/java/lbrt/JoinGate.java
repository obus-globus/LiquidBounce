package lbrt;

import java.lang.invoke.MethodType;
import java.lang.reflect.*;

/** Defers the latest server-connect request while late bootstrap temporarily thaws registries. */
public final class JoinGate {
    private static final ClassLoader SYS = lbrt.Platform.LOADER;
    private static boolean blocked;
    private static Call pending;

    private record Call(String owner, String name, String desc, Object[] args) {}

    private JoinGate() {}

    public static synchronized void block() {
        blocked = true;
        pending = null;
    }

    /** Called from an injected method prologue. True means the caller must return without connecting. */
    public static synchronized boolean deferStatic(String owner, String name, String desc, Object[] args) {
        if (!blocked) return false;
        pending = new Call(owner, name, desc, args.clone());
        InjectionLogger.info("server join deferred until registry restoration completes");
        return true;
    }

    /** Must normally be called on Minecraft's main thread. Replays the latest deferred click exactly once. The deferral
     *  window is short (bootstrap only) and the user stays parked on the parent screen, so the shallow-cloned args stay
     *  live; if a replay ever does hit a stale frame the invoke throws and the sole caller (FullInjectAgent.
     *  restoreAndOpen) catches it, logs DEFERRED_JOIN_FAILURE and reopens the gate — the user simply retries. */
    public static void open() {
        Call call;
        synchronized (JoinGate.class) {
            blocked = false;
            call = pending;
            pending = null;
        }
        if (call == null) return;
        try {
            Class<?> owner = Class.forName(call.owner.replace('/','.'), false, SYS);
            Class<?>[] params = MethodType.fromMethodDescriptorString(call.desc, SYS).parameterArray();
            Method method = owner.getDeclaredMethod(call.name, params);
            method.setAccessible(true);
            method.invoke(null, call.args);
            InjectionLogger.info("resumed deferred server join");
        } catch (InvocationTargetException e) {
            throw re(e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            throw re(t);
        }
    }

    public static synchronized boolean isBlocked() { return blocked; }

    /** Failure fallback when no Minecraft-main-thread replay can be guaranteed. */
    public static synchronized void cancelAndOpen() {
        pending = null;
        blocked = false;
    }

    private static RuntimeException re(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
