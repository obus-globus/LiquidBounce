package vspike.mixins;
import org.spongepowered.asm.mixin.Mixin;
import vspike.TestAddition;
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class MixinTestAddition implements TestAddition {   // adds interface TestAddition to Minecraft
    @Override public int lb$getX() { return 4242; }
}
