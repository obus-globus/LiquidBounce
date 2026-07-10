package vspike.mixins;
import vspike.LBProbe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MixinConvWrap {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/profiling/Profiler;get()Lnet/minecraft/util/profiling/ProfilerFiller;"))
    private ProfilerFiller convWrap(Operation<ProfilerFiller> op) {
        return (ProfilerFiller) LBProbe.wrap(op.call());   // synthetic Operation must resolve on the retransformed class
    }
}
