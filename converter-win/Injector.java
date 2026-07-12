// Injector.java — tiny standalone dynamic-attach injector (Windows port of the lost /tmp/attach-spike/out/Injector).
// Attaches to a running JVM by PID and loads a -javaagent jar into it via agentmain (VirtualMachine.loadAgent).
// Used for every attach in the vanilla-agent work: full-agent.jar, enterworld.jar, opengui.jar, probe jars.
//
// It relies ONLY on the JDK's jdk.attach module (com.sun.tools.attach.VirtualMachine). No tools.jar, no extra deps.
// A SEPARATE injector process is the correct model: jdk.attach.allowAttachSelf is NOT needed (never self-attach).
//
// ---------------------------------------------------------------------------------------------------------------
// COMPILE (Windows, PowerShell or cmd) — any JDK 9+; the project uses JDK 25:
//   javac -d out converter-win\Injector.java
//     (jdk.attach is resolved automatically; no --add-modules needed for an unnamed-module program.)
//
// RUN:
//   java -cp out Injector <pid> <C:\path\to\agent.jar> [agentArgs]
//   e.g.  java -cp out Injector 12345 C:\dev\full\full-agent.jar ""
//
// NOTES:
//   - On JDK 25 dynamic attach still works but prints:  "WARNING: A Java agent has been loaded dynamically ..."
//     That warning is harmless (see docs/vanilla-agent-resume/04-environment-gotchas.md).
//   - <pid> is the PID of the REAL Minecraft JVM (net.minecraft.client.main.Main), not a launcher/wrapper.
//     Verify with VirtualMachine.list() — only real JVMs appear (a "timeout"/wrapper PID will NOT).
//   - agentArgs is a single string handed to agentmain(String, Instrumentation). "" (empty) is fine for full-agent.
// ---------------------------------------------------------------------------------------------------------------

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

public class Injector {
    /**
     * Performs one agent injection without terminating the caller. This is also
     * used by InjectorUi, while main below remains compatible with the original
     * command-line interface.
     */
    public static void inject(String pid, File jar, String agentArgs, Consumer<String> log) throws Exception {
        inject(pid, jar, agentArgs, log, null);
    }

    public static void inject(String pid, File jar, String agentArgs, Consumer<String> log,
                              Consumer<File> targetLogFound) throws Exception {
        if (pid == null || pid.isBlank())
            throw new IllegalArgumentException("PID is required");
        if (jar == null || !jar.isFile())
            throw new IllegalArgumentException("Agent jar not found: " + (jar == null ? "<null>" : jar.getAbsolutePath()));

        File absoluteJar = jar.getAbsoluteFile();
        Consumer<String> output = log != null ? log : ignored -> {};
        VirtualMachine vm = null;
        try {
            output.accept("Attaching to PID " + pid + " ...");
            vm = VirtualMachine.attach(pid.trim());
            output.accept("Attached successfully.");
            if (targetLogFound != null) try {
                File targetLog = findMinecraftLog(vm.getSystemProperties());
                if (targetLog != null) {
                    output.accept("Minecraft log: " + targetLog);
                    targetLogFound.accept(targetLog);
                } else {
                    output.accept("Minecraft latest.log could not be located; target loading logs will not be streamed.");
                }
            } catch (Exception logDiscoveryFailure) {
                output.accept("Minecraft log discovery failed; continuing injection: " + logDiscoveryFailure.getMessage());
            }
            output.accept("Loading agent: " + absoluteJar);
            vm.loadAgent(absoluteJar.getPath(), agentArgs == null ? "" : agentArgs);
            output.accept("Agent loaded successfully.");
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                    output.accept("Detached from PID " + pid + ".");
                } catch (Throwable detachFailure) {
                    output.accept("Warning: detach failed: " + detachFailure.getMessage());
                }
            }
        }
    }

    private static File findMinecraftLog(Properties properties) {
        String command = properties.getProperty("sun.java.command", "");
        List<String> arguments = splitCommandLine(command);
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).equals("--gameDir")) {
                File log = new File(arguments.get(i + 1), "logs" + File.separator + "latest.log");
                if (log.isFile()) return log.getAbsoluteFile();
            }
        }

        String userDir = properties.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            File log = new File(userDir, "logs" + File.separator + "latest.log");
            if (log.isFile()) return log.getAbsoluteFile();
        }
        return null;
    }

    /** Enough Windows-style command-line parsing for quoted --gameDir paths. */
    private static List<String> splitCommandLine(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: java -cp out Injector <pid> <agent.jar> [agentArgs]");
            System.err.println("running JVMs visible to this attach API:");
            for (VirtualMachineDescriptor d : VirtualMachine.list())
                System.err.println("  pid=" + d.id() + "  " + d.displayName());
            System.exit(2);
        }
        String pid = args[0].trim();
        File jar = new File(args[1]);
        String agentArgs = args.length >= 3 ? args[2] : "";

        if (!jar.isFile()) {
            System.err.println("ERROR: agent jar not found: " + jar.getAbsolutePath());
            System.exit(3);
        }

        try {
            inject(pid, jar, agentArgs, message -> System.out.println("[INJ] " + message));
        } catch (Throwable t) {
            System.err.println("[INJ] FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            // Most common causes on Windows:
            //  - wrong PID (attached to a wrapper, not the JVM) -> see VirtualMachine.list() above
            //  - target JVM is a different vendor/user or elevated -> run injector as the same user
            //  - agentmain threw inside the target (check the target's own stdout/log, not here)
            t.printStackTrace();
            // Note: an AgentLoadException/AgentInitializationException means the agent WAS delivered but
            // agentmain returned non-zero / threw — inspect the Minecraft log, the attach itself succeeded.
            System.exit(1);
        }
        // Print the currently-visible JVMs for convenience (helps confirm the PID was the real one).
        List<VirtualMachineDescriptor> list = VirtualMachine.list();
        System.out.println("[INJ] done. JVMs now visible: " + list.size());
    }
}
