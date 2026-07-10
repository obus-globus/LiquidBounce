package vspike;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.io.IOException;
import java.io.InputStream;

public class VSpikeBytecodeProvider implements IClassBytecodeProvider {
    private ClassLoader cl(){ ClassLoader c=Thread.currentThread().getContextClassLoader(); return c!=null?c:VSpikeBytecodeProvider.class.getClassLoader(); }
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, true, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
    public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
        String path = name.replace('.', '/') + ".class";
        try (InputStream in = cl().getResourceAsStream(path)) {
            if (in == null) throw new ClassNotFoundException(name);
            ClassReader cr = new ClassReader(in);
            ClassNode cn = new ClassNode();
            cr.accept(cn, flags);
            return cn;
        }
    }
}
