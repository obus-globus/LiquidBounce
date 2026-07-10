package vspike;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.io.IOException;
import java.io.InputStream;

public class VSpikeBytecodeProvider implements IClassBytecodeProvider {
    /**
     * The same AccessWidener the transformer applies. Mixin builds its ClassInfo metadata
     * from the nodes returned here; if we hand it the ORIGINAL (un-widened) class, methods
     * the AW makes accessible (e.g. a private method widened to public) won't match what the
     * transformed class exposes, and ClassInfo.findMethod returns null -> LVTGeneratorError
     * ("Could not locate method metadata"). Applying the AW here keeps metadata in sync.
     */
    static AccessWidener AW;

    private ClassLoader cl(){ ClassLoader c=Thread.currentThread().getContextClassLoader(); return c!=null?c:VSpikeBytecodeProvider.class.getClassLoader(); }
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, true, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
        String internal = name.replace('.', '/');
        String path = internal + ".class";
        try (InputStream in = cl().getResourceAsStream(path)) {
            if (in == null) throw new ClassNotFoundException(name);
            byte[] raw = in.readAllBytes();
            if (AW != null) { byte[] w = AW.apply(internal, raw); if (w != null) raw = w; }
            ClassReader cr = new ClassReader(raw);
            ClassNode cn = new ClassNode();
            cr.accept(cn, flags);
            return cn;
        }
    }
}
