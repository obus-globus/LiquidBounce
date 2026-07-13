import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attach fallback via the bundled {@code jattach} native binary — lets a plain JRE (no {@code jdk.attach} module)
 * inject the agent. The binary is extracted from the tool jar to a temp dir per platform, then invoked.
 */
final class Jattach {
    private Jattach() {}

    static void loadAgent(String pid, java.io.File agentJar, String agentArgs, Consumer<String> log) throws Exception {
        java.io.File bin = extractBinary();
        log.accept("Attaching to PID " + pid + " via jattach (" + bin.getName() + ") ...");
        // jattach <pid> load instrument false "<agentjar>=<args>"  — "instrument" is the JVM's built-in JPLIS agent,
        // "false" = the option string that follows is not an absolute library path, and the option is jar=args.
        String option = agentJar.getAbsolutePath() + (agentArgs == null || agentArgs.isEmpty() ? "" : "=" + agentArgs);
        // jattach prints the agent's Agent_OnAttach result as "return code: N" (0 = success; non-zero = agentmain failed).
        int[] agentReturn = {Integer.MIN_VALUE};
        Pattern rc = Pattern.compile("return code[:=]\\s*(-?\\d+)");
        int code = run(List.of(bin.getAbsolutePath(), pid.trim(), "load", "instrument", "false", option), line -> {
            log.accept(line);
            Matcher m = rc.matcher(line.toLowerCase());
            if (m.find()) agentReturn[0] = Integer.parseInt(m.group(1));
        });
        if (code != 0)
            throw new IllegalStateException("jattach exited with code " + code + " (could not attach; see the messages above)");
        if (agentReturn[0] != Integer.MIN_VALUE && agentReturn[0] != 0)
            throw new IllegalStateException("the agent's Agent_OnAttach returned " + agentReturn[0]
                    + " (agentmain failed inside the client — check the target's log)");
        log.accept("Agent loaded successfully (jattach).");
    }

    /** Target JVM system properties via `jattach <pid> jcmd VM.system_properties` (a lightweight jcmd, loads no agent). */
    static Properties systemProperties(String pid) throws Exception {
        java.io.File bin = extractBinary();
        StringBuilder out = new StringBuilder();
        run(List.of(bin.getAbsolutePath(), pid.trim(), "jcmd", "VM.system_properties"), line -> out.append(line).append('\n'));
        Properties p = new Properties();
        try { p.load(new StringReader(out.toString())); } catch (Throwable ignored) {}
        return p;
    }

    private static int run(List<String> command, Consumer<String> log) throws Exception {
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        return proc.waitFor();
    }

    private static java.io.File extractBinary() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String resource, outName;
        if (os.contains("win")) { resource = "jattach-windows-x64.exe"; outName = "jattach.exe"; }
        else if (os.contains("mac") || os.contains("darwin")) { resource = "jattach-macos"; outName = "jattach"; }
        else if (arch.contains("aarch64") || arch.contains("arm64")) { resource = "jattach-linux-arm64"; outName = "jattach"; }
        else { resource = "jattach-linux-x64"; outName = "jattach"; }
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "lb-jattach");
        Files.createDirectories(dir);
        Path out = dir.resolve(outName);
        try (InputStream in = Jattach.class.getResourceAsStream("/jattach/" + resource)) {
            if (in == null) throw new FileNotFoundException("bundled jattach binary /jattach/" + resource + " is missing from the tool jar");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        out.toFile().setExecutable(true, true);
        return out.toFile();
    }
}
