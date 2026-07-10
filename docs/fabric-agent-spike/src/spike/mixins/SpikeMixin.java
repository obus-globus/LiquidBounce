package spike.mixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Trivial mixin added LATE by the agent (not declared in any fabric.mod.json).
// If this fires, a premain agent can register a mixin config into the LIVE Fabric Mixin
// at the PreLaunch window and have it apply to a game class.
@Mixin(targets = "net.minecraft.client.Minecraft")
public class SpikeMixin {
    @Inject(method = "getWindow", at = @At("HEAD"))
    private void fspike$onGetWindow(CallbackInfoReturnable<?> cir){
        System.out.println("[FSPIKE] >>> AGENT-ADDED MIXIN FIRED inside net.minecraft.client.Minecraft on a LIVE FABRIC install <<<");
    }
}
