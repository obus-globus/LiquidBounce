package spike;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import java.nio.file.Paths;
/** Inserted (via agent bytecode hook) at the end of FabricMixinBootstrap.init:
 *  Fabric's Mixin is up + its configs added; game classes not yet transformed. */
public class SpikeHook {
    public static void onFabricMixinReady(){
        try {
            String jar = System.getProperty("fspike.jar", "/tmp/fspike/agent.jar");
            FabricLauncher launcher = FabricLauncherBase.getLauncher();
            launcher.addToClassPath(Paths.get(jar), "spike");   // spike.* onto Knot
            System.out.println("[FSPIKE] added agent jar to Knot classpath, target CL=" + launcher.getTargetClassLoader());
            String svc = MixinService.getService().getClass().getName();
            Mixins.addConfiguration("spike.mixins.json");
            System.out.println("[FSPIKE] addConfiguration(spike.mixins.json) OK; live service=" + svc);
        } catch (Throwable t){
            System.out.println("[FSPIKE] onFabricMixinReady FAILED: " + t); t.printStackTrace();
        }
    }
}
