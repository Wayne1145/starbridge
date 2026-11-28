package dev.starbridge.qa.wide;

import com.google.gson.Gson;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgewideqa", value = Dist.CLIENT)
public final class Engineer {
    private static final boolean ENABLED =
            Files.exists(Path.of("integrated-lan-guest.fixture"))
                    && Files.exists(Path.of("wide-fleet-guest.fixture"));

    private enum Step {
        APPROACH_BARREL,
        TAKE_ORE,
        CLOSE_BARREL,
        DOWNSTAIRS,
        OPEN_FURNACE,
        INPUT,
        SMELT,
        COLLECT,
        UPSTAIRS,
        RETURN_BARREL,
        DEPOSIT,
        VERIFY,
        DONE
    }

    private static Step step = Step.APPROACH_BARREL;
    private static int clock, started, after;
    private static BlockPos bridge;
    private static final List<Object> actions = new ArrayList<>();

    static BlockPos bridge(Path start) throws Exception {
        String[] parts = Files.readString(start).trim().split(" +");
        return new BlockPos(
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private static final Gson JSON = new Gson();

    private static void record(Minecraft mc, String action) throws Exception {
        var row =
                Map.of(
                        "tick",
                        clock,
                        "action",
                        action,
                        "position",
                        List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                        "screen",
                        mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        actions.add(row);
        Files.writeString(
                Path.of("wide-engineer-log.jsonl"),
                JSON.toJson(row) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void go(Minecraft mc, Step next, String text) throws Exception {
        record(mc, text);
        step = next;
        after = clock + 3;
    }

    private static void use(Minecraft mc, BlockPos pos) {
        var face = Direction.WEST;
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(-.5, 0, 0), face, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    private static void shift(Minecraft mc, AbstractContainerMenu menu, int index) {
        mc.gameMode.handleInventoryMouseClick(
                menu.containerId, index, 0, ClickType.QUICK_MOVE, mc.player);
    }

    private static int find(AbstractContainerMenu menu, int start, Item item) {
        for (int i = start; i < menu.slots.size(); i++)
            if (menu.getSlot(i).getItem().is(item)) return i;
        return -1;
    }

    @SubscribeEvent
    public static void client(TickEvent.ClientTickEvent e) throws Exception {
        if (!ENABLED || e.phase != TickEvent.Phase.END || step == Step.DONE) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        ++clock;
        if (bridge == null) {
            Path phase = Path.of("..", "lan-phase.json"),
                    start = Path.of("..", "fleet-flight-start");
            if (!Files.exists(start)
                    || !Files.exists(phase)
                    || !Files.readString(phase).contains("outbound-space")) return;
            bridge = bridge(start);
            started = clock;
            record(mc, "outbound cruise began on upper deck");
        }
        if (clock - started > 1100)
            throw new IllegalStateException(
                    "Wide-hull job stopped at "
                            + step
                            + ", player="
                            + mc.player.position()
                            + ", GUI="
                            + mc.screen);
        if (clock < after) return;
        var upper = bridge.offset(3, 0, 2);
        var lower = bridge.offset(3, -3, 7);
        var menu = mc.player.containerMenu;
        switch (step) {
            case APPROACH_BARREL -> {
                if (mc.screen == null) {
                    use(mc, upper);
                    go(mc, Step.TAKE_ORE, "used upper cargo barrel");
                }
            }
            case TAKE_ORE -> {
                if (mc.screen instanceof ContainerScreen
                        && menu.getSlot(0).getItem().is(Items.RAW_COPPER)) {
                    shift(mc, menu, 0);
                    go(mc, Step.CLOSE_BARREL, "took raw copper using the barrel screen");
                }
            }
            case CLOSE_BARREL -> {
                if (find(menu, 27, Items.RAW_COPPER) >= 0) {
                    mc.player.closeContainer();
                    go(mc, Step.DOWNSTAIRS, "closed barrel and started walking downstairs");
                }
            }
            case DOWNSTAIRS -> {
                mc.player.setYRot(0);
                mc.options.keyUp.setDown(true);
                if (mc.player.getZ() >= lower.getZ() + .15
                        && mc.player.getY() < bridge.getY() - 2.4) {
                    mc.options.keyUp.setDown(false);
                    go(mc, Step.OPEN_FURNACE, "walked down three stair treads");
                }
            }
            case OPEN_FURNACE -> {
                use(mc, lower);
                go(mc, Step.INPUT, "used lower blast furnace");
            }
            case INPUT -> {
                if (mc.screen instanceof BlastFurnaceScreen) {
                    int slot = find(menu, 3, Items.RAW_COPPER);
                    if (slot >= 0) {
                        shift(mc, menu, slot);
                        go(mc, Step.SMELT, "loaded carried ore into blast furnace");
                    }
                }
            }
            case SMELT -> {
                if (mc.screen instanceof BlastFurnaceScreen
                        && menu.getSlot(2).getItem().is(Items.COPPER_INGOT)) {
                    shift(mc, menu, 2);
                    go(mc, Step.COLLECT, "collected freshly smelted copper from the output slot");
                }
            }
            case COLLECT -> {
                if (find(menu, 3, Items.COPPER_INGOT) >= 0) {
                    mc.player.closeContainer();
                    go(mc, Step.UPSTAIRS, "closed furnace and started walking upstairs");
                }
            }
            case UPSTAIRS -> {
                mc.player.setYRot(180);
                mc.options.keyUp.setDown(true);
                if (mc.player.getZ() <= upper.getZ() + .70
                        && mc.player.getY() >= bridge.getY() - .1) {
                    mc.options.keyUp.setDown(false);
                    go(mc, Step.RETURN_BARREL, "walked back up the stairs");
                }
            }
            case RETURN_BARREL -> {
                use(mc, upper);
                go(mc, Step.DEPOSIT, "reopened the upper barrel");
            }
            case DEPOSIT -> {
                if (mc.screen instanceof ContainerScreen) {
                    int slot = find(menu, 27, Items.COPPER_INGOT);
                    if (slot >= 0) {
                        shift(mc, menu, slot);
                        go(mc, Step.VERIFY, "stowed the smelted ingot on the upper deck");
                    }
                }
            }
            case VERIFY -> {
                if (menu.getSlot(0).getItem().is(Items.COPPER_INGOT)
                        && find(menu, 27, Items.COPPER_INGOT) < 0) {
                    mc.player.closeContainer();
                    go(mc, Step.DONE, "checked barrel contents after deposit");
                    Files.writeString(Path.of("wide-engineer-complete.json"), JSON.toJson(actions));
                }
            }
            default -> {}
        }
    }
}
