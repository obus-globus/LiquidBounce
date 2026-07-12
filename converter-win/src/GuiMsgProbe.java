import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * guimsgprobe.jar — bug #22 characterization probe. Attach to the SAME MC after full-agent init (menu or in-world).
 *
 * ORDERING MATTERS (learned in run 1): getDeclaredFields() on the retransformed record GuiMessage caused a native
 * VM crash (EXCEPTION_ACCESS_VIOLATION in Class.getDeclaredFields0 — see evidence\bug22\hs_err_pid31380.log), so
 * reflection over fields goes LAST, and everything runs sequentially on the MC main thread:
 *   1. direct canonical-ctor construction of GuiMessage (the documented NoSuchFieldError site)
 *   2. same for GuiMessage$Line (also a record, also mixin-targeted)
 *   3. the real LB path (ChatHudExtensionKt.addMessage)
 *   4. getDeclaredFields ground truth (may hard-crash the VM -> keep last)
 */
public class GuiMsgProbe {
    static final String GM = "net.minecraft.client.multiplayer.chat.GuiMessage";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        final boolean withReflect = args != null && args.contains("reflect");
        final ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> gmSeen = null;
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(GM)) { gmSeen = c; break; }
        System.out.println("[PROBE22] GuiMessage loaded=" + (gmSeen != null));

        final Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, sys);
        final Object mc = mcCls.getMethod("getInstance").invoke(null);
        Runnable work = () -> {
            try {
                Class<?> gm = Class.forName(GM, false, sys);
                Class<?> comp = Class.forName("net.minecraft.network.chat.Component", false, sys);
                Class<?> sig = Class.forName("net.minecraft.network.chat.MessageSignature", false, sys);
                Class<?> src = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageSource", false, sys);
                Class<?> tag = Class.forName("net.minecraft.client.multiplayer.chat.GuiMessageTag", false, sys);
                Object text = comp.getMethod("literal", String.class).invoke(null, "probe22-direct");
                Object sysClient = src.getField("SYSTEM_CLIENT").get(null);
                Object msg = null;
                // 1. direct canonical ctor
                try {
                    Constructor<?> ctor = gm.getDeclaredConstructor(int.class, comp, sig, src, tag);
                    msg = ctor.newInstance(1, text, null, sysClient, null);
                    System.out.println("[PROBE22] step1 direct GuiMessage ctor OK: " + msg);
                } catch (Throwable t) { System.out.println("[PROBE22] step1 direct GuiMessage ctor FAILED:"); deepPrint(t); }
                // 2. GuiMessage$Line ctor (Line(GuiMessage, FormattedCharSequence, boolean))
                try {
                    if (msg != null) {
                        Class<?> line = Class.forName(GM + "$Line", false, sys);
                        Class<?> fcs = Class.forName("net.minecraft.util.FormattedCharSequence", false, sys);
                        Object empty = fcs.getField("EMPTY").get(null);
                        Constructor<?> lc = line.getDeclaredConstructor(gm, fcs, boolean.class);
                        Object ln = lc.newInstance(msg, empty, true);
                        System.out.println("[PROBE22] step2 GuiMessage$Line ctor OK: " + ln.getClass().getName());
                    } else System.out.println("[PROBE22] step2 skipped (no msg)");
                } catch (Throwable t) { System.out.println("[PROBE22] step2 Line ctor FAILED:"); deepPrint(t); }
                // 3. real LB path
                try {
                    Object gui = mcCls.getField("gui").get(mc);
                    Object hud = gui.getClass().getField("hud").get(gui);
                    Object chat = hud.getClass().getMethod("getChat").invoke(hud);
                    Object text2 = comp.getMethod("literal", String.class).invoke(null, "probe22-lb-path");
                    Class<?> ext = Class.forName("net.ccbluex.liquidbounce.utils.client.ChatHudExtensionKt", false, sys);
                    Class<?> chatCls = Class.forName("net.minecraft.client.gui.components.ChatComponent", false, sys);
                    Method add = ext.getMethod("addMessage", chatCls, comp, String.class, int.class);
                    add.invoke(null, chat, text2, "probe22", 1);
                    System.out.println("[PROBE22] step3 LB addMessage dispatched (inner mc.execute -> next tick)");
                } catch (Throwable t) { System.out.println("[PROBE22] step3 LB addMessage FAILED:"); deepPrint(t); }
                // 4. field reflection (CRASHED THE VM in run 1 -> gated behind arg "reflect")
                if (withReflect) {
                    try {
                        StringBuilder fs = new StringBuilder();
                        for (Field f : gm.getDeclaredFields()) fs.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
                        System.out.println("[PROBE22] step4 declaredFields: " + fs);
                        System.out.println("[PROBE22] step4 isRecord=" + gm.isRecord());
                    } catch (Throwable t) { System.out.println("[PROBE22] step4 reflect FAILED:"); deepPrint(t); }
                } else System.out.println("[PROBE22] step4 (reflection) skipped — pass agent arg 'reflect' to run it");
                System.out.println("[PROBE22] all steps done");
            } catch (Throwable t) { System.out.println("[PROBE22] setup FAILED:"); deepPrint(t); }
        };
        mcCls.getMethod("execute", Runnable.class).invoke(mc, work);
        System.out.println("[PROBE22] scheduled main-thread probes (reflect=" + withReflect + ")");
    }

    static void deepPrint(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && (r instanceof java.lang.reflect.InvocationTargetException || r instanceof RuntimeException)) r = r.getCause();
        r.printStackTrace(System.out);
    }
}
