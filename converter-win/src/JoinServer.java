import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/** Test helper: attach after LiquidBounce initialization and join a server on Minecraft's main thread. */
public final class JoinServer {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        String server = args == null || args.isBlank() ? "test.ccbluex.net" : args.trim();
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", false, sys);
        Object minecraft = mcClass.getMethod("getInstance").invoke(null);
        Runnable join = () -> {
            try {
                Class<?> screenClass = Class.forName("net.minecraft.client.gui.screens.Screen", false, sys);
                Class<?> titleClass = Class.forName("net.minecraft.client.gui.screens.TitleScreen", false, sys);
                Object parent = titleClass.getConstructor().newInstance();
                Class<?> addressClass = Class.forName("net.minecraft.client.multiplayer.resolver.ServerAddress", false, sys);
                Object address = addressClass.getMethod("parseString", String.class).invoke(null, server);
                Class<?> dataClass = Class.forName("net.minecraft.client.multiplayer.ServerData", false, sys);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends Enum> typeClass = (Class<? extends Enum>) Class.forName("net.minecraft.client.multiplayer.ServerData$Type", false, sys);
                @SuppressWarnings({"unchecked", "rawtypes"}) Object other = Enum.valueOf((Class)typeClass, "OTHER");
                Object data = dataClass.getConstructor(String.class, String.class, typeClass).newInstance("Codex Test", server, other);
                Class<?> transferClass = Class.forName("net.minecraft.client.multiplayer.TransferState", false, sys);
                Class<?> connectClass = Class.forName("net.minecraft.client.gui.screens.ConnectScreen", false, sys);
                Method start = connectClass.getMethod("startConnecting", screenClass, mcClass, addressClass, dataClass, boolean.class, transferClass);
                start.invoke(null, parent, minecraft, address, data, false, null);
                System.out.println("[JOINSERVER] startConnecting(" + server + ") invoked on main thread");
            } catch (Throwable t) {
                while (t.getCause() != null) t = t.getCause();
                System.out.println("[JOINSERVER] FAILED -> " + t);
                t.printStackTrace(System.out);
            }
        };
        mcClass.getMethod("execute", Runnable.class).invoke(minecraft, join);
        System.out.println("[JOINSERVER] scheduled " + server);
    }
}
