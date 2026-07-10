package scagent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;

/**
 * Self-contained non-mod Fabric agent. Same DEFER hook as the spike agent
 * (rewrite FabricMixinBootstrap.init), but the payload (LB + its full dep tree + AccessWidener)
 * is carried INSIDE this jar under agent-libs/ and staged at runtime — no -Dlb.* dev paths,
 * no external staging. A user attaches only `-javaagent:liquidbounce-agent-fabric.jar`.
 */
public class SCAgent {
    static final String TARGET = "net/fabricmc/loader/impl/launch/FabricMixinBootstrap";

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[SCAGENT] premain: self-contained LB agent hooking FabricMixinBootstrap (not a mod)");
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!TARGET.equals(name)) return null;
                try {
                    ClassReader cr = new ClassReader(buf);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    for (MethodNode m : cn.methods)
                        if (m.name.equals("init"))
                            for (AbstractInsnNode in : m.instructions.toArray())
                                if (in.getOpcode() == Opcodes.RETURN)
                                    m.instructions.insertBefore(in, new MethodInsnNode(
                                        Opcodes.INVOKESTATIC, "scagent/SCHook", "onReady", "()V", false));
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    System.out.println("[SCAGENT] rewrote FabricMixinBootstrap.init");
                    return cw.toByteArray();
                } catch (Throwable t) { t.printStackTrace(); return null; }
            }
        }, false);
    }
}
