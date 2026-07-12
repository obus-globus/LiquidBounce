import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import java.io.File; import java.lang.instrument.Instrumentation; import java.nio.file.Path; import java.security.ProtectionDomain;

/** STUB — Fabric (Knot) late-attach platform. See converter-win/design/FABRIC-LATE-ATTACH.md. Phase 1 wires only
 *  detection + the interface shape; every seam that needs real Knot work throws. */
final class FabricPlatform implements LoaderPlatform {
    /** §1a — FabricLauncherBase.getLauncher().getTargetClassLoader() (KnotClassLoader). */
    public ClassLoader targetLoader(){ throw nyi(); }
    /** §3.2 — extract agent-libs, launcher.addToClassPath(lbJar,"net.ccbluex") + deps; do NOT bundle ASM (§1e). */
    public Path stageBundle(Instrumentation inst, File agentJar){ throw nyi(); }
    /** §1c/§3.3 — Mixins.addConfiguration into the LIVE MixinServiceKnot; no second service standup. */
    public void initMixin(){ throw nyi(); }
    /** §1c — reuse Knot's live transformer via MixinServiceKnot.getTransformer(). */
    public IMixinTransformer transformer(){ throw nyi(); }
    public MixinEnvironment environment(){ throw nyi(); }
    /** §1f — parse liquidbounce.accesswidener as DATA only for the already-loaded INACC/Resolver. */
    public Object accessWidener(){ throw nyi(); }
    /** §1f — Knot's ClassTweaker widens FUTURE classes, so the CFT does NOT apply AW itself. */
    public boolean cftAppliesAw(){ return false; }
    public byte[] applyAw(String internalName, byte[] bytes){ throw nyi(); }
    /** §1d — ((KnotClassDelegate.ClassLoaderAccess) knotCL).defineClassFwd(name,bytes,0,len,cs). */
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ throw nyi(); }
    /** §1b — Knot getRawClassBytes(name) as the schema baseline. */
    public byte[] originalBytes(String internalName){ throw nyi(); }
    /** §3.2 — read bundled resources from the agent jar / process classpath. */
    public byte[] bundleResource(String path){ throw nyi(); }
    private static UnsupportedOperationException nyi(){ return new UnsupportedOperationException("not yet implemented"); }
}
