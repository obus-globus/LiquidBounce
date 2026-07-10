# Converter build — progress + discovered complications (hard-stop)

## Verified LIVE (auto-converter: real Mixin engine -> RetransformConverter -> retransform onto
## ALREADY-LOADED net.minecraft.client.Minecraft in a running bare MC; handler observable):

| category | status | evidence |
|---|---|---|
| `@Inject` handler -> external static | CLEAN, verified live | handler fires every tick |
| instance `@Unique` field -> external State (identity map) | CLEAN, verified live | convTickCount 100/200/300/400 |
| static `@Unique` -> sidecar static | CLEAN (in code) | offline |
| `@Local(argsOnly)` param -> passed to relocated handler | CLEAN, verified live | captured param=1.0 |
| added interface -> dropped | CLEAN | offline schema-equal |

## DISCOVERED complications (need judgment / non-trivial extension — NOT the simple mechanical form
## the residue map assumed):

### A. MixinExtras `@WrapOperation`/`@Wrap*` — invokedynamic + bridge dispatch
Empirically: `@WrapOperation` makes MixinExtras add a BRIDGE method to the target
(`mixinextras$bridge$get$NNN(Object[])`) and dispatch it through an **`Operation` lambda created via
`invokedynamic`** (LambdaMetafactory). The bridge `MethodHandle` lives in the invokedynamic's
BOOTSTRAP ARGS, not a plain `MethodInsnNode`. The converter relocates the bridge method fine, but the
call-site (the invokedynamic Handle + the lambda's captured `this`) is not rewritten by a
MethodInsnNode-only pass -> at runtime `NoSuchMethodError: Minecraft.mixinextras$bridge$get$NNN`.
Fix = extend the converter to rewrite `InvokeDynamicInsnNode` bootstrap `Handle` args that point to
relocated (target) methods -> the sidecar static, and re-thread the captured `this` as the static's
param0. Mechanical but non-trivial (lambda-capture semantics). ~200 LB uses touch MixinExtras.

### B. `@Shadow` of a PRIVATE member — reflection, not AccessWidener
For the retro-apply path the target is ALREADY LOADED without the AccessWidener. Widening a field's
access flags via `retransformClasses` is REJECTED by HotSpot:
`UnsupportedOperationException: class redefinition failed: attempted to change the schema (add/remove fields)`
(HotSpot rejects any field-modifier change, not just add/remove). So the sidecar cannot read a
private shadowed member via a widened GETFIELD. Fix = the converter rewrites GETFIELD/PUTFIELD/INVOKE
of a private shadowed member -> a reflective `MethodHandle`/`Field.setAccessible` accessor in the
sidecar (no schema change on the target). Mechanical but a distinct code path from the on-load AW flip.

## Manual residue (the original hard-stop, unchanged): 5 hard `@Local` sites (esp.
## MixinPlayerTabOverlay `LocalIntRef` write-back), the `ItemCooldownsAddition.Entry` type-move.

## UPDATE — A and B built + verified live (all four categories, one retransform onto already-loaded Minecraft)

`[CONVAUTO][LIVE]` (single live run, MC stays alive, retransform SUCCESS, 0 NoSuchMethod/Field/Verify/crash):
- handler+field: convTickCount 100/200/300/400 (external State works)
- @Local(argsOnly): captured param=1.0 passed to relocated handler
- **A @WrapOperation: handler fired — invokedynamic bridge Handle rewired to the sidecar static, no NoSuchMethodError**
- **B @Shadow-of-private (window): read OK via the reflective sidecar accessor (no AW, no schema change)**

A = `RetransformConverter.rewriteRefs` now rewrites `InvokeDynamicInsnNode` bootstrap `Handle`s that point
at a relocated target method -> `H_INVOKESTATIC` on the sidecar with the target prepended as param0
(LambdaMetafactory adapts the captured `this` to a leading static param identically to an instance receiver).
B = non-public target field access from the sidecar is rewritten to a reflective accessor (`Field.setAccessible`,
cached static, primitive get/set via `Field.getInt`/`setInt` etc.) instead of an AccessWidener flip (which
retransform rejects).

## Mechanical categories now handled by the converter (verified live unless noted):
@Inject handler, instance @Unique field, static @Unique (offline), @WrapOperation/@Wrap* (invokedynamic),
@Shadow-of-private (reflection), @Local(argsOnly + clean early-in-scope locals — same relocation path), interface-drop.

## @Local disposition
CLEAN (convert): the 23 argsOnly (params) + ~13 single early/in-scope locals — the relocated handler receives
the captured value as an argument passed by the body-edit (same path proven for argsOnly). Verified live for argsOnly.
LEAVE TO ON-LOAD PATH (default, small live-attach coverage loss — named):
- MixinPlayerTabOverlay.hookTabColumnHeight — @Local LocalIntRef rows/cols WRITE-BACK (mutates the target frame;
  a pass-by-value external static cannot express it).
- MixinFireworkRocketEntity.hookExtendedFirework — @Local(ordinal) Vec3 lookAngle/movement (transient intermediates).
- MixinPlayerTabOverlay.hookRenderPlayerBackground — @Local int i loop-induction counter.
- MixinScreenRectangle recycle injects — @Local Vector2f pooled corner vectors (debug-name dependent).
- MixinChatComponent.hookAddVisibleMessage — @Local List lines (interior computed list).
These ~5 mixins retain the on-load agent (premain/attach-early), which has no @Local restriction; only their
retro-apply-to-already-loaded coverage is deferred.
