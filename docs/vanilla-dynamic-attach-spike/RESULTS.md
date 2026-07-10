# No-flag dynamic-attach injection — feasibility spike

> **CORRECTION (see `BARRIER1-PROBE.md`):** the Case A "a running MC does not catch SIGQUIT / attach
> fails" result below was a **measurement artifact** — the pid used was the `timeout` wrapper process,
> not the java JVM. The real JVM catches SIGQUIT throughout and `VirtualMachine.attach` works on a live
> MC at any point (no early window, no ptrace). Case B (early attach) still works; the retransform
> schema-change wall (Wall 2) is real and unchanged. Read `BARRIER1-PROBE.md` for the corrected picture.


**Question:** can LB be injected into vanilla MC started with **no flags** (a separate
injector process `VirtualMachine.attach`es), instead of needing `-javaagent` at launch?

**Answer:** yes — via **early-attach-at-boot (Case B)**, not via attach-to-running
(Case A). Case A is doubly blocked; Case B works.

Self-attach caveat: all tests use a **separate injector process** (the normal, allowed
model) — `jdk.attach.allowAttachSelf` is not needed and not used.

## Case A — attach into a running/loaded MC: **BLOCKED (two walls)**

**Wall 1 — you can't even attach.** A running MC **does not catch SIGQUIT**, which the
HotSpot Linux attach handshake requires (it sends SIGQUIT to trigger the target's attach
listener):
- MC at menu: `SigCgt=0x16003` → SIGQUIT (bit 2 / `0x4`) **not** caught.
- a plain JVM (control): `SigCgt=0x101005ccf` → SIGQUIT caught, and attach succeeds.
- `VirtualMachine.attach(mcPid)` → `AttachNotSupportedException: … state is not ready to
  participate in attach handshake`.

So MC-specific signal handling (LWJGL/GLFW drops SIGQUIT at window init — see Wall/Case B
below) makes a running MC un-attachable. Plain JVMs attach fine, so it isn't the environment.

**Wall 2 — even if you could attach, retransform forbids schema changes.** Applying mixins
to already-loaded classes needs `Instrumentation.retransformClasses`, whose contract bans
add/remove of fields/methods/interfaces. Demonstrated on a plain JVM (where attach works):
- body-only retransform → **SUCCESS**
- schema-changing retransform (adds a method) → **FAILED**:
  `java.lang.UnsupportedOperationException: class redefinition failed: attempted to add a method`

LB's mixins add methods/fields (and merge mixin members) → this wall would block them even
if Wall 1 didn't.

## Case B — attach EARLY at boot, before the game classes load: **VIABLE**

SIGQUIT **is** caught early in boot (`SigCgt=0x16007`, bit 2 set) — it's only dropped later
when GLFW inits the window. So a separate injector can attach in that early window and
register a `ClassFileTransformer` **before** the mixin-target classes load; MC then
transforms them **as it loads** — with **no** retransform schema restriction (on-load class
definition, not redefinition). Proven with a warm injector (in-process attach retry):

- MC pid detected at **+1009ms**, `VirtualMachine.attach` + `loadAgent` **OK at +1424ms
  (first attempt)** — inside the early SIGQUIT window.
- `agentmain` registered the transformer for `net/minecraft/client/gui/screens/TitleScreen`
  while it was **not yet loaded**, then:
  `>>> transformed …/TitleScreen AS IT LOADED (20937 → 21234 bytes, schema change accepted
  on-load) <<<` — the class was transformed on load, **including an added method** (schema
  change), which on-load definition permits.

This is the **same `ClassFileTransformer` registration point the pure `-javaagent` uses**, so
the full pure-agent pipeline (standalone Mixin service + AW + the synthetic-injection into the
system loader) plugs in identically — just triggered by early-attach instead of `premain`.

### Honest coverage caveat (the tight part)

The early-attach window has a floor: pid detection + the attach handshake took ~1.4s here, and
the window closes when GLFW inits (drops SIGQUIT), roughly when `Minecraft.<init>` runs. So a
transformer registered at ~1.4s catches everything loaded **after** that (render/screen/client/
world classes — the bulk of LB's targets) but **misses classes loaded in the first ~1s**
(bootstrap/registry-phase classes). A `premain` `-javaagent` has **no** such gap (it's armed
from class 0). Whether the missed early classes matter for LB depends on whether any LB mixin
targets a bootstrap-phase class; that wasn't characterized here. Going faster (native `jattach`,
earlier pid discovery) shrinks but can't eliminate the floor.

## Verdict

No-flag injection is achievable in the **early-attach-at-boot** form (Case B), reusing the
pure-agent transform pipeline. Attach-to-a-running-MC (Case A) is not — blocked first by MC not
catching SIGQUIT, and behind that by the retransform schema-change ban. Sources in `src/`.
