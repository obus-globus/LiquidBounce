package lbrt;

/** Tiny runtime holder for the target classloader that owns net.minecraft.* and staged LB. Set once at agentmain by
 *  the host to the platform's target loader; read by the staged runtime helpers (AwReflect / DuckDispatch / JoinGate)
 *  instead of hardcoding getSystemClassLoader(). Vanilla: LOADER == system loader, so behavior is unchanged. */
public final class Platform {
    private Platform() {}
    /** The loader against which the runtime helpers resolve MC/LB classes. Defaults to the system loader. */
    public static volatile ClassLoader LOADER = ClassLoader.getSystemClassLoader();
    public static Class<?> load(String dotted) throws ClassNotFoundException { return Class.forName(dotted, false, LOADER); }
}
