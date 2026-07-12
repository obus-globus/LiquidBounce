import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/** Focused fixtures for the generalized late-attach lowering. */
public final class ConverterAutoTests {
    public static class A {}
    public static class B {}
    public static class Base {}
    public static class Child extends Base {}
    public static class ASidecar { public static String h$value(A a){ return "A"; } public static int h$wide(A a,long x,double y){return (int)(x+y);} }
    public static class BSidecar { public static String h$value(B b){ return "B"; } public static int h$wide(B b,long x,double y){return (int)(x-y);} }
    public static class BaseSidecar { public static String h$value(Base a){return "base";} }
    public static class ChildSidecar { public static String h$value(Child a){return "child";} }
    public static class AccessorTarget {
        private static final String SECRET="ok"; private static int NUMBER;
        private final String value; private AccessorTarget(String value){this.value=value;}
    }
    public static class GateTarget { public static int calls; public static void startConnecting(String host,int port){
        List<String> values;if(port>0)values=new ArrayList<>();else values=new LinkedList<>();values.add(host);calls+=values.size();} }
    public static class AwTarget {
        private final int number; private AwTarget(int number){this.number=number;}
        private static long combine(long x,double y){return x+(long)y;}
        private int add(int x){return number+x;}
    }

    public static void main(String[] args) throws Exception {
        testMixinJava25Compatibility();
        if(args.length>0&&args[0].equals("compat")){System.out.println("[AUTO-TEST] assembled agent reports Java 25-compatible ASM");return;}
        testDuckDispatch();
        testAmbiguousCallerRewrite();
        testStaticAccessorRewrite();
        testAwInlineRewrite();
        testJoinGateRewrite();
        testSchemaGate();
        testConverterRestoresOriginalSchema();
        System.out.println("[AUTO-TEST] all converter automation fixtures passed");
    }

    static void testMixinJava25Compatibility() throws Exception {
        Class<?> levels=Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$CompatibilityLevel");
        Object java25=levels.getField("JAVA_25").get(null);
        Method supported=levels.getDeclaredMethod("isSupported");supported.setAccessible(true);
        yes((Boolean)supported.invoke(java25));
    }

    static void testDuckDispatch() {
        String iface="fixture/Duck";
        lbrt.DuckDispatch.register(iface, internal(A.class), internal(ASidecar.class));
        lbrt.DuckDispatch.register(iface, internal(B.class), internal(BSidecar.class));
        eq("A",lbrt.DuckDispatch.invoke(new A(),iface,"value","()Ljava/lang/String;",new Object[0]));
        eq("B",lbrt.DuckDispatch.invoke(new B(),iface,"value","()Ljava/lang/String;",new Object[0]));
        eq(9,lbrt.DuckDispatch.invoke(new A(),iface,"wide","(JD)I",new Object[]{7L,2D}));
        yes(lbrt.DuckDispatch.isInstance(new A(),iface));
        no(lbrt.DuckDispatch.isInstance("not-a-target",iface));
        String hierarchy="fixture/HierarchyDuck";
        lbrt.DuckDispatch.register(hierarchy,internal(Base.class),internal(BaseSidecar.class));
        lbrt.DuckDispatch.register(hierarchy,internal(Child.class),internal(ChildSidecar.class));
        eq("child",lbrt.DuckDispatch.invoke(new Child(),hierarchy,"value","()Ljava/lang/String;",new Object[0]));
    }

