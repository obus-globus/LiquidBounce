import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
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
        // Sidecar lives in the TARGET'S OWN PACKAGE so relocated handler bodies keep package-private access to
        // target-package internals (e.g. a package-private inner class like GuiRenderState$Node). Only genuinely
        // PRIVATE members still need the reflective path (B). Defined with the target's ProtectionDomain.
        this.sidecar = targetInternal + "$$LBSidecar";
        this.stateName = sidecar + "$State";
    }

    public static Result convert(String targetInternal, byte[] original, byte[] transformed) {
        return new RetransformConverter(targetInternal).run(original, transformed);
    }

    final Map<String,Integer> oFieldAcc = new HashMap<>();     // original field name -> access (for B: non-public => reflect)
    final Map<String,String> oFieldDesc = new HashMap<>();
    final LinkedHashSet<String> reflectFields = new LinkedHashSet<>();  // "name desc" of non-public target fields accessed
    final Map<String,Integer> oMethodAcc = new HashMap<>();    // original "name desc" -> access
    final LinkedHashSet<String> reflectMethods = new LinkedHashSet<>(); // "name desc" of private target methods called from sidecar
    final Set<String> reflectStaticMethods = new HashSet<>();  // subset of reflectMethods invoked without a receiver
    final LinkedHashMap<String,Object[]> ifaceDispatchTramps = new LinkedHashMap<>();

    Result run(byte[] original, byte[] transformed) {
        ClassNode O = read(original), X = read(transformed);
        for (FieldNode f : O.fields) { oFieldAcc.put(f.name, f.access); oFieldDesc.put(f.name, f.desc); }
        for (MethodNode m : O.methods) oMethodAcc.put(m.name + " " + m.desc, m.access);
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
        // static fields (relocated static @Unique) go straight on the sidecar. Force PUBLIC and non-FINAL: they are
        // now read/written cross-class from target' (a different class than the sidecar), which private/final forbids.
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) != 0)
            S.fields.add(new FieldNode((f.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC, f.name, f.desc, f.signature, f.value));
        // STATE map + getState()
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STATE", "Ljava/util/Map;", null, null));
        addSidecarClinit(S);
        addGetState(S);
        for (FieldNode f : addedFields) if ((f.access & Opcodes.ACC_STATIC) == 0) addFieldAccessors(S, f);
        S.methods.add(buildInstanceStateInitializer(X));
        // relocate the added methods as sidecar statics (body rewritten); an added <clinit> (static @Unique field
        // initializer the mixin merged into the target) is MERGED into the sidecar's own <clinit>, not relocated
        // as an (illegal) h$<clinit>.
        for (MethodNode m : addedMethods) {
            if (m.name.equals("<clinit>")) { mergeIntoSidecarClinit(S, m); continue; }
            if (m.name.equals("<init>")) { r.notes.add("WARNING: mixin added <init> to " + targetInternal + " — dropped (unexpected)"); continue; }
            S.methods.add(relocateMethod(m));
        }

        // MODIFIED <clinit>: the mixin APPENDED static @Unique field init to the target's EXISTING <clinit> (so it is
        // not an "added" method). For an ALREADY-LOADED target that <clinit> won't re-run after retransform, leaving
        // the relocated static field null -> copy the appended delta into the sidecar <clinit> (which runs fresh when
        // the sidecar loads). Harmlessly redundant for future-loaded targets.
        if (!addedStaticFields.isEmpty()) {
            MethodNode xc = findMethod(X, "<clinit>"), oc = findMethod(O, "<clinit>");
            if (xc != null && oc != null) copyClinitDeltaToSidecar(S, oc, xc);
        }

        // ---- rewrite target' ----
        X.interfaces.removeIf(i -> r.droppedInterfaces.contains(i));
        X.fields.removeIf(f -> addedFieldKeys.contains(f.name + " " + f.desc));
        X.methods.removeIf(m -> addedMethodKeys.contains(m.name + " " + m.desc));
        for (MethodNode m : X.methods) rewriteRefs(m, false);
        for (var e : ifaceDispatchTramps.entrySet()) {
            Object[] v = e.getValue();
            S.methods.add(buildIfaceDispatchTramp(e.getKey(), (String)v[0], (String)v[1], (String)v[2]));
        }
        // B: generate reflective accessors in the sidecar for every non-public target field we rewrote
        for (String key : reflectFields) { String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1); addReflectiveAccessors(S, nm, dc); }
        if (!reflectFields.isEmpty()) addReflectInit(S);
        r.notes.add("reflective (non-public @Shadow) fields: " + reflectFields);
        // B (methods): reflective invokers for private target methods the relocated sidecar body calls
        for (String key : reflectMethods) { String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1); addReflectiveInvoker(S, nm, dc, reflectStaticMethods.contains(key)); }
        if (!reflectMethods.isEmpty()) addReflectMethodInit(S);
        r.notes.add("reflective (private @Shadow) methods: " + reflectMethods);

        // Retransform must publish the loaded class's exact redefinition-constrained schema. Mixin can reorder overwritten methods
        // and @Mutable deliberately clears ACC_FINAL on @Shadow fields; both are useful while producing X but neither
        // is legal to publish over an already-loaded class. Put the surviving transformed members back in O's order
        // and restore their original modifiers/structural attributes while retaining X's transformed method bodies and
        // supported class-file version (Mixin intentionally lifts older dependency classes to its compatibility level).
        restoreOriginalSchema(O, X);
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

    static void restoreOriginalSchema(ClassNode O, ClassNode X) {
        if (!Objects.equals(O.name, X.name)) throw schemaFailure(O.name, "class name changed to " + X.name);
        if (!Objects.equals(O.superName, X.superName)) throw schemaFailure(O.name, "superclass changed from " + O.superName + " to " + X.superName);
        int classKind = Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_MODULE | Opcodes.ACC_RECORD;
        if (((O.access ^ X.access) & classKind) != 0) throw schemaFailure(O.name, "class kind modifiers changed");
        if (O.interfaces.size() != X.interfaces.size() || !new HashSet<>(O.interfaces).equals(new HashSet<>(X.interfaces)))
            throw schemaFailure(O.name, "original interface set changed from " + O.interfaces + " to " + X.interfaces);
        X.access = O.access;
        X.name = O.name;
        X.signature = O.signature;
        X.superName = O.superName;
        X.interfaces = new ArrayList<>(O.interfaces);
        X.outerClass = O.outerClass;
        X.outerMethod = O.outerMethod;
        X.outerMethodDesc = O.outerMethodDesc;
        X.nestHostClass = O.nestHostClass;
        X.nestMembers = copy(O.nestMembers);
        X.permittedSubclasses = copy(O.permittedSubclasses);
        X.recordComponents = O.recordComponents == null ? null : new ArrayList<>(O.recordComponents);
        X.innerClasses = O.innerClasses == null ? null : new ArrayList<>(O.innerClasses);

        LinkedHashMap<String,FieldNode> fields = new LinkedHashMap<>();
        for (FieldNode f : X.fields) putUnique(fields, f.name + " " + f.desc, f, "field", X.name);
        List<FieldNode> orderedFields = new ArrayList<>(O.fields.size());
        for (FieldNode of : O.fields) {
            String key = of.name + " " + of.desc;
            FieldNode xf = fields.remove(key);
            if (xf == null) throw schemaFailure(X.name, "missing field " + key);
            if (((of.access ^ xf.access) & Opcodes.ACC_STATIC) != 0)
                throw schemaFailure(X.name, "field static kind changed for " + key);
            xf.access = of.access;
            xf.signature = of.signature;
            xf.value = of.value;
            orderedFields.add(xf);
        }
        if (!fields.isEmpty()) throw schemaFailure(X.name, "extra fields " + fields.keySet());
        X.fields = orderedFields;

        LinkedHashMap<String,MethodNode> methods = new LinkedHashMap<>();
        for (MethodNode m : X.methods) putUnique(methods, m.name + " " + m.desc, m, "method", X.name);
        List<MethodNode> orderedMethods = new ArrayList<>(O.methods.size());
        for (MethodNode om : O.methods) {
            String key = om.name + " " + om.desc;
            MethodNode xm = methods.remove(key);
            if (xm == null) throw schemaFailure(X.name, "missing method " + key);
            int codeKind = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
            if (((om.access ^ xm.access) & codeKind) != 0)
                throw schemaFailure(X.name, "method static/abstract/native kind changed for " + key);
            boolean originalHasCode = (om.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            boolean transformedHasCode = xm.instructions != null && xm.instructions.size() != 0;
            if (originalHasCode != transformedHasCode)
                throw schemaFailure(X.name, "method code kind changed for " + key);
            xm.access = om.access;
            xm.signature = om.signature;
            xm.exceptions = copy(om.exceptions);
            orderedMethods.add(xm);
        }
        if (!methods.isEmpty()) throw schemaFailure(X.name, "extra methods " + methods.keySet());
        X.methods = orderedMethods;
    }

    static <T> List<T> copy(List<T> values) {
        return values == null ? null : new ArrayList<>(values);
    }

    static <T> void putUnique(Map<String,T> values, String key, T value, String kind, String owner) {
        if (values.put(key, value) != null) throw schemaFailure(owner, "duplicate " + kind + " " + key);
    }

    static IllegalStateException schemaFailure(String owner, String detail) {
        return new IllegalStateException("Cannot restore original schema for " + owner + ": " + detail);
    }

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
        // IDENTITY-keyed per-instance store: must NOT call target.hashCode()/equals() — a mixin-modified hashCode()
        // that reads a relocated field would recurse into getState() -> map.get(self) -> hashCode() -> StackOverflow.
        // (Also correct in general: per-instance state keys on object identity, not value-equality.)
        in.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
        in.add(new InsnNode(Opcodes.DUP));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "synchronizedMap", "(Ljava/util/Map;)Ljava/util/Map;", false));
        in.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "STATE", "Ljava/util/Map;"));
        in.add(new InsnNode(Opcodes.RETURN));
        c.maxStack = 2; c.maxLocals = 0; S.methods.add(c);
    }
    void addGetState(ClassNode S) {
        // static State getState(Target self){ return (State) STATE.computeIfAbsent(self, k -> new State()); }
        MethodNode g = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
            "getState", "(" + targetDesc + ")L" + stateName + ";", null, null);
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
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "initState", "("+targetDesc+"L"+stateName+";)V", false));
        in.add(notNull);
        in.add(new VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new InsnNode(Opcodes.ARETURN));
        g.maxStack = 3; g.maxLocals = 2; S.methods.add(g);
    }

    /** Replay mixin-added instance-field initializer statements for objects whose vanilla constructor ran before
     *  attachment. Mixin appends these assignments to every transformed constructor; a compact straight-line
     *  assignment is copied into initState(Target,State) and runs once when sidecar state is first materialized. */
    MethodNode buildInstanceStateInitializer(ClassNode transformed) {
        MethodNode out=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"initState","("+targetDesc+"L"+stateName+";)V",null,null);
        Set<String> done=new HashSet<>();
        for(MethodNode ctor:transformed.methods){if(!ctor.name.equals("<init>")||ctor.instructions==null)continue;
            try{
                Analyzer<BasicValue> analyzer=new Analyzer<>(new BasicInterpreter());Frame<BasicValue>[] frames=analyzer.analyze(transformed.name,ctor);AbstractInsnNode[] ins=ctor.instructions.toArray();
                for(int i=0;i<ins.length;i++){if(!(ins[i] instanceof FieldInsnNode f)||f.getOpcode()!=Opcodes.PUTFIELD||!f.owner.equals(targetInternal)||!addedInstanceFields.contains(f.name)||!done.add(f.name+" "+f.desc))continue;
                    Frame<BasicValue> at=frames[i];if(at==null||at.getStackSize()<2){done.remove(f.name+" "+f.desc);continue;}int base=at.getStackSize()-2,start=-1;
                    for(int s=i-1;s>=0;s--)if(ins[s].getOpcode()>=0&&frames[s]!=null&&frames[s].getStackSize()==base){start=s;break;}
                    if(start<0||!(ins[start] instanceof VarInsnNode recv)||recv.getOpcode()!=Opcodes.ALOAD||recv.var!=0){done.remove(f.name+" "+f.desc);continue;}
                    boolean safe=true;for(int s=start+1;s<i;s++){AbstractInsnNode p=ins[s];if(p instanceof JumpInsnNode||p instanceof TableSwitchInsnNode||p instanceof LookupSwitchInsnNode||p instanceof IincInsnNode||(p instanceof VarInsnNode v&&v.var!=0)){safe=false;break;}}
                    if(!safe){done.remove(f.name+" "+f.desc);continue;}
                    out.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));Map<LabelNode,LabelNode> labels=new HashMap<>();
                    for(int s=start+1;s<i;s++)if(ins[s].getOpcode()>=0)out.instructions.add(ins[s].clone(labels));
                    out.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,stateName,f.name,f.desc));
                }
            }catch(Throwable ignored){}
        }
        out.instructions.add(new InsnNode(Opcodes.RETURN));rewriteRefs(out,true);out.maxLocals=2;out.maxStack=8;return out;
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

    static MethodNode findMethod(ClassNode c, String name) { for (MethodNode m : c.methods) if (m.name.equals(name)) return m; return null; }
    static List<AbstractInsnNode> realInsns(MethodNode m) { List<AbstractInsnNode> l = new ArrayList<>(); for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) if (p.getOpcode() >= 0) l.add(p); return l; }
    /** The mixin appends its static-field init to the target's existing <clinit>; extract that appended delta (the
     *  suffix of X's <clinit> past O's body) and, ref-rewritten, append it to the sidecar's <clinit>. */
    void copyClinitDeltaToSidecar(ClassNode S, MethodNode oc, MethodNode xc) {
        List<AbstractInsnNode> oR = realInsns(oc), xR = realInsns(xc);
        int oBody = oR.size(); while (oBody > 0 && oR.get(oBody-1).getOpcode() == Opcodes.RETURN) oBody--;
        int xEnd = xR.size(); while (xEnd > 0 && xR.get(xEnd-1).getOpcode() == Opcodes.RETURN) xEnd--;
        if (oBody > xEnd) return;                                        // unexpected shape -> skip
        for (int i = 0; i < oBody; i++) if (oR.get(i).getOpcode() != xR.get(i).getOpcode()) return;   // X must extend O's body (append)
        if (oBody == xEnd) return;                                       // no delta
        // clone the delta instructions (build a label map for any labels inside the delta window in X)
        AbstractInsnNode from = xR.get(oBody), to = xR.get(xEnd - 1);
        Map<LabelNode,LabelNode> lm = new HashMap<>();
        for (AbstractInsnNode p = from; p != null; p = p.getNext()) { if (p instanceof LabelNode ln) lm.put(ln, new LabelNode()); if (p == to) break; }
        InsnList delta = new InsnList();
        for (AbstractInsnNode p = from; p != null; p = p.getNext()) { delta.add(p.clone(lm)); if (p == to) break; }
        MethodNode tmp = new MethodNode(); tmp.instructions = delta; rewriteRefs(tmp, true);   // relocate added-static PUTSTATIC -> sidecar etc.
        MethodNode sc = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { sc = m; break; }
        AbstractInsnNode ret = sc.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        sc.instructions.insertBefore(ret, tmp.instructions);
    }
    /** Merge a mixin-added <clinit> (initializes relocated static @Unique fields) into the sidecar's own <clinit>. */
    void mergeIntoSidecarClinit(ClassNode S, MethodNode added) {
        rewriteRefs(added, true);   // retarget added static-field PUTSTATIC -> sidecar; lambda-init Handle -> h$...
        MethodNode clinit = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { clinit = m; break; }
        AbstractInsnNode ret = clinit.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        AbstractInsnNode last = added.instructions.getLast(); while (last != null && last.getOpcode() != Opcodes.RETURN) last = last.getPrevious();
        if (last != null) added.instructions.remove(last);   // strip the added body's trailing RETURN
        clinit.instructions.insertBefore(ret, added.instructions);
        if (added.tryCatchBlocks != null && !added.tryCatchBlocks.isEmpty()) {
            if (clinit.tryCatchBlocks == null) clinit.tryCatchBlocks = new ArrayList<>();
            clinit.tryCatchBlocks.addAll(added.tryCatchBlocks);
        }
        clinit.maxStack = Math.max(clinit.maxStack, added.maxStack);
        clinit.maxLocals = Math.max(clinit.maxLocals, added.maxLocals);
    }

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

    /** GLOBAL table of mixin-ADDED methods across ALL targets, keyed by "owner name desc" -> [owner, sidecar,
     *  isStatic]. Keyed by OWNER (not just name+desc) because the same handler name+desc can be added to multiple
     *  targets; a call is resolved by walking the receiver's class hierarchy to the declaring target. */
    public static Map<String,String[]> GADDED, GFIELD;
    public static Map<String,List<String[]>> GIFACE;
    String[] addedFieldTarget(String owner, String name, String desc) {
        if (GFIELD == null) return null;
        for (String c = owner; c != null && !c.equals("java/lang/Object"); c = superOf(c)) { String[] g = GFIELD.get(gkey(c, name, desc)); if (g != null) return g; }
        return null;
    }
    static String gkey(String owner, String name, String desc) { return owner + " " + name + " " + desc; }
    public static void collectAdded(String internal, byte[] O, byte[] X, Map<String,String[]> gMethods, Map<String,String[]> gFields, Map<String,List<String[]>> gIfaces) {
        ClassNode o = read(O), x = read(X); Set<String> om = keysM(o), of = keysF(o);
        String sc = internal + "$$LBSidecar";
        for (MethodNode m : x.methods) { String k = m.name + " " + m.desc;
            if (!om.contains(k) && !m.name.equals("<clinit>") && !m.name.equals("<init>"))
                gMethods.put(gkey(internal, m.name, m.desc), new String[]{internal, sc, (m.access & Opcodes.ACC_STATIC) != 0 ? "1" : "0"}); }
        for (FieldNode f : x.fields) { if (!of.contains(f.name + " " + f.desc))
            gFields.put(gkey(internal, f.name, f.desc), new String[]{internal, sc, (f.access & Opcodes.ACC_STATIC) != 0 ? "S" : "I"}); }
        for (String i : x.interfaces) if (!o.interfaces.contains(i)) {
            List<String[]> impls = gIfaces.computeIfAbsent(i, k -> new ArrayList<>());
            boolean duplicate = false;
            for (String[] impl : impls) if (impl[0].equals(internal)) { duplicate = true; break; }
            if (!duplicate) impls.add(new String[]{internal, sc});
        }
    }
    /** resolve a call to a mixin-added method: walk the receiver type's hierarchy to the target that declared it. */
    String[] addedCall(String owner, String name, String desc, boolean callStatic) {
        if (GADDED == null) return null;
        for (String c = owner; c != null && !c.equals("java/lang/Object"); c = superOf(c)) {
            String[] g = GADDED.get(gkey(c, name, desc));
            if (g != null) return callStatic == "1".equals(g[2]) ? g : null;
        }
        return null;
    }
    String[] addedCallTarget(MethodInsnNode mi) { return addedCall(mi.owner, mi.name, mi.desc, mi.getOpcode() == Opcodes.INVOKESTATIC); }

    /** Rewrite references to added members (field->accessor, method->sidecar static, invokedynamic Handle->
     *  sidecar static [A]) and, inside the sidecar, non-public target field access via reflection [B]. */
    void rewriteRefs(MethodNode m, boolean inSidecar) {
        if (m.instructions == null) return;
        for (AbstractInsnNode p = m.instructions.getFirst(), next; p != null; p = next) {
            next = p.getNext();   // capture BEFORE any set() detaches p (else iteration stops after one rewrite)
            if (GIFACE != null && p instanceof TypeInsnNode gti && GIFACE.containsKey(gti.desc)
                    && (gti.getOpcode() == Opcodes.CHECKCAST || gti.getOpcode() == Opcodes.INSTANCEOF)) {
                List<String[]> impls = GIFACE.get(gti.desc);
                if (impls.size() == 1) {
                    gti.desc = impls.get(0)[0];
                } else if (gti.getOpcode() == Opcodes.CHECKCAST) {
                    InsnList suffix = new InsnList(); suffix.add(new LdcInsnNode(gti.desc));
                    suffix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lbrt/DuckDispatch", "cast",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                    m.instructions.insertBefore(gti, suffix); m.instructions.remove(gti);
                } else {
                    InsnList suffix = new InsnList(); suffix.add(new LdcInsnNode(gti.desc));
                    suffix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lbrt/DuckDispatch", "isInstance", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                    m.instructions.insertBefore(gti, suffix); m.instructions.remove(gti);
                }
            } else if (GIFACE != null && p instanceof MethodInsnNode gmi && gmi.getOpcode() == Opcodes.INVOKEINTERFACE && GIFACE.containsKey(gmi.owner)) {
                List<String[]> impls = GIFACE.get(gmi.owner);
                if (impls.size() == 1) {
                    String[] ts = impls.get(0);
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, ts[1], "h$" + gmi.name, "(L" + ts[0] + ";" + gmi.desc.substring(1), false));
                } else {
                    String tn = ifaceTrampName(gmi.owner, gmi.name, gmi.desc);
                    ifaceDispatchTramps.putIfAbsent(tn, new Object[]{gmi.owner, gmi.name, gmi.desc});
                    m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, tn,
                        "(Ljava/lang/Object;" + gmi.desc.substring(1), false));
                }
            } else if (p instanceof FieldInsnNode fi && addedFieldTarget(fi.owner, fi.name, fi.desc) != null) {
                // mixin-ADDED field (relocated). GFIELD spans all targets so a base-class @Unique field accessed via a
                // subclass receiver routes to the BASE target's sidecar accessor/static.
                String[] gf = addedFieldTarget(fi.owner, fi.name, fi.desc); String ot = gf[0], os = gf[1];
                if ("S".equals(gf[2])) { m.instructions.set(p, new FieldInsnNode(fi.getOpcode(), os, fi.name, fi.desc)); }
                else if (fi.getOpcode() == Opcodes.GETFIELD) m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, accGet(fi.name), "(L" + ot + ";)" + fi.desc, false));
                else if (fi.getOpcode() == Opcodes.PUTFIELD) m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, accSet(fi.name), "(L" + ot + ";" + fi.desc + ")V", false));
            } else if (p instanceof FieldInsnNode fi && fi.owner.equals(targetInternal)) {
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
                } else if (inSidecar && nonPublicField(fi.owner,fi.name,fi.desc)) rewriteFieldAw(m,fi);
            } else if (inSidecar && p instanceof FieldInsnNode fi && nonPublicField(fi.owner,fi.name,fi.desc)) {
                // Relocated handlers are no longer subclasses/nestmates of their target. Protected or package-private
                // fields inherited from another package (for example Screen.minecraft) must therefore use reflection.
                rewriteFieldAw(m,fi);
            } else if (p instanceof MethodInsnNode mi && addedCallTarget(mi) != null) {
                // call to a mixin-ADDED method (relocated to a sidecar). GADDED spans ALL targets, so a call to a base
                // class's @Unique method from a subclass mixin's relocated body routes to the BASE class's sidecar.
                String[] g = addedCallTarget(mi); String ot = g[0], os = g[1]; boolean gStatic = "1".equals(g[2]);
                String nd = gStatic ? mi.desc : "(L" + ot + ";" + mi.desc.substring(1);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, os, "h$" + mi.name, nd, false));
            } else if (inSidecar && p instanceof MethodInsnNode mi && mi.owner.equals(targetInternal)
                    && (mi.getOpcode() == Opcodes.INVOKESPECIAL || mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKESTATIC) && !mi.name.equals("<init>")
                    && (oMethodAcc.getOrDefault(mi.name + " " + mi.desc, 0) & Opcodes.ACC_PRIVATE) != 0) {
                // B (methods): a private EXISTING target method called from the relocated sidecar body (invokespecial,
                // invokevirtual for a nestmate/MixinExtras bridge, or invokestatic) is illegal cross-class -> cached
                // reflective invoker.
                String key = mi.name + " " + mi.desc;
                boolean isStatic = mi.getOpcode() == Opcodes.INVOKESTATIC;
                reflectMethods.add(key);
                if (isStatic) reflectStaticMethods.add(key);
                String nd = isStatic ? mi.desc : "(" + targetDesc + mi.desc.substring(1);
                m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, sidecar, "rmi$" + invokerId(mi.name, mi.desc), nd, false));
            } else if (inSidecar && p instanceof MethodInsnNode mi && illegalSidecarMethod(sidecar,mi.owner,mi.name,mi.desc)) {
                // Relocation also loses nestmate/subclass privileges for methods and constructors declared outside
                // the target package. Lower them through the same cached reflection runtime used for late AW access.
                if (mi.getOpcode()==Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) {
                    AbstractInsnNode nw=mi.getPrevious();
                    while(nw!=null&&!(nw instanceof TypeInsnNode tn&&tn.getOpcode()==Opcodes.NEW&&tn.desc.equals(mi.owner)))nw=nw.getPrevious();
                    if(nw!=null&&nw.getNext()!=null&&nw.getNext().getOpcode()==Opcodes.DUP){AbstractInsnNode dup=nw.getNext();m.instructions.remove(nw);m.instructions.remove(dup);rewriteAwCtorInline(m,mi);}
                } else if (!mi.name.startsWith("<") && (mi.getOpcode()==Opcodes.INVOKEVIRTUAL||mi.getOpcode()==Opcodes.INVOKESTATIC||mi.getOpcode()==Opcodes.INVOKESPECIAL)) {
                    rewriteAwMethodInline(m,mi);
                }
            } else if (p instanceof InvokeDynamicInsnNode idn) {
                // A: rewrite bootstrap Handle args that point at a relocated target method -> sidecar static.
                // The lambda's captured `this` (target) becomes the static's param0 (LambdaMetafactory adapts
                // a captured arg to a leading static parameter identically to an instance receiver).
                for (int k = 0; k < idn.bsmArgs.length; k++) {
                    if (idn.bsmArgs[k] instanceof Handle h) { String[] g = addedCall(h.getOwner(), h.getName(), h.getDesc(), h.getTag() == Opcodes.H_INVOKESTATIC);
                        if (g != null) { boolean gStatic = "1".equals(g[2]);
                            String nd = gStatic ? h.getDesc() : "(L" + g[0] + ";" + h.getDesc().substring(1);
                            idn.bsmArgs[k] = new Handle(Opcodes.H_INVOKESTATIC, g[1], "h$" + h.getName(), nd, false);
                        } else if (inSidecar && h.getOwner().equals(targetInternal)
                                && (h.getTag() == Opcodes.H_INVOKESPECIAL || h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKESTATIC)
                                && (oMethodAcc.getOrDefault(h.getName() + " " + h.getDesc(), 0) & Opcodes.ACC_PRIVATE) != 0) {
                            // A relocated body may contain a lambda/metafactory handle to an ORIGINAL private target
                            // method (for example ClientPacketListener::lambda$handleChunkBlocksUpdate$0). The lambda
                            // is spun with the sidecar as its caller, so leaving that handle unchanged fails at runtime
                            // with IllegalAccessError even though an ordinary MethodInsn would have been routed above.
                            // Point the handle at the same cached reflective bridge used for direct private calls. The
                            // captured target receiver becomes param0 of the static bridge.
                            String key = h.getName() + " " + h.getDesc();
                            boolean isStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
                            reflectMethods.add(key);
                            if (isStatic) reflectStaticMethods.add(key);
                            String nd = isStatic ? h.getDesc() : "(L" + targetInternal + ";" + h.getDesc().substring(1);
                            idn.bsmArgs[k] = new Handle(Opcodes.H_INVOKESTATIC, sidecar,
                                "rmi$" + invokerId(h.getName(), h.getDesc()), nd, false);
                        }
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

    static String invokerId(String name, String desc) { return name + "__" + Integer.toHexString(desc.hashCode() & 0xffffff); }

    /** B (methods): a static invoker rmi$<id>(Target self, args...) that reflectively calls the private target method. */
    void addReflectiveInvoker(ClassNode S, String name, String desc, boolean isStatic) {
        String id = invokerId(name, desc);
        S.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "RM$" + id, "Ljava/lang/reflect/Method;", null, null));
        Type[] at = Type.getArgumentTypes(desc); Type rt = Type.getReturnType(desc);
        String bridgeDesc = isStatic ? desc : "(" + targetDesc + desc.substring(1);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rmi$" + id, bridgeDesc, null, null);
        InsnList in = m.instructions;
        in.add(new FieldInsnNode(Opcodes.GETSTATIC, sidecar, "RM$" + id, "Ljava/lang/reflect/Method;"));
        if (isStatic) in.add(new InsnNode(Opcodes.ACONST_NULL));
        else in.add(new VarInsnNode(Opcodes.ALOAD, 0));                  // receiver
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < at.length; i++) {
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD), slot));
            box(in, at[i]);
            in.add(new InsnNode(Opcodes.AASTORE));
            slot += at[i].getSize();
        }
        in.add(new LdcInsnNode(targetInternal+"."+name+desc));
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "invokeResolved", "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        if (rt.getSort() == Type.VOID) { in.add(new InsnNode(Opcodes.POP)); in.add(new InsnNode(Opcodes.RETURN)); }
        else if (rt.getSort() <= Type.DOUBLE) { unbox(in, rt); in.add(new InsnNode(rt.getOpcode(Opcodes.IRETURN))); }
        else { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getInternalName())); in.add(new InsnNode(Opcodes.ARETURN)); }
        m.maxStack = 9 + at.length; m.maxLocals = slot; S.methods.add(m);
    }
    /** resolve each private target Method reflectively in the sidecar <clinit>. */
    void addReflectMethodInit(ClassNode S) {
        MethodNode clinit = null; for (MethodNode m : S.methods) if (m.name.equals("<clinit>")) { clinit = m; break; }
        AbstractInsnNode ret = clinit.instructions.getLast(); while (ret != null && ret.getOpcode() != Opcodes.RETURN) ret = ret.getPrevious();
        InsnList add = new InsnList();
        for (String key : reflectMethods) {
            String nm = key.substring(0, key.indexOf(' ')), dc = key.substring(key.indexOf(' ') + 1);
            Type[] at = Type.getArgumentTypes(dc);
            add.add(new LdcInsnNode(Type.getObjectType(targetInternal)));
            add.add(new LdcInsnNode(nm));
            add.add(intConst(at.length)); add.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            for (int i = 0; i < at.length; i++) { add.add(new InsnNode(Opcodes.DUP)); add.add(intConst(i)); loadClassConst(add, at[i]); add.add(new InsnNode(Opcodes.AASTORE)); }
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
            add.add(new InsnNode(Opcodes.DUP));
            add.add(new InsnNode(Opcodes.ICONST_1));
            add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false));
            add.add(new FieldInsnNode(Opcodes.PUTSTATIC, sidecar, "RM$" + invokerId(nm, dc), "Ljava/lang/reflect/Method;"));
        }
        clinit.instructions.insertBefore(ret, add); clinit.maxStack = Math.max(clinit.maxStack, 6);
    }
    static AbstractInsnNode intConst(int v) { if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v); if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v); if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v); return new LdcInsnNode(v); }
    static void box(InsnList in, Type t) { if (t.getSort() > Type.DOUBLE) return; String b = boxOwner(t); in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, b, "valueOf", "(" + t.getDescriptor() + ")L" + b + ";", false)); }
    static void unbox(InsnList in, Type t) { String b = boxOwner(t); in.add(new TypeInsnNode(Opcodes.CHECKCAST, b)); in.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, b, unboxMethod(t), "()" + t.getDescriptor(), false)); }
    static String unboxMethod(Type t) { switch (t.getSort()) { case Type.BOOLEAN: return "booleanValue"; case Type.BYTE: return "byteValue"; case Type.CHAR: return "charValue"; case Type.SHORT: return "shortValue"; case Type.INT: return "intValue"; case Type.LONG: return "longValue"; case Type.FLOAT: return "floatValue"; default: return "doubleValue"; } }
    static void loadClassConst(InsnList in, Type t) { if (t.getSort() <= Type.DOUBLE) in.add(new FieldInsnNode(Opcodes.GETSTATIC, boxOwner(t), "TYPE", "Ljava/lang/Class;")); else in.add(new LdcInsnNode(t)); }

    static String ifaceTrampName(String iface, String name, String desc) {
        return "ifd$" + name.replace('<','_').replace('>','_') + "__" + Integer.toHexString((iface + name + desc).hashCode() & 0xffffff);
    }

    static MethodNode buildIfaceDispatchTramp(String trampName, String iface, String name, String desc) {
        Type[] at = Type.getArgumentTypes(desc); Type rt = Type.getReturnType(desc);
        String td = "(Ljava/lang/Object;" + desc.substring(1);
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, trampName, td, null, null);
        InsnList in = m.instructions;
        in.add(new VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new LdcInsnNode(iface)); in.add(new LdcInsnNode(name)); in.add(new LdcInsnNode(desc));
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = 1;
        for (int i = 0; i < at.length; i++) {
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD), slot)); box(in, at[i]);
            in.add(new InsnNode(Opcodes.AASTORE)); slot += at[i].getSize();
        }
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lbrt/DuckDispatch", "invoke",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        emitReturn(in, rt);
        m.maxStack = 8 + at.length; m.maxLocals = slot;
        return m;
    }

    /** Schema-neutral LB-caller rewrite for interfaces dropped from already-loaded targets. */
    public static byte[] rewriteCaller(byte[] callerBytes, Map<String,List<String[]>> ifaceMap) {
        ClassNode c = read(callerBytes); int n = 0;
        for (MethodNode m : c.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode p = m.instructions.getFirst(), nx; p != null; p = nx) {
                nx = p.getNext();
                if (p instanceof TypeInsnNode ti && ifaceMap.containsKey(ti.desc)
                        && (ti.getOpcode() == Opcodes.CHECKCAST || ti.getOpcode() == Opcodes.INSTANCEOF)) {
                    String iface = ti.desc; List<String[]> impls = ifaceMap.get(iface);
                    if (impls.size() == 1) ti.desc = impls.get(0)[0];
                    else if (ti.getOpcode() == Opcodes.CHECKCAST) {
                        InsnList call = new InsnList(); call.add(new LdcInsnNode(iface));
                        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lbrt/DuckDispatch", "cast",
                            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                        m.instructions.insertBefore(ti, call); m.instructions.remove(ti);
                    }
                    else {
                        InsnList call = new InsnList(); call.add(new LdcInsnNode(iface));
                        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "lbrt/DuckDispatch", "isInstance", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                        m.instructions.insertBefore(ti, call); m.instructions.remove(ti);
                    }
                    n++;
                } else if (p instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEINTERFACE && ifaceMap.containsKey(mi.owner)) {
                    List<String[]> impls = ifaceMap.get(mi.owner);
                    if (impls.size() == 1) {
                        String[] ts = impls.get(0);
                        m.instructions.set(p, new MethodInsnNode(Opcodes.INVOKESTATIC, ts[1], "h$" + mi.name, "(L" + ts[0] + ";" + mi.desc.substring(1), false));
                    } else {
                        rewriteDuckInvokeInline(m, mi);
                    }
                    n++;
                }
            }
        }
        return n == 0 ? callerBytes : write(c, callerBytes);
    }

    /** Spill an interface invocation to fresh locals and call the generic dispatcher without adding a helper method. */
    static void rewriteDuckInvokeInline(MethodNode m, MethodInsnNode mi) {
        Type[] at = Type.getArgumentTypes(mi.desc); Type rt = Type.getReturnType(mi.desc);
        int next = m.maxLocals, receiver = next++;
        int[] slots = new int[at.length];
        for (int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in = new InsnList();
        for (int i=at.length-1;i>=0;i--) in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        in.add(new VarInsnNode(Opcodes.ASTORE,receiver));
        in.add(new VarInsnNode(Opcodes.ALOAD,receiver));
        in.add(new LdcInsnNode(mi.owner)); in.add(new LdcInsnNode(mi.name)); in.add(new LdcInsnNode(mi.desc));
        in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for(int i=0;i<at.length;i++){in.add(new InsnNode(Opcodes.DUP));in.add(intConst(i));
            in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD),slots[i]));box(in,at[i]);in.add(new InsnNode(Opcodes.AASTORE));}
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"lbrt/DuckDispatch","invoke",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        adaptInlineResult(in,rt,false);
        m.instructions.insertBefore(mi,in);m.instructions.remove(mi);m.maxLocals=Math.max(m.maxLocals,next);
    }

    /** Decides whether an LB access of an MC member must be reflection-routed, and which MC types are inaccessible
     *  (already-loaded + package-private: can be neither AW-widened nor referenced from LB bytecode). */
    public interface Resolver {
        boolean fieldNeedsReflect(String owner, String name, String desc);
        boolean methodNeedsReflect(String owner, String name, String desc);
        boolean typeInaccessible(String internalName);
    }
    static final String AWR = "lbrt/AwReflect";
    static Resolver RES;   // set for the duration of a rewriteLbAw call (single-threaded per CFT invocation is fine)

    /** Rewrite LB's own direct access to non-public members / inaccessible TYPES of already-loaded MC classes into
     *  reflective calls (fields/methods/ctors -> lbrt/AwReflect via string-named owner+param types, so no inaccessible
     *  type appears as a constant), and erase CHECKCASTs to inaccessible types. Classes loaded AFTER attach get the
     *  AccessWidener on-load, so their members stay direct (Resolver returns false for them). */
    public static synchronized byte[] rewriteLbAw(byte[] lbBytes, Resolver r) {
        RES = r;
        try {
            ClassNode c = read(lbBytes);
            boolean[] changed = {false};
            for (MethodNode m : c.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode p = m.instructions.getFirst(), nx; p != null; p = nx) {
                    nx = p.getNext();
                    if (p instanceof FieldInsnNode fi && r.fieldNeedsReflect(fi.owner, fi.name, fi.desc)) {
                        rewriteFieldAw(m, fi); changed[0] = true;
                    } else if (p instanceof MethodInsnNode ci && ci.getOpcode() == Opcodes.INVOKESPECIAL && ci.name.equals("<init>")
                            && r.methodNeedsReflect(ci.owner, ci.name, ci.desc)) {
                        AbstractInsnNode nw = ci.getPrevious();
                        while (nw != null && !(nw instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW && tn.desc.equals(ci.owner))) nw = nw.getPrevious();
                        if (nw != null && nw.getNext() != null && nw.getNext().getOpcode() == Opcodes.DUP) {
                            AbstractInsnNode dup = nw.getNext();
                            m.instructions.remove(nw); m.instructions.remove(dup);
                            rewriteAwCtorInline(m,ci); changed[0] = true;
                        }
                    } else if (p instanceof MethodInsnNode mi && mi.name.charAt(0) != '<'
                            && (mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL)
                            && r.methodNeedsReflect(mi.owner, mi.name, mi.desc)) {
                        rewriteAwMethodInline(m,mi); changed[0] = true;
                    } else if (p instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.CHECKCAST
                            && ti.desc.charAt(0) != '[' && r.typeInaccessible(ti.desc)) {
                        m.instructions.remove(p);   // erase CHECKCAST to an inaccessible type (value flows as Object)
                        changed[0] = true;
                    }
                }
            }
            return changed[0] ? write(c, lbBytes) : lbBytes;
        } finally {
            RES = null;
        }
    }

    static void rewriteAwMethodInline(MethodNode m, MethodInsnNode mi) {
        boolean isStatic=mi.getOpcode()==Opcodes.INVOKESTATIC;
        Type[] at=Type.getArgumentTypes(mi.desc);Type rt=Type.getReturnType(mi.desc);
        int next=m.maxLocals,receiver=isStatic?-1:next++;
        int[] slots=new int[at.length];for(int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in=new InsnList();
        for(int i=at.length-1;i>=0;i--)in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        if(!isStatic)in.add(new VarInsnNode(Opcodes.ASTORE,receiver));
        in.add(new LdcInsnNode(mi.owner));in.add(new LdcInsnNode(mi.name));pushStrArray(in,at);
        in.add(new LdcInsnNode(mi.owner+"#"+mi.name+mi.desc));
        if(isStatic)in.add(new InsnNode(Opcodes.ACONST_NULL));else in.add(new VarInsnNode(Opcodes.ALOAD,receiver));
        pushObjectArgs(in,at,slots);
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,AWR,"inv",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        adaptInlineResult(in,rt,true);
        m.instructions.insertBefore(mi,in);m.instructions.remove(mi);m.maxLocals=Math.max(m.maxLocals,next);
    }

    static void rewriteAwCtorInline(MethodNode m, MethodInsnNode ci) {
        Type[] at=Type.getArgumentTypes(ci.desc);int next=m.maxLocals;int[] slots=new int[at.length];
        for(int i=0;i<at.length;i++){slots[i]=next;next+=at[i].getSize();}
        InsnList in=new InsnList();for(int i=at.length-1;i>=0;i--)in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ISTORE),slots[i]));
        in.add(new LdcInsnNode(ci.owner));pushStrArray(in,at);in.add(new LdcInsnNode(ci.owner+"#<init>"+ci.desc));pushObjectArgs(in,at,slots);
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC,AWR,"newInst",
            "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        if(RES==null||!RES.typeInaccessible(ci.owner))in.add(new TypeInsnNode(Opcodes.CHECKCAST,ci.owner));
        m.instructions.insertBefore(ci,in);m.instructions.remove(ci);m.maxLocals=Math.max(m.maxLocals,next);
    }

    static void pushObjectArgs(InsnList in,Type[] at,int[] slots){in.add(intConst(at.length));in.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
        for(int i=0;i<at.length;i++){in.add(new InsnNode(Opcodes.DUP));in.add(intConst(i));in.add(new VarInsnNode(at[i].getOpcode(Opcodes.ILOAD),slots[i]));box(in,at[i]);in.add(new InsnNode(Opcodes.AASTORE));}}

    static void adaptInlineResult(InsnList in,Type rt,boolean eraseInaccessible){
        if(rt.getSort()==Type.VOID){in.add(new InsnNode(Opcodes.POP));return;}
        if(rt.getSort()<=Type.DOUBLE){unbox(in,rt);return;}
        if(rt.getSort()==Type.ARRAY)in.add(new TypeInsnNode(Opcodes.CHECKCAST,rt.getDescriptor()));
        else if(!eraseInaccessible||RES==null||!RES.typeInaccessible(rt.getInternalName()))in.add(new TypeInsnNode(Opcodes.CHECKCAST,rt.getInternalName()));
    }
    /** internal name (object) or descriptor (array/primitive) as a STRING to hand AwReflect for Class.forName. */
    static String typeName(Type t) { return t.getSort() == Type.OBJECT ? t.getInternalName() : t.getDescriptor(); }
    static void rewriteFieldAw(MethodNode m, FieldInsnNode fi) {
        Type ft = Type.getType(fi.desc); String sfx = awSfx(ft);
        String valDesc = ft.getSort() <= Type.DOUBLE ? ft.getDescriptor() : "Ljava/lang/Object;";
        int op = fi.getOpcode();
        InsnList pre = new InsnList();
        if (op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD) {
            if (op == Opcodes.GETSTATIC) pre.add(new InsnNode(Opcodes.ACONST_NULL));
            pre.add(new LdcInsnNode(fi.owner)); pre.add(new LdcInsnNode(fi.name));
            m.instructions.insertBefore(fi, pre);
            MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "g" + sfx, "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)" + valDesc, false);
            m.instructions.set(fi, call);
            // CHECKCAST back to the field type, UNLESS that type is inaccessible (then leave as Object)
            if (ft.getSort() == Type.ARRAY) m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, ft.getDescriptor()));
            else if (ft.getSort() == Type.OBJECT && !(RES != null && RES.typeInaccessible(ft.getInternalName()))) m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
        } else { // PUTFIELD / PUTSTATIC
            pre.add(new LdcInsnNode(fi.owner)); pre.add(new LdcInsnNode(fi.name));
            m.instructions.insertBefore(fi, pre);
            if (op == Opcodes.PUTFIELD)
                m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "s" + sfx, "(Ljava/lang/Object;" + valDesc + "Ljava/lang/String;Ljava/lang/String;)V", false));
            else
                m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "ss" + sfx, "(" + valDesc + "Ljava/lang/String;Ljava/lang/String;)V", false));
        }
    }
    static String awSfx(Type t) { switch (t.getSort()) { case Type.INT: return "I"; case Type.LONG: return "J"; case Type.BOOLEAN: return "Z"; case Type.FLOAT: return "F"; case Type.DOUBLE: return "D"; case Type.BYTE: return "B"; case Type.SHORT: return "S"; case Type.CHAR: return "C"; default: return "O"; } }
    static void pushStrArray(InsnList in, Type[] at) { in.add(intConst(at.length)); in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String")); for (int i = 0; i < at.length; i++) { in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i)); in.add(new LdcInsnNode(typeName(at[i]))); in.add(new InsnNode(Opcodes.AASTORE)); } }
    static void emitReturn(InsnList in, Type rt) {
        if (rt.getSort() == Type.VOID) { in.add(new InsnNode(Opcodes.POP)); in.add(new InsnNode(Opcodes.RETURN)); }
        else if (rt.getSort() <= Type.DOUBLE) { unbox(in, rt); in.add(new InsnNode(rt.getOpcode(Opcodes.IRETURN))); }
        else if (rt.getSort() == Type.ARRAY) { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getDescriptor())); in.add(new InsnNode(Opcodes.ARETURN)); }
        else { in.add(new TypeInsnNode(Opcodes.CHECKCAST, rt.getInternalName())); in.add(new InsnNode(Opcodes.ARETURN)); }
    }

    // ---- io ----
    static ClassNode read(byte[] b) { ClassReader r = new ClassReader(b); ClassNode n = new ClassNode(); r.accept(n, ClassReader.SKIP_FRAMES); return n; }
    /** Normalize against and re-emit from the authoritative JVM-supplied retransform buffer. */
    public static byte[] rebase(byte[] candidate, byte[] actualBaseline) {
        ClassNode baseline = read(actualBaseline), rebased = read(candidate);
        restoreOriginalSchema(baseline, rebased);
        return write(rebased, actualBaseline);
    }
    /** reads class metadata (superName / interfaces / isInterface) WITHOUT loading the class; set by the agent. */
    public static java.util.function.Function<String,byte[]> CLASS_BYTES;
    /** [BUG22 FIX] When emitting a class that will be REDEFINED over an already-loaded original (target',
     *  rewritten LB callers), seed the ClassWriter with the ORIGINAL bytes' ClassReader so the emitted constant
     *  pool is an index-preserving EXTENSION of the loaded class's pool (original entries keep their indices, new
     *  entries appended). A freely reordered pool triggers a JVM redefinition bug on records with annotated record
     *  components (GuiMessage: @Nullable on the `signature` component): RetransformClasses succeeds but the class's
     *  field metadata is corrupted -> intermittent `NoSuchFieldError: addedTime` at GuiMessage.<init>, and
     *  EXCEPTION_ACCESS_VIOLATION in Class.getDeclaredFields0 (JDK-8315575 family; reproduced LB-free with a pure
     *  ASM round-trip, and clean with an identity clone and with this CP-preserving mode — see
     *  converter-win\evidence\bug22\). NOTE: the basis MUST be the loaded original O, not the mixin output X
     *  (Mixin's own ClassWriter already reordered X's pool). */
    static byte[] write(ClassNode n, byte[] orig) {
        ClassWriter w = orig != null
            ? new ClassWriter(new ClassReader(orig), ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                  protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
              }
            : new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                  protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
              };
        n.accept(w); return w.toByteArray();
    }
    // --- correct getCommonSuperClass by reading class bytes (no classloading, no init) ---
    static final Map<String,String[]> META = new HashMap<>();   // internal -> [superName, isInterface?"1":"0", iface...]
    static final java.util.concurrent.ConcurrentHashMap<String,Boolean> NON_PUBLIC_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean nonPublicField(String owner,String name,String desc){String key=owner+'\0'+name+' '+desc;Boolean cached=NON_PUBLIC_FIELDS.get(key);if(cached!=null)return cached;
        boolean result=false;String c=owner;int guard=0;while(c!=null&&!c.equals("java/lang/Object")&&guard++<64){try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(c);if(b==null)break;ClassReader cr=new ClassReader(b);ClassNode n=new ClassNode();cr.accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);boolean found=false;for(FieldNode f:n.fields)if(f.name.equals(name)&&f.desc.equals(desc)){result=(f.access&Opcodes.ACC_PUBLIC)==0;found=true;break;}if(found)break;c=cr.getSuperName();}catch(Throwable t){break;}}
        Boolean raced=NON_PUBLIC_FIELDS.putIfAbsent(key,result);return raced==null?result:raced;}
    record MethodAccess(String owner,int ownerAccess,int memberAccess){}
    static final java.util.concurrent.ConcurrentHashMap<String,Optional<MethodAccess>> METHOD_ACCESS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean illegalSidecarMethod(String caller,String owner,String name,String desc){
        Optional<MethodAccess> found=METHOD_ACCESS.computeIfAbsent(owner+'\0'+name+desc,k->resolveMethodAccess(owner,name,desc));
        if(found.isEmpty())return false;MethodAccess a=found.get();
        if((a.memberAccess&Opcodes.ACC_PRIVATE)!=0)return true;
        if((a.ownerAccess&Opcodes.ACC_PUBLIC)!=0&&(a.memberAccess&Opcodes.ACC_PUBLIC)!=0)return false;
        return !pkg(caller).equals(pkg(a.owner));
    }
    static Optional<MethodAccess> resolveMethodAccess(String owner,String name,String desc){
        ArrayDeque<String> q=new ArrayDeque<>();HashSet<String> seen=new HashSet<>();q.add(owner);
        while(!q.isEmpty()){String c=q.removeFirst();if(!seen.add(c))continue;try{byte[] b=CLASS_BYTES==null?null:CLASS_BYTES.apply(c);if(b==null)continue;ClassNode n=new ClassNode();new ClassReader(b).accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return Optional.of(new MethodAccess(c,n.access,m.access));if(n.superName!=null)q.addLast(n.superName);q.addAll(n.interfaces);}catch(Throwable ignored){}}
        return Optional.empty();
    }
    static String pkg(String n){int i=n.lastIndexOf('/');return i<0?"":n.substring(0,i);}
    static String[] meta(String cn) {
        String[] m = META.get(cn); if (m != null) return m;
        try {
            byte[] b = CLASS_BYTES != null ? CLASS_BYTES.apply(cn) : null;
            if (b == null) { m = new String[]{"java/lang/Object","0"}; }
            else { ClassReader cr = new ClassReader(b); List<String> l = new ArrayList<>();
                l.add(cr.getSuperName() == null ? "java/lang/Object" : cr.getSuperName());
                l.add((cr.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0");
                for (String i : cr.getInterfaces()) l.add(i);
                m = l.toArray(new String[0]); }
        } catch (Throwable t) { m = new String[]{"java/lang/Object","0"}; }
        META.put(cn, m); return m;
    }
    static boolean isIface(String cn) { return "1".equals(meta(cn)[1]); }
    static String superOf(String cn) { return meta(cn)[0]; }
    static boolean isAssignable(String from, String to) {   // is `from` a subtype of `to`?
        if (to.equals("java/lang/Object")) return true;
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(); q.add(from); java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (!q.isEmpty()) { String c = q.poll(); if (!seen.add(c)) continue; if (c.equals(to)) return true; if (c.equals("java/lang/Object")) continue;
            String[] m = meta(c); q.add(m[0]); for (int i = 2; i < m.length; i++) q.add(m[i]); }
        return false;
    }
    static String commonSuper(String a, String b) {
        if (a.equals(b)) return a;
        if (a.equals("java/lang/Object") || b.equals("java/lang/Object")) return "java/lang/Object";
        if (isAssignable(a, b)) return b;
        if (isAssignable(b, a)) return a;
        if (isIface(a) || isIface(b)) return "java/lang/Object";
        String c = a; int guard = 0;
        do { c = superOf(c); if (c == null || c.equals("java/lang/Object") || ++guard > 50) return "java/lang/Object"; } while (!isAssignable(b, c));
        return c;
    }
    static Set<String> keysF(ClassNode n) { Set<String> s = new HashSet<>(); for (FieldNode f : n.fields) s.add(f.name + " " + f.desc); return s; }
    static Set<String> keysM(ClassNode n) { Set<String> s = new HashSet<>(); for (MethodNode m : n.methods) s.add(m.name + " " + m.desc); return s; }
}
