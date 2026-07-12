import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.RecordComponentNode;
import java.io.InputStream;
import java.nio.file.*;
import java.util.jar.JarFile;

/** Scan the mixin target list against the MC jar: which targets are records, and which of those carry
 *  RuntimeVisible(Type)Annotations on record components (the pinned bug-#22 JVM-crash trigger)? */
public class ScanTargets {
    public static void main(String[] a) throws Exception {
        JarFile jar = new JarFile(a[0]);
        for (String line : Files.readAllLines(Path.of(a[1]))) {
            if (line.isBlank()) continue;
            String internal = line.trim().replace('.', '/');
            var e = jar.getJarEntry(internal + ".class");
            if (e == null) { continue; }
            ClassNode cn = new ClassNode();
            try (InputStream in = jar.getInputStream(e)) { new ClassReader(in.readAllBytes()).accept(cn, 0); }
            if (cn.recordComponents == null || cn.recordComponents.isEmpty()) continue;
            boolean anno = false;
            StringBuilder which = new StringBuilder();
            for (RecordComponentNode rc : cn.recordComponents) {
                boolean h = (rc.visibleTypeAnnotations != null && !rc.visibleTypeAnnotations.isEmpty())
                         || (rc.visibleAnnotations != null && !rc.visibleAnnotations.isEmpty());
                if (h) { anno = true; which.append(rc.name).append(' '); }
            }
            System.out.println((anno ? "HAZARD " : "record ") + internal + (anno ? "  [components: " + which + "]" : ""));
        }
    }
}
