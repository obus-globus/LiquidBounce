package vspike;
public class Launcher {
    public static void main(String[] a) throws Exception {
        // Target is loaded lazily HERE, after premain registered the transformer
        Class<?> tc = Class.forName("vspike.Target");
        Object t = tc.getDeclaredConstructor().newInstance();
        String r = (String) tc.getMethod("greet").invoke(t);
        System.out.println("[VSPIKE] Target.greet() returned: " + r);
        System.out.println(r.equals("MIXIN-APPLIED") ? "[VSPIKE] RESULT: MIXIN APPLIED ✓" : "[VSPIKE] RESULT: mixin NOT applied ✗");
    }
}
