package dev.starbridge.content;

import dev.starbridge.Starbridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.ModList;

/** Keeps the 0.2 worlds and recipes available to existing saves without adding planets to new fusion worlds. */
public final class PrototypePack {
    public static final String ID="starbridge:prototype_worlds";
    private PrototypePack() {}
    public static void find(AddPackFindersEvent event) {
        if(event.getPackType()!=PackType.SERVER_DATA) return;
        var root=ModList.get().getModFileById(Starbridge.ID).getFile().findResource("resourcepacks","starbridge_prototype");
        event.addRepositorySource(consumer -> {
            var pack=Pack.readMetaAndCreate(ID,Component.translatable("pack.starbridge.prototype"),Boolean.getBoolean("starbridge.prototypeWorlds"),
                    name->new PathPackResources(name,root,true),PackType.SERVER_DATA,Pack.Position.BOTTOM,PackSource.BUILT_IN);
            if(pack!=null) consumer.accept(pack);
        });
    }
}
