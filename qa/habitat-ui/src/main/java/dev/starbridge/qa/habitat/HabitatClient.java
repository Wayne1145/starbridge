package dev.starbridge.qa.habitat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.client.ChartScreen;
import dev.starbridge.client.PilotScreen;
import dev.starbridge.client.VentScreen;
import dev.starbridge.voyage.VentMenu;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.Arrays;

@Mod.EventBusSubscriber(modid="starbridgehabitatqa", value=Dist.CLIENT)
public final class HabitatClient {
    private static int ticks, step, last, leakFrames;
    private static boolean started,lan;
    private static String screen="";
    static void log(String event) throws Exception {Files.writeString(Path.of("habitat-actions.log"),event+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    static boolean click(Screen screen,String key) throws Exception {
        for (var element : screen.children()) if(element instanceof Button b && b.active && b.getMessage().getContents() instanceof TranslatableContents t && key.equals(t.getKey())) {
            int x=b.getX()+b.getWidth()/2,y=b.getY()+b.getHeight()/2;
            screen.mouseClicked(x,y,0);screen.mouseReleased(x,y,0);log("Clicked "+key);return true;
        }
        return false;
    }
    static void snap(Minecraft mc,String name) {Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),m->{});}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if(event.phase != TickEvent.Phase.END) return;
        boolean host=Files.isRegularFile(Path.of("starbridge-habitat.host"));
        if(!host && !Files.isRegularFile(Path.of("starbridge-habitat.guest"))) return;
        Minecraft mc=Minecraft.getInstance();
        if (Files.isRegularFile(Path.of("habitat-stop"))) { mc.stop(); return; }
        mc.setWindowActive(true);ticks++;
        String now=mc.screen==null?"world":mc.screen.getClass().getSimpleName();if(!now.equals(screen)){log(now);screen=now;}
        if(!host) {
            if(mc.player != null) {if(!Files.isRegularFile(Path.of("lan-guest-joined")) && mc.level!=null) Files.writeString(Path.of("lan-guest-joined"),mc.level.dimension().location().toString());return;}
            if((mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen) && mc.getOverlay()==null && ticks-last>100) {
                last=ticks;String address="127.0.0.1:"+Files.readString(Path.of("habitat-port")).trim();
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("Habitat LAN",address,true),false);
            }
            return;
        }
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true;
            var settings=new LevelSettings("Habitat",GameType.SURVIVAL,false,Difficulty.NORMAL,false,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("Habitat",settings,new WorldOptions(576618501L,true,false),WorldPresets::createNormalWorldDimensions);
            return;
        }
        if(mc.player==null || mc.level==null || ticks-last<8)return;
        if (Files.isRegularFile(RockSampleCheck.MARKER)) { RockSampleCheck.tick(mc, ticks); return; }
        if (Files.isRegularFile(Path.of("starbridge-starter.fixture")) && !FirstCraft.tick(mc, ticks)) return;
        if(step==0 && mc.screen==null && mc.level.getBlockState(Habitat.VENT).hasBlockEntity() && mc.player.distanceToSqr(Vec3.atCenterOf(Habitat.VENT))<25) {
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(Habitat.VENT).add(.5,0,0),Direction.EAST,Habitat.VENT,false));last=ticks;
        } else if(mc.screen instanceof VentScreen panel && mc.player.containerMenu instanceof VentMenu vent) {
            if(step==0 && vent.sealed() && vent.canisters()==2 && vent.charge()==0) {snap(mc,"01-vent-sealed");if(click(panel,"vent.starbridge.fill")){step=1;last=ticks;}}
            if(step==1 && vent.canisters()==1 && vent.charge()==120 && !vent.sealed() && ++leakFrames >= 4) {snap(mc,"02-vent-leak");if(click(panel,"gui.done")){Files.writeString(Path.of("qa-vent-inspected"),"120 person-seconds, one canister remaining, hatch open");step=2;last=ticks;}}
        } else if(step==2 && mc.screen instanceof dev.starbridge.client.FirstFlightScreen guide) {
            click(guide,"gui.done");last=ticks;
        } else if(step==2 && mc.screen==null && mc.player.getMainHandItem().is(dev.starbridge.voyage.VoyageModule.SLATE.get())) {
            mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);last=ticks;
        } else if(mc.screen instanceof ChartScreen chart && step==2) {
            if (!Files.isRegularFile(Path.of("starbridge-touchdown.fixture")) || Files.isRegularFile(Path.of("surface-live.json"))) {
                if(!click(chart,"nav.starbridge.surface"))click(chart,"nav.starbridge.systems");
            }
            last=ticks;
        } else if(mc.screen instanceof PilotScreen helm && step==2) {
            var binding=Arrays.stream(mc.options.keyMappings).filter(k->k.getName().equals("key.starbridge.surface.forward")).findFirst().orElseThrow();
            binding.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_I));KeyMapping.resetMapping();helm.keyPressed(GLFW.GLFW_KEY_I,0,0);last=ticks;step=3;log("Held rebound I at the near-surface helm");
        } else if(step==3 && ticks-last>=22 && mc.screen instanceof PilotScreen helm && Files.isRegularFile(Path.of("qa-manual-input"))) {
            snap(mc,"03-surface-helm");helm.keyReleased(GLFW.GLFW_KEY_I,0,0);
            if (Files.isRegularFile(Path.of("starbridge-touchdown.fixture"))) {
                helm.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
                log("Held the descent key until measured, stable surface contact");
                step=7; last=ticks;
            } else if(click(helm,"surface.starbridge.brake")){step=4;last=ticks;}
        } else if (step==7 && mc.screen instanceof PilotScreen helm && Files.isRegularFile(Path.of("touchdown-result.json"))) {
            snap(mc,"04-surface-contact");helm.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
            if(click(helm,"surface.starbridge.brake")){step=4;last=ticks;}
        } else if(step==4 && mc.screen instanceof PilotScreen helm && click(helm,"surface.starbridge.leave")) {
            Files.writeString(Path.of("qa-ui-finished"),"braked and left the helm");step=5;
        }
        if(step==5 && !lan && mc.getSingleplayerServer()!=null) {
            var server=mc.getSingleplayerServer();
            // Offline scripted clients connect only to the loopback listener supplied by the QA-only mixin.
            server.setUsesAuthentication(false);
            if(!server.publishServer(GameType.SURVIVAL,false,Integer.parseInt(Files.readString(Path.of("habitat-port")).trim()))) throw new IllegalStateException("Could not open the test LAN world");
            lan=true;Files.writeString(Path.of("lan-ready"),"published");
        }
    }
}
