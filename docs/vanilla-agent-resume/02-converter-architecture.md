# 02 — The schema-neutral converter (architecture)

Canonical source: **`converter-core/RetransformConverter.java`** (+ `FullInjectAgent.java`, `AwReflect.java`) in this directory. These are the culmination versions from the live end-to-end runs. Older/simpler copies: `docs/vanilla-dynamic-attach-spike/barrier2-converter/converter-core/src/`.

---

## The problem it solves

Attaching to a **fully-loaded** MC means the mixin target classes are **already defined**. `Instrumentation.retransformClasses` can change method *bodies*, the constant pool, and attributes — but it **forbids** adding/removing fields or methods, changing signatures/modifiers, or changing NestHost/NestMembers/Record/PermittedSubclasses. LB's mixins add `@Unique` fields/methods, implement duck-typing interfaces, etc. → illegal to apply directly.

**The converter rewrites each mixin's output into a retransform-legal form**: the target keeps the *same schema*, and everything the mixin added is relocated **out** of the target into a sidecar + external state, with all references (in the target, in other mixins, and in LB's own code) routed to that sidecar / to reflection.

Result: `retransformClasses(alreadyLoadedTarget, target')` succeeds; future targets get `target'` via an on-load `ClassFileTransformer`. Verified: **146/146 LB mixin targets convert clean.**

---

## Inputs per target

- `O` = original class bytes (from the loaded MC jar, read via a `CLASS_BYTES` supplier).
- `X` = `O` after LB's real Mixin transformer runs (`IMixinTransformer.transformClassBytes`), with LB's AccessWidener pre-applied on the future path.
- Diff `X` vs `O` → **added fields**, **added methods**, **dropped/added interfaces**.

Output: `target'` (retransform-legal, same schema as `O`) + `<target>$$LBSidecar` + `<target>$$LBSidecar$State`.

---

## Conversion categories (all handled)

### 1. Added instance `@Unique` fields → external per-instance State
- A `State` class (`<sidecar>$State`) holds one field per added instance field.
- A `STATE` map on the sidecar keys `State` **by object identity** — `Collections.synchronizedMap(new IdentityHashMap<>())`. **Must be identity-keyed**, NOT `ConcurrentHashMap`: a mixin-modified `hashCode()`/`equals()` that reads a relocated field would recurse `getState → map.get(self) → self.hashCode() → getState → …` → `StackOverflowError` (hit live on `TextColor`). Identity keying uses `System.identityHashCode`, never the object's `hashCode`.
- Accessors `get$<f>(Target)` / `set$<f>(Target, v)` generated on the sidecar; every `GETFIELD`/`PUTFIELD` of an added field is rewritten to call them.
- Perf: ~0.6 ns direct vs ~7–8 ns external store; negligible for LB (no per-vertex/per-block field). See `docs/vanilla-dynamic-attach-spike/barrier2-converter/RESIDUE-MAP.md`.

