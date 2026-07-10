package vspike.mixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.Minecraft")
public class MinecraftMixin {
    private static boolean vspike$printed = false;
    // getWindow() is an instance method called very early during <init>; fires fast on software GL.
    @Inject(method = "getWindow", at = @At("HEAD"))
    private void vspike$onGetWindow(CallbackInfoReturnable<?> cir){
        if (vspike$printed) return;
        vspike$printed = true;
        System.out.println("[VSPIKE] >>> Mixin applied to vanilla net.minecraft.client.Minecraft (getWindow HEAD) <<<");
        try {
            Object u = vspike.AwProbe.readUserField(this);   // public-only getField => proves AW widened Minecraft.user
            System.out.println("[VSPIKE] >>> AccessWidener OK: read Minecraft.user cross-class via getField (value=" + u + ") <<<");
        } catch (Throwable t){
            System.out.println("[VSPIKE] !!! AccessWidener read FAILED: " + t);
        }
    }
}
