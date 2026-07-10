package nfspike;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Trivial mixin registered by the premain agent into FML's LIVE mixin service — NOT via a
 * discovered ModFile. If the injected callback fires, an agent-registered Mixin has applied to
 * Minecraft on a live NeoForge install. Target by string (no compile-time MC dependency).
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MixinNfMc {
    @Inject(method = "run", at = @At("HEAD"))
    private void nf$onRun(CallbackInfo ci) {
        System.out.println("[NFMIXIN] >>> agent-registered MIXIN fired inside net.minecraft.client.Minecraft.run "
                + "on a LIVE NeoForge install — registered via FMLMixinService by a premain agent, NOT a mod <<<");
    }
}
