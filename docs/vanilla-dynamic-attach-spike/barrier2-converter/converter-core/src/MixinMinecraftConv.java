package vspike.mixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MixinMinecraftConv {
    @Unique private int convTickCount;        // added @Unique FIELD  -> converter relocates to external State
    @Inject(method = "tick", at = @At("HEAD")) // added @Inject HANDLER -> converter relocates to sidecar static
    private void convHook(CallbackInfo ci) {
        this.convTickCount++;
        if (this.convTickCount % 100 == 0)
            System.out.println("[CONVAUTO][LIVE] auto-converted mixin fired convTickCount=" + this.convTickCount
                + " (Mixin-engine output -> RetransformConverter -> retransformed onto ALREADY-LOADED Minecraft)");
    }
}
