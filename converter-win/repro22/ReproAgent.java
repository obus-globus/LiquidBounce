import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Bug #22 offline reproducer agent. -javaagent; premain stashes Instrumentation.
 * Repro.main() drives retransformation of net.minecraft.client.multiplayer.chat.GuiMessage with a
 * controlled byte delta (mode) and then exercises reflection + the canonical ctor.
 *
 * Modes:
 *   none  - ASM read->write round-trip of the ORIGINAL bytes (pure constant-pool reshuffle, no semantic delta)
 *   sig   - round-trip + ADD class-level Signature attribute listing interfaces NOT in the interfaces array
 *           (exactly what the converter leaves behind after dropping mixin-added interfaces)
 *   smap  - round-trip + ADD a SourceDebugExtension (Mixin SMAP)
 *   both  - sig + smap
 *   cft   - return EXACT bytes from the file given as -Drepro.cft=<path> (the live-captured CFT output)
 */
public class ReproAgent {
    public static volatile Instrumentation INST;
    public static volatile String MODE = "none";
    static final String TARGET = System.getProperty("repro.target", "net.minecraft.client.multiplayer.chat.GuiMessage").replace('.', '/');
    static final String SIG = "Ljava/lang/Record;Lnet/ccbluex/liquidbounce/interfaces/GuiMessageAddition;Lnet/ccbluex/liquidbounce/interfaces/GuiMessageLineAddition;";
    static final String SMAP = "SMAP\nGuiMessage.java\nMixin\n*S Mixin\n*F\n+ 1 GuiMessage.java\nnet/minecraft/client/multiplayer/chat/GuiMessage.java\n+ 2 MixinGuiMessage.java\nnet/ccbluex/liquidbounce/injection/mixins/minecraft/text/MixinGuiMessage.java\n*L\n1#1,500:1\n1#2,500:501\n*E\n";

    public static void premain(String args, Instrumentation inst) {
        INST = inst;
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {
                if (c == null || !TARGET.equals(n)) return null;   // only act on the retransform
                try {
                    byte[] out = rewrite(b, MODE);
                    System.out.println("[REPRO-CFT] mode=" + MODE + " in=" + b.length + " out=" + (out == null ? -1 : out.length)
                            + " identical=" + java.util.Arrays.equals(b, out));
                    return out;
                } catch (Throwable t) { t.printStackTrace(); return null; }
            }
        }, true);
    }

    static byte[] rewrite(byte[] b, String mode) throws Exception {
        if (mode.equals("cft")) return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("repro.cft")));
        if (mode.equals("cftstrip")) {
            // the fix candidate applied to the REAL live-captured converted bytes: strip record component annotations
            byte[] cft = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("repro.cft")));
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(cft).accept(cn, 0);
            if (cn.recordComponents != null) for (var rc : cn.recordComponents) {
                rc.visibleAnnotations = null; rc.invisibleAnnotations = null;
                rc.visibleTypeAnnotations = null; rc.invisibleTypeAnnotations = null;
            }
            ClassWriter w = new ClassWriter(0);
            cn.accept(w);
            return w.toByteArray();
        }
        if (mode.equals("verbatim")) return b.clone();   // EXACT same bytes back - pure redefinition, zero delta
        if (mode.equals("striprc") || mode.equals("stripfa")) {
            // candidate converter fix: drop (type) annotations from record components (striprc), optionally also
            // from fields (stripfa), while still going through the CP-reordering ASM round-trip.
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(b).accept(cn, 0);
            if (cn.recordComponents != null) for (var rc : cn.recordComponents) {
                rc.visibleAnnotations = null; rc.invisibleAnnotations = null;
                rc.visibleTypeAnnotations = null; rc.invisibleTypeAnnotations = null;
            }
            if (mode.equals("stripfa")) for (var f : cn.fields) {
                f.visibleAnnotations = null; f.invisibleAnnotations = null;
                f.visibleTypeAnnotations = null; f.invisibleTypeAnnotations = null;
            }
            ClassWriter w = new ClassWriter(0);
            cn.accept(w);
            return w.toByteArray();
        }
        final boolean sig = mode.equals("sig") || mode.equals("both");
        final boolean smap = mode.equals("smap") || mode.equals("both");
        ClassReader cr = new ClassReader(b);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public void visit(int ver, int acc, String name, String signature, String superName, String[] ifs) {
                super.visit(ver, acc, name, sig ? SIG : signature, superName, ifs);
            }
            @Override public void visitSource(String source, String debug) {
                super.visitSource(source, smap ? SMAP : debug);
            }
        }, 0);
        return cw.toByteArray();
    }
}
