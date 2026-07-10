package vspike;
import org.spongepowered.asm.service.IClassProvider;
import java.net.URL;

public class VSpikeClassProvider implements IClassProvider {
    private ClassLoader cl(){ ClassLoader c=Thread.currentThread().getContextClassLoader(); return c!=null?c:VSpikeClassProvider.class.getClassLoader(); }
    @Deprecated public URL[] getClassPath(){ return new URL[0]; }
    public Class<?> findClass(String name) throws ClassNotFoundException { return Class.forName(name, false, cl()); }
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, cl()); }
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, VSpikeClassProvider.class.getClassLoader()); }
}
