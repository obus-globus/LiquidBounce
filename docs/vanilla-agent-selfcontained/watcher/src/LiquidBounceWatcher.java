import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * No-flag injector ("watcher"): run this FIRST, then launch stock vanilla Minecraft with NO -javaagent.
 * It polls for a Minecraft JVM and, the instant one appears, attaches (separate-process model, the allowed
 * one) and loads the pure LB agent in the early SIGQUIT window — before GLFW drops the signal — so LB is
 * injected as MC's classes load. Warm in-process poller (no per-attempt JVM spawn) for a tight window hit.
 *
 * Usage: java -jar liquidbounce-watcher.jar [path-to-liquidbounce-agent-vanilla-pure.jar] [agentOptions]
 * (defaults to liquidbounce-agent-vanilla-pure.jar next to this jar)
 */
public class LiquidBounceWatcher {
    public static void main(String[] a) throws Exception {
        String agentJar = a.length > 0 ? a[0] : defaultAgent();
        String opts = a.length > 1 ? a[1] : "";
        if (agentJar == null || !new File(agentJar).isFile()) {
            System.err.println("[WATCHER] agent jar not found: " + agentJar); System.exit(2);
        }
        System.out.println("[WATCHER] watching for a vanilla Minecraft JVM (agent=" + agentJar + ")");
        Set<String> tried = new HashSet<>();
        while (true) {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                if (d.displayName().contains("net.minecraft.client.main.Main") && tried.add(d.id())) {
                    final String pid = d.id();
                    System.out.println("[WATCHER] Minecraft detected pid=" + pid + " — racing attach in the early SIGQUIT window");
                    new Thread(() -> race(pid, agentJar, opts), "watcher-attach-" + pid).start();
                }
            }
            Thread.sleep(20);
        }
    }

    static void race(String pid, String jar, String opts) {
        long t0 = System.nanoTime();
        for (int i = 0; i < 4000; i++) {
            try {
                VirtualMachine vm = VirtualMachine.attach(pid);
                vm.loadAgent(jar, opts);
                vm.detach();
                System.out.printf("[WATCHER] ATTACHED pid=%s at +%.0fms (attempt %d) — LB agent armed%n",
                        pid, (System.nanoTime() - t0) / 1e6, i);
                return;
            } catch (Throwable e) { try { Thread.sleep(2); } catch (InterruptedException ignore) {} }
        }
        System.out.println("[WATCHER] pid=" + pid + " — MISSED the attach window (JVM never became attach-ready "
                + "before SIGQUIT was dropped; on a fast machine the window is narrower)");
    }

    static String defaultAgent() {
        try {
            File self = new File(LiquidBounceWatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File cand = new File(self.getParentFile(), "liquidbounce-agent-vanilla-pure.jar");
            return cand.isFile() ? cand.getAbsolutePath() : cand.getAbsolutePath();
        } catch (Exception e) { return null; }
    }
}
