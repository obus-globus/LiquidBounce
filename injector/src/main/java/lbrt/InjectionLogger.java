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

    private InjectionLogger() {}

    private static PrintWriter fileOutput;

    /** Agent args may contain logFile=C:\\path; all other args are ignored. */
    public static synchronized void configure(String agentArgs) {
        if (agentArgs == null || !agentArgs.startsWith("logFile=")) return;
        try {
            Path path = Path.of(agentArgs.substring("logFile=".length())).toAbsolutePath();
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
