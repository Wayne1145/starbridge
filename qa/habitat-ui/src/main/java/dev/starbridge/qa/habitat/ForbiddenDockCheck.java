package dev.starbridge.qa.habitat;

import com.google.gson.GsonBuilder;
import dev.starbridge.compat.rocketry.ShipyardWire.ForbiddenBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A real 300-block pad uses the original scan and server-configured fuel tank and chest exclusions. */
@Mod.EventBusSubscriber(modid = "starbridgehabitatqa")
public final class ForbiddenDockCheck {
    private static final Path MARKER = Path.of("starbridge-forbidden-dock.fixture");
    private static final BlockPos CONSOLE = new BlockPos(100, 191, 40);

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        var server = event.getServer();
        if (event.phase != TickEvent.Phase.END || !server.isDedicatedServer() || server.getTickCount() != 65 || !Files.isRegularFile(MARKER)) return;
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            check(server.overworld(), result);
            result.put("pass", true);
        } catch (Throwable failure) {
            while (failure instanceof java.lang.reflect.InvocationTargetException) failure = failure.getCause();
            result.put("pass", false);
            result.put("error", failure.toString());
            failure.printStackTrace();
        } finally {
            result.put("connectedPlayers", server.getPlayerCount());
            try { Files.writeString(Path.of("forbidden-dock-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { server.halt(false); }
        }
    }

    private static Block block(String name) {
        var id = ResourceLocation.parse("advancedrocketry:" + name);
        if (!ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalStateException("Missing " + id);
        return ForgeRegistries.BLOCKS.getValue(id);
    }

    private static void check(ServerLevel level, Map<String, Object> out) throws Exception {
        for (var p : BlockPos.betweenClosed(99,190,41,101,190,340)) level.setBlock(p, block("launchpad").defaultBlockState(), 2);
        for (int y = 190; y <= 196; y++) level.setBlock(new BlockPos(98,y,42), block("structuretower").defaultBlockState(), 2);
        Block builder = block("rocketbuilder");
        level.setBlock(CONSOLE, builder.defaultBlockState().setValue((IntegerProperty) builder.getStateDefinition().getProperty("meta"), 2), 3);
        level.setBlock(CONSOLE.above(), block("blockmonitoringstation").defaultBlockState(), 3);
        var entity = level.getBlockEntity(CONSOLE);
        Object nativeTile = entity.getClass().getMethod("legacyTile").invoke(entity);
        Object world = Class.forName("retrofit.forge.world.LegacyContent").getMethod("legacyWorld", Level.class).invoke(null,level);
        Class<?> worldType = nativeTile.getClass().getClassLoader().loadClass("net.minecraft.world.World");
        var originalBox = nativeTile.getClass().getMethod("getRocketPadBounds", worldType, int.class, int.class, int.class)
                .invoke(nativeTile, world, CONSOLE.getX(),CONSOLE.getY(),CONSOLE.getZ());
        var hull = Class.forName("dev.starbridge.compat.rocketry.ShipyardHull");
        Method pad = hull.getDeclaredMethod("pad", Object.class); pad.setAccessible(true);
        Object box = pad.invoke(null, originalBox);
        Method min = box.getClass().getDeclaredMethod("min"), max = box.getClass().getDeclaredMethod("max");
        min.setAccessible(true); max.setAccessible(true);
        BlockPos low = (BlockPos) min.invoke(box), high = (BlockPos) max.invoke(box);
        if (high.getZ() - low.getZ() + 1 != 300) throw new IllegalStateException("Wrong native bounds " + high + "/" + low);
        var chest = low.offset(1,3,285); var tank = low.offset(2,4,297); var barrel = low.offset(0,0,6);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
        level.setBlock(tank, block("fueltank").defaultBlockState(), 2);
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(barrel.south(), Blocks.FURNACE.defaultBlockState(), 2);
        level.setBlock(barrel.north(), Blocks.TRAPPED_CHEST.defaultBlockState(), 2);
        level.setBlock(low.offset(-1,3,285), Blocks.CHEST.defaultBlockState(), 2);
        ((BarrelBlockEntity) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND,7));
        var nativeScan = nativeTile.getClass().getMethod("scanRocket", worldType, int.class, int.class, int.class, originalBox.getClass());
        var getStatus = nativeTile.getClass().getMethod("getStatus");
        var lookup = Class.forName("dev.starbridge.compat.rocketry.ForbiddenHullBlocks");
        var constructor = lookup.getDeclaredConstructor(ServerLevel.class,box.getClass(),Object.class);
        constructor.setAccessible(true);
        Object locator = constructor.newInstance(level,box,nativeTile);
        var advance = lookup.getDeclaredMethod("advance",ServerLevel.class); advance.setAccessible(true);
        var done = lookup.getDeclaredMethod("done"); done.setAccessible(true);
        java.util.Set<BlockPos> remaining = new java.util.HashSet<>(List.of(chest,tank));
        var observations = new java.util.ArrayList<Object>();
        for (int i = 0; i < 2; i++) {
            nativeScan.invoke(nativeTile,world,CONSOLE.getX(),CONSOLE.getY(),CONSOLE.getZ(),originalBox);
            String status = ((Enum<?>) getStatus.invoke(nativeTile)).name();
            if (!status.equals("INVALIDBLOCK")) throw new IllegalStateException("Original assembler returned " + status);
            ForbiddenBlock found = null; int requests = 0;
            while (found == null && requests < 30) { requests++; found = (ForbiddenBlock) advance.invoke(locator,level); }
            if (found == null || !remaining.remove(found.position()) || !found.block().equals(ForgeRegistries.BLOCKS.getKey(level.getBlockState(found.position()).getBlock())))
                throw new IllegalStateException("Wrong forbidden cell: " + found);
            int dx = found.position().getX() - low.getX(), dz = found.position().getZ() - low.getZ(), layer = found.position().getY() - low.getY();
            int stride = 8, sampleX = dx / stride * stride, sampleZ = dz / stride * stride;
            if (found.position().equals(low.offset(sampleX,layer,sampleZ)) || !found.onTile(low,layer,sampleX,sampleZ,stride)
                    || found.onTile(low,layer+1,sampleX,sampleZ,stride) || found.onTile(low,layer,sampleX,sampleZ+stride,stride))
                throw new IllegalStateException("Wrong coarse tile or deck");
            observations.add(Map.of("block", found.block().toString(), "status", status, "position", List.of(found.position().getX(),found.position().getY(),found.position().getZ()),
                    "layer", layer+1, "stride", stride, "sampledCorner", List.of(low.getX()+sampleX,low.getY()+layer,low.getZ()+sampleZ), "refreshes", requests));
            level.removeBlock(found.position(),false);
        }
        out.put("markers", observations);
        int retries = 0;
        do { if (advance.invoke(locator,level) != null) throw new IllegalStateException("Marked an allowed or outside block"); }
        while (!(boolean) done.invoke(locator) && ++retries < 30);
        if (!(boolean) done.invoke(locator)) throw new IllegalStateException("Search did not finish");
        nativeScan.invoke(nativeTile,world,CONSOLE.getX(),CONSOLE.getY(),CONSOLE.getZ(),originalBox);
        String finalStatus = ((Enum<?>) getStatus.invoke(nativeTile)).name();
        if (!finalStatus.equals("NOGUIDANCE")) throw new IllegalStateException("Original status after removing banned blocks: " + finalStatus);
        if (!level.getBlockState(barrel.north()).is(Blocks.TRAPPED_CHEST) || !level.getBlockState(barrel.south()).is(Blocks.FURNACE)
                || !level.getBlockState(low.offset(-1,3,285)).is(Blocks.CHEST) || !((BarrelBlockEntity) level.getBlockEntity(barrel)).getItem(0).is(Items.DIAMOND)
                || ((BarrelBlockEntity) level.getBlockEntity(barrel)).getItem(0).getCount() != 7) throw new IllegalStateException("Non-excluded freight changed");
        out.put("widthHeightLength",List.of(high.getX()-low.getX()+1,high.getY()-low.getY()+1,high.getZ()-low.getZ()+1));
        out.put("finalNativeStatus",finalStatus);out.put("barrelDiamonds",7);out.put("outsideExcludedChestIntact",true);
        out.put("allowedBlocks",List.of("minecraft:barrel","minecraft:furnace","minecraft:trapped_chest"));
    }
}
