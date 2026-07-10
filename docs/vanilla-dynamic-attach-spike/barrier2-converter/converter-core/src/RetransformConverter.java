import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Schema-neutral converter core. Given an ALREADY-LOADED class's original bytes O and the Mixin-transformed
 * bytes X (X = O + added members {fields, methods, interfaces} + body-only call-site edits), produce:
 *   - target': X with all ADDED members removed and their references rewritten to an external sidecar
 *     -> same schema as O (same fields/methods/interfaces) -> legal for Instrumentation.retransformClasses.
 *   - sidecar: a standalone class holding the relocated state + handler methods.
 *
 * Relocation:
 *   added instance field  -> a field on a per-instance State object kept in an IdentityHashMap (one map),
 *                            accessed via static get_/set_ helpers (no boxing; matches the benchmark).
 *   added static field    -> a static field on the sidecar.
 *   added method          -> a static method on the sidecar with the target as param0 (instance slot0=this
 *                            maps 1:1 to static slot0=param0, so bodies need no local reindexing).
 *   added interface       -> dropped from target' (external LB call-sites are rewritten at build time).
 */
public class RetransformConverter {
    public static final class Result {
        public byte[] target, sidecar; public String sidecarName;
        public List<String> droppedInterfaces = new ArrayList<>();
        public List<String> relocatedFields = new ArrayList<>(), relocatedMethods = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }

    final String targetInternal, targetDesc, sidecar, stateName;
    final Set<String> addedFieldKeys = new HashSet<>();   // name+" "+desc
    final Set<String> addedInstanceFields = new HashSet<>(); // name (instance only)
    final Set<String> addedStaticFields = new HashSet<>();
    final Set<String> addedMethodKeys = new HashSet<>();  // name+" "+desc
    final Map<String,String> fieldDesc = new HashMap<>();

    RetransformConverter(String targetInternal) {
        this.targetInternal = targetInternal;
        this.targetDesc = "L" + targetInternal + ";";
        this.sidecar = "lbconv/" + targetInternal.replace('/', '_') + "$Sidecar";
        this.stateName = sidecar + "$State";
    }

    public static Result convert(String targetInternal, byte[] original, byte[] transformed) {
        return new RetransformConverter(targetInternal).run(original, transformed);
    }

