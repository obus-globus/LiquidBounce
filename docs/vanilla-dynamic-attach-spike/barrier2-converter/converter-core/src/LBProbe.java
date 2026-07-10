package vspike;
public class LBProbe {
    static volatile boolean w, s, l;
    public static java.util.function.Supplier<Object> op; // unused
    public static Object wrap(Object v){ if(!w){w=true; System.out.println("[CONVAUTO][LIVE] @WrapOperation handler fired -> synthetic Operation resolved on already-loaded class");} return v; }
    public static void shadow(String win){ if(!s){s=true; System.out.println("[CONVAUTO][LIVE] @Shadow-of-private-field read OK (window="+win+") -> AccessWidener flip on already-loaded target worked");} }
    public static void local(float f){ if(!l){l=true; System.out.println("[CONVAUTO][LIVE] @Local(argsOnly) captured param="+f+" -> passed through to relocated handler");} }
}
