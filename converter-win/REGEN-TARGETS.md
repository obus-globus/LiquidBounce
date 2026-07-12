# REGEN-TARGETS.md — how `lb-mixin-targets.txt` is regenerated

`lb-mixin-targets.txt` is the list the converter agent (`FullInjectAgent`) reads as a jar resource. Each line is
ONE mixin **target** class (an MC/library class that a LiquidBounce mixin injects into), dotted, one per line.
`FullInjectAgent` does `line.trim().replace('.','/')` then `getResourceAsStream(internal + ".class")`, so:

- Use the **fully-qualified dotted** name (`net.minecraft.client.gui.Gui`).
- For **inner/nested** targets use a **`$`** separator, NOT a dot (`...GuiRenderState$Node`), because `replace('.','/')`
  must yield `.../GuiRenderState$Node.class`. Writing `GuiRenderState.Node` would wrongly become `GuiRenderState/Node`.

This list is the set of **targets**, not the set of **mixin classes**. Many mixin classes target the same class
(e.g. main `MixinGui` and fabric `MixinGui` both target `net.minecraft.client.gui.Gui`), so the converter dedupes by
target internal name.

## Source of truth

Two things define the active mixin set:

1. The mixin **config JSONs** — they list which mixin CLASSES are active (by simple name, under a `package`):
   - `src/main/resources/liquidbounce.mixins.json`  — `package = net.ccbluex.liquidbounce.injection.mixins`, `client[]` (169 entries)
   - `src/fabric/resources/liquidbounce-fabric.mixins.json` — `package = ...injection.mixins.fabric`, `client[]` (7 entries)
   - (`neoforge/src/main/resources/liquidbounce-neoforge.mixins.json` exists but is NOT loaded by the vanilla agent —
     `FullInjectAgent` only adds `liquidbounce.mixins.json` + `liquidbounce-fabric.mixins.json`.)

2. The **`@Mixin(...)` annotation** on each mixin class gives the target(s). The JSON does NOT contain targets; you MUST
   read the annotation. Forms seen in this repo:
   - `@Mixin(SimpleName.class)` and `@Mixin(value = SimpleName.class, ...)` → resolve `SimpleName` via the file's `import`
     (or a wildcard `import pkg.*;`).
   - `@Mixin(Outer.Inner.class)` → nested target; emit `pkg.Outer$Inner`. (4 of these: `GuiRenderState.Node`,
     `BakedQuad.MaterialInfo`, `GuiMessage.Line`, `DeltaTracker.Timer`.)
   - `@Mixin(targets = "dotted.or/internal/Name")` → string target (used for non-remapped libs: truffle, viaversion,
     and two MC inner classes). Use the string directly, normalizing `/`→`.`.

## Procedure (what produced the checked-in `lb-mixin-targets.txt`)

Run from the repo root. For every `*.java` under
`src/main/java/.../injection/mixins/` and `src/fabric/java/.../injection/mixins/`:

1. Grab the first `@Mixin(...)` line.
2. If it contains `targets`, take each quoted string, normalize `/`→`.`, emit.
3. Else, for each `Name.class` token, find the matching `import ... .Name;` and emit that FQN. If `Name` is a nested
   target (`Outer.Inner.class`) resolve `Outer` via import and emit `pkg.Outer$Inner`. If no explicit import, check for a
   wildcard `import pkg.*;` (e.g. `Style` → `net.minecraft.network.chat.Style`).
4. `sort -u`.

### Two extraction hazards (both hit while producing this file)

- **Nested target whose outer isn't the token.** `@Mixin(GuiRenderState.Node.class)` — a naive `Name.class` regex grabs
  `Node.class`, which has no import → flagged UNRESOLVED. Resolve the OUTER (`GuiRenderState`) and append `$Node`.
- **Nested inner name that collides with an unrelated import.** `@Mixin(DeltaTracker.Timer.class)` in
  `MixinRenderTickCounter` — the inner `Timer` matched the file's `import net.ccbluex.liquidbounce.utils.client.Timer;`
  and silently mis-resolved to the LB `Timer`. The correct target is `net.minecraft.client.DeltaTracker$Timer`. Always
  resolve nested targets from their OUTER name; sanity-check that no target is a `net.ccbluex.*` class.

A robust regenerator would parse the annotation with the OUTER simple name and only fall back to imports for that outer
name. The four `Outer.Inner.class` cases are enumerable, so the pragmatic path (used here) is: extract, then hand-fix the
4 nested targets.

## Result (this run)

- **151 distinct targets** (checked in as `converter-win/lb-mixin-targets.txt`). Matches the docs' "~146".
- The ~5 delta vs 146 is expected: this machine is **MC 26.1.2** (the docs/VM were 26.2 — small mixin-set drift), and
  the list includes **library-only targets** that are ABSENT from a bare-vanilla classpath (djl, lithium×2, sodium×3,
  truffle×4, viaversion×1). At runtime `FullInjectAgent` pass-1 does `getResourceAsStream(target+".class")`; a missing
  class returns null → `continue` (skipped cleanly). So on bare vanilla only the classes actually present convert —
  which is why the live count landed at ~146, not 151. Keeping the extras in the file is harmless.

## Verifying against a running MC (optional, most accurate)

The definitive target set is "every class the loaded mixin configs actually transform." To capture it live, log each
`internal` in `FullInjectAgent`'s pass-1 loop where `X != null && !Arrays.equals(X,O)` (i.e. the mixin actually changed
the class). That yields the exact converted set for the running MC version and is the ground truth if the static
extraction ever drifts from a version bump.
