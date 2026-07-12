package vspike;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;

/** Minimal AccessWidener v1 applier (namespace-agnostic; expects targets already in the jar's namespace). */
public class AccessWidener {
    private final Set<String> classAccessible = new HashSet<>();
    private final Set<String> classExtendable = new HashSet<>();
    // owner -> set of member "name" (desc ignored for matching robustness)
    private final Map<String,Set<String>> fieldAccessible = new HashMap<>();
    private final Map<String,Set<String>> fieldMutable = new HashMap<>();
    private final Map<String,Set<String>> methodAccessible = new HashMap<>();
    private final Map<String,Set<String>> methodExtendable = new HashMap<>();
    private final Set<String> touchedClasses = new HashSet<>();

    public AccessWidener(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line; boolean header=false;
            while ((line = r.readLine()) != null) {
                line = line.replace("\t"," ").trim();
                int c = line.indexOf('#'); if (c>=0) line = line.substring(0,c).trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                if (!header) { header = true; continue; } // "accessWidener v1 <ns>"
                if (t.length < 3) continue;
                String access = t[0], type = t[1], owner = t[2];
                touchedClasses.add(owner);
                switch (type) {
                    case "class":
                        if (access.equals("accessible")) classAccessible.add(owner);
                        else if (access.equals("extendable")) classExtendable.add(owner);
                        break;
                    case "field": {
                        String name = t.length>3? t[3] : "";
                        if (access.equals("accessible")) fieldAccessible.computeIfAbsent(owner,k->new HashSet<>()).add(name);
                        else if (access.equals("mutable")) fieldMutable.computeIfAbsent(owner,k->new HashSet<>()).add(name);
                        break; }
                    case "method": {
                        String name = t.length>3? t[3] : "";
                        if (access.equals("accessible")) methodAccessible.computeIfAbsent(owner,k->new HashSet<>()).add(name);
                        else if (access.equals("extendable")) methodExtendable.computeIfAbsent(owner,k->new HashSet<>()).add(name);
                        break; }
                }
            }
        }
    }

    public boolean isTarget(String internalName){ return touchedClasses.contains(internalName); }

    private static int makePublic(int a){ return (a & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }
    private static int removeFinal(int a){ return a & ~Opcodes.ACC_FINAL; }

    /** Returns rewritten bytes, or null if this class is not an AW target. */
    public byte[] apply(String internalName, byte[] bytes){
        if (!isTarget(internalName)) return null;
        final Set<String> fA = fieldAccessible.getOrDefault(internalName, Collections.emptySet());
        final Set<String> fM = fieldMutable.getOrDefault(internalName, Collections.emptySet());
        final Set<String> mA = methodAccessible.getOrDefault(internalName, Collections.emptySet());
        final Set<String> mE = methodExtendable.getOrDefault(internalName, Collections.emptySet());
        final boolean cA = classAccessible.contains(internalName);
        final boolean cE = classExtendable.contains(internalName);
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw){
            public void visit(int version,int access,String name,String sig,String supr,String[] itf){
                if (cA) access = makePublic(access);
                if (cE) access = removeFinal(access);
                super.visit(version, access, name, sig, supr, itf);
            }
            public FieldVisitor visitField(int access,String name,String desc,String sig,Object val){
                if (fA.contains(name)) access = makePublic(access);
                if (fM.contains(name)) access = removeFinal(access);
                return super.visitField(access, name, desc, sig, val);
            }
            public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if (mA.contains(name)) access = makePublic(access);
                if (mE.contains(name)) access = removeFinal(access);
                return super.visitMethod(access, name, desc, sig, ex);
            }
        }, 0);
        return cw.toByteArray();
    }
}
