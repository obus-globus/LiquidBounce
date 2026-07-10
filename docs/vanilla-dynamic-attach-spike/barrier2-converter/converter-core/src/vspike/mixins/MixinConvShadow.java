package vspike.mixins;
import vspike.LBProbe;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class MixinConvShadow {
    @Shadow @Final private Window window;   // private final field -> needs AccessWidener to be read from the sidecar
    @Inject(method = "tick", at = @At("HEAD"))
    private void convShadow(CallbackInfo ci) { if (window != null) LBProbe.shadow(window.getClass().getSimpleName()); }
}
