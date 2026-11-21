package dev.starbridge.legacydock;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/** FML entry in Retrofit's 1.7.10 realm. The modern side remains a normal Forge 1.20.1 mod. */
@IFMLLoadingPlugin.Name("Starbridge orbital dock")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(1100)
@IFMLLoadingPlugin.TransformerExclusions({"dev.starbridge.legacydock"})
public final class DockPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return new String[]{"dev.starbridge.legacydock.DockTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public String getAccessTransformerClass() { return null; }
    public void injectData(Map<String,Object> data) { }
}
