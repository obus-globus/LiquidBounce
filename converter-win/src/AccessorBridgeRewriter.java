import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Rewrites loadable static Mixin {@code @Accessor}/{@code @Invoker} stubs into schema-neutral
 * calls through lbrt.AwReflect. Normal Mixin fabricates target members for these declarations,
 * but a late attach cannot add those members to an already-defined target class.
 */
public final class AccessorBridgeRewriter {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AWR = "lbrt/AwReflect";

    private AccessorBridgeRewriter() {}

    public static byte[] rewrite(String internalName, byte[] bytes) {
        try {
            ClassNode c = new ClassNode();
            new ClassReader(bytes).accept(c, 0);
            AnnotationNode mixin = annotation(c.visibleAnnotations, MIXIN);
            if (mixin == null) mixin = annotation(c.invisibleAnnotations, MIXIN);
            if (mixin == null) return bytes;
            List<String> targets = mixinTargets(mixin);
            if (targets.size() != 1) return bytes; // explicit unsupported case; verifier reports live stubs
            String target = targets.get(0);
            boolean changed = false;
            for (MethodNode m : c.methods) {
                if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
                AnnotationNode accessor = annotation(m.visibleAnnotations, ACCESSOR);
                if (accessor == null) accessor = annotation(m.invisibleAnnotations, ACCESSOR);
                AnnotationNode invoker = annotation(m.visibleAnnotations, INVOKER);
                if (invoker == null) invoker = annotation(m.invisibleAnnotations, INVOKER);
                if (accessor != null) changed |= rewriteAccessor(m, target, stringValue(accessor, "value"));
                else if (invoker != null) changed |= rewriteInvoker(m, target, stringValue(invoker, "value"));
            }
            if (!changed) return bytes;
            ClassWriter w = new ClassWriter(new ClassReader(bytes), ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
            };
            c.accept(w);
            return w.toByteArray();
        } catch (Throwable t) {
            System.out.println("[FULL] accessor bridge rewrite failed for " + internalName + " -> " + t);
            return bytes;
        }
    }

    private static boolean rewriteAccessor(MethodNode m, String target, String explicit) {
        Type[] args = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        boolean getter = args.length == 0 && ret.getSort() != Type.VOID;
        boolean setter = args.length == 1 && ret.getSort() == Type.VOID;
        if (!getter && !setter) return false;
        String field = explicit == null || explicit.isBlank() ? inferAccessorName(m.name, getter) : explicit;
        if (field == null) return false;
        reset(m);
        if (getter) {
            String suffix = suffix(ret);
            m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            m.instructions.add(new LdcInsnNode(target));
            m.instructions.add(new LdcInsnNode(field));
            String valueDesc = primitive(ret) ? ret.getDescriptor() : "Ljava/lang/Object;";
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "g" + suffix,
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)" + valueDesc, false));
            if (primitive(ret)) m.instructions.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            else castAndReturn(m.instructions, ret);
        } else {
            Type value = args[0];
            m.instructions.add(new VarInsnNode(value.getOpcode(Opcodes.ILOAD), 0));
            m.instructions.add(new LdcInsnNode(target));
            m.instructions.add(new LdcInsnNode(field));
            String valueDesc = primitive(value) ? value.getDescriptor() : "Ljava/lang/Object;";
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "ss" + suffix(value),
                "(" + valueDesc + "Ljava/lang/String;Ljava/lang/String;)V", false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        return true;
    }

    private static boolean rewriteInvoker(MethodNode m, String target, String explicit) {
        String member = explicit == null || explicit.isBlank() ? inferInvokerName(m.name) : explicit;
        if (member == null) return false;
        Type[] args = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        reset(m);
        InsnList in = m.instructions;
        if (member.equals("<init>")) {
            in.add(new LdcInsnNode(target));
            pushTypeNames(in, args);
            in.add(new LdcInsnNode(target + "#<init>" + constructorDesc(args)));
            pushArgs(in, args, 0);
            in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "newInst",
                "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        } else {
            in.add(new LdcInsnNode(target));
            in.add(new LdcInsnNode(member));
            pushTypeNames(in, args);
            in.add(new LdcInsnNode(target + "#" + member + m.desc));
            in.add(new InsnNode(Opcodes.ACONST_NULL)); // static target invoker
            pushArgs(in, args, 0);
            in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AWR, "inv",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        }
        castAndReturn(in, ret);
        return true;
    }

