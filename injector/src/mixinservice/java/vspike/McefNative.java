package vspike;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * OPTIONAL, TEMPORARY offline-MCEF support. When the agent jar is built with {@code -PbundleMcefNative}
 * it carries MCEF's native libcef (~350 MB) under {@code mcef-native/}. This extracts it once (keyed by
 * tmpdir) and sets the {@code PROVIDED_JCEF_PATH} env var MCEF reads in
 * {@code MCEFDownloadManager.newResourceManager()} — which makes it use {@code MCEFProvidedResourceManager}
 * (whose {@code requiresDownload()} is unconditionally false), so MCEF initializes with ZERO network.
 *
 * Default artifacts do NOT bundle the native and this is a no-op (MCEF downloads on load as usual).
 *
 * NOTE: injecting an env var into the running JVM needs {@code --add-opens java.base/java.lang=ALL-UNNAMED}.
 * If that isn't present the injection fails gracefully and MCEF falls back to download-on-load.
 */
final class McefNative {
    static void stageIfBundled(File selfJar, String tag) {
        try {
            if (selfJar == null || !selfJar.isFile()) return;
            try (JarFile jf = new JarFile(selfJar)) {
                if (jf.getEntry("mcef-native/libcef.so") == null) return; // native not bundled -> download-on-load
                Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "lb-mcef-native");
                Path marker = dir.resolve(".extract-complete");
                Files.createDirectories(dir);
                // Cross-process exclusive lock: the staging dir is fixed and SHARED, so two clients attaching on the same
                // host would otherwise both see the marker absent and race on the same ~350 MB tree — one could hand MCEF
                // a half-overwritten libcef.so. Serialize the whole check-and-extract; the second JVM blocks here, then
                // sees the completed marker and skips. Skip only if a PRIOR extraction fully COMPLETED (marker written
                // last) — a partial/interrupted extraction (native present, helpers missing) must re-extract, overwriting.
                try (FileChannel lockCh = FileChannel.open(dir.resolve(".extract.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = lockCh.lock()) {
                    if (!Files.exists(marker)) {
                        for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                            JarEntry e = en.nextElement();
                            String n = e.getName();
                            if (!n.startsWith("mcef-native/")) continue;
                            String rel = n.substring("mcef-native/".length());
                            if (rel.isEmpty()) continue;
                            Path out = dir.resolve(rel);
                            if (e.isDirectory()) { Files.createDirectories(out); continue; }
                            if (out.getParent() != null) Files.createDirectories(out.getParent());
                            try (InputStream in = jf.getInputStream(e)) {
                                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        Files.write(marker, new byte[0]);   // mark complete only after the whole tree extracted
                    }
                }
                for (String x : new String[]{"jcef_helper", "chrome-sandbox", "jcef_helper.exe"}) {
                    File f = dir.resolve(x).toFile();
                    if (f.exists()) f.setExecutable(true, true);   // owner-only, not world-executable
                }
                setEnv("PROVIDED_JCEF_PATH", dir.toAbsolutePath().toString());
                System.out.println(tag + " offline MCEF: bundled native staged, PROVIDED_JCEF_PATH=" + dir.toAbsolutePath());
            }
        } catch (Throwable t) {
            System.out.println(tag + " offline MCEF staging skipped (falls back to download-on-load): " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
        Field f = pe.getDeclaredField("theEnvironment");
        f.setAccessible(true); // requires --add-opens java.base/java.lang=ALL-UNNAMED
        Map<Object, Object> env = (Map<Object, Object>) f.get(null);
        Class<?> var = Class.forName("java.lang.ProcessEnvironment$Variable");
        Class<?> val = Class.forName("java.lang.ProcessEnvironment$Value");
        Method vof = var.getDeclaredMethod("valueOf", String.class); vof.setAccessible(true);
        Method lof = val.getDeclaredMethod("valueOf", String.class); lof.setAccessible(true);
        env.put(vof.invoke(null, key), lof.invoke(null, value));
    }

    private McefNative() {}
}
