package spike;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class Agent {
    static final String TARGET = "net/fabricmc/loader/impl/launch/FabricMixinBootstrap";
    public static void premain(String args, Instrumentation inst){
        System.out.println("[FSPIKE] premain: installing FabricMixinBootstrap hook (non-mod)");
        inst.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader, String name, Class<?> cbr, ProtectionDomain pd, byte[] buf){
                if (!TARGET.equals(name)) return null;
                try {
                    ClassReader cr = new ClassReader(buf);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    int hooks = 0;
                    for (MethodNode m : cn.methods){
                        if (!m.name.equals("init")) continue;
                        for (AbstractInsnNode insn : m.instructions.toArray()){
                            if (insn.getOpcode() == Opcodes.RETURN){
                                m.instructions.insertBefore(insn, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, "spike/SpikeHook", "onFabricMixinReady", "()V", false));
                                hooks++;
                            }
                        }
                    }
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    System.out.println("[FSPIKE] rewrote FabricMixinBootstrap.init (" + hooks + " return site(s) hooked)");
                    return cw.toByteArray();
                } catch (Throwable t){
                    System.out.println("[FSPIKE] hook transform failed: " + t); t.printStackTrace(); return null;
                }
            }
        }, false);
    }
}
