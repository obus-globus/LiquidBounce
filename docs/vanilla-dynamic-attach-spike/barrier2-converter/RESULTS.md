# Barrier ② converter — retro-apply schema-changing mixins to an already-loaded class

**Question:** can LB's **schema-changing** mixins be mechanically rewritten to a
**retransform-legal** (body-only + external state) form and applied to an **already-loaded**
MC class in a live game — closing the only real gap (already-loaded classes at attach time)?

**Verdict: yes on the dominant cases (proven live); the residue is cost/complexity, not
impossibility — because LB owns both the mixin and its call-sites.**

## Kill-shot (live, reproduced continuously)

Attached to a **fully-loaded, running** bare MC (LB absent, `net.minecraft.client.Minecraft`
long since loaded) and retransformed it with a converted mixin carrying **both** dominant
schema-change types:

```
[CONVERT] target net.minecraft.client.Minecraft loaded=true retransformSupported=true
[CONVERT] retransformClasses(ALREADY-LOADED Minecraft) SUCCESS — schema-neutral body-only patch applied live
[CONVERT][LIVE] LBHooks.onTick fired 100x … external-field state=100 (schema-changing mixin retro-applied to ALREADY-LOADED class)
[CONVERT][LIVE] LBHooks.onTick fired 200x … external-field state=200
… (continues every tick)
```

- **added `@Inject` handler → external static** (`LBHooks.onTick(self)`): the handler fires
  every tick, called from a body-only insertion at the head of `Minecraft.tick()`.
- **added `@Unique` field → external per-instance storage** (`IdentityHashMap<Object,long[]>`):
  the per-`Minecraft` counter behaves as a real field would.
- `retransformClasses` **succeeded** on the already-loaded class — no
  `UnsupportedOperationException: attempted to add a method`, because no member was added.

So the Barrier ② wall (retransform forbids schema change) is dissolved by converting the two
schema-change types out of the class and leaving only the call-site body patch.

## What a generic converter does, per construct

| construct | how it converts | verdict |
|---|---|---|
| `@Inject`/`@Redirect`/`@ModifyArg(s)`/`@ModifyVariable`/`@ModifyConstant`/`@Wrap*`/`@Modify*Value` handler | handler body → external static `LBHooks.x(target, args…)`; injection point → body-only call-site insert/replace (the Args/Operation synthetics are already external classes) | **clean** |
| `@Unique` field | field → external `IdentityHashMap`/`ClassValue` keyed by the instance; every read/write in LB code rewritten to a map op | **converts; perf cost on hot paths** |
| `@Accessor`/`@Invoker` | added accessor method → its callers rewritten to reflection/`MethodHandle` on the (widened) member | **clean (more surface)** |
| interface addition (`class Mixin… implements <X>Addition`) | LB's `<X>Addition` duck-typing interface + its `((XAddition)o).m()` call-sites → external `LBHooks.m(o)` + external state | **converts — because LB owns both sides** |
| `@Local` capture | the captured locals must be extractable at the insertion point and passed to the external static | **partial** |

(rendered as a table in the file; Discord gets the prose summary.)

## Census of LB's mixins (159 source files) + residue estimate

- **132** use `@Inject`-family handlers — the bulk, **clean-convert**.
- **37** add `@Unique` fields — convert to external storage; the **awkward part is performance**
  (an `IdentityHashMap` lookup per access is far slower than a real field on a hot path — per
  tick / per entity / per render). Functionally fine, perf-degraded on the hot ones.
- **14** `@Accessor`/`@Invoker` — convert (callers → reflection/MH).
- **17** add an interface — all LB `<Thing>Addition` duck-typing interfaces, cast **only by LB's
  own code** (2–4 sites each; MC never casts to them). Convertible because we bundle + rewrite
  LB too — but it widens the converter's surface (it must rewrite call-sites, not just the mixin).
- **26** use `@Local` capture — **partial**: simple in-scope locals convert; deep/loop-interior
  captures need manual extraction.

**Estimate:** roughly **85–90% mechanically clean-convert** (handlers, accessors, simple
fields/interfaces). The awkward **10–15%** is not "impossible" but "costs something":
hot-path `@Unique` fields (**perf**), complex `@Local` captures (**manual extraction**), and
interface-adders (**caller-rewrite surface**). Genuinely non-convertible cases are essentially
**nil for LB's patterns**, because LB controls both the mixin and every consumer — nothing
requires MC itself to see an added member/interface (MC never dispatches on LB's additions).

## Bottom line

"Full injection into a fully-loaded, running game" is achievable via a schema-neutral converter:
the retransform applies to already-loaded classes and the mixin's effect works live. The blocker
is not correctness but engineering cost — a real converter backend + accepting hot-path perf
degradation on a minority of fields. The clean alternative remains the on-load path
(`-javaagent`/premain, or attach-early), which needs no conversion because on-load definition
already permits schema changes.
