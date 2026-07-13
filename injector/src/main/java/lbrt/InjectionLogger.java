package lbrt;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Dedicated logger for late-injection diagnostics consumed by the injector UI. */
public final class InjectionLogger {
    public static final String PREFIX = "[LB-INJECT]";
    /** Separator between key=value agent-arg tokens; a control char that cannot appear in a filesystem path. */
    public static final char ARG_SEP = (char) 1;

    private InjectionLogger() {}

    private static PrintWriter fileOutput;

    /** Agent args are {@code key=value} tokens joined by {@link #ARG_SEP}. Returns the value for {@code key}, or null
     *  if absent. A bare legacy {@code logFile=<path>} (no separator) still parses as a single token. */
    public static String argValue(String agentArgs, String key) {
        if (agentArgs == null) return null;
        for (String token : agentArgs.split(String.valueOf(ARG_SEP)))
            if (token.startsWith(key + "=")) return token.substring(key.length() + 1);
        return null;
    }

    /** Opens the dedicated injection log if the args carry logFile=; other args are handled by the agent. */
    public static synchronized void configure(String agentArgs) {
        String logFile = argValue(agentArgs, "logFile");
        if (logFile == null) return;
        try {
            Path path = Path.of(logFile).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            fileOutput = new PrintWriter(writer, true);
        } catch (Exception failure) {
            System.err.println(PREFIX + " [ERROR] unable to open dedicated injection log: " + failure);
        }
    }

    public static void info(String message) { log("INFO", message); }
    public static void warn(String message) { log("WARN", message); }
    public static void error(String message) { log("ERROR", message); }

    public static void error(String message, Throwable failure) {
        error(message + " -> " + failure);
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        for (String line : text.toString().split("\\R")) error(line);
    }

    private static synchronized void log(String level, String message) {
        String line = PREFIX + " [" + level + "] " + message;
        System.out.println(line);
        if (fileOutput != null) fileOutput.println(line);
    }
}
