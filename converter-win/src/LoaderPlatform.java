import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import java.io.File; import java.lang.instrument.Instrumentation; import java.nio.file.Path; import java.security.ProtectionDomain;

/** Host-side abstraction over the loader-specific seams FullInjectAgent needs. Vanilla stands up its OWN Sponge Mixin
 *  service on the SYSTEM loader (VanillaPlatform); Fabric/NeoForge defer into the live Knot/FML service on their own
 *  loader (see converter-win/design/{FABRIC,NEOFORGE}-LATE-ATTACH.md §3). The transactional convert/verify/publish
 *  algorithm, the CFT, the kick, and RetransformConverter stay in FullInjectAgent and drive these primitives. */
interface LoaderPlatform {
    /** Coupling #1: loader that holds net.minecraft.* and the staged LB bundle (vanilla: system; Fabric: Knot;
     *  NeoForge: TransformingClassLoader). All Class.forName / kick resolution routes through this. */
    ClassLoader targetLoader();

    /** Coupling #2/#7: stage the bundled LB+deps onto the target loader and define the lbrt runtime helpers; returns
     *  the extracted liquidbounce.jar (for caller preflight). agentJar is the agent's own jar. */
    Path stageBundle(Instrumentation inst, File agentJar) throws Exception;

    /** Coupling #3: stand up (vanilla) or acquire (modded) the Mixin service, register LB configs, and expose the
     *  transformer/environment used to compute X and generate synthetics. Call once, after stageBundle. */
    void initMixin() throws Exception;
    IMixinTransformer transformer();
    MixinEnvironment environment();

    /** Coupling #4: the parsed AccessWidener (as data, for the already-loaded INACC/Resolver computation). */
    Object accessWidener();
    /** Coupling #4: whether the CFT applies the AccessWidener to FUTURE MC classes itself. Vanilla=true (it owns AW);
     *  Fabric/NeoForge=false (Knot ClassTweaker / FML AT widen future classes on load). */
    boolean cftAppliesAw();
    /** Coupling #4: widen bytes on-load (only meaningful when cftAppliesAw()); null if unchanged/failed. */
    byte[] applyAw(String internalName, byte[] bytes);

    /** Coupling #7: define a generated class (target'/sidecar/state/synthetic/runtime) into the target loader with the
     *  given ProtectionDomain. true on success or benign already-defined, false on a real ClassFormat/Verify defect. */
    boolean defineClass(String dotted, byte[] bytes, ProtectionDomain pd);

    /** Coupling #5: ORIGINAL (schema-baseline) bytes of a class by internal name (no ".class"); null if unavailable. */
    byte[] originalBytes(String internalName);

    /** Coupling #6: read an agent-bundle resource by path (e.g. lb-mixin-targets.txt); null if absent. */
    byte[] bundleResource(String path);
}
