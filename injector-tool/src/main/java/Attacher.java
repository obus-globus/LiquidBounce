import java.io.File;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Chooses how to attach to a running JVM: the JDK attach API ({@code jdk.attach} module) when present, otherwise the
 * bundled {@code jattach} native binary. The jattach fallback lets a plain JRE inject — including the stripped runtime
 * the Minecraft launcher bundles, which usually omits {@code jdk.attach}.
 *
 * This class deliberately does NOT reference {@code com.sun.tools.attach}; that lives only in {@link JdkAttacher},
 * which is loaded lazily and only when the probe below succeeds, so the whole tool runs on a JRE without it.
 */
final class Attacher {
    private static final boolean JDK_ATTACH = probe();

    private Attacher() {}

    private static boolean probe() {
        try { Class.forName("com.sun.tools.attach.VirtualMachine"); return true; }
        catch (Throwable t) { return false; }
    }

    static boolean usesJdkAttach() { return JDK_ATTACH; }
    static String mechanism() { return JDK_ATTACH ? "JDK attach" : "jattach"; }

    static void loadAgent(String pid, File agentJar, String agentArgs, Consumer<String> log) throws Exception {
        if (JDK_ATTACH) JdkAttacher.loadAgent(pid, agentJar, agentArgs, log);
        else Jattach.loadAgent(pid, agentJar, agentArgs, log);
    }

    /** Target JVM system properties (best-effort — may be empty via the jattach path). */
    static Properties systemProperties(String pid) throws Exception {
        return JDK_ATTACH ? JdkAttacher.systemProperties(pid) : Jattach.systemProperties(pid);
    }

    /** pid -> attach displayName for JVMs the attach API can see (JDK attach only; empty on the jattach/JRE path). */
    static Map<Long, String> attachableJvms() {
        if (!JDK_ATTACH) return Map.of();
        try { return JdkAttacher.attachableJvms(); } catch (Throwable t) { return Map.of(); }
    }
}
