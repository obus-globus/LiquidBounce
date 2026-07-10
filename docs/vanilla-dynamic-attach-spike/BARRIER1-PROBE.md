# Barrier ① probe — inject into a fully-running, no-flag MC

**Question:** can we get code executing inside a fully-running, no-`-javaagent` MC — the wall
that supposedly blocks attaching to a live game (MC "having dropped SIGQUIT")?

**Verdict: Barrier ① does not exist. Standard `VirtualMachine.attach` works on a live MC.**
And — honest correction — the earlier "MC drops SIGQUIT, attach fails" finding
(`vanilla-dynamic-attach-spike/RESULTS.md`, Case A) was a **measurement artifact**.

## The kill-shot (reproduced)

Genuinely bare MC (verified LB-absent), fully at the menu, no flags. A separate injector:
`VirtualMachine.attach(pid).loadAgent(agent.jar)` → **`loadAgent returned OK`**, and the agent
ran inside the live JVM (printed on MC's "Attach Listener" thread):

```
[ATTACH] agentmain args=caseA retransformSupported=true redefineSupported=true
[ATTACH][A] target net.minecraft.client.Minecraft loaded=true
[ATTACH][A] (a) BODY-ONLY retransform: SUCCESS
[ATTACH][A] (b) SCHEMA-CHANGE retransform: FAILED -> java.lang.UnsupportedOperationException: class redefinition failed: attempted to add a method
```

Reproduced across two attaches to the same live MC, full JVMTI (retransform/redefine
supported). No ptrace, no native `.so`, no `jattach`, **no elevation** — plain `VirtualMachine.attach`.
`ptrace_scope=1` and non-root are irrelevant because ptrace isn't needed.

## The earlier "barrier" was attaching to the `timeout` wrapper, not the JVM

MC was launched under `env … timeout … java …`. `pgrep -f net.minecraft.client.main.Main`
matches **both** the `timeout` wrapper (its argv contains the full java command) and the java
process, and `head -1` returned the **wrapper**. Attaching to that non-JVM process gives exactly
the symptom I misread as a barrier:

```
pid=443435 comm=timeout  SigCgt=0x…16003/16007  (mask = SIGHUP,SIGINT,SIGALRM,SIGTERM,SIGCHLD — no SIGQUIT; a coreutils mask, not a JVM)
pid=443437 comm=java     SigCgt=0x2000000101005ccf  (SIGQUIT caught)
```

- The real **java** JVM catches SIGQUIT for the entire boot→menu (polled continuously; it never
  drops) and is attachable throughout.
- `VirtualMachine.list()` lists only the real JVM (443437), never the wrapper — which is why the
  **watcher** (which uses `list()`, not `pgrep`) always attached to the right process and worked.
- Attaching by the wrapper pid → `AttachNotSupportedException: state is not ready to participate in
  attach handshake` (it's not a JVM) — the "wall" I reported.

**So there is no early SIGQUIT window and no need to race the boot.** The watcher works, but not
for the reason the dynamic-attach spike claimed — attach works at *any* point in a live MC's life,
early or fully-loaded. (The watcher's early attach still has value for *coverage* — see below.)

## What the real wall is: Barrier ② (retransform schema change)

The genuine limit for a **fully-loaded** MC is unchanged and confirmed here: classes already
defined can only be **retransformed body-only**. Applying LB's mixins (which add methods/fields)
to an already-loaded class fails: `UnsupportedOperationException: class redefinition failed:
attempted to add a method`. Classes loaded **after** attach are transformed on-load (initial
definition — no schema restriction), which is why attaching to a *running* MC still injects LB
into everything not-yet-loaded; only the already-loaded classes' schema-changing mixins are blocked.

## Corrected coverage picture

- Inject code into a live no-flag MC: **works, anytime, plainly** (no barrier).
- Full LB via on-load transform: covers all classes loaded after attach. Earlier attach ⇒ fewer
  already-loaded ⇒ more coverage (that is the watcher's actual value — not beating a SIGQUIT window,
  but minimizing the already-loaded set). Already-loaded classes with schema-changing mixins are the
  only gap, and only `retransform` (Barrier ②) — not attach (Barrier ①) — blocks those.
