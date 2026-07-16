package vspike;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;

/** Minimal AccessWidener v1 applier (namespace-agnostic; expects targets already in the jar's namespace). Follows
 *  Fabric AW-v1 semantics: members are matched by name AND descriptor (so only the named overload/constructor is
 *  widened, not every same-named member); `extendable class` becomes public + non-final; an `accessible method` that
 *  was private also becomes final (preserve invokespecial/non-virtual dispatch), except constructors and statics; an
 *  `extendable method` that was private becomes protected + non-final. The header is validated. */
public class AccessWidener {
    private final Set<String> classAccessible = new HashSet<>();   // read reflectively by the agent to derive inaccessible types
    private final Set<String> classExtendable = new HashSet<>();
    // owner -> set of "name desc" (Fabric AW-v1 matches on name + descriptor, not name alone)
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
                if (!header) {   // first real line MUST be "accessWidener v1|v2 <namespace>"
                    if (t.length < 2 || !t[0].equals("accessWidener") || !(t[1].equals("v1") || t[1].equals("v2")))
                        throw new IOException("Invalid AccessWidener header: '" + line + "' (expected 'accessWidener v1|v2 <namespace>')");
                    header = true; continue;
                }
                if (t.length < 3) continue;
                String access = t[0], type = t[1], owner = t[2];
                // AW-v2 marks directives that propagate to dependents with a `transitive-` prefix. For this standalone,
                // local applier (we widen the class in place, not across a mod graph) transitivity is irrelevant, so
                // normalise `transitive-accessible` -> `accessible` etc. Without this the v2 line matches no branch below
                // and is SILENTLY dropped while the class is still marked touched, so the widen never happens and a
                // dependent mixin later fails with a confusing accessibility/LVT error.
                if (access.startsWith("transitive-")) access = access.substring("transitive-".length());
                touchedClasses.add(owner);
                switch (type) {
                    case "class":
                        if (access.equals("accessible")) classAccessible.add(owner);
                        else if (access.equals("extendable")) { classExtendable.add(owner); classAccessible.add(owner); } // extendable class ⇒ public
                        break;
                    case "field": {
                        if (t.length < 5) break; String key = t[3] + " " + t[4];
                        if (access.equals("accessible")) fieldAccessible.computeIfAbsent(owner,k->new HashSet<>()).add(key);
                        else if (access.equals("mutable")) fieldMutable.computeIfAbsent(owner,k->new HashSet<>()).add(key);
                        break; }
                    case "method": {
                        if (t.length < 5) break; String key = t[3] + " " + t[4];
                        if (access.equals("accessible")) methodAccessible.computeIfAbsent(owner,k->new HashSet<>()).add(key);
                        else if (access.equals("extendable")) methodExtendable.computeIfAbsent(owner,k->new HashSet<>()).add(key);
                        break; }
                }
            }
            if (!header) throw new IOException("Empty AccessWidener (missing 'accessWidener v1 <namespace>' header)");
        }
    }

    public boolean isTarget(String internalName){ return touchedClasses.contains(internalName); }

    private static int makePublic(int a){ return (a & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }
    private static int makeProtected(int a){ return (a & Opcodes.ACC_PUBLIC) != 0 ? a : (a & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED; }
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
                String key = name + " " + desc;
                if (fA.contains(key)) access = makePublic(access);
                if (fM.contains(key)) access = removeFinal(access);
                return super.visitField(access, name, desc, sig, val);
            }
            public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                String key = name + " " + desc;
                if (mA.contains(key)) { boolean wasPrivate=(access & Opcodes.ACC_PRIVATE)!=0; access = makePublic(access);
                    // A now-public formerly-private instance method must stay non-virtual (final) to preserve
                    // invokespecial dispatch; constructors (final is illegal) and statics are exempt.
                    if (wasPrivate && (access & Opcodes.ACC_STATIC)==0 && !name.equals("<init>")) access |= Opcodes.ACC_FINAL; }
                if (mE.contains(key)) access = removeFinal(makeProtected(access));
                return super.visitMethod(access, name, desc, sig, ex);
            }
        }, 0);
        return cw.toByteArray();
    }
}
