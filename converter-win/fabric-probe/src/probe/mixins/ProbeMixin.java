package probe.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Trivial mixin added LATE (post-boot, via agentmain) into an ALREADY-LOADED target.
// If the probe finds this handler / its marker string inside the bytes returned by a DIRECT
// call to Knot's live IMixinTransformer.transformClassBytes(...), the late-added config was
// applied on-demand -> GREEN.
@Mixin(targets = "net.minecraft.client.Minecraft")
public class ProbeMixin {
    @Inject(method = "getWindow", at = @At("HEAD"))
    private void probe$onGetWindow(CallbackInfoReturnable<?> cir) {
        System.out.println("[FPROBE] >>> PROBE MIXIN HANDLER probe$onGetWindow EXECUTED inside Minecraft <<<");
    }
}
