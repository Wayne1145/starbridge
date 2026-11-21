package dev.starbridge.mixin;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class OriginalWorldMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith("Vs2411WatchHandoffMixin")) {
            var mods = FMLLoader.getLoadingModList();
            return mods != null && mods.getMods().stream().anyMatch(mod ->
                    mod.getModId().equals("valkyrienskies") && mod.getVersion().toString().equals("2.4.11"));
        }
        if (!mixin.endsWith("OriginalChunkSeedMixin") && !mixin.endsWith("OriginalGeneratorSeedMixin")
                && !mixin.endsWith("OriginalDeferredTileMixin") && !mixin.endsWith("OriginalMoonTickerMixin")) return true;
        var mods = FMLLoader.getLoadingModList();
        if (mods == null) return false;
        if ((mixin.endsWith("OriginalChunkSeedMixin") || mixin.endsWith("OriginalGeneratorSeedMixin")) && mods.getModFileById("starbridgelunarseedprobe") != null) return false;
        return mods.getMods().stream()
                .anyMatch(mod -> mod.getModId().equals("retrofit") && mod.getVersion().toString().equals("0.6.2-beta"));
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
