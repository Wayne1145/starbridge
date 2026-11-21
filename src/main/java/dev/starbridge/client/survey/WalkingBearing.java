package dev.starbridge.client.survey;

import com.mojang.math.Axis;
import dev.starbridge.Starbridge;
import dev.starbridge.survey.FieldNotes;
import dev.starbridge.survey.FieldNotes.Marker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** The survey's selected marker, visible while the same terminal is in either hand. */
@Mod.EventBusSubscriber(modid = Starbridge.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WalkingBearing {
    private record Selection(UUID terminal, ResourceLocation dimension, Marker marker) {}
    private static Selection selection;
    private WalkingBearing() {}

    static Marker focusFor(FieldNotes.View view) {
        if (selection != null && selection.terminal().equals(view.id()) && selection.dimension().equals(view.dimension())
                && view.at(selection.marker()) != null) return selection.marker();
        return view.ship() == null && view.site() != null ? Marker.SITE : Marker.SHIP;
    }

    static void follow(FieldNotes.View view, Marker marker) {
        if (view.at(marker) == null || view.notice() == FieldNotes.Notice.MOVED || view.notice() == FieldNotes.Notice.CHANGED_WORLD) {
            selection = null;
            return;
        }
        selection = new Selection(view.id(), view.dimension(), marker);
    }

    @SubscribeEvent public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "walking_bearing", (gui, graphics, partialTick, width, height) -> render(graphics));
    }

    private static void render(GuiGraphics graphics) {
        var game = Minecraft.getInstance();
        var player = game.player;
        if (selection == null || player == null || game.level == null || game.screen != null || game.options.hideGui
                || game.options.renderDebug || player.isSpectator() || !selection.dimension().equals(game.level.dimension().location())) return;
        BlockPos goal = FieldNotes.markedOn(player.getMainHandItem(), selection.terminal(), selection.dimension(), selection.marker());
        if (goal == null) goal = FieldNotes.markedOn(player.getOffhandItem(), selection.terminal(), selection.dimension(), selection.marker());
        if (goal == null) return;

        var feet = player.blockPosition();
        boolean sameColumn = feet.getX() == goal.getX() && feet.getZ() == goal.getZ();
        int rise = goal.getY() - feet.getY();
        double dx = goal.getX() + 0.5 - player.getX(), dz = goal.getZ() + 0.5 - player.getZ();
        boolean ship = selection.marker() == Marker.SHIP;
        int color = ship ? 0xff76e0c7 : 0xffffbd78;
        Component name = Component.translatable(ship ? "slate.starbridge.field.ship" : "slate.starbridge.field.site");
        Component label;
        if (!sameColumn) label = Component.translatable("slate.starbridge.field.hud.range", name, Math.max(1L, Math.round(Math.hypot(dx, dz))));
        else if (rise == 0) label = Component.translatable("slate.starbridge.field.hud.here", name);
        else label = Component.translatable(rise > 0 ? "slate.starbridge.field.hud.above" : "slate.starbridge.field.hud.below", name, Math.abs(rise));

        graphics.fill(6, 6, 35 + game.font.width(label), 27, 0xdb142837);
        graphics.renderOutline(8, 8, 17, 17, 0xff527281);
        if (sameColumn && rise == 0) {
            graphics.renderOutline(13, 13, 7, 7, color);
            graphics.fill(16, 16, 17, 17, color);
        } else {
            float cameraYaw = game.gameRenderer.getMainCamera().getYRot();
            float angle = sameColumn ? (rise > 0 ? 0 : 180) : (float) Math.toDegrees(Math.atan2(-dx, dz)) - cameraYaw;
            graphics.pose().pushPose();
            graphics.pose().translate(16.5f, 16.5f, 0);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(angle));
            graphics.fill(-1, -6, 1, 6, color);
            for (int i = 1; i <= 4; i++) graphics.fill(-i, -6 + i, i + 1, -5 + i, color);
            graphics.pose().popPose();
        }
        graphics.drawString(game.font, label, 30, 12, 0xffdeecec, false);
    }
}
