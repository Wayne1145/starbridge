package dev.starbridge.qa.habitat;

import dev.starbridge.client.ChartScreen;
import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.client.shipyard.ShipyardScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;

final class SupplyInputs {
    private static int step, next, machineSlot;
    private SupplyInputs() {}
    static boolean tick(Minecraft game, int ticks) throws Exception {
        if (step == 16) return true;
        if (ticks < next) return false;
        var screen = game.screen;
        var player = game.player;
        switch (step) {
            case 0 -> { if (!(screen instanceof ChartScreen) || !HabitatClient.click(screen, "slate.starbridge.guide.title")) return false; }
            case 1 -> { if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen, "slate.starbridge.guide.supplies")) return false; }
            case 2 -> {
                HabitatClient.snap(game, "00-supply-generator");
                if (!HabitatClient.click(screen,"slate.starbridge.guide.generator_recipe")) return false;
            }
            case 3 -> {
                if (!(screen instanceof FabricationScreen)) return false;
                HabitatClient.snap(game,"00-generator-recipe");
                if (!HabitatClient.click(screen,"slate.starbridge.materials.back_plan")) return false;
            }
            case 4 -> { if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen,"slate.starbridge.supply.propellant")) return false; }
            case 5 -> { HabitatClient.snap(game,"00-supply-propellant"); if (!HabitatClient.click(screen,"slate.starbridge.supply.helm")) return false; }
            case 6 -> { HabitatClient.snap(game,"00-supply-helm"); if (!HabitatClient.click(screen,"gui.done")) return false; }
            case 7 -> { if (!(screen instanceof ChartScreen)) return false; screen.onClose(); player.getInventory().selected = 0; }
            case 8 -> { if (screen != null) return false; use(game, SupplyWitness.GENERATOR); }
            case 9 -> {
                var menu = player.containerMenu;
                if (!menu.getClass().getName().equals("retrofit.forge.world.LegacyMenu")) return false;
                var from = menu.slots.stream().filter(s -> s.container == player.getInventory() && s.getItem().is(Items.COAL)).findFirst().orElseThrow();
                var machine = menu.slots.stream().filter(s -> s.container != player.getInventory()).toList();
                if (machine.size() != 1) throw new IllegalStateException("Expected one fuel slot, found " + machine.size());
                machineSlot = machine.get(0).index;
                game.gameMode.handleInventoryMouseClick(menu.containerId, from.index, 0, ClickType.PICKUP, player);
            }
            case 10 -> {
                if (!player.containerMenu.getCarried().is(Items.COAL)) return false;
                game.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, machineSlot, 0, ClickType.PICKUP, player);
            }
            case 11 -> {
                if (!Files.isRegularFile(Path.of("assembler-powered.json"))) return false;
                HabitatClient.snap(game,"00-original-generator"); player.closeContainer();
            }
            case 12 -> { if (screen != null) return false; use(game, SupplyWitness.ASSEMBLER); }
            case 13 -> { if (!(screen instanceof ShipyardScreen)) return false; HabitatClient.snap(game,"00-powered-assembler"); screen.onClose(); player.getInventory().selected = 4; }
            case 14 -> { if (screen != null || !player.getMainHandItem().is(Items.LAVA_BUCKET)) return false; use(game, SupplyWitness.HELM); }
            case 15 -> {
                if (!(screen instanceof ChartScreen) || !Files.isRegularFile(Path.of("supply-server.json"))) return false;
                Files.writeString(Path.of("supply-client.json"),"{\"pass\":true,\"originalGeneratorGui\":true,\"observedChargedAssembler\":true,\"emptiedLavaBucketOnHelm\":true,\"viewedAllThreeSupplies\":true}");
                player.getInventory().selected = 6;
            }
            default -> throw new IllegalStateException();
        }
        step++; next = ticks + 12;return step == 16;
    }
    private static void use(Minecraft game, BlockPos position) {
        var point = Vec3.atCenterOf(position).add(0,0,.5);
        game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, point);
        game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, new BlockHitResult(point, Direction.SOUTH, position, false));
    }
}
