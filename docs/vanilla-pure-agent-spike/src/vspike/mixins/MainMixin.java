package vspike.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * The kill-shot: a single @ModifyArgs on the earliest, guaranteed-to-run call in stock MC —
 * OptionParser.accepts(String) at the very top of net.minecraft.client.main.Main.main.
 *
 * @ModifyArgs makes Mixin generate a runtime SYNTHETIC class (org.spongepowered.asm.synthetic.args.Args$N)
 * and instantiate it at the call site. Under a pure -javaagent a ClassFileTransformer cannot fabricate
 * that class on demand -> ClassNotFoundException. If this handler prints WITHOUT that CNF, the synthetic
 * wall is breached and vanilla-as-pure-agent is viable.
 */
@Mixin(targets = "net.minecraft.client.main.Main")
public class MainMixin {
    @ModifyArgs(
        method = "main([Ljava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Ljoptsimple/OptionParser;accepts(Ljava/lang/String;)Ljoptsimple/OptionSpecBuilder;",
            ordinal = 0
        )
    )
    private static void pureSpike$onAccepts(Args args) {
        System.out.println("[PURE] >>> @ModifyArgs FIRED on Main.main OptionParser.accepts — argCount="
            + args.size() + " arg0=" + args.<Object>get(0)
            + " (synthetic Args resolved, no CNF) <<<");
    }
}
