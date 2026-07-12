import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

/**
 * Bug #22 minimal-trigger isolation: which class feature makes a CP-reordering retransform corrupt
 * HotSpot 25 field metadata?
 *
 *   java -javaagent:repro-agent.jar -Drepro.target=<binary name> -cp out;asm.jar Repro2 <simpleName>
 *
 * Candidates (all defined below, in this file):
 *   Repro2$RecPlain  - record, no annotations
 *   Repro2$RecAnno   - record with a RUNTIME TYPE_USE annotation on a component (like GuiMessage's @Nullable)
 *   Repro2$ClsAnno   - plain class with a RUNTIME TYPE_USE annotated field
 *   Repro2$RecField  - record with an extra static field with ConstantValue (like MESSAGE_TAG_MARGIN_LEFT)
 */
public class Repro2 {
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE}) public @interface TA {}

    public record RecPlain(int a, String b) {}
    public record RecAnno(int a, @TA String b) {}
    public static class ClsAnno { public int a; public @TA String b; }
    public record RecField(int a, String b) { static final int MARGIN = 4; }

    public static void main(String[] args) throws Exception {
        String simple = args[0];
        ReproAgent.MODE = args.length > 1 ? args[1] : "none";
        Class<?> c = Class.forName("Repro2$" + simple, false, Repro2.class.getClassLoader());
        System.out.println("[R2] loaded " + c.getName() + " on " + System.getProperty("java.vm.version"));
        ReproAgent.INST.retransformClasses(c);
        System.out.println("[R2] retransform OK");
        for (int i = 0; i < 100; i++) {
            Field[] fs = c.getDeclaredFields();
            if (i == 0) { StringBuilder s = new StringBuilder(); for (Field f : fs) s.append(f.getName()).append(' '); System.out.println("[R2] fields: " + s); }
            if (c.isRecord()) c.getRecordComponents();
            for (Field f : fs) { f.getAnnotations(); f.getAnnotatedType(); }
            if (i % 10 == 0) System.gc();
        }
        System.out.println("[R2] SURVIVED " + simple);
    }
}
