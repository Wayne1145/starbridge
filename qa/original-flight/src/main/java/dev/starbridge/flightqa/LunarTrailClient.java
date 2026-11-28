package dev.starbridge.flightqa;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;

/** Both roles operate their own clients. The captain uses the default centre site on every fresh world. */
@Mod.EventBusSubscriber(modid="starbridge_flightqa",value=Dist.CLIENT)
public final class LunarTrailClient {
    private static int clock,next;
    private static String phase="", loadingScreen="";
    private static boolean cruise, instrument, closed, openedLocalCopy;
    private static final boolean LOCAL_COPY = Files.isRegularFile(Path.of("lunar-local-resume.fixture"));
    private static final LunarWalker WALKER = new LunarWalker();
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if(!LunarTrail.ENABLED || event.phase!=TickEvent.Phase.END) return;
        var mc=Minecraft.getInstance();
        if (LOCAL_COPY && Files.isRegularFile(Path.of("stop-local-lunar-test"))) { mc.stop(); return; }
        if (LOCAL_COPY && mc.player == null && mc.screen != null) {
            String title = mc.screen.getClass().getSimpleName() + " " + mc.screen.getTitle().getString();
            if (!title.equals(loadingScreen)) { FlightClient.log("Load saved lunar deck: " + title); loadingScreen = title; }
            if (mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen) {
                FlightClient.click(mc.screen,"selectWorld.backupJoinSkipButton");
                return;
            }
            if (mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen && mc.screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && ("selectWorld.warning.experimental.title".equals(t.getKey()) || "selectWorld.backupQuestion.experimental".equals(t.getKey()))) {
                if (!FlightClient.click(mc.screen,"gui.proceed")) FlightClient.click(mc.screen,"gui.yes");
                return;
            }
        }
        if (LOCAL_COPY && !openedLocalCopy && mc.player == null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                && mc.getOverlay() == null && Files.isRegularFile(Path.of("saves/LunarTrail/level.dat"))) {
            openedLocalCopy = true;
            mc.createWorldOpenFlows().loadLevel(mc.screen, "LunarTrail");
        }
        if(mc.player==null || mc.level==null) return;
        ++clock; var file=Path.of(LOCAL_COPY ? "lunar-trail-phase.txt" : "../server/lunar-trail-phase.txt"); if(!Files.isRegularFile(file))return;
        var command=Files.readString(file).trim(); if(command.isEmpty())return;
        if(!phase.equals(command)){phase=command;cruise=instrument=closed=false;next=0;FlightClient.log("Lunar traversal " + phase);}
        mc.setWindowActive(true);
        if(mc.player.getGameProfile().getName().equals("OrbitEngineer")) {
            if (phase.equals("walk")) {
                if (!LunarHands.tick(mc,clock)) WALKER.tick(mc,clock);
            }
            else if(mc.screen!=null && !mc.screen.getClass().getSimpleName().equals("ReceivingLevelScreen"))mc.screen.onClose();
            return;
        }
        if((!phase.equals("outbound") && !phase.equals("home")) || clock<next)return;
        boolean returning=phase.equals("home"); boolean onMoon=mc.level.dimension().location().toString().equals(LunarTrail.MOON);
        if(onMoon!=returning) return;
        String page=mc.screen==null?"world":mc.screen.getClass().getSimpleName();
        if(page.equals("ChartScreen") && !closed){mc.screen.onClose();closed=true;next=clock+8;return;}
        if(page.equals("world") && !cruise){mc.player.getInventory().selected=0;mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);next=clock+25;closed=true;}
        else if(page.equals("ChartScreen")&&!cruise){if(FlightClient.click(mc.screen,"route.starbridge."+(returning?"home":"moon")) && FlightClient.click(mc.screen,"nav.starbridge.cruise")) {cruise=true;next=clock+20;}}
        else if(page.equals("OrbitScreen")){
            if(!instrument){if(FlightClient.click(mc.screen,"orbit.starbridge.landing")){instrument=true;next=clock+12;}}
            else if(FlightClient.click(mc.screen,"orbit.starbridge.touchdown")){next=clock+120;}
        }
    }
}
