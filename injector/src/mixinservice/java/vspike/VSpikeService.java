package vspike;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;

public class VSpikeService extends MixinServiceAbstract {
    private final VSpikeClassProvider classProvider = new VSpikeClassProvider();
    private final VSpikeBytecodeProvider bytecodeProvider = new VSpikeBytecodeProvider();

    public String getName(){ return "VSpike"; }
    public boolean isValid(){ return true; }
    public IClassProvider getClassProvider(){ return classProvider; }
    public IClassBytecodeProvider getBytecodeProvider(){ return bytecodeProvider; }
    public ITransformerProvider getTransformerProvider(){ return null; }
    public IClassTracker getClassTracker(){ return null; }
    public IMixinAuditTrail getAuditTrail(){ return null; }
    public IFeatureValidator getFeatureValidator(){ return null; }
    public IAdviceProvider getAdviceProvider(){ return null; }
    public Collection<String> getPlatformAgents(){ return Collections.emptyList(); }
    public IContainerHandle getPrimaryContainer(){ return new ContainerHandleVirtual(getName()); }
    public InputStream getResourceAsStream(String name){
        ClassLoader c = Thread.currentThread().getContextClassLoader();
        if (c == null) c = VSpikeService.class.getClassLoader();
        return c.getResourceAsStream(name);
    }

    /** After MixinBootstrap.init() has offered the factory, create the transformer. */
    public IMixinTransformer createTransformer(){
        IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
        if (factory == null) throw new IllegalStateException("Mixin transformer factory not yet offered — call createTransformer() after MixinBootstrap.init()");
        return factory.createTransformer();
    }
}
