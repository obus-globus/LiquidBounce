import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fail-fast structural and residual-reference validation for schema-neutral late-attach output. */
public final class LateAttachVerifier {
    public record Issue(String severity, String code, String owner, String artifact, String method, String message) {}
    private static final List<Issue> ISSUES = new CopyOnWriteArrayList<>();
    private static final Set<String> DEDUP = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<Integer> LOCAL_ERRORS = ThreadLocal.withInitial(() -> 0);
    private static volatile boolean REPORT_STARTED;

    private LateAttachVerifier() {}

    public static boolean verifyConversion(String owner, byte[] original, byte[] target, byte[] sidecar, byte[] state,
            Map<String,String[]> removedMethods, Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        int before = localErrors();
        verifySchema(owner, "target", original, target);
        scan(owner, "target", target, removedMethods, removedFields, droppedIfaces);
        scan(owner, "sidecar", sidecar, removedMethods, removedFields, droppedIfaces);
        scan(owner, "state", state, removedMethods, removedFields, droppedIfaces);
        return localErrors() == before;
    }

    public static boolean verifyRetransform(String owner, byte[] actualBaseline, byte[] candidate) {
        int before = localErrors();
        verifySchema(owner, "retransform", actualBaseline, candidate);
        return localErrors() == before;
    }

    public static boolean verifyCaller(String owner, byte[] baseline, byte[] candidate, Map<String,String[]> removedMethods,
            Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        int before = localErrors();
        verifySchema(owner, "caller", baseline, candidate);
        scan(owner, "caller", candidate, removedMethods, removedFields, droppedIfaces);
        detectAccessorStubs(owner, candidate);
        return localErrors() == before;
    }

    public static void warn(String code, String owner, String artifact, String message) {
        add("WARN", code, owner, artifact, "", message);
    }

    public static void error(String code, String owner, String artifact, String message) {
        add("ERROR", code, owner, artifact, "", message);
    }

    public static boolean hasErrors() { return errorCount() > 0; }
    public static int errorCount() { int n=0; for(Issue i:ISSUES) if(i.severity.equals("ERROR")) n++; return n; }
    private static int localErrors() { return LOCAL_ERRORS.get(); }
    public static List<Issue> issues() { return List.copyOf(ISSUES); }

    private static void verifySchema(String owner, String artifact, byte[] expectedBytes, byte[] actualBytes) {
        try {
            Schema e = Schema.read(expectedBytes), a = Schema.read(actualBytes);
            compare(owner, artifact, "class", e.header, a.header);
            compare(owner, artifact, "interfaces", e.interfaces, a.interfaces);
            compare(owner, artifact, "fields", e.fields, a.fields);
            compare(owner, artifact, "methods", e.methods, a.methods);
            compare(owner, artifact, "nest", e.nest, a.nest);
            compare(owner, artifact, "record", e.record, a.record);
            compare(owner, artifact, "permitted", e.permitted, a.permitted);
        } catch (Throwable t) { error("SCHEMA_PARSE", owner, artifact, String.valueOf(t)); }
    }

    private static void compare(String owner, String artifact, String part, Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            error("SCHEMA_MISMATCH", owner, artifact, part + " expected=" + expected + " actual=" + actual);
    }

    private static final class Schema {
        String header; List<String> interfaces, fields, methods, nest, record, permitted;
        static Schema read(byte[] b) {
            ClassNode c = new ClassNode(); new ClassReader(b).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Schema s = new Schema();
            s.header = c.version + "|" + c.name + "|" + c.access + "|" + c.superName;
            s.interfaces = List.copyOf(c.interfaces);
            s.fields = new ArrayList<>(); for(FieldNode f:c.fields) s.fields.add(f.name+"|"+f.desc+"|"+f.access);
            s.methods = new ArrayList<>(); for(MethodNode m:c.methods) s.methods.add(m.name+"|"+m.desc+"|"+m.access);
            s.nest = new ArrayList<>(); s.nest.add(String.valueOf(c.nestHostClass)); if(c.nestMembers!=null)s.nest.addAll(c.nestMembers);
            s.record = new ArrayList<>(); if(c.recordComponents!=null)for(RecordComponentNode r:c.recordComponents)s.record.add(r.name+"|"+r.descriptor+"|"+r.signature);
            s.permitted = c.permittedSubclasses==null?List.of():List.copyOf(c.permittedSubclasses);
            return s;
        }
    }

