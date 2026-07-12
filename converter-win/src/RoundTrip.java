import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline: round-trip a .class with the converter's exact flags; optionally CP-preserving. */
public class RoundTrip {
    public static void main(String[] a) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(a[0]));
        boolean keepCp = a.length > 2 && a[2].equals("keepcp");
        ClassReader cr = new ClassReader(in);
        ClassNode n = new ClassNode();
        cr.accept(n, ClassReader.SKIP_FRAMES);
        ClassWriter w = keepCp ? new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
                               : new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        n.accept(w);
        byte[] out = w.toByteArray();
        Files.write(Path.of(a[1]), out);
        System.out.println("in=" + in.length + " out=" + out.length + " identical=" + java.util.Arrays.equals(in, out) + " keepCp=" + keepCp);
    }
}
