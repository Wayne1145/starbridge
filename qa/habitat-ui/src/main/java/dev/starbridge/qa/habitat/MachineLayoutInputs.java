package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.MachineAssemblyScreen;
import dev.starbridge.client.PropellantScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.compat.rocketry.OriginalMachineLayout.Role;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Follow both real machine layouts, a specific outlet's item variant, and the native projector recipe. */
final class MachineLayoutInputs {
    private static int step, next;
    private static final Map<String, Object> observed = new LinkedHashMap<>();

    static boolean finished(Minecraft game, int tick) throws Exception {
        if (!Files.isRegularFile(Path.of("starbridge-machine-layout.fixture")) || step == 15) return true;
        if (tick < next) return false;
        var s = game.screen;
        switch (step) {
            case 0, 10 -> { if (!(s instanceof PropellantScreen) || !HabitatClient.click(s,"slate.starbridge.propellant.assembly")) return false; }
            case 1, 11 -> {
                if (!(s instanceof MachineAssemblyScreen)) return false;
                var field = s.getClass().getDeclaredField("plan"); field.setAccessible(true);
                var plan = (OriginalMachineLayout)field.get(s);
                if (plan == null || plan.width()!=3 || plan.depth()!=2 || plan.height()!=2) throw new IllegalStateException("Missing original machine dimensions");
                int inputs = step == 1 ? 2 : 1, outputs = step == 1 ? 1 : 2;
                if (plan.cells().stream().filter(c->c.role()==Role.FLUID_IN && c.metadata()==2).count()!=inputs ||
                    plan.cells().stream().filter(c->c.role()==Role.FLUID_OUT && c.metadata()==3).count()!=outputs ||
                    plan.cells().stream().filter(c->c.role()==Role.CONTROLLER).count()!=1) throw new IllegalStateException("Wrong native controller or ports");
                String station = step == 1 ? "reactor" : "electrolyser";
                var cells = new ArrayList<Map<String,Object>>();
                for (var c:plan.cells()) cells.add(Map.of("x",c.x(),"layer",c.layer(),"z",c.z(),"role",c.role().name(),"item",c.item().toString(),"metadata",c.metadata()));
                observed.put(station,cells);
                HabitatClient.snap(game, "assembly-"+station+"-lower");
                if (step == 1) {
                    var outlet = plan.cells().stream().filter(c->c.role()==Role.FLUID_OUT).findFirst().orElseThrow();
                    boolean hit = false;
                    for (var e:s.children()) if(e instanceof Button b && b.getMessage().equals(outlet.stack().getHoverName())) {
                        int x=b.getX()+b.getWidth()/2,y=b.getY()+b.getHeight()/2;
                        if (s.mouseClicked(x,y,0)) {s.mouseReleased(x,y,0); hit=true;break;}
                    }
                    if (!hit) throw new IllegalStateException("Could not select the visible reactor outlet");
                    if (!HabitatClient.click(s,"slate.starbridge.machine.part_recipe")) throw new IllegalStateException("No recipe action");
                } else if(!HabitatClient.click(s,"slate.starbridge.machine.higher")) return false;
            }
            case 2, 6 -> {
                if (!(s instanceof FabricationScreen)) return false;
                var field=FabricationScreen.class.getDeclaredField("selection");field.setAccessible(true);
                var selection=field.get(s);var method=selection.getClass().getDeclaredMethod("item");method.setAccessible(true);
                var stack=(ItemStack)method.invoke(selection);var id=ForgeRegistries.ITEMS.getKey(stack.getItem());
                var pages=FabricationScreen.class.getDeclaredField("pages");pages.setAccessible(true);
                if (((java.util.List<?>)pages.get(s)).isEmpty() || (step==2 && (stack.getTag()==null || stack.getTag().getInt("LegacyMeta")!=3)) ||
                    (step==6 && !OriginalMachineLayout.PROJECTOR.equals(id))) throw new IllegalStateException("Wrong recipe for "+id);
                if (step == 6) HabitatClient.snap(game, "assembly-projector-recipe");
                if(!HabitatClient.click(s,"slate.starbridge.materials.back_plan"))return false;
            }
            case 3 -> { if (!HabitatClient.click(s,"slate.starbridge.machine.higher")) return false; }
            case 4 -> { HabitatClient.snap(game,"assembly-reactor-upper"); if (!HabitatClient.click(s,"slate.starbridge.machine.lower")) return false; }
            case 5 -> { if (!HabitatClient.click(s,"slate.starbridge.machine.projector_recipe")) return false; }
            case 7, 12 -> { if (step==12) HabitatClient.snap(game,"assembly-electrolyser-upper"); if (!HabitatClient.click(s,"slate.starbridge.machine.back")) return false; }
            case 8 -> { if (!HabitatClient.click(s,"slate.starbridge.propellant.electrolyser")) return false; }
            case 9 -> { if (!(s instanceof PropellantScreen)) return false; }
            case 13 -> { if (!HabitatClient.click(s,"slate.starbridge.propellant.reactor")) return false; }
            case 14 -> { observed.put("pass",true); Files.writeString(Path.of("machine-layout-result.json"),new Gson().toJson(observed)); }
        }
        step++;next=tick+12;return step==15;
    }
}
