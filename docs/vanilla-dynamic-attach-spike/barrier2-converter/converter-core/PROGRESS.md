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