    static void testAmbiguousCallerRewrite() throws Exception {
        String iface="fixture/Duck";
        byte[] input=callerFixture("fixture/RewrittenCaller",iface);
        Map<String,List<String[]>> map=new HashMap<>();
        map.put(iface,List.of(new String[]{internal(A.class),internal(ASidecar.class)},new String[]{internal(B.class),internal(BSidecar.class)}));
        byte[] output=RetransformConverter.rewriteCaller(input,map);
        ClassNode before=new ClassNode(),c=new ClassNode();new ClassReader(input).accept(before,0);new ClassReader(output).accept(c,0);
        eq(before.methods.size(),c.methods.size());
        for(MethodNode m:c.methods)for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext()){
            if(p instanceof TypeInsnNode ti&&ti.desc.equals(iface))fail("dropped interface type remained");
            if(p instanceof MethodInsnNode mi&&mi.owner.equals(iface))fail("dropped interface invoke remained");
        }
        yes(LateAttachVerifier.verifyCaller(c.name,input,output,Map.of(),Map.of(),map));
        Class<?> k=new Loader().define(c.name.replace('/','.'),output);
        Method call=k.getMethod("call",Object.class);
        eq("A",call.invoke(null,new A())); eq("B",call.invoke(null,new B()));
        eq(true,k.getMethod("isDuck",Object.class).invoke(null,new A()));
        eq(false,k.getMethod("isDuck",Object.class).invoke(null,"no"));
        eq(9,k.getMethod("callWide",Object.class,long.class,double.class).invoke(null,new A(),7L,2D));
        try{call.invoke(null,"bad");fail("duck cast accepted invalid receiver");}
        catch(InvocationTargetException e){yes(e.getCause() instanceof ClassCastException);}
    }

    static void testStaticAccessorRewrite() throws Exception {
        byte[] input=accessorFixture("fixture/StaticAccessor",internal(AccessorTarget.class));
        byte[] output=AccessorBridgeRewriter.rewrite("fixture/StaticAccessor",input);
        ClassNode c=new ClassNode();new ClassReader(output).accept(c,0);boolean bridge=false,stub=false;
        for(MethodNode m:c.methods)for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext()){
            if(p instanceof MethodInsnNode mi&&mi.owner.equals("lbrt/AwReflect")&&mi.name.equals("gO"))bridge=true;
            if(p instanceof TypeInsnNode ti&&ti.desc.equals("java/lang/AssertionError"))stub=true;
        }
        yes(bridge);no(stub);
        Class<?> k=new Loader().define("fixture.StaticAccessor",output);
        eq("ok",k.getMethod("getSecret").invoke(null));
        k.getMethod("setNumber",int.class).invoke(null,42);
        eq(42,k.getMethod("getNumber").invoke(null));
        Object made=k.getMethod("createAccessorTarget",String.class).invoke(null,"made");
        yes(made instanceof AccessorTarget);
        byte[] abstractAccessor=abstractAccessorFixture("fixture/InstanceAccessor",internal(AccessorTarget.class));
        yes(LateAttachVerifier.verifyCaller("fixture/InstanceAccessor",abstractAccessor,abstractAccessor,Map.of(),Map.of(),Map.of()));
    }

    static void testJoinGateRewrite() throws Exception {
        GateTarget.calls=0;byte[] input=bytesOf(GateTarget.class),output=JoinGateRewriter.rewrite(internal(GateTarget.class),input);
        yes(LateAttachVerifier.verifyRetransform(internal(GateTarget.class),input,output));
        Class<?> rewritten=new Loader().define(GateTarget.class.getName(),output);
        lbrt.JoinGate.block();rewritten.getMethod("startConnecting",String.class,int.class).invoke(null,"localhost",25565);
        eq(0,rewritten.getField("calls").get(null));eq(0,GateTarget.calls);
        lbrt.JoinGate.open();eq(1,GateTarget.calls);
    }

    static void testAwInlineRewrite() throws Exception {
        byte[] input=awCallerFixture("fixture/AwCaller",internal(AwTarget.class));
        RetransformConverter.Resolver all=new RetransformConverter.Resolver(){
            public boolean fieldNeedsReflect(String o,String n,String d){return o.equals(internal(AwTarget.class));}
            public boolean methodNeedsReflect(String o,String n,String d){return o.equals(internal(AwTarget.class));}
            public boolean typeInaccessible(String n){return false;}
        };
        byte[] output=RetransformConverter.rewriteLbAw(input,all);
        yes(LateAttachVerifier.verifyCaller("fixture/AwCaller",input,output,Map.of(),Map.of(),Map.of()));
        ClassNode a=new ClassNode(),b=new ClassNode();new ClassReader(input).accept(a,0);new ClassReader(output).accept(b,0);eq(a.methods.size(),b.methods.size());
        Class<?> k=new Loader().define("fixture.AwCaller",output);
        Object target=k.getMethod("make",int.class).invoke(null,10);yes(target instanceof AwTarget);
        eq(15,k.getMethod("add",AwTarget.class,int.class).invoke(null,target,5));
        eq(12L,k.getMethod("combine",long.class,double.class).invoke(null,7L,5D));
        eq(10,k.getMethod("get",AwTarget.class).invoke(null,target));
        k.getMethod("set",AwTarget.class,int.class).invoke(null,target,21);eq(21,k.getMethod("get",AwTarget.class).invoke(null,target));
    }

    static void testSchemaGate() {
        byte[] original=schemaFixture("fixture/Schema",false), same=schemaFixture("fixture/Schema",false), changed=schemaFixture("fixture/Schema",true);
        yes(LateAttachVerifier.verifyRetransform("fixture/Schema",original,same));
        no(LateAttachVerifier.verifyRetransform("fixture/Schema",original,changed));
        no(LateAttachVerifier.verifyRetransform("fixture/Schema",original,changed));
    }

    static void testConverterRestoresOriginalSchema() throws Exception {
        String owner="fixture/RestoredSchema";
        byte[] original=mutableSchemaFixture(owner,true,false,1);
        byte[] mixed=mutableSchemaFixture(owner,false,true,2);
        byte[] converted=RetransformConverter.convert(owner,original,mixed).target;
        yes(LateAttachVerifier.verifyRetransform(owner,original,converted));
        ClassNode c=new ClassNode();new ClassReader(converted).accept(c,0);
        eq(List.of("<init>","first","second"),c.methods.stream().map(m->m.name).toList());
        eq(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,c.fields.get(0).access);
        MethodNode first=c.methods.stream().filter(m->m.name.equals("first")).findFirst().orElseThrow();
        AbstractInsnNode p=first.instructions.getFirst();while(p!=null&&p.getOpcode()<0)p=p.getNext();
        eq(Opcodes.ICONST_2,p.getOpcode());
        Class<?> k=new Loader().define(owner.replace('/','.'),converted);
        eq(2,k.getMethod("first").invoke(k.getConstructor().newInstance()));

        byte[] rebased=RetransformConverter.rebase(mixed,original);
        yes(LateAttachVerifier.verifyRetransform(owner,original,rebased));
        ClassNode r=new ClassNode();new ClassReader(rebased).accept(r,0);
        eq(List.of("<init>","first","second"),r.methods.stream().map(m->m.name).toList());
        eq(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,r.fields.get(0).access);
        Class<?> rk=new Loader().define(owner.replace('/','.'),rebased);
        eq(2,rk.getMethod("first").invoke(rk.getConstructor().newInstance()));
    }

    static byte[] callerFixture(String owner,String iface){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"call","(Ljava/lang/Object;)Ljava/lang/String;",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitTypeInsn(Opcodes.CHECKCAST,iface);m.visitMethodInsn(Opcodes.INVOKEINTERFACE,iface,"value","()Ljava/lang/String;",true);m.visitInsn(Opcodes.ARETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"isDuck","(Ljava/lang/Object;)Z",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitTypeInsn(Opcodes.INSTANCEOF,iface);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"callWide","(Ljava/lang/Object;JD)I",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitTypeInsn(Opcodes.CHECKCAST,iface);m.visitVarInsn(Opcodes.LLOAD,1);m.visitVarInsn(Opcodes.DLOAD,3);m.visitMethodInsn(Opcodes.INVOKEINTERFACE,iface,"wide","(JD)I",true);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();}
    static byte[] accessorFixture(String owner,String target){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_INTERFACE,owner,null,"java/lang/Object",null);addMixin(w,target);addAccessorStub(w,"getSecret","()Ljava/lang/String;","SECRET");addAccessorStub(w,"getNumber","()I","NUMBER");addAccessorStub(w,"setNumber","(I)V","NUMBER");MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"createAccessorTarget","(Ljava/lang/String;)L"+target+";",null,null);m.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Invoker;",false).visitEnd();stub(m);w.visitEnd();return w.toByteArray();}
    static byte[] abstractAccessorFixture(String owner,String target){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_INTERFACE,owner,null,"java/lang/Object",null);addMixin(w,target);MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"getValue","()Ljava/lang/String;",null,null);m.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;",false).visitEnd();m.visitEnd();w.visitEnd();return w.toByteArray();}
    static byte[] awCallerFixture(String owner,String target){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"make","(I)Ljava/lang/Object;",null,null);m.visitCode();m.visitTypeInsn(Opcodes.NEW,target);m.visitInsn(Opcodes.DUP);m.visitVarInsn(Opcodes.ILOAD,0);m.visitMethodInsn(Opcodes.INVOKESPECIAL,target,"<init>","(I)V",false);m.visitInsn(Opcodes.ARETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"combine","(JD)J",null,null);m.visitCode();m.visitVarInsn(Opcodes.LLOAD,0);m.visitVarInsn(Opcodes.DLOAD,2);m.visitMethodInsn(Opcodes.INVOKESTATIC,target,"combine","(JD)J",false);m.visitInsn(Opcodes.LRETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"add","(L"+target+";I)I",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ILOAD,1);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,target,"add","(I)I",false);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"get","(L"+target+";)I",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,target,"number","I");m.visitInsn(Opcodes.IRETURN);m.visitMaxs(0,0);m.visitEnd();m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"set","(L"+target+";I)V",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ILOAD,1);m.visitFieldInsn(Opcodes.PUTFIELD,target,"number","I");m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();}
    static void addMixin(ClassWriter w,String target){AnnotationVisitor ma=w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;",false);AnnotationVisitor av=ma.visitArray("value");av.visit(null,Type.getObjectType(target));av.visitEnd();ma.visitEnd();}
    static void addAccessorStub(ClassWriter w,String name,String desc,String field){MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,desc,null,null);AnnotationVisitor a=m.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;",false);a.visit("value",field);a.visitEnd();stub(m);}
    static void stub(MethodVisitor m){m.visitCode();m.visitTypeInsn(Opcodes.NEW,"java/lang/AssertionError");m.visitInsn(Opcodes.DUP);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","()V",false);m.visitInsn(Opcodes.ATHROW);m.visitMaxs(0,0);m.visitEnd();}
    static byte[] schemaFixture(String owner,boolean extra){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PRIVATE,"x","I",null,null).visitEnd();if(extra)w.visitField(Opcodes.ACC_PRIVATE,"y","I",null,null).visitEnd();MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,"value","()I",null,null);m.visitCode();m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,1);m.visitEnd();w.visitEnd();return w.toByteArray();}
    static byte[] mutableSchemaFixture(String owner,boolean isFinal,boolean reverseMethods,int value){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PRIVATE|(isFinal?Opcodes.ACC_FINAL:0),"state","I",null,null).visitEnd();MethodVisitor c=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);c.visitCode();c.visitVarInsn(Opcodes.ALOAD,0);c.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);c.visitInsn(Opcodes.RETURN);c.visitMaxs(1,1);c.visitEnd();if(reverseMethods){constantMethod(w,"second",3);constantMethod(w,"first",value);}else{constantMethod(w,"first",value);constantMethod(w,"second",3);}w.visitEnd();return w.toByteArray();}
    static void constantMethod(ClassWriter w,String name,int value){MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC,name,"()I",null,null);m.visitCode();m.visitInsn(Opcodes.ICONST_0+value);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,1);m.visitEnd();}

    static String internal(Class<?> c){return c.getName().replace('.','/');}
    static byte[] bytesOf(Class<?> c)throws Exception{try(InputStream in=c.getClassLoader().getResourceAsStream(internal(c)+".class")){return Objects.requireNonNull(in).readAllBytes();}}
    static void eq(Object a,Object b){if(!Objects.equals(a,b))fail("expected="+a+" actual="+b);}
    static void yes(boolean b){if(!b)fail("expected true");}static void no(boolean b){if(b)fail("expected false");}
    static void fail(String m){throw new AssertionError(m);}
    static final class Loader extends ClassLoader { Loader(){super(ConverterAutoTests.class.getClassLoader());} Class<?> define(String n,byte[] b){return defineClass(n,b,0,b.length);} }
}
