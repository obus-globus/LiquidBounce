package vspike;
import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class VSpikeServiceBootstrap implements IMixinServiceBootstrap {
    public String getName(){ return "VSpike"; }
    public String getServiceClassName(){ return "vspike.VSpikeService"; }
    public void bootstrap(){ /* nothing to pre-bootstrap */ }
}
