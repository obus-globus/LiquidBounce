import java.lang.instrument.Instrumentation;

/** Test helper: open the local player's inventory on Minecraft's main thread. */
public final class OpenInventory {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        ClassLoader sys=ClassLoader.getSystemClassLoader();
        Class<?> mcClass=Class.forName("net.minecraft.client.Minecraft",false,sys);
        Object minecraft=mcClass.getMethod("getInstance").invoke(null);
        Runnable open=()->{try{
            Object player=mcClass.getField("player").get(minecraft);
            if(player==null)throw new IllegalStateException("No local player; join a world first");
            Class<?> playerClass=Class.forName("net.minecraft.world.entity.player.Player",false,sys);
            Class<?> screenClass=Class.forName("net.minecraft.client.gui.screens.Screen",false,sys);
            Class<?> inventoryClass=Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen",false,sys);
            Object screen=inventoryClass.getConstructor(playerClass).newInstance(player);
            mcClass.getMethod("setScreenAndShow",screenClass).invoke(minecraft,screen);
            System.out.println("[OPENINVENTORY] inventory screen opened");
        }catch(Throwable t){while(t.getCause()!=null)t=t.getCause();System.out.println("[OPENINVENTORY] FAILED -> "+t);t.printStackTrace(System.out);}};
        mcClass.getMethod("execute",Runnable.class).invoke(minecraft,open);
        System.out.println("[OPENINVENTORY] scheduled");
    }
}
