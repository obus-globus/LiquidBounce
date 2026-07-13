import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The JDK attach-API implementation. Referenced only by {@link Attacher} and only when {@code jdk.attach} is present,
 * so a JRE without that module never triggers loading/verification of this class.
 */
final class JdkAttacher {
    private JdkAttacher() {}

    static void loadAgent(String pid, File agentJar, String agentArgs, Consumer<String> log) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(pid.trim());
        try {
            log.accept("Attached successfully (JDK attach).");
            log.accept("Loading agent: " + agentJar);
            vm.loadAgent(agentJar.getPath(), agentArgs == null ? "" : agentArgs);
        } finally {
            try { vm.detach(); } catch (Throwable ignored) {}
        }
    }

    static Properties systemProperties(String pid) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(pid.trim());
        try { return vm.getSystemProperties(); }
        finally { try { vm.detach(); } catch (Throwable ignored) {} }
    }

    static Map<Long, String> attachableJvms() {
        Map<Long, String> m = new HashMap<>();
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            try { m.put(Long.parseLong(d.id()), d.displayName()); } catch (NumberFormatException ignored) {}
        }
        return m;
    }
}
