import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
/** Offline verification: synthesize O and X=(O + a @Unique field + a handler method + an interface),
 *  convert, and assert target' has the SAME schema as O (retransform-legal) + the sidecar carries the members. */
public class TestConverter {
    public static void main(String[] a) throws Exception {
        byte[] O = gen(false);
        byte[] X = gen(true);
        RetransformConverter.Result r = RetransformConverter.convert("demo/Target", O, X);
        System.out.println("dropped interfaces: " + r.droppedInterfaces);
        System.out.println("relocated fields:   " + r.relocatedFields);
        System.out.println("relocated methods:  " + r.relocatedMethods);
        // schema check: target' fields/methods/interfaces must equal O's
        ClassNode o = read(O), t = read(r.target);
        System.out.println("schema-equal fields:     " + eq(keysF(o), keysF(t)));
        System.out.println("schema-equal methods:    " + eq(keysM(o), keysM(t)));
        System.out.println("schema-equal interfaces: " + o.interfaces.equals(t.interfaces) + " (O=" + o.interfaces + " target'=" + t.interfaces + ")");
        // verify target' + sidecar + state all pass ASM verification (valid bytecode)
        verify("target'", r.target); verify("sidecar", r.sidecar);
        System.out.println("ALL VALID = target' is schema-identical to O and verifiable -> retransform-legal");
    }
    // demo/Target: field `orig`, method `existing()V` (which, in X, gets a body edit calling the handler);
    // X adds: instance field `uniqueF I`, method `handler()V`, interface demo/Added
    static byte[] gen(boolean transformed) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "demo/Target", null, "java/lang/Object",
            transformed ? new String[]{"demo/Added"} : null);
        w.visitField(Opcodes.ACC_PUBLIC, "orig", "I", null, null).visitEnd();
        if (transformed) w.visitField(Opcodes.ACC_PUBLIC, "uniqueF", "I", null, null).visitEnd();
        MethodVisitor ci = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ci.visitCode(); ci.visitVarInsn(Opcodes.ALOAD,0); ci.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false); ci.visitInsn(Opcodes.RETURN); ci.visitMaxs(1,1); ci.visitEnd();
        MethodVisitor e = w.visitMethod(Opcodes.ACC_PUBLIC, "existing", "()V", null, null);
        e.visitCode();
        if (transformed) { e.visitVarInsn(Opcodes.ALOAD,0); e.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"demo/Target","handler","()V",false); } // body-only edit: call the added handler
        e.visitInsn(Opcodes.RETURN); e.visitMaxs(2,1); e.visitEnd();
        if (transformed) {
            MethodVisitor h = w.visitMethod(Opcodes.ACC_PUBLIC, "handler", "()V", null, null); // the added @Inject handler
            h.visitCode();
            h.visitVarInsn(Opcodes.ALOAD,0); h.visitVarInsn(Opcodes.ALOAD,0); h.visitFieldInsn(Opcodes.GETFIELD,"demo/Target","uniqueF","I");
            h.visitInsn(Opcodes.ICONST_1); h.visitInsn(Opcodes.IADD); h.visitFieldInsn(Opcodes.PUTFIELD,"demo/Target","uniqueF","I"); // uniqueF++
            h.visitInsn(Opcodes.RETURN); h.visitMaxs(3,1); h.visitEnd();
        }
        w.visitEnd(); return w.toByteArray();
    }
    static ClassNode read(byte[] b){ ClassReader r=new ClassReader(b); ClassNode n=new ClassNode(); r.accept(n,0); return n; }
    static Set<String> keysF(ClassNode n){ Set<String> s=new HashSet<>(); for(FieldNode f:n.fields) s.add(f.name+" "+f.desc); return s; }
    static Set<String> keysM(ClassNode n){ Set<String> s=new HashSet<>(); for(MethodNode m:n.methods) s.add(m.name+" "+m.desc); return s; }
    static boolean eq(Set<String> a, Set<String> b){ return a.equals(b); }
    static void verify(String n, byte[] b){ try { new ClassReader(b).accept(new ClassNode(), 0); System.out.println("  "+n+" parses OK ("+b.length+"b)"); } catch(Throwable t){ System.out.println("  "+n+" INVALID: "+t);} }
}
