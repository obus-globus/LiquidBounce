package vspike.mixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vspike.Target;

@Mixin(value = Target.class, remap = false)
public class DummyMixin {
    @Inject(method = "greet", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGreet(CallbackInfoReturnable<String> cir){
        System.out.println("[VSPIKE] DummyMixin @Inject fired inside Target.greet()");
        cir.setReturnValue("MIXIN-APPLIED");
    }
}