### 2. Added static `@Unique` fields → public non-final on the sidecar
- Relocated straight onto the sidecar, **stripped of `private`/`final` and made `public`** (they're now read/written cross-class from `target'`).

### 3. Added methods (handlers, `@Unique` methods) → sidecar statics `h$<name>`
- Instance-added method → `public static h$<name>(Target self, …args)` on the sidecar (the instance receiver `this`/slot0 maps 1:1 to static param0). Static-added → same-signature static.
- Every call to an added method is rewritten to `INVOKESTATIC sidecar.h$<name>(...)`.

### 4. Added `<clinit>` and modified `<clinit>` (static-field initializers)
- **Added `<clinit>`** (mixin added a whole static initializer): merged into the sidecar's own `<clinit>` (NOT relocated as an illegal method named `h$<clinit>`).
- **Modified `<clinit>`** (mixin *appended* init to the target's existing `<clinit>`): the appended **delta** (suffix of `X.<clinit>` past `O`'s body) is copied into the sidecar `<clinit>`. Needed because for an **already-loaded** target the `<clinit>` won't re-run after retransform, so the relocated static field would stay null (hit live on `Gui.RECURSIVE_SCREEN_OPENING` `ScopedValue`).

### 5. `@WrapOperation` / `@Modify*Value` / MixinExtras — invokedynamic Handle rewriting
- These dispatch via `invokedynamic` + a `LambdaMetafactory` bootstrap whose `Handle` args point at relocated target methods. `rewriteRefs` rewrites those bootstrap `Handle`s to `H_INVOKESTATIC` on the sidecar (target prepended — `LambdaMetafactory` adapts a captured receiver to a leading static param identically). The `Operation`/`Args` synthetics are separate synthetic classes, defined into the loader on demand.

### 6. `@Shadow`-private **fields** → reflection (category B-fields)
- Non-public existing target fields accessed from the sidecar can't be AccessWidened on an already-loaded class (retransform bans field modifier changes). Routed through cached reflective accessors (`Field`, `setAccessible`, primitive `getInt`/`setInt` variants), generated on the sidecar (`refGet$<f>`/`refSet$<f>`).

### 7. Private **methods** called from the sidecar → reflection (category B-methods)
- A private existing target method invoked from a relocated body is illegal cross-class. Routed through a cached reflective invoker (`Method.invoke`, boxed args). Handles both `INVOKESPECIAL` **and `INVOKEVIRTUAL`** (nestmate/MixinExtras bridges emit invokevirtual for private methods) — hit live on `ModelBlockRenderer.resetTintCache()`.

### 8. Interface-adders (`class Mixin… implements <X>Addition`)
- The added duck-typing interface is **dropped** from `target'` (`droppedInterfaces`); its methods are `@Unique` methods relocated to the sidecar.
- Casts + interface dispatch are rewritten: `((Iface)o).m(args)` → `CHECKCAST Target` + `INVOKESTATIC sidecar.h$m((Target)o, args)`. Done in **two places**: `rewriteCaller` (LB's own `net/ccbluex/` code) and inside mixin bodies via **GIFACE** (below). Hit live on `GuiAddition` (`Hud`).

### 9. Cross-target dispatch tables — GADDED / GFIELD / GIFACE
- A base-class `@Unique` member (e.g. added to `Entity`) is often called/accessed from a **subclass** mixin (`Player`, `LivingEntity`). The subclass's sidecar must route to the **base class's** sidecar.
- Built in **Phase A** across ALL targets (before any conversion), keyed by `"owner name desc"`:
  - **GADDED** — added methods → `[owner, sidecar, isStatic]`.
  - **GFIELD** — added fields → `[owner, sidecar, "I"|"S"]`.
  - **GIFACE** — dropped interfaces → `[target, sidecar]`.
- Resolution **walks the receiver type's class hierarchy** (`owner → super → …`) to find the declaring target, then routes to that target's sidecar. Hit live on `Entity.liquid_bounce$isClientPlayer()` (GADDED), `Input.initial` (GFIELD), `GuiAddition` in mixin bodies (GIFACE).
- **Keying by owner (not just name+desc) is essential** — the same handler name (`handler$zXX$init`) can be added to multiple targets; keying by name alone collides. Hit live on `Screen.handler$zdn000$init`.

### 10. Inaccessible-type erasure (`AwReflect`, string-based reflection)
- Some MC classes LB references **by type** are package-private *and already loaded* (e.g. `GuiGraphicsExtractor$ScissorStack`, `GuiRenderState$Node`, `Hud$ContextualInfo`, `GuiRenderer$Draw`, `CreativeModeTab$Output`). They can't be AccessWidened (retransform bans **class** modifier changes too — confirmed live), and reflection can't route a *type reference*.
- The set is **INACC** = AW `accessible class` entries ∩ already-loaded ∩ non-public.
- Fix: `AwReflect` (a runtime helper, `lbrt.AwReflect`) does **string-based reflection** — owner + parameter types passed as class-name **strings** and resolved via `Class.forName` (which performs **no access check**), so no inaccessible type ever appears as a constant/CHECKCAST in LB's verified bytecode. All CHECKCASTs to inaccessible types are **erased** (value flows as `Object`). LB's field/method/ctor accesses to non-public members of already-loaded classes are rewritten to `AwReflect` calls (`gO/gI/…`, `sO/…`, `inv`, `newInst`).
- Method-arg loading in the generated trampolines must use the **erased** arg types at the right slots (off-by-one on instance `self` caused a `VerifyError: Bad local variable type`).

### 11. Private constructors of already-loaded classes → reflective `newInst`
- `new AlreadyLoaded$PrivateCtor()` → the `NEW`+`DUP` are removed and the `INVOKESPECIAL <init>` replaced by `INVOKESTATIC` a trampoline that does `Constructor.newInstance`. Hit live on `RenderPipeline$Builder.<init>()`.

### 12. Correct stack-map frames
- The `ClassWriter` uses `COMPUTE_FRAMES`, and `getCommonSuperClass` **reads class bytes** (via the `CLASS_BYTES` supplier) to compute the *real* common supertype without loading classes. The earlier "always return `java/lang/Object`" hack produced **invalid frames** for classes with branch-merges of related types → HotSpot **retransform `VerifyError`** (hit live on `Gui`, `ClientPacketListener`, `ClientCommonPacketListenerImpl`). Fixing frames dropped retransform failures from 6 → 1.

### 13. Nest / Record / PermittedSubclasses attribute preservation
- The Mixin transform can add synthetic nest members; retransform rejects any change to `NestHost`/`NestMembers`/`Record`/`PermittedSubclasses`. `target'.nestHostClass/nestMembers/permittedSubclasses/recordComponents = O`'s. Hit live on `AvatarRenderer`, `MinecraftClient`, `Level`.

---

## Sidecar packaging + definition

- Sidecar lives in the **target's own package** (`<target>$$LBSidecar`) so relocated bodies keep **package-private** access to target-package internals; only genuinely *private* members need reflection (§6/§7).
- Defined via reflective `ClassLoader.defineClass(name, bytes, 0, len, ProtectionDomain)` with the **target's ProtectionDomain** (the MC jar is **signed**; a null/mismatched PD → `SecurityException: signer information does not match`).
- **Eager definition:** ALL 146 sidecars (+ state + synthetics) are defined **up front** in Phase B — already-loaded targets use their own PD, future targets use a reference MC PD (`Minecraft.class.getProtectionDomain()`; all MC classes share the signed jar's CodeSource → matching signers). Without this, a cross-target sidecar reference could execute before that target loads → `NoClassDefFoundError` (hit live on `Inventory$$LBSidecar`, `ClientInput$$LBSidecar` under concurrent ticks).

## Two-phase pass (in `FullInjectAgent`)
- **Phase A:** mixin-transform every target, cache `(O, X)`, and populate GADDED/GFIELD/GIFACE globally.
- **Phase B:** convert each target (rewriting now consults the global tables), then eager-define its sidecar/state/synthetics.

---

## The 5 hard `@Local` residue (left to the on-load path)

These `@Local` captures don't mechanically convert cleanly; documented and **deferred to the on-load path** (they're a single-digit fraction of 159 mixin files). See `docs/vanilla-dynamic-attach-spike/barrier2-converter/RESIDUE-MAP.md` for the full analysis.

1. **`MixinPlayerTabOverlay` — `@Local LocalIntRef rows/cols`** — a **write-back** into the target's locals (`ref.set(...)`). A pass-by-value external static can't mutate the caller's frame. The single hardest case.
2. **`MixinFireworkRocketEntity` — `@Local(ordinal=…) Vec3 lookAngle/movement`** — intermediate arithmetic vectors captured **by ordinal** (no name), existing transiently at an INVOKE → must be re-derived.
3. **`MixinPlayerTabOverlay` render-background — `@Local int i`** — loop-induction counter → call-site must sit inside the loop.
4. **`MixinScreenRectangle`** — up to 4 `@Local Vector2f` pooled corner vectors captured at RETURN → extractable only if local-name debug info survives.
5. **`MixinChatComponent` — `@Local List lines`** — interior computed list → nameable only if names survive.

## The `ItemCooldownsAddition.Entry` note
- `ItemCooldownsAddition` is a trivial interface-adder cast, **but** its nested `record Entry` is a public return type consumed elsewhere → relocate `Entry` to a standalone class (one non-mechanical step). Otherwise all 17 `*Addition` interfaces convert mechanically (15 pure cast-rewrites; `PlayerAddition`/`GuiAddition` are `this`-only → just drop the interface).

---

## Verification status
- **146/146** targets convert clean at scale (`docs/vanilla-dynamic-attach-spike/barrier2-converter/converter-core/MILESTONE1-SCALE.md`).
- All categories verified **live** on already-loaded Minecraft + full in-world run.
- At the menu, only ~6 targets are already-loaded (all retransform-legal after the frame/nest fixes except `ChatComponent`, which adds a field — an accepted degradation, that one HUD mixin absent). In-world, ~94 already-loaded targets retransform; the rest convert on-load.
