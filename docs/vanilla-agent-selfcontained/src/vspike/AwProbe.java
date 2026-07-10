package vspike;
import java.lang.reflect.Field;
/** Proves AccessWidener widened a field to PUBLIC. Class.getField() only finds
 *  PUBLIC members, so success here means AW ran; NoSuchFieldException means it didn't. */
public class AwProbe {
    public static Object readUserField(Object mc) throws Exception {
        Field f = mc.getClass().getField("user");  // public-only lookup
        return f.get(mc);
    }
}
