import com.sun.tools.attach.VirtualMachine;

/** Separate injector process: attach to a running JVM by PID and load the agent (the normal, allowed model
 *  — no jdk.attach.allowAttachSelf needed since this is a different process). */
public class Injector {
    public static void main(String[] a) throws Exception {
        String pid = a[0], jar = a[1], mode = a.length > 2 ? a[2] : "";
        System.out.println("[INJECTOR] attaching to pid " + pid + " …");
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            System.out.println("[INJECTOR] attached; loadAgent(" + jar + ", \"" + mode + "\")");
            vm.loadAgent(jar, mode);
            System.out.println("[INJECTOR] loadAgent returned OK");
        } finally { vm.detach(); }
    }
}
