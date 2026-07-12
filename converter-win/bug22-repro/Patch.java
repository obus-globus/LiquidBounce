import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.Files;
import java.nio.file.Path;

/** bug22 variant generator: strip class-level Signature / SourceDebugExtension from the converter's
 *  retransform bytes (or just round-trip vanilla through ASM) to isolate what corrupts the record on
 *  RedefineClasses. usage: java Patch <in.class> <out.class> <keep|nosig|nosde|nosig-nosde|copy>[+poolO=<orig.class>]
 *  +poolO: serialize with the ORIGINAL class's constant pool copied as an untouched prefix (ClassWriter
 *  copy-pool), so every CP index the loaded class already uses stays valid in the redefined bytes. */
public class Patch {
    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(args[0]));
        ClassNode c = new ClassNode();
        new ClassReader(in).accept(c, 0);
        String mode = args[2];
        if (mode.contains("nosig")) c.signature = null;
        if (mode.contains("nosde")) c.sourceDebug = null;
        ClassWriter w;
        int po = mode.indexOf("+poolO=");
        if (po >= 0) w = new ClassWriter(new ClassReader(Files.readAllBytes(Path.of(mode.substring(po + 7)))), 0);
        else w = new ClassWriter(0);
        c.accept(w);
        Files.write(Path.of(args[1]), w.toByteArray());
        System.out.println("[PATCH] " + args[1] + " (" + mode + ") sig=" + c.signature + " sde=" + (c.sourceDebug != null));
    }
}
