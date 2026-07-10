import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
public class ConverterAgent {
    static final ClassDesc CD_void = ClassDesc.ofDescriptor("V");
    static final ClassDesc CD_Object = ClassDesc.of("java.lang.Object");
    static final ClassDesc CD_LBHooks = ClassDesc.of("LBHooks");
    public static void agentmain(String args, Instrumentation inst) {
        Class<?> t = null;
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals("net.minecraft.client.Minecraft")) { t = c; break; }
        System.out.println("[CONVERT] target net.minecraft.client.Minecraft loaded=" + (t != null)
            + " retransformSupported=" + inst.isRetransformClassesSupported());
        if (t == null) { System.out.println("[CONVERT] target not loaded"); return; }
        final String internal = "net/minecraft/client/Minecraft";
        ClassFileTransformer cft = new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                return internal.equals(n) ? rewriteTickHead(b) : null;
            }
        };
        inst.addTransformer(cft, true);
        try {
            inst.retransformClasses(t);
            System.out.println("[CONVERT] retransformClasses(ALREADY-LOADED Minecraft) SUCCESS — schema-neutral body-only patch applied live");
        } catch (Throwable e) {
            System.out.println("[CONVERT] retransform FAILED -> " + e);
        }
    }
    // Converted mixin: insert `LBHooks.onTick(this)` at the HEAD of tick()V. No members added to Minecraft.
    static byte[] rewriteTickHead(byte[] buf) {
        try {
            ClassFile cf = ClassFile.of();
            ClassModel cm = cf.parse(buf);
            return cf.build(cm.thisClass().asSymbol(), clb -> {
                for (var e : cm) {
                    if (e instanceof MethodModel mm && mm.methodName().equalsString("tick") && mm.methodTypeSymbol().equals(MethodTypeDesc.of(CD_void))) {
                        clb.withMethod("tick", mm.methodTypeSymbol(), mm.flags().flagsMask(), mb ->
                            mb.withCode(cob -> {
                                cob.aload(0).invokestatic(CD_LBHooks, "onTick", MethodTypeDesc.of(CD_void, CD_Object));
                                mm.code().get().forEach(cob);
                            }));
                    } else clb.with(e);
                }
            });
        } catch (Throwable t) { System.out.println("[CONVERT] rewrite failed: " + t); return null; }
    }
}
