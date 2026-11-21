package dev.starbridge.survivalqa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Dedicated diagnostic world only. Native generators burn coal without injecting FE. */
final class PowerProbe {
    private static final boolean ACTIVE = Files.isRegularFile(Path.of("power-diagnostics.fixture"));
    private static final List<Pair> pairs = new ArrayList<>();
    private static int started = -1;
    private static boolean finished;
    private record Pair(Direction neighbor, BlockPos generator, BlockPos assembler, String fuelEntry, int initial) {}

    static void tick(MinecraftServer server) {
        if (!ACTIVE || finished) return;
        var level=server.overworld();
        try {
            if(started<0) {
                started=server.getTickCount();
                var origin=level.getSharedSpawnPos().above(10);
                var generator=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse("libvulpes:tile.coalgenerator")).orElseThrow();
                var assembler=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse("advancedrocketry:rocketbuilder")).orElseThrow();
                for(Direction direction:Direction.values()) {
                    int index=direction.ordinal();
                    var source=origin.offset((index%3)*5,0,(index/3)*5);
                    var target=source.relative(direction);
                    level.setBlockAndUpdate(source,generator.defaultBlockState());
                    level.setBlockAndUpdate(target,assembler.defaultBlockState());
                    int baseline=stored(level.getBlockEntity(target),direction.getOpposite());
                    String accepted=addCoal(level.getBlockEntity(source));
                    pairs.add(new Pair(direction,source,target,accepted,baseline));
                }
                return;
            }
            if(server.getTickCount()-started<220) return;
            List<Object> samples=new ArrayList<>();
            for(var pair:pairs) {
                var target=level.getBlockEntity(pair.assembler());
                var source=level.getBlockEntity(pair.generator());
                int charged=stored(target,pair.neighbor().getOpposite());
                samples.add(Map.of("assemblerFromGenerator",pair.neighbor().getName(),"fuelEntry",pair.fuelEntry(),"initialAssemblerFE",pair.initial(),"finalAssemblerFE",charged,"finalGeneratorFE",stored(source,pair.neighbor()),"nativeAssembler",nativeClass(target),"nativeGenerator",nativeClass(source)));
            }
            Expedition.save("generator-power-probe.json",Map.of("elapsedTicks",server.getTickCount()-started,"samples",samples));
            finished=true;
        } catch(RuntimeException e) {
            Expedition.save("generator-power-probe.json",Map.of("failure",e.toString()));
            finished=true;
        }
    }

    static String nativeClass(BlockEntity tile) {
        try { return tile.getClass().getMethod("legacyTile").invoke(tile).getClass().getName(); }
        catch(ReflectiveOperationException e) { return tile==null?"missing":tile.getClass().getName(); }
    }
    static int stored(BlockEntity tile,Direction face) {
        return tile==null?-1:tile.getCapability(ForgeCapabilities.ENERGY,face).map(net.minecraftforge.energy.IEnergyStorage::getEnergyStored).orElse(-1);
    }
    static String addCoal(BlockEntity tile) {
        if (tile==null) return "missing native tile";
        List<Direction> sides=new ArrayList<>(Arrays.asList(Direction.values()));
        sides.add(0,null);
        for(var side:sides) {
            var cap=tile.getCapability(ForgeCapabilities.ITEM_HANDLER,side).resolve();
            if(cap.isEmpty()) continue;
            var handler=cap.orElseThrow();
            for(int slot=0;slot<handler.getSlots();slot++) if(handler.insertItem(slot,new ItemStack(Items.COAL),false).isEmpty()) return "Forge item handler";
        }
        try {
            Object original=tile.getClass().getMethod("legacyTile").invoke(tile);
            Object coal=Class.forName("retrofit.forge.world.LegacyStacks").getMethod("toLegacy",ItemStack.class).invoke(null,new ItemStack(Items.COAL));
            for (var method:original.getClass().getMethods()) {
                if ((method.getName().equals("setInventorySlotContents") || method.getName().equals("func_70299_a")) && method.getParameterCount()==2 && method.getParameterTypes()[0]==int.class && method.getParameterTypes()[1].isInstance(coal)) {
                    method.invoke(original,0,coal);
                    return "original inventory slot 0";
                }
            }
            return "original fuel slot setter missing";
        } catch(ReflectiveOperationException e) { return "original fuel slot failed: "+e.toString(); }
    }
    private PowerProbe() {}
}
