package lbrt;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime reflection helper for LB's direct access to non-public members / inaccessible TYPES of ALREADY-LOADED MC
 *  classes (which the AccessWidener could not widen — retransform forbids modifier changes). Owner + parameter types
 *  are passed as class-name STRINGS and resolved via Class.forName (which performs no access check), so no inaccessible
 *  type ever appears as a constant/CHECKCAST in LB's verified bytecode. Handles cached, setAccessible. Static access
 *  passes target=null. */
public final class AwReflect {
    private static final ClassLoader SYS = ClassLoader.getSystemClassLoader();
    private static final Map<String,Class<?>> KC = new ConcurrentHashMap<>();
    private static final Map<String,Field> FC = new ConcurrentHashMap<>();
    private static final Map<String,Method> MC = new ConcurrentHashMap<>();
    private static final Map<String,Constructor<?>> CC = new ConcurrentHashMap<>();
    private static Class<?> cls(String internalOrDotted){ return KC.computeIfAbsent(internalOrDotted, k -> { try { return Class.forName(k.replace('/','.'), false, SYS); } catch (Throwable t){ throw re(t); } }); }
    private static Class<?>[] types(String[] pt){ Class<?>[] a = new Class<?>[pt.length]; for(int i=0;i<pt.length;i++) a[i]=prim(pt[i]); return a; }
    private static Class<?> prim(String d){ switch(d){ case "I": return int.class; case "J": return long.class; case "Z": return boolean.class; case "F": return float.class; case "D": return double.class; case "B": return byte.class; case "S": return short.class; case "C": return char.class; case "V": return void.class; default:
        if (d.startsWith("[")) { try { return Class.forName(d.replace('/','.'), false, SYS); } catch(Throwable t){ throw re(t); } }
        return cls(d); } }
    private static Field f(String o, String n){ return FC.computeIfAbsent(o+"#"+n, k -> { Class<?> c=cls(o); for(Class<?> t=c;t!=null;t=t.getSuperclass()){ try{ Field f=t.getDeclaredField(n); f.setAccessible(true); return f; }catch(NoSuchFieldException e){} } throw re(new NoSuchFieldException(o+"."+n)); }); }
    private static Method m(String o, String n, String[] pt, String key){ return MC.computeIfAbsent(key, k -> { Class<?>[] p=types(pt); Class<?> c=cls(o); for(Class<?> t=c;t!=null;t=t.getSuperclass()){ try{ Method m=t.getDeclaredMethod(n,p); m.setAccessible(true); return m; }catch(NoSuchMethodException e){} } throw re(new NoSuchMethodException(o+"."+n)); }); }
    private static Constructor<?> ct(String o, String[] pt, String key){ return CC.computeIfAbsent(key, k -> { try{ Constructor<?> c=cls(o).getDeclaredConstructor(types(pt)); c.setAccessible(true); return c; }catch(Throwable t){ throw re(t); } }); }
    // field GET (target null => static)
    public static Object gO(Object t, String o, String n){ try { return f(o,n).get(t); } catch(Throwable e){ throw re(e); } }
    public static int    gI(Object t, String o, String n){ try { return f(o,n).getInt(t); } catch(Throwable e){ throw re(e); } }
    public static long   gJ(Object t, String o, String n){ try { return f(o,n).getLong(t); } catch(Throwable e){ throw re(e); } }
    public static boolean gZ(Object t, String o, String n){ try { return f(o,n).getBoolean(t); } catch(Throwable e){ throw re(e); } }
    public static float  gF(Object t, String o, String n){ try { return f(o,n).getFloat(t); } catch(Throwable e){ throw re(e); } }
    public static double gD(Object t, String o, String n){ try { return f(o,n).getDouble(t); } catch(Throwable e){ throw re(e); } }
    public static byte   gB(Object t, String o, String n){ try { return f(o,n).getByte(t); } catch(Throwable e){ throw re(e); } }
    public static short  gS(Object t, String o, String n){ try { return f(o,n).getShort(t); } catch(Throwable e){ throw re(e); } }
    public static char   gC(Object t, String o, String n){ try { return f(o,n).getChar(t); } catch(Throwable e){ throw re(e); } }
    // instance field SET (target first)
    public static void sO(Object t, Object v, String o, String n){ try { f(o,n).set(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sI(Object t, int v, String o, String n){ try { f(o,n).setInt(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sJ(Object t, long v, String o, String n){ try { f(o,n).setLong(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sZ(Object t, boolean v, String o, String n){ try { f(o,n).setBoolean(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sF(Object t, float v, String o, String n){ try { f(o,n).setFloat(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sD(Object t, double v, String o, String n){ try { f(o,n).setDouble(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sB(Object t, byte v, String o, String n){ try { f(o,n).setByte(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sS(Object t, short v, String o, String n){ try { f(o,n).setShort(t,v); } catch(Throwable e){ throw re(e); } }
    public static void sC(Object t, char v, String o, String n){ try { f(o,n).setChar(t,v); } catch(Throwable e){ throw re(e); } }
    // static field SET (value first)
    public static void ssO(Object v, String o, String n){ try { f(o,n).set(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssI(int v, String o, String n){ try { f(o,n).setInt(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssJ(long v, String o, String n){ try { f(o,n).setLong(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssZ(boolean v, String o, String n){ try { f(o,n).setBoolean(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssF(float v, String o, String n){ try { f(o,n).setFloat(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssD(double v, String o, String n){ try { f(o,n).setDouble(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssB(byte v, String o, String n){ try { f(o,n).setByte(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssS(short v, String o, String n){ try { f(o,n).setShort(null,v); } catch(Throwable e){ throw re(e); } }
    public static void ssC(char v, String o, String n){ try { f(o,n).setChar(null,v); } catch(Throwable e){ throw re(e); } }
    // method invoke (target null => static)
    public static Object inv(String o, String n, String[] pt, String key, Object t, Object[] a){ try { return m(o,n,pt,key).invoke(t,a); } catch(InvocationTargetException e){ throw re(e.getCause()==null?e:e.getCause()); } catch(Throwable e){ throw re(e); } }
    public static Object newInst(String o, String[] pt, String key, Object[] a){ try { return ct(o,pt,key).newInstance(a); } catch(InvocationTargetException e){ throw re(e.getCause()==null?e:e.getCause()); } catch(Throwable e){ throw re(e); } }
    /** Invoke a sidecar-cached Method with the same throwable semantics as bytecode invocation. Reflection wraps the
     *  target throwable in InvocationTargetException; preserving that wrapper breaks Minecraft's catch/control flow. */
    public static Object invokeResolved(Method method,Object target,Object[] args,String label){ try{return method.invoke(target,args);}
        catch(InvocationTargetException e){Throwable cause=e.getCause()==null?e:e.getCause();log(label,cause);throw sneaky(cause);}
        catch(Throwable e){log(label,e);throw sneaky(e);} }
    private static void log(String label,Throwable t){if(label==null||t.getClass().getName().equals("net.minecraft.server.RunningOnDifferentThreadException"))return;InjectionLogger.error("REFLECT-INVOKE-FAIL "+label,t);}
    @SuppressWarnings("unchecked") private static <T extends Throwable> RuntimeException sneaky(Throwable t)throws T{throw (T)t;}
    private static RuntimeException re(Throwable e){ return e instanceof RuntimeException r ? r : new RuntimeException(e); }
}
