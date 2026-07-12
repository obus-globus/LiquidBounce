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
import java.util.List;

public class Injector {
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

        VirtualMachine vm = null;
        try {
            System.out.println("[INJ] attaching to pid " + pid + " ...");
            vm = VirtualMachine.attach(pid);
            System.out.println("[INJ] attached; loadAgent(" + jar.getAbsolutePath() + ", \"" + agentArgs + "\")");
            // Pass the ABSOLUTE path so the target JVM (whose cwd differs) resolves it correctly.
            vm.loadAgent(jar.getAbsolutePath(), agentArgs);
            System.out.println("[INJ] loadAgent returned OK");
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
        } finally {
            if (vm != null) try { vm.detach(); } catch (Throwable ignore) {}
        }
        // Print the currently-visible JVMs for convenience (helps confirm the PID was the real one).
        List<VirtualMachineDescriptor> list = VirtualMachine.list();
        System.out.println("[INJ] done. JVMs now visible: " + list.size());
    }
}
