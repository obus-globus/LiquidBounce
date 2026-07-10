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

    final Map<String,Integer> oFieldAcc = new HashMap<>();     // original field name -> access (for B: non-public => reflect)
    final Map<String,String> oFieldDesc = new HashMap<>();
    final LinkedHashSet<String> reflectFields = new LinkedHashSet<>();  // "name desc" of non-public target fields accessed

    Result run(byte[] original, byte[] transformed) {
        ClassNode O = read(original), X = read(transformed);
        for (FieldNode f : O.fields) { oFieldAcc.put(f.name, f.access); oFieldDesc.put(f.name, f.desc); }
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
        // B: generate reflective accessors in the sidecar for every non-public target field we rewrote
        for (String key : reflectFields) { String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1); addReflectiveAccessors(S, nm, dc); }
        if (!reflectFields.isEmpty()) addReflectInit(S);
        r.notes.add("reflective (non-public @Shadow) fields: " + reflectFields);

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

    /** Rewrite references to added members (field->accessor, method->sidecar static, invokedynamic Handle->
     *  sidecar static [A]) and, inside the sidecar, non-public target field access via reflection [B]. */
    void rewriteRefs(MethodNode m, boolean inSidecar) {
        if (m.instructions == null) return;
        for (AbstractInsnNode p = m.instructions.getFirst(), next; p != null; p = next) {
            next = p.getNext();   // capture BEFORE any set() detaches p (else iteration stops after one rewrite)
            if (p instanceof FieldInsnNode fi && fi.owner.equals(targetInternal)) {
                if (addedInstanceFields.contains(fi.name)) {
                    if (fi.getOpcode() == Opcodes.GETFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accGet(fi.name), "(" + targetDesc + ")" + fi.desc, false));
                    else if (fi.getOpcode() == Opcodes.PUTFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, accSet(fi.name), "(" + targetDesc + fi.desc + ")V", false));
                } else if (addedStaticFields.contains(fi.name)) {
                    m.instructions.set(p, new FieldInsnNode(fi.getOpcode(), sidecar, fi.name, fi.desc));
                } else if (inSidecar && oFieldAcc.containsKey(fi.name) && (oFieldAcc.get(fi.name) & Opcodes.ACC_PUBLIC) == 0
                        && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.PUTFIELD)) {
                    // B: non-public target field accessed from the sidecar -> reflective accessor (no AW on target)
                    reflectFields.add(fi.name + " " + fi.desc);
                    if (fi.getOpcode() == Opcodes.GETFIELD)
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "refGet$" + fi.name, "(" + targetDesc + ")" + fi.desc, false));
                    else
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "refSet$" + fi.name, "(" + targetDesc + fi.desc + ")V", false));
                }
            } else if (p instanceof MethodInsnNode mi && mi.owner.equals(targetInternal) && addedMethodKeys.contains(mi.name + " " + mi.desc)) {
                boolean wasStatic = mi.getOpcode() == Opcodes.INVOKESTATIC;
                String nd = wasStatic ? mi.desc : "(" + targetDesc + mi.desc.substring(1);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "h$" + mi.name, nd, false));
            } else if (p instanceof InvokeDynamicInsnNode idn) {
                // A: rewrite bootstrap Handle args that point at a relocated target method -> sidecar static.
                // The lambda's captured `this` (target) becomes the static's param0 (LambdaMetafactory adapts
                // a captured arg to a leading static parameter identically to an instance receiver).
                for (int k = 0; k < idn.bsmArgs.length; k++) {
                    if (idn.bsmArgs[k] instanceof Handle h && h.getOwner().equals(targetInternal)
                            && addedMethodKeys.contains(h.getName() + " " + h.getDesc())) {
                        boolean wasStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
                        String nd = wasStatic ? h.getDesc() : "(" + targetDesc + h.getDesc().substring(1);
                        idn.bsmArgs[k] = new Handle(Opcodes.H_INVOKESTATIC, sidecar, "h$" + h.getName(), nd, false);
                    }
                }
            }
        }
    }

    /** B: reflective get/set of a non-public target field (Field cached in a sidecar static, setAccessible). */
    void addReflectiveAccessors(ClassNode S, String name, String desc) {
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F$" + name, "Ljava/lang/reflect/Field;", null, null));
        Type t = Type.getType(desc);
        boolean prim = t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE;
        String box = prim ? boxOwner(t) : null;
        // get
        MethodNode g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "refGet$" + name, "(" + targetDesc + ")" + desc, null, null);
        InsnList gi = g.instructions;
        gi.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "F$" + name, "Ljava/lang/reflect/Field;"));
        gi.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (prim) { gi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", primGetter(t), "(Ljava/lang/Object;)" + t.getDescriptor(), false)); gi.add(new InsnNode(t.getOpcode(Opcodes.IRETURN))); }
        else { gi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false)); gi.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getInternalName())); gi.add(new InsnNode(Opcodes.ARETURN)); }
        wrapTryCatch(g); g.maxStack = 3; g.maxLocals = 3; S.methods.add(g);
        // set
        MethodNode s = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "refSet$" + name, "(" + targetDesc + desc + ")V", null, null);
        InsnList si = s.instructions;
        si.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "F$" + name, "Ljava/lang/reflect/Field;"));
        si.add(new VarInsnNode(Opcodes.ALOAD, 0));
        si.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), 1));
        if (prim) si.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box, "valueOf", "(" + t.getDescriptor() + ")L" + box + ";", false));
        si.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        si.add(new InsnNode(Opcodes.RETURN));
        wrapTryCatch(s); s.maxStack = 3; s.maxLocals = 1 + t.getSize(); S.methods.add(s);
    }
    static String primGetter(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "getBoolean"; case Type.BYTE: return "getByte"; case Type.CHAR: return "getChar"; case Type.SHORT: return "getShort"; case Type.INT: return "getInt"; case Type.LONG: return "getLong"; case Type.FLOAT: return "getFloat"; default: return "getDouble"; } }
    static String boxOwner(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "java/lang/Boolean"; case Type.BYTE: return "java/lang/Byte"; case Type.CHAR: return "java/lang/Character"; case Type.SHORT: return "java/lang/Short"; case Type.INT: return "java/lang/Integer"; case Type.LONG: return "java/lang/Long"; case Type.FLOAT: return "java/lang/Float"; default: return "java/lang/Double"; } }
    void wrapTryCatch(MethodNode m) {
        LabelNode s = new LabelNode(), e = new LabelNode(), h = new LabelNode();
        m.instructions.insertBefore(m.instructions.getFirst(), s);
        m.instructions.add(e);
        m.instructions.add(h);
        m.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        m.instructions.add(new InsnNode(Opcodes.DUP_X1)); m.instructions.add(new InsnNode(Opcodes.SWAP));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false));
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s, e, h, "java/lang/Throwable"));
    }
    /** append FIELD lookups to the sidecar <clinit>. */
    void addReflectInit(ClassNode S) {
        MethodNode clinit = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { clinit = m; break; }
        AbstractInsnNode ret = clinit.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        InsnList add = new InsnList();
        for (String key : reflectFields) {
            String nm = key.substring(0, key.indexOf(' '));
            add.add(new LdcInsnNode(Type.getObjectType(targetInternal)));
            add.add(new LdcInsnNode(nm));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
            add.add(new InsnNode(Opcodes.DUP));
            add.add(new InsnNode(Opcodes.ICONST_1));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false));
            add.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "F$" + nm, "Ljava/lang/reflect/Field;"));
        }
        clinit.instructions.insertBefore(ret, add); clinit.maxStack = Math.max(clinit.maxStack, 3);
    }

    /** Build-time LB-caller rewrite: ((Iface)o).m(args) -> Sidecar.h$m((Target)o, args), for interfaces the
     *  converter dropped from their target. ifaceMap: ifaceInternal -> [targetInternal, sidecarInternal]. */
    public static byte[] rewriteCaller(byte[] callerBytes, Map<String,String[]> ifaceMap) {
        ClassNode c = read(callerBytes); int n = 0;
        for (MethodNode m : c.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode p = m.instructions.getFirst(), nx; p != null; p = nx) {
                nx = p.getNext();
                if (p instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.CHECKCAST && ifaceMap.containsKey(ti.desc)) {
                    ti.desc = ifaceMap.get(ti.desc)[0];     // CHECKCAST Iface -> CHECKCAST Target (o is really the target)
                    n++;
                } else if (p instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEINTERFACE && ifaceMap.containsKey(mi.owner)) {
                    String[] ts = ifaceMap.get(mi.owner);
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, ts[1], "h$" + mi.name, "(L" + ts[0] + ";" + mi.desc.substring(1), false));
                    n++;
                }
            }
        }
        return n == 0 ? callerBytes : write(c, callerBytes);
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
