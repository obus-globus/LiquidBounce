package vspike;
public class Target {
    public String greet(){ return "ORIGINAL"; }
    public static void main(String[] a){
        String r = new Target().greet();
        System.out.println("[VSPIKE] Target.greet() returned: " + r);
        System.out.println(r.equals("MIXIN-APPLIED") ? "[VSPIKE] RESULT: MIXIN APPLIED ✓" : "[VSPIKE] RESULT: mixin NOT applied ✗");
    }
}
