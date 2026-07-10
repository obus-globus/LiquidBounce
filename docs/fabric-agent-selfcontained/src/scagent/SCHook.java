package scagent;

import org.spongepowered.asm.mixin.Mixins;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Self-contained injection: stage the bundled payload out of THIS agent jar (no -Dlb.*), add it
 * to Knot, apply the AccessWidener, and register LB's mixin configs into the live loader Mixin.
 *
 * Agent jar layout (produced by the Gradle task):
 *   scagent/*.class                        — the agent (on the app classloader via -javaagent)
 *   agent-libs/liquidbounce.jar            — LB's compiled classes + resources (mixin json, AW, assets)
 *   agent-libs/<dep>.jar ...               — LB's full non-loader dep tree (kotlin, DJL, mcef Java, ...)
 *   liquidbounce.accesswidener             — LB's AW, read jar-relative (no FileReader on a dev path)
 */
public final class SCHook {

    public static void onReady() {
        try {
            FabricLauncher launcher = FabricLauncherBase.getLauncher();

            // 1) locate this agent jar and extract its bundled libs to a temp dir
            File agentJar = new File(SCHook.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // Optional offline MCEF (-PbundleMcefNative): stage bundled native + set PROVIDED_JCEF_PATH.
            McefNative.stageIfBundled(agentJar, "[SCAGENT]");
            Path tmp = Files.createTempDirectory("lb-agent-");
            tmp.toFile().deleteOnExit();
            Path lbJar = null;
            List<Path> deps = new ArrayList<>();
            try (JarFile jf = new JarFile(agentJar)) {
                for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                    JarEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("agent-libs/") || !n.endsWith(".jar")) continue;
                    Path out = tmp.resolve(new File(n).getName());
                    try (InputStream in = jf.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
                    out.toFile().deleteOnExit();
                    if (n.endsWith("/liquidbounce.jar")) lbJar = out; else deps.add(out);
                }
            }
            if (lbJar == null) throw new IllegalStateException("agent-libs/liquidbounce.jar missing from agent jar");

            // 2) add the full payload to Knot (one classloader identity with MC).
            //    LB's own jar is prefix-restricted to net.ccbluex; deps serve all their packages.
            launcher.addToClassPath(lbJar, "net.ccbluex");
            for (Path d : deps) launcher.addToClassPath(d);
            System.out.println("[SCAGENT] staged LB + " + deps.size() + " bundled deps onto Knot="
                    + launcher.getTargetClassLoader());

            // 3) apply LB's AccessWidener via the loader's ClassTweaker (26.2 runtime namespace = official)
            byte[] aw;
            try (InputStream in = SCHook.class.getResourceAsStream("/liquidbounce.accesswidener")) {
                if (in == null) throw new IllegalStateException("liquidbounce.accesswidener missing from agent jar");
                aw = in.readAllBytes();
            }
            ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw, "official");
            System.out.println("[SCAGENT] applied liquidbounce.accesswidener via ClassTweaker (" + aw.length + " bytes)");

            // 4) register LB's mixin configs into the live Mixin
            Mixins.addConfiguration("liquidbounce.mixins.json");
            Mixins.addConfiguration("liquidbounce-fabric.mixins.json");
            System.out.println("[SCAGENT] added liquidbounce.mixins.json + liquidbounce-fabric.mixins.json");
        } catch (Throwable t) {
            System.out.println("[SCAGENT] onReady FAILED: " + t);
            t.printStackTrace();
        }
    }
}
