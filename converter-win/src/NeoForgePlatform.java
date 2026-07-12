import java.io.File; import java.lang.instrument.Instrumentation; import java.nio.file.Path; import java.security.ProtectionDomain;

/** STUB — NeoForge (FML / TransformingClassLoader) late-attach platform. See converter-win/design/NEOFORGE-LATE-ATTACH.md.
 *  Phase 1 wires only detection + the interface shape; every seam that needs real FML work throws. */
final class NeoForgePlatform implements LoaderPlatform {
    /** §1a — the TransformingClassLoader (found via a loaded net.minecraft.* class's getClassLoader()). */
    public ClassLoader targetLoader(){ throw nyi(); }
    /** §1a/§3.3 — install LbLoader as the TCL fallback + registerParentLoaders; extract agent-libs. */
    public Path stageBundle(Instrumentation inst, File agentJar){ throw nyi(); }
    /** §1c/§3.2 — register LB configs into the LIVE FMLMixinService (emitRegister); no second service standup. */
    public void initMixin(){ throw nyi(); }
    /** §1c — borrow FML's live transformer (behind reflection). */
    public byte[] transform(String dotted, byte[] originalO){ throw nyi(); }
    public byte[] generateClass(String dotted){ throw nyi(); }
    /** §1e — live-class Resolver drives already-loaded non-public access; AW parsed as data. */
    public Object accessWidener(){ throw nyi(); }
    /** §1e — FML's AccessTransformerEngine widens FUTURE classes on load, so the CFT does NOT apply AW itself. */
    public boolean cftAppliesAw(){ return false; }
    public byte[] applyAw(String internalName, byte[] bytes){ throw nyi(); }
    /** §1d — define sidecars into an LB-owned package via LbLoader + parentLoaders (JPMS module ownership). */
    public boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd){ throw nyi(); }
    /** §1b — O comes from the retransform buffer; hierarchy metadata resolves via the TCL. */
    public byte[] originalBytes(String internalName){ throw nyi(); }
    /** §3.2 — read bundled resources from the agent jar / process classpath. */
    public byte[] bundleResource(String path){ throw nyi(); }
    private static UnsupportedOperationException nyi(){ return new UnsupportedOperationException("not yet implemented"); }
}
