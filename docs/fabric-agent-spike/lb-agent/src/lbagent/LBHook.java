package lbagent;
import org.spongepowered.asm.mixin.Mixins;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;
import java.nio.file.*;
public class LBHook {
    public static void onReady(){
        try {
            FabricLauncher launcher = FabricLauncherBase.getLauncher();
            String kotlinMain = System.getProperty("lb.classesKotlin");
            String javaMain   = System.getProperty("lb.classesJava");
            String resources  = System.getProperty("lb.resources");
            String theme      = System.getProperty("lb.theme");
            // 1) put LB's classes + resources onto Knot (classloader identity)
            launcher.addToClassPath(Paths.get(kotlinMain), "net.ccbluex");
            launcher.addToClassPath(Paths.get(javaMain), "net.ccbluex");
            launcher.addToClassPath(Paths.get(resources));
            if (theme != null) launcher.addToClassPath(Paths.get(theme));
            System.out.println("[LBAGENT] LB classes+resources added to Knot=" + launcher.getTargetClassLoader());
            // 2) apply LB's AccessWidener via the loader's ClassTweaker (dev namespace = official)
            byte[] aw = Files.readAllBytes(Paths.get(resources, "liquidbounce.accesswidener"));
            ClassTweakerReader.create(FabricLoaderImpl.INSTANCE.getClassTweaker()).read(aw, "official");
            System.out.println("[LBAGENT] applied liquidbounce.accesswidener via ClassTweaker (" + aw.length + " bytes)");
            // 3) register LB's mixin configs into the LIVE Mixin
            Mixins.addConfiguration("liquidbounce.mixins.json");
            Mixins.addConfiguration("liquidbounce-fabric.mixins.json");
            System.out.println("[LBAGENT] added liquidbounce.mixins.json + liquidbounce-fabric.mixins.json");
        } catch (Throwable t){ System.out.println("[LBAGENT] onReady FAILED: " + t); t.printStackTrace(); }
    }
}
