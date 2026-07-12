import java.io.File; import java.lang.instrument.Instrumentation; import java.nio.file.Path; import java.security.ProtectionDomain;

/** Host-side abstraction over the loader-specific seams FullInjectAgent needs. Vanilla stands up its OWN Sponge Mixin
 *  service on the SYSTEM loader (VanillaPlatform); Fabric/NeoForge defer into the live Knot/FML service on their own
 *  loader. The transactional convert/verify/publish
 *  algorithm, the CFT, the kick, and RetransformConverter stay in FullInjectAgent and drive these primitives. */
interface LoaderPlatform {
    /** Coupling #1: loader that holds net.minecraft.* and the staged LB bundle (vanilla: system; Fabric: Knot;
     *  NeoForge: TransformingClassLoader). All Class.forName / kick resolution routes through this. */
    ClassLoader targetLoader();

    /** Coupling #2/#7: stage the bundled LB+deps onto the target loader and define the lbrt runtime helpers; returns
     *  the extracted liquidbounce.jar (for caller preflight). agentJar is the agent's own jar. */
    Path stageBundle(Instrumentation inst, File agentJar) throws Exception;

    /** Coupling #3: stand up (vanilla) or acquire (modded) the Mixin service and register LB configs. Call once,
     *  after stageBundle. The transformer itself stays loader-internal (Fabric's lives on Knot / behind reflection). */
    void initMixin() throws Exception;

    /** Coupling #3: (Fabric-only) pre-compute the schema baseline for each target while LB's late-added config is still
     *  unvisited, so `originalBytes(target)` returns the "all OTHER mods applied, LB not yet" plane. On Fabric the live
     *  Knot transformer re-applies the whole mod mixin stack (fabric-api/iris/sodium/...) on every call, so the diff
     *  baseline must be that stack MINUS LB — otherwise every other mod's additions are misattributed to LB. No-op on
     *  loaders whose transformer only carries LB's mixins (vanilla). Call once, right before phase A. */
    default void prepareBaselines(java.util.List<String> internalTargets) throws Exception {}

    /** Coupling #3: mixin-transform a target and return X (the mixin output), or null if the mixin did not change the
     *  input (nothing to convert). `originalO` is the schema baseline (originalBytes); the platform chooses the actual
     *  transform INPUT (vanilla: originalO; Fabric: the pre-mixin/AW-applied plane) and hides its own IMixinTransformer,
     *  so this handle is loader-neutral (Fabric's transformer object is Knot-loaded, not castable on the system loader). */
    byte[] transform(String dotted, byte[] originalO) throws Exception;

    /** Coupling #3: generate a Mixin synthetic class by name (org.spongepowered.asm.synthetic.* / $Anonymous$), or null
     *  if none is produced (on Fabric Knot auto-generates most synthetics, so null is normal). */
    byte[] generateClass(String dotted);

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
