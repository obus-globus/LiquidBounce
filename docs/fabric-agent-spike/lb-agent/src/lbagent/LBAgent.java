package lbagent;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
/** Non-mod agent: hooks FabricMixinBootstrap.init to inject LiquidBounce into the live loader. */
public class LBAgent {
    static final String TARGET = "net/fabricmc/loader/impl/launch/FabricMixinBootstrap";
    public static void premain(String args, Instrumentation inst){
        System.out.println("[LBAGENT] premain: hooking FabricMixinBootstrap (LB via agent, not a mod)");
        inst.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf){
                if (!TARGET.equals(name)) return null;
                try {
                    ClassReader cr = new ClassReader(buf); ClassNode cn = new ClassNode(); cr.accept(cn, 0);
                    for (MethodNode m : cn.methods) if (m.name.equals("init"))
                        for (AbstractInsnNode in : m.instructions.toArray())
                            if (in.getOpcode()==Opcodes.RETURN)
                                m.instructions.insertBefore(in, new MethodInsnNode(Opcodes.INVOKESTATIC,"lbagent/LBHook","onReady","()V",false));
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(cw);
                    System.out.println("[LBAGENT] rewrote FabricMixinBootstrap.init");
                    return cw.toByteArray();
                } catch (Throwable t){ t.printStackTrace(); return null; }
            }
        }, false);
    }
}
