# Barrier ② converter — verified residue map (measure-twice pass)

Deep verification (actual file inspection + a real benchmark) of the "awkward residue" from the
converter probe — the constructs that don't obviously rewrite to retransform-legal (body-only +
external state) form. **Headline: there are zero genuinely-non-convertible constructs in LB.**
Everything converts; the residue is a handful of hand-touched cases + a benchmarked-negligible
perf cost.

## Category 1 — `@Unique` fields (35 fields) — perf residue: effectively nil

Classification (verified by reading every field + its access path):
- **13** static / singleton / derivable (`static final`, `ScopedValue`, `ThreadLocal`, lambda
  constants) → a single external static holder, no per-instance map. Trivial.
- **7** cold + per-instance (chat-message ids, transient-screen fields, occasional packet flags)
  → external store, negligible.
- **15** hot + per-instance — but only **8** are genuinely *multi-instance* hot
  (`MixinEntityRenderState` ×2, `MixinLivingEntity` ×3, `MixinPlayerInfo` ×2, `MixinTextColor` ×1);
  the other 7 (`MixinLocalPlayer` ×5, `MixinClientInput` ×2) are on effectively-singleton hosts
  (one live instance) so per-instance storage cost is irrelevant.

**Benchmark** (`field-perf-benchmark.txt`, single thread, 256 instances, 100M accesses):
real field **~0.6 ns/access** vs external per-instance store **~6.8 ns** (unsync IdentityHashMap,
thread-confined) / **~8 ns** (ConcurrentHashMap, thread-safe) → **+~7–8 ns absolute, ~10–13×**.

The 8 genuine-hot fields live on **per-entity / per-render-state / per-remote-player / per-text-color**
paths — dozens-to-low-thousands of accesses per frame, *not* inner loops (no per-block / per-vertex /
per-particle field). +8 ns × hundreds/frame = **sub-microsecond per frame = negligible**. There is
**no field hot enough** for the external-store cost to matter. Worst offenders by class are the
base-class field-adders (`Entity`, `LivingEntity`) — but those are per-entity-per-tick, still not
inner-loop. Perf residue: **effectively nil.** (Mitigations if ever needed: unsync IHM for
thread-confined fields; static holder for the singleton-host ones.)

## Category 2 — `@Local` captures (41 sites / 26 files) — 5 need manual work

- **23** `argsOnly=true` → the captured "local" is a **target method parameter**, always in scope →
  CLEAN (trivial).
- **~13** non-argsOnly but a single early / in-scope named local (re-derivable at the call-site) →
  CLEAN-ish.
- **5 hard/borderline** — the real manual set:
  1. **`MixinPlayerTabOverlay` `@Local LocalIntRef rows/cols`** — a **write-back** into the target's
     locals (`o.set(...)`). A pass-by-value external static can't mutate the caller's frame; needs the
     injected call structured to store the return back into those locals. **The single hardest case.**
  2. **`MixinFireworkRocketEntity`** — `@Local(ordinal=…) Vec3 lookAngle/movement`: intermediate
     arithmetic vectors captured **by ordinal** (no name), existing only transiently at an INVOKE →
     must be re-derived.
  3. `MixinPlayerTabOverlay` render-background — `@Local int i` (loop-induction counter) → call-site
     must sit inside the loop.
  4. `MixinScreenRectangle` — up to 4 `@Local Vector2f` pooled corner vectors captured at RETURN →
     extractable only if local-name debug info survives.
  5. `MixinChatComponent` — `@Local List lines` interior computed list → nameable only if names survive.

Verdict: ~36/41 port cleanly; **5 sites need hand-work**, one (the `LocalIntRef` write-back) genuinely awkward.

## Category 3 — interface-adders (17) — all convert; 1 needs a type move

Verified: all 17 `*Addition` interfaces are LB-defined, **MC/vanilla never casts to or dispatches on
any of them**, none is used in `instanceof`/`is` control flow, stored in an interface-typed collection,
or passed across an API boundary as the interface type.
- **15** are pure mechanical `((XAddition)o).m()` → `LBHooks.m(o)` rewrites (the Kotlin ones already
  ship an `internal inline` wrapper doing exactly `(this as XAddition).m()` — already the LBHooks shape).
- **2** (`PlayerAddition`, `GuiAddition`) have **zero external references** — called only on `this`
  inside their own mixin → just drop the interface, make the method a plain `@Unique` mixin method.
- **1** (`ItemCooldownsAddition`) — trivial cast, but its **nested `record Entry`** is a public return
  type consumed elsewhere → relocate `Entry` to a standalone class. One non-mechanical step.

## Category 4 — census-missed constructs — all retransform-legal

- **`@Overwrite` (7)** — pure body replacement of existing methods (all carry `@author/@reason`); no
  member added. Clean.
- **`@Redirect` (1) / `@WrapOperation` (36) / `@WrapMethod` (4) / `@ModifyReturnValue` (32) /
  `@ModifyExpressionValue` (109) / `@ModifyArg(s)` (19) / `@ModifyConstant` (3)** — emit their
  `Operation`/`Args` bridges as **separate synthetic classes**, not target members; the target gets
  only a body-only call-site edit. Clean, *provided* the transformer defines those synthetics into the
  target's loader at retransform (identical to the on-load path already proven).
- **Static `@Unique` fields (11)** — external static holder. Clean.
- **`@Shadow` (151, 4 `@Mutable`)** — references an existing, access-widened member; no schema change.
  Clean — needs the AccessWidener applied to the loaded target, which is a **body-independent
  access-flag flip, itself retransform-legal**.
- **Awkward targets** (needs-care, not blockers): 5 string `targets=` (resolve the class/inner-class by
  name at retransform); 2 interface/abstract targets (`MixinBlockGetter`, `MixinAddressCheck` — body-only
  on the interface class, retransform the interface not implementors); base-class targets
  (`MixinEntity`/`MixinLivingEntity`/`MixinAbstractClientPlayer` add fields/interfaces to hot,
  universally-loaded base classes — the hardest *instances* of the field/interface-adder classes, handled
  by the external-store/hook converter).
- **Genuinely non-convertible: NONE.** No mixin swaps a superclass, rewrites the interface hierarchy,
  adds a constructor/`<clinit>` member (constructor `@Inject`s are body-only edits of the existing
  `<init>`), or needs a member visible to MC reflection/serialization.

## Bottom line for the "full try" decision

- **No hard blockers.** Every LB mixin construct converts to a retransform-legal form. The converter
  needs three proven capabilities: body-only call-site edits (retransform-legal), the AccessWidener as
  an access-flag flip on the loaded target (retransform-legal), and defining Mixin synthetics into the
  target's loader at retransform (already demonstrated on-load and identical here).
- **Perf residue ≈ nil** — no LB `@Unique` field is on a path hot enough for the +8 ns external-store
  cost to register; the hottest are per-entity/per-render (sub-µs/frame).
- **Manual residue ≈ small** — ~5 `@Local` sites (one write-back genuinely awkward), 1 interface
  nested-type relocation, string-target name resolution. Single-digit hand-touched cases out of 159 files.

So "full injection into a fully-loaded running game" via a schema-neutral converter is engineering
work, **not blocked by any impossible case** — the cut is worth measuring against ~5–10 manual cases and
zero real perf loss, versus the on-load path (`-javaagent`/premain/attach-early) which needs no converter
at all and only misses classes already loaded at attach time.