    Result run(byte[] original, byte[] transformed) {
        ClassNode O = read(original), X = read(transformed);
        Result r = new Result(); r.sidecarName = sidecar;

        Set<String> oF = keysF(O), oM = keysM(O); Set<String> oI = new HashSet<>(O.interfaces);
        List<FieldNode> addedFields = new ArrayList<>();
        for (FieldNode f : X.fields) if (!oF.contains(f.name + " " + f.desc)) {
            addedFields.add(f); addedFieldKeys.add(f.name + " " + f.desc); fieldDesc.put(f.name, f.desc);
            if ((f.access & Opcodes.ACC_STATIC) != 0) addedStaticFields.add(f.name); else addedInstanceFields.add(f.name);
            r.relocatedFields.add(((f.access & Opcodes.ACC_STATIC) != 0 ? "static " : "") + f.name + " " + f.desc);
        }
        List<MethodNode> addedMethods = new ArrayList<>();
        for (MethodNode m : X.methods) if (!oM.contains(m.name + " " + m.desc)) {
            addedMethods.add(m); addedMethodKeys.add(m.name + " " + m.desc);
            r.relocatedMethods.add(m.name + " " + m.desc);
        }
        for (String i : X.interfaces) if (!oI.contains(i)) r.droppedInterfaces.add(i);

        // ---- build sidecar ----
        ClassNode S = new ClassNode();
        S.version = X.version; S.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER; S.name = sidecar; S.superName = "java/lang/Object";
        // State object (one field per added instance field) + the identity map
        ClassNode St = new ClassNode();
        St.version = X.version; St.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER; St.name = stateName; St.superName = "java/lang/Object";
        St.methods.add(defaultCtor(St.superName));
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) == 0)
            St.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, f.name, f.desc, null, null));
        // static fields (relocated static @Unique) go straight on the sidecar
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) != 0)
            S.fields.add(new FieldNode(f.access, f.name, f.desc, f.signature, f.value));
        // STATE map + getState()
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STATE", "Ljava/util/Map;", null, null));
        addSidecarClinit(S);
        addGetState(S);
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) == 0) addFieldAccessors(S, f);
        // relocate the added methods as sidecar statics (body rewritten)
        for (MethodNode m : addedMethods) S.methods.add(relocateMethod(m));

        // ---- rewrite target' ----
        X.interfaces.removeIf(i -> r.droppedInterfaces.contains(i));
        X.fields.removeIf(f -> addedFieldKeys.contains(f.name + " " + f.desc));
        X.methods.removeIf(m -> addedMethodKeys.contains(m.name + " " + m.desc));
        for (MethodNode m : X.methods) rewriteRefs(m, false);

        r.target = write(X, original);
        // emit sidecar + state as one combined verify pass isn't needed; caller defines both
        r.sidecar = write(S, null);
        r.notes.add("state class: " + stateName + " (" + addedInstanceFields.size() + " instance fields)");
        // stash the state bytes on the result via a side channel: append as a second class is awkward; caller
        // regenerates State from stateBytes below.
        stateBytes = write(St, null);
        return r;
    }
    byte[] stateBytes;
    public byte[] stateBytes() { return stateBytes; }
    public String stateName() { return stateName; }

    // ---- sidecar helpers ----
    static MethodNode defaultCtor(String superName) {
        MethodNode c = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
        c.instructions.add(new InsnNode(Opcodes.RETURN));
        c.maxStack = 1; c.maxLocals = 1; return c;
    }
    void addSidecarClinit(ClassNode S) {
        MethodNode c = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList in = c.instructions;
        in.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V", false));
        in.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new InsnNode(Opcodes.RETURN));
        c.maxStack = 2; c.maxLocals = 0; S.methods.add(c);
    }
    void addGetState(ClassNode S) {
        // static State getState(Target self){ return (State) STATE.computeIfAbsent(self, k -> new State()); }
        MethodNode g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getState", "(" + targetDesc + ")L" + stateName + ";", null, null);
        InsnList in = g.instructions;
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // computeIfAbsent with a lambda is complex in raw ASM; use get-or-create explicitly
        // State s = (State) STATE.get(self); if(s==null){ s=new State(); STATE.put(self,s);} return s;
        in.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        in.add(new TypeInsnNode(Opcodes.CHECKCAST, stateName));
        in.add(new VarInsnNode(Opcodes.ASTORE, 1));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        LabelNode notNull = new LabelNode();
        in.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        in.add(new TypeInsnNode(Opcodes.NEW, stateName));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, stateName, "<init>", "()V", false));
        in.add(new VarInsnNode(Opcodes.ASTORE, 1));
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        in.add(new InsnNode(Opcodes.POP));
        in.add(notNull);
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new InsnNode(Opcodes.ARETURN));
        g.maxStack = 3; g.maxLocals = 2; S.methods.add(g);
    }
    void addFieldAccessors(ClassNode S, FieldNode f) {
        Type t = Type.getType(f.desc);
        // get_f(Target self){ return getState(self).f; }
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, accGet(f.name), "(" + targetDesc + ")" + f.desc, null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "getState", "(" + targetDesc + ")L" + stateName + ";", false));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, stateName, f.name, f.desc));
        get.instructions.add(new InsnNode(t.getOpcode(Opcodes.IRETURN)));
        get.maxStack = Math.max(1, t.getSize()); get.maxLocals = 1; S.methods.add(get);
        // set_f(Target self, T v){ getState(self).f = v; }
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, accSet(f.name), "(" + targetDesc + f.desc + ")V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        set.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "getState", "(" + targetDesc + ")L" + stateName + ";", false));
        set.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, stateName, f.name, f.desc));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        set.maxStack = 1 + t.getSize(); set.maxLocals = 1 + t.getSize(); S.methods.add(set);
    }
    String accGet(String f) { return "get$" + f; }
    String accSet(String f) { return "set$" + f; }

    MethodNode relocateMethod(MethodNode m) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        String newDesc = isStatic ? m.desc : "(" + targetDesc + m.desc.substring(1);
        MethodNode s = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "h$" + m.name, newDesc, null,
            m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
        s.instructions = m.instructions; s.tryCatchBlocks = m.tryCatchBlocks;
        s.maxStack = m.maxStack; s.maxLocals = Math.max(m.maxLocals, isStatic ? m.maxLocals : m.maxLocals);
        relocatedHandlerName.put(m.name + " " + m.desc, "h$" + m.name + "|" + newDesc);
        rewriteRefs(s, true);   // rewrite added-member refs inside the moved body
        return s;
    }
    final Map<String,String> relocatedHandlerName = new HashMap<>();

    /** Rewrite references to added members: field->accessor, added-method-invoke->sidecar static. */
    void rewriteRefs(MethodNode m, boolean inSidecar) {
        if (m.instructions == null) return;
        for (AbstractInsnNode p = m.instructions.getFirst(), next; p != null; p = next) {
            next = p.getNext();   // capture BEFORE any set() detaches p (else iteration stops after one rewrite)
            if (p instanceof FieldInsnNode fi && fi.owner.equals(targetInternal)) {
                if (addedInstanceFields.contains(fi.name)) {
                    Type t = Type.getType(fi.desc);
                    if (fi.getOpcode() == Opcodes.GETFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accGet(fi.name), "(" + targetDesc + ")" + fi.desc, false));
                    else if (fi.getOpcode() == Opcodes.PUTFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accSet(fi.name), "(" + targetDesc + fi.desc + ")V", false));
                } else if (addedStaticFields.contains(fi.name)) {
                    m.instructions.set(p, new FieldInsnNode(fi.getOpcode(), sidecar, fi.name, fi.desc));
                }
            } else if (p instanceof MethodInsnNode mi && mi.owner.equals(targetInternal) && addedMethodKeys.contains(mi.name + " " + mi.desc)) {
                boolean wasStatic = mi.getOpcode() == Opcodes.INVOKESTATIC;
                String nd = wasStatic ? mi.desc : "(" + targetDesc + mi.desc.substring(1);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "h$" + mi.name, nd, false));
            }
        }
    }

    // ---- io ----
    static ClassNode read(byte[] b) { ClassReader r = new ClassReader(b); ClassNode n = new ClassNode(); r.accept(n, ClassReader.SKIP_FRAMES); return n; }
    static byte[] write(ClassNode n, byte[] orig) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        n.accept(w); return w.toByteArray();
    }
    static Set<String> keysF(ClassNode n) { Set<String> s = new HashSet<>(); for (FieldNode f : n.fields) s.add(f.name + " " + f.desc); return s; }
    static Set<String> keysM(ClassNode n) { Set<String> s = new HashSet<>(); for (MethodNode m : n.methods) s.add(m.name + " " + m.desc); return s; }
}
