package net.ccbluex.liquidbounce.platform.vanilla;
import net.ccbluex.liquidbounce.platform.Platform;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/** Minimal vanilla (no mod loader) Platform for the agent PoC. */
public class VanillaPlatform implements Platform {
    private final Path gameDir = Paths.get(System.getProperty("vspike.gameDir", ".")).toAbsolutePath().normalize();

    public Path getGameDirectory(){ return gameDir; }
    public boolean isModLoaded(String id){ return id.equals("minecraft") || id.equals("liquidbounce"); }
    public boolean hideModsFromModList(Collection<String> ids){ return false; }
    public boolean restoreModsInModList(){ return false; }
    public boolean removeModAndDeleteJars(String id){ return false; }
    public CreativeModeTab buildCreativeTab(Component title, Supplier<ItemStack> icon, CreativeModeTab.DisplayItemsGenerator gen){ return null; }
    public boolean registerResourceReloadListeners(Map<String, ? extends PreparableReloadListener> listeners){ return false; }
}
