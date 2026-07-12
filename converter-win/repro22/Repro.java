import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Bug #22 offline reproducer driver. Run with -javaagent:repro-agent.jar and the loom minecraft-client.jar
 * (+ ASM + sidecar stub for cft mode) on the classpath:
 *
 *   java -javaagent:repro-agent.jar -cp "out;asm.jar;minecraft-client.jar" Repro <mode> [iters]
 *
 * Mirrors the live failure sequence:
 *   1. GuiMessage is LOADED (not initialized) - like MC at late attach (klass state was 'linked' in hs_err).
 *   2. retransformClasses with the mode's byte delta (like FullInjectAgent's converted target').
 *   3. GC churn (old-constant-pool / previous-version purge happens at class-unloading cycles).
 *   4. getDeclaredFields() + canonical-ctor construction, repeatedly, multi-threaded.
 * Exit code 0 = survived; NoSuchFieldError / hs_err crash = reproduced.
 */
public class Repro {
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";

    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "none";
        int iters = a.length > 1 ? Integer.parseInt(a[1]) : 300;
        ReproAgent.MODE = mode;
        System.out.println("[REPRO] mode=" + mode + " jvm=" + System.getProperty("java.vm.version"));

        Class<?> gm = Class.forName(GM, false, Repro.class.getClassLoader());   // load, do NOT initialize
        System.out.println("[REPRO] loaded (not initialized): " + gm.getName());

        ReproAgent.INST.retransformClasses(gm);
        System.out.println("[REPRO] retransform OK");

        // stage 1: pure reflection like GuiMsgProbe (this is what segfaulted live), with GC churn
        for (int i = 0; i < iters; i++) {
            Field[] fs = gm.getDeclaredFields();
            if (i == 0) { StringBuilder s = new StringBuilder(); for (Field f : fs) s.append(f.getName()).append(' '); System.out.println("[REPRO] fields: " + s); }
            gm.getRecordComponents();
            if (i % 10 == 0) System.gc();
        }
        System.out.println("[REPRO] stage1 (reflection x" + iters + " + gc) OK");

        // stage 2: canonical ctor - the exact resolution site of the live NoSuchFieldError - incl. class init
        Class<?> comp = Class.forName("net.minecraft.network.chat.Component", false, Repro.class.getClassLoader());
        Class<?> sig  = Class.forName("net.minecraft.network.chat.MessageSignature", false, Repro.class.getClassLoader());
        Class<?> src  = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", false, Repro.class.getClassLoader());
        Class<?> tag  = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, Repro.class.getClassLoader());
        Constructor<?> ctor = gm.getDeclaredConstructor(int.class, comp, sig, src, tag);
        for (int i = 0; i < iters; i++) {
            Object m = ctor.newInstance(i, null, null, null, null);
            if (i == 0) System.out.println("[REPRO] first construction OK: " + m.getClass().getName() + " addedTime=" + call(m, "addedTime"));
            if (i % 10 == 0) System.gc();
        }
        System.out.println("[REPRO] stage2 (ctor x" + iters + " + gc) OK");

        // stage 3: multithreaded mixed load (timing-dependent effects)
        Thread[] ts = new Thread[4];
        final Throwable[] err = new Throwable[1];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) { gm.getDeclaredFields(); ctor.newInstance(i, null, null, null, null); }
                } catch (Throwable x) { err[0] = x; }
            });
            ts[t].start();
        }
        for (int i = 0; i < 20; i++) { System.gc(); Thread.sleep(20); }
        for (Thread t : ts) t.join();
        if (err[0] != null) { System.out.println("[REPRO] stage3 FAILED:"); err[0].printStackTrace(System.out); System.exit(2); }
        System.out.println("[REPRO] stage3 (4 threads mixed + gc) OK");
        System.out.println("[REPRO] SURVIVED mode=" + mode);
    }

    static Object call(Object o, String m) { try { return o.getClass().getMethod(m).invoke(o); } catch (Throwable t) { return "ERR:" + t; } }
}
