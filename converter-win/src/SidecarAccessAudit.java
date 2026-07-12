import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Live audit helper: find member references which are legal in a mixin target but illegal after sidecar relocation. */
public final class SidecarAccessAudit {
    static final ClassLoader SYS=ClassLoader.getSystemClassLoader();
    static final Map<String,Info> FIELDS=new HashMap<>(),METHODS=new HashMap<>();
    record Info(String owner,int ownerAccess,int memberAccess){}
    record Issue(String sidecar,String method,String kind,String reference){}

    public static void agentmain(String args,Instrumentation inst){
        List<Issue> issues=new ArrayList<>();int sidecars=0,instructions=0;
        for(FullInjectAgent.Conv conversion:FullInjectAgent.convMap.values()){
            sidecars++;ClassNode c=new ClassNode();new ClassReader(conversion.sidecar).accept(c,0);
            for(MethodNode m:c.methods)for(AbstractInsnNode p=m.instructions.getFirst();p!=null;p=p.getNext()){
                instructions++;
                if(p instanceof FieldInsnNode f)checkField(issues,c.name,m.name+m.desc,f.owner,f.name,f.desc);
                else if(p instanceof MethodInsnNode call)checkMethod(issues,c.name,m.name+m.desc,call.owner,call.name,call.desc);
                else if(p instanceof InvokeDynamicInsnNode indy){checkHandle(issues,c.name,m.name+m.desc,indy.bsm);for(Object x:indy.bsmArgs)checkConstant(issues,c.name,m.name+m.desc,x);}
                else if(p instanceof LdcInsnNode ldc)checkConstant(issues,c.name,m.name+m.desc,ldc.cst);
            }
        }
        for(Issue i:issues)System.out.println("[ACCESS-AUDIT] "+i.kind+" "+i.sidecar+"."+i.method+" -> "+i.reference);
        System.out.println("[ACCESS-AUDIT] sidecars="+sidecars+" instructions="+instructions+" illegal="+issues.size());
    }
    static void checkConstant(List<Issue> out,String sidecar,String method,Object x){if(x instanceof Handle h)checkHandle(out,sidecar,method,h);else if(x instanceof ConstantDynamic d){checkHandle(out,sidecar,method,d.getBootstrapMethod());for(int i=0;i<d.getBootstrapMethodArgumentCount();i++)checkConstant(out,sidecar,method,d.getBootstrapMethodArgument(i));}}
    static void checkHandle(List<Issue> out,String sidecar,String method,Handle h){if(h.getTag()>=Opcodes.H_GETFIELD&&h.getTag()<=Opcodes.H_PUTSTATIC)checkField(out,sidecar,method,h.getOwner(),h.getName(),h.getDesc());else checkMethod(out,sidecar,method,h.getOwner(),h.getName(),h.getDesc());}
    static void checkField(List<Issue> out,String sidecar,String method,String owner,String name,String desc){Info i=resolve(owner,name,desc,false);if(i!=null&&!legal(sidecar,i))out.add(new Issue(sidecar,method,"FIELD",owner+"."+name+" "+desc+" declared="+i.owner+" access="+i.memberAccess));}
    static void checkMethod(List<Issue> out,String sidecar,String method,String owner,String name,String desc){Info i=resolve(owner,name,desc,true);if(i!=null&&!legal(sidecar,i))out.add(new Issue(sidecar,method,"METHOD",owner+"."+name+desc+" declared="+i.owner+" access="+i.memberAccess));}
    static boolean legal(String caller,Info i){
        if((i.memberAccess&Opcodes.ACC_PRIVATE)!=0)return false;
        if((i.ownerAccess&Opcodes.ACC_PUBLIC)!=0&&(i.memberAccess&Opcodes.ACC_PUBLIC)!=0)return true;
        return pkg(caller).equals(pkg(i.owner));
    }
    static String pkg(String n){int x=n.lastIndexOf('/');return x<0?"":n.substring(0,x);}
    static Info resolve(String owner,String name,String desc,boolean method){Map<String,Info> cache=method?METHODS:FIELDS;String key=owner+'\0'+name+desc;Info cached=cache.get(key);if(cached!=null)return cached;Set<String> seen=new HashSet<>();ArrayDeque<String> q=new ArrayDeque<>();q.add(owner);
        while(!q.isEmpty()){String c=q.removeFirst();if(!seen.add(c))continue;try{byte[] b=bytes(c);if(b==null)continue;ClassReader cr=new ClassReader(b);ClassNode n=new ClassNode();cr.accept(n,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);if(method){for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc)){Info i=new Info(c,n.access,m.access);cache.put(key,i);return i;}}else for(FieldNode f:n.fields)if(f.name.equals(name)&&f.desc.equals(desc)){Info i=new Info(c,n.access,f.access);cache.put(key,i);return i;}if(n.superName!=null)q.addLast(n.superName);q.addAll(n.interfaces);}catch(Throwable ignored){}}
        return null;}
    static byte[] bytes(String internal){try(InputStream in=SYS.getResourceAsStream(internal+".class")){return in==null?null:in.readAllBytes();}catch(Throwable t){return null;}}
}
