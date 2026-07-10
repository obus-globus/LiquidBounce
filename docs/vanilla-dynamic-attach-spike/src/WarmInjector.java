import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/** Warm injector: already running before MC starts; detects the MC JVM and attaches in-process (fast retry,
 *  no per-attempt JVM spawn) to hit the early SIGQUIT window before the game classes load. */
public class WarmInjector {
    public static void main(String[] a) throws Exception {
        String jar = a[0], mode = a[1];
        System.out.println("[WARM] polling for MC JVM…");
        String pid = null;
        long t0 = System.nanoTime();
        while (pid == null) {
            for (VirtualMachineDescriptor d : VirtualMachine.list())
                if (d.displayName().contains("net.minecraft.client.main.Main")) { pid = d.id(); break; }
            if (pid == null) Thread.sleep(2);
        }
        System.out.printf("[WARM] MC pid %s detected at +%.0fms; racing attach%n", pid, (System.nanoTime() - t0) / 1e6);
        for (int i = 0; i < 3000; i++) {
            try {
                VirtualMachine vm = VirtualMachine.attach(pid);
                vm.loadAgent(jar, mode);
                vm.detach();
                System.out.printf("[WARM] ATTACHED + loadAgent OK at +%.0fms (attempt %d)%n", (System.nanoTime() - t0) / 1e6, i);
                return;
            } catch (Throwable e) {
                Thread.sleep(2); // "not ready" (too early) or dropped SIGQUIT (too late) — keep trying
            }
        }
        System.out.println("[WARM] gave up (never hit the attach window)");
    }
}
