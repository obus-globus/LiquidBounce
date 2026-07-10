import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
/** Schema-neutral targets for a converted mixin:
 *  - onTick   = the converted @Inject HANDLER method (was a member added to Minecraft -> now an external static)
 *  - STATE    = the converted @Unique FIELD (was a field added to Minecraft -> now external per-instance storage) */
public class LBHooks {
    static final Map<Object, long[]> STATE = Collections.synchronizedMap(new IdentityHashMap<>());
    public static void onTick(Object self) {
        long[] s = STATE.computeIfAbsent(self, k -> new long[1]);   // external "@Unique field" for `self`
        s[0]++;
        if (s[0] % 100 == 0)
            System.out.println("[CONVERT][LIVE] LBHooks.onTick fired " + s[0] + "x on "
                + self.getClass().getName() + "@" + System.identityHashCode(self)
                + " — external-field state=" + s[0] + " (schema-changing mixin retro-applied to ALREADY-LOADED class)");
    }
}