    private static void reset(MethodNode m) {
        m.instructions.clear();
        if (m.tryCatchBlocks != null) m.tryCatchBlocks.clear();
        if (m.localVariables != null) m.localVariables.clear();
    }

    private static void pushTypeNames(InsnList in, Type[] args) {
        in.add(intConst(args.length));
        in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        for (int i = 0; i < args.length; i++) {
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new LdcInsnNode(typeName(args[i]))); in.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    private static void pushArgs(InsnList in, Type[] args, int firstSlot) {
        in.add(intConst(args.length));
        in.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = firstSlot;
        for (int i = 0; i < args.length; i++) {
            Type t = args[i];
            in.add(new InsnNode(Opcodes.DUP)); in.add(intConst(i));
            in.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            box(in, t); in.add(new InsnNode(Opcodes.AASTORE)); slot += t.getSize();
        }
    }

    private static void castAndReturn(InsnList in, Type t) {
        if (t.getSort() == Type.VOID) { in.add(new InsnNode(Opcodes.POP)); in.add(new InsnNode(Opcodes.RETURN)); return; }
        if (primitive(t)) {
            String owner = boxOwner(t);
            in.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
            in.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, unboxName(t), "()" + t.getDescriptor(), false));
            in.add(new InsnNode(t.getOpcode(Opcodes.IRETURN)));
        } else {
            in.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName()));
            in.add(new InsnNode(Opcodes.ARETURN));
        }
    }

    private static void box(InsnList in, Type t) {
        if (!primitive(t)) return;
        String owner = boxOwner(t);
        in.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(" + t.getDescriptor() + ")L" + owner + ";", false));
    }

    private static boolean primitive(Type t) { return t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE; }
    private static String suffix(Type t) { return primitive(t) ? t.getDescriptor() : "O"; }
    private static String typeName(Type t) { return t.getSort() == Type.OBJECT ? t.getInternalName() : t.getDescriptor(); }
    private static String constructorDesc(Type[] args) { return Type.getMethodDescriptor(Type.VOID_TYPE, args); }
    private static String boxOwner(Type t) { return switch (t.getSort()) {
        case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte";
        case Type.CHAR -> "java/lang/Character"; case Type.SHORT -> "java/lang/Short";
        case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long";
        case Type.FLOAT -> "java/lang/Float"; default -> "java/lang/Double"; };
    }
    private static String unboxName(Type t) { return switch (t.getSort()) {
        case Type.BOOLEAN -> "booleanValue"; case Type.BYTE -> "byteValue";
        case Type.CHAR -> "charValue"; case Type.SHORT -> "shortValue";
        case Type.INT -> "intValue"; case Type.LONG -> "longValue";
        case Type.FLOAT -> "floatValue"; default -> "doubleValue"; };
    }

    private static String inferAccessorName(String method, boolean getter) {
        String stem = null;
        if (getter && method.startsWith("get") && method.length() > 3) stem = method.substring(3);
        else if (getter && method.startsWith("is") && method.length() > 2) stem = method.substring(2);
        else if (!getter && method.startsWith("set") && method.length() > 3) stem = method.substring(3);
        return stem == null ? null : decap(stem);
    }

    private static String inferInvokerName(String method) {
        for (String p : new String[]{"new", "create"})
            if (method.startsWith(p) && method.length() > p.length()) return "<init>";
        for (String p : new String[]{"call", "invoke"})
            if (method.startsWith(p) && method.length() > p.length()) return decap(method.substring(p.length()));
        return null;
    }

    private static String decap(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> mixinTargets(AnnotationNode a) {
        List<String> out = new ArrayList<>();
        Object v = value(a, "value");
        if (v instanceof List<?> l) for (Object x : l) if (x instanceof Type t) out.add(t.getInternalName());
        Object ts = value(a, "targets");
        if (ts instanceof List<?> l) for (Object x : l) if (x instanceof String s) out.add(s.replace('.', '/'));
        return out;
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        if (list != null) for (AnnotationNode a : list) if (a.desc.equals(desc)) return a;
        return null;
    }
    private static Object value(AnnotationNode a, String key) {
        if (a == null || a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        return null;
    }
    private static String stringValue(AnnotationNode a, String key) { Object v = value(a, key); return v instanceof String s ? s : null; }
    private static AbstractInsnNode intConst(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v);
        return new IntInsnNode(Opcodes.SIPUSH, v);
    }
}
