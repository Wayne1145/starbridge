package dev.starbridge.qa.encounter;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "starbridgelunarqa", value = Dist.CLIENT)
public final class EncounterClient {
    static final EncounterSite SITE = EncounterSite.SELECTED;
    static int t, attempts;
    static int waitForJoin, uiTicks, guideStep;
    static String screenName = "";
    static double lastHp = -1;

    static void record(String msg) throws Exception {
        Files.writeString(
                Path.of("lunar-ui-actions.log"),
                msg + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || !Files.exists(Path.of("starbridge-lunar-encounter.fixture"))) return;
        var mc = Minecraft.getInstance();
        var p = mc.player;
        mc.setWindowActive(true);
        String current = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        if (!current.equals(screenName)) {
            record(
                    current
                            + " "
                            + (mc.screen == null
                                    ? ""
                                    : mc.screen.getNarrationMessage().getString()));
            screenName = current;
        }
        ++uiTicks;
        if (p == null) {
            if (mc.getOverlay() == null
                    && (mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen)
                    && uiTicks > waitForJoin + 65) {
                waitForJoin = uiTicks + 160;
                String addr = Files.readString(Path.of("lunar-server.txt")).trim();
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(addr),
                        new ServerData("Lunar QA", addr, true),
                        false);
            }
            return;
        }
        if (mc.level == null || mc.gameMode == null) return;
        if (!mc.level.dimension().location().getPath().equals(SITE.path())) {
            if (uiTicks % 10 != 0) return;
            if (guideStep == 0
                    && mc.screen == null
                    && "starbridge:navigation_slate"
                            .equals(
                                    String.valueOf(
                                            ForgeRegistries.ITEMS.getKey(
                                                    p.getMainHandItem().getItem())))) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                record("Used unpaired slate normally");
                guideStep = 1;
            } else if (current.equals("FirstFlightScreen")) {
                if (guideStep == 1 || guideStep == 2) {
                    String file = guideStep == 1 ? "unbound-slate" : "original-shipyard-guide";
                    Screenshot.grab(
                            mc.gameDirectory, file + ".png", mc.getMainRenderTarget(), m -> {});
                    String wanted = guideStep == 1 ? "slate.starbridge.guide.shipyard" : "gui.done";
                    for (var widget : mc.screen.children())
                        if (widget instanceof Button b
                                && b.active
                                && b.getMessage().getContents()
                                        instanceof
                                        net.minecraft.network.chat.contents.TranslatableContents
                                        name
                                && name.getKey().equals(wanted)) {
                            int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
                            mc.screen.mouseClicked(x, y, 0);
                            if (mc.screen != null) mc.screen.mouseReleased(x, y, 0);
                            record("Clicked " + wanted);
                            ++guideStep;
                            break;
                        }
                    if (guideStep == 3)
                        Files.writeString(
                                Path.of("slate-guide-done"), "clicked both tabs and closed");
                }
            }
            return;
        }
        ++t;
        mc.setWindowActive(true);
        if (mc.screen instanceof AbstractContainerScreen<?> menu) {
            if (t % 8 != 0) return;
            for (var slot : menu.getMenu().slots) {
                if (slot.container != p.getInventory() && slot.hasItem()) {
                    var cargo = ForgeRegistries.ITEMS.getKey(slot.getItem().getItem());
                    mc.gameMode.handleInventoryMouseClick(
                            menu.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE, p);
                    record(
                            "Shift-clicked original chest slot "
                                    + slot.index
                                    + " containing "
                                    + cargo);
                    return;
                }
            }
            return;
        }
        if (mc.screen != null) return;
        if (p.position()
                        .distanceToSqr(
                                SITE.chest().getX() + .5,
                                SITE.chest().getY(),
                                SITE.chest().getZ() + 2.5)
                < 10) {
            mc.options.keyUp.setDown(false);
            if (t % 12 != 0) return;
            for (int i = 0; i < p.getInventory().items.size(); ++i) {
                var stack = p.getInventory().getItem(i);
                var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null
                        && id.getNamespace().equals(SITE.keyNamespace())
                        && id.getPath().contains("key")
                        && i < 9) {
                    p.getInventory().selected = i;
                    break;
                }
            }
            var pos = SITE.chest();
            var hit = Vec3.atCenterOf(pos).add(0, 0, .49);
            p.lookAt(Anchor.EYES, hit);
            var result =
                    mc.gameMode.useItemOn(
                            p,
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(hit, Direction.SOUTH, pos, false));
            if (++attempts <= 4)
                record(
                        "Right-clicked original reward chest with "
                                + p.getMainHandItem()
                                + " result="
                                + result);
            return;
        }
        LivingEntity boss = null;
        ItemEntity key = null;
        for (var ent : mc.level.entitiesForRendering()) {
            var id = ForgeRegistries.ENTITY_TYPES.getKey(ent.getType());
            if (ent instanceof LivingEntity living
                    && id != null
                    && id.getPath().toLowerCase(Locale.ROOT).contains("boss")
                    && living.isAlive()) boss = living;
            if (ent instanceof ItemEntity item) {
                var iid = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
                if (iid != null
                        && iid.getNamespace().equals(SITE.keyNamespace())
                        && iid.getPath().contains("key")) key = item;
            }
        }
        if (key != null) {
            p.lookAt(Anchor.EYES, key.position());
            mc.options.keyUp.setDown(key.distanceToSqr(p) > .15);
            return;
        }
        if (boss == null) {
            mc.options.keyUp.setDown(false);
            return;
        }
        var point = boss.getBoundingBox().getCenter();
        p.lookAt(Anchor.EYES, point);
        double dist = boss.getBoundingBox().distanceToSqr(p.getEyePosition());
        mc.options.keyUp.setDown(dist > 2.6);
        if (dist < 9 && p.getAttackStrengthScale(0) > .94f && t % 2 == 0) {
            mc.gameMode.attack(p, boss);
            p.swing(InteractionHand.MAIN_HAND);
            if (boss.getHealth() != lastHp) {
                record(
                        "Attacked in range with "
                                + p.getMainHandItem()
                                + " boss health="
                                + boss.getHealth());
                lastHp = boss.getHealth();
            }
        }
    }
}