    private static void scan(String owner, String artifact, byte[] bytes, Map<String,String[]> removedMethods,
            Map<String,String[]> removedFields, Map<String,List<String[]>> droppedIfaces) {
        try {
            ClassNode c=new ClassNode(); new ClassReader(bytes).accept(c,0);
            for(FieldNode f:c.fields) scanDesc(owner,artifact,f.name,f.desc,droppedIfaces);
            for(MethodNode m:c.methods) {
                scanDesc(owner,artifact,m.name,m.desc,droppedIfaces);
                if(m.instructions==null)continue;
                for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext()) {
                    if(p instanceof FieldInsnNode fi) {
                        scanDesc(owner,artifact,m.name,fi.desc,droppedIfaces);
                        checkField(owner,artifact,m.name,fi.owner,fi.name,fi.desc,removedFields);
                    }
                    else if(p instanceof MethodInsnNode mi) {
                        scanDesc(owner,artifact,m.name,mi.desc,droppedIfaces);
                        if(droppedIfaces.containsKey(mi.owner) && mi.getOpcode()==Opcodes.INVOKEINTERFACE)
                            add("ERROR","DROPPED_INTERFACE_INVOKE",owner,artifact,m.name,mi.owner+"."+mi.name+mi.desc);
                        checkMethod(owner,artifact,m.name,mi.owner,mi.name,mi.desc,removedMethods);
                    } else if(p instanceof TypeInsnNode ti && droppedIfaces.containsKey(ti.desc)) {
                        String code=ti.getOpcode()==Opcodes.INSTANCEOF?"DROPPED_INTERFACE_INSTANCEOF":"DROPPED_INTERFACE_CAST";
                        add("ERROR",code,owner,artifact,m.name,ti.desc);
                    } else if(p instanceof InvokeDynamicInsnNode id) {
                        scanDesc(owner,artifact,m.name,id.desc,droppedIfaces);
                        inspectConst(owner,artifact,m.name,id.bsm,removedMethods,removedFields,droppedIfaces,true);
                        for(Object x:id.bsmArgs)inspectConst(owner,artifact,m.name,x,removedMethods,removedFields,droppedIfaces,true);
                    } else if(p instanceof MultiANewArrayInsnNode ma) {
                        scanDesc(owner,artifact,m.name,ma.desc,droppedIfaces);
                    } else if(p instanceof LdcInsnNode ldc) inspectConst(owner,artifact,m.name,ldc.cst,removedMethods,removedFields,droppedIfaces,true);
                }
            }
        } catch(Throwable t){ error("BYTECODE_PARSE",owner,artifact,String.valueOf(t)); }
    }

    private static void inspectConst(String owner,String artifact,String method,Object x,Map<String,String[]> rm,
            Map<String,String[]> rf,Map<String,List<String[]>> di,boolean bootstrap) {
        if(x instanceof Handle h) {
            scanDesc(owner,artifact,method,h.getDesc(),di);
            if(h.getTag()>=Opcodes.H_GETFIELD&&h.getTag()<=Opcodes.H_PUTSTATIC)checkField(owner,artifact,method,h.getOwner(),h.getName(),h.getDesc(),rf);
            else checkMethod(owner,artifact,method,h.getOwner(),h.getName(),h.getDesc(),rm);
            if(di.containsKey(h.getOwner()))add("ERROR","BOOTSTRAP_RESIDUAL_REF",owner,artifact,method,h.toString());
        } else if(x instanceof ConstantDynamic cd) {
            scanDesc(owner,artifact,method,cd.getDescriptor(),di);
            inspectConst(owner,artifact,method,cd.getBootstrapMethod(),rm,rf,di,true);
            for(int i=0;i<cd.getBootstrapMethodArgumentCount();i++)inspectConst(owner,artifact,method,cd.getBootstrapMethodArgument(i),rm,rf,di,true);
        } else if(x instanceof Type t) scanDesc(owner,artifact,method,t.getDescriptor(),di);
    }

    private static void checkField(String owner,String artifact,String method,String o,String n,String d,Map<String,String[]> removed) {
        if(containsRemoved(removed,o,n,d))add("ERROR","REMOVED_FIELD_REF",owner,artifact,method,o+"."+n+" "+d);
    }
    private static void checkMethod(String owner,String artifact,String method,String o,String n,String d,Map<String,String[]> removed) {
        if(containsRemoved(removed,o,n,d))add("ERROR","REMOVED_METHOD_REF",owner,artifact,method,o+"."+n+d);
    }
    private static String key(String o,String n,String d){return o+'\0'+n+' '+d;}
    private static boolean containsRemoved(Map<String,String[]> removed,String owner,String name,String desc){
        String c=owner;for(int guard=0;c!=null&&!c.equals("java/lang/Object")&&guard++<64;){
            if(removed.containsKey(key(c,name,desc)))return true;
            try{c=RetransformConverter.superOf(c);}catch(Throwable t){break;}
        }return false;
    }

    private static void scanDesc(String owner,String artifact,String method,String desc,Map<String,List<String[]>> dropped) {
        for(String iface:dropped.keySet()) if(desc.contains("L"+iface+";"))
            add("ERROR","DROPPED_INTERFACE_DESCRIPTOR",owner,artifact,method,desc);
    }

    private static void detectAccessorStubs(String owner, byte[] bytes) {
        try {
            ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);
            for(MethodNode m:c.methods){boolean accessor=hasAnn(m,"Lorg/spongepowered/asm/mixin/gen/Accessor;")||hasAnn(m,"Lorg/spongepowered/asm/mixin/gen/Invoker;");
                if(!accessor||(m.access&Opcodes.ACC_STATIC)==0)continue; boolean throwsStub=false;
                if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)throwsStub=true;
                if(m.instructions!=null)for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext())
                    if(p instanceof TypeInsnNode ti&&ti.getOpcode()==Opcodes.NEW&&(ti.desc.endsWith("AssertionError")||ti.desc.endsWith("NotImplementedError")||ti.desc.endsWith("AbstractMethodError")))throwsStub=true;
                if(throwsStub)add("ERROR","ACCESSOR_STUB",owner,"caller",m.name,m.name+m.desc);
            }
        }catch(Throwable t){error("ACCESSOR_SCAN",owner,"caller",String.valueOf(t));}
    }
    private static boolean hasAnn(MethodNode m,String d){if(m.visibleAnnotations!=null)for(AnnotationNode a:m.visibleAnnotations)if(a.desc.equals(d))return true;if(m.invisibleAnnotations!=null)for(AnnotationNode a:m.invisibleAnnotations)if(a.desc.equals(d))return true;return false;}

    private static void add(String severity,String code,String owner,String artifact,String method,String message){
        if(severity.equals("ERROR"))LOCAL_ERRORS.set(LOCAL_ERRORS.get()+1);
        String k=severity+'|'+code+'|'+owner+'|'+artifact+'|'+method+'|'+message;
        boolean added=DEDUP.add(k);if(added)ISSUES.add(new Issue(severity,code,owner,artifact,method,message));
        if(added&&REPORT_STARTED)writeReport();
    }

    public static synchronized Path writeReport() {
        try {
            String override=System.getProperty("lb.agent.verificationReport");
            Path out=override==null||override.isBlank()?Path.of(System.getProperty("java.io.tmpdir"),"liquidbounce-lateattach-verification-"+ProcessHandle.current().pid()+".json"):Path.of(override);
            Path tmp=out.resolveSibling(out.getFileName()+".tmp");
            StringBuilder j=new StringBuilder("{\"schemaVersion\":1,\"status\":\"").append(hasErrors()?"error":"ok").append("\",\"summary\":{\"errors\":").append(errorCount()).append(",\"issues\":").append(ISSUES.size()).append("},\"issues\":[");
            for(int i=0;i<ISSUES.size();i++){if(i>0)j.append(',');Issue x=ISSUES.get(i);j.append('{').append(json("severity",x.severity)).append(',').append(json("code",x.code)).append(',').append(json("class",x.owner)).append(',').append(json("artifact",x.artifact)).append(',').append(json("method",x.method)).append(',').append(json("message",x.message)).append('}');}
            j.append("]}");Files.writeString(tmp,j,StandardCharsets.UTF_8);
            try{Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING);}
            REPORT_STARTED=true;System.out.println("[FULL] verification report: "+out);return out;
        }catch(Throwable t){System.out.println("[FULL] verification report failed -> "+t);return null;}
    }
    private static String json(String k,String v){return "\""+k+"\":\""+escape(v)+"\"";}
    private static String escape(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}
