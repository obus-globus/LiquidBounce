package vspike.mixins;
import vspike.LBProbe;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MixinConvLocal {
    @Inject(method = "pick", at = @At("HEAD"))
    private void convLocal(CallbackInfo ci, @Local(argsOnly = true) float partialTick) { LBProbe.local(partialTick); }
}
