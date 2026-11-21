package dev.starbridge.survivalqa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.*;

@Mod("starbridge_survivalqa")
public final class Expedition {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    static boolean enabled() {
        return Files.isRegularFile(Path.of("survival-expedition.fixture"));
    }

    public Expedition() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    static void save(String file, Object value) {
        try {
            Path target = Path.of(file);
            Path pending = target.resolveSibling(target.getFileName() + ".new");
            Files.writeString(pending, JSON.toJson(value));
            try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void log(String event) {
        try {
            Files.writeString(
                    Path.of("survival-events.log"),
                    event + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String item(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Map<String, Integer> inventory(ServerPlayer player) {
        Map<String, Integer> items = new TreeMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) items.merge(item(stack), stack.getCount(), Integer::sum);
        }
        return items;
    }

    static List<Map<String,Object>> slots(ServerPlayer player) {
        List<Map<String,Object>> stacks = new ArrayList<>();
        var inventory=player.getInventory();
        for(int slot=0;slot<inventory.getContainerSize();slot++) {
            var stack=inventory.getItem(slot);
            if(!stack.isEmpty()) stacks.add(Map.of("slot",slot,"item",item(stack),"count",stack.getCount(),"tag",stack.hasTag()?stack.getTag().toString():""));
        }
        return stacks;
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        if (!enabled()) return;
        var server = event.getServer();
        List<Object> catalog = new ArrayList<>();
        for (var recipe : server.getRecipeManager().getRecipes()) {
            var output = recipe.getResultItem(server.registryAccess());
            if (output.isEmpty()) continue;
            String outputId = item(output);
            if (outputId.startsWith("minecraft:")
                    && !recipe.getId().getNamespace().equals("retrofit")) continue;
            List<Object> cells = new ArrayList<>();
            for (var ingredient : recipe.getIngredients()) {
                List<Object> options = new ArrayList<>();
                for (var stack : ingredient.getItems())
                    options.add(
                            Map.of(
                                    "item",
                                    item(stack),
                                    "tag",
                                    stack.hasTag() ? stack.getTag().toString() : ""));
                cells.add(options);
            }
            catalog.add(
                    Map.of(
                            "id",
                            recipe.getId().toString(),
                            "result",
                            outputId,
                            "count",
                            output.getCount(),
                            "tag",
                            output.hasTag() ? output.getTag().toString() : "",
                            "serializer",
                            BuiltInRegistries.RECIPE_SERIALIZER
                                    .getKey(recipe.getSerializer())
                                    .toString(),
                            "width",
                            recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shape
                                    ? shape.getWidth()
                                    : 0,
                            "cells",
                            cells));
        }
        save("survival-recipes.json", catalog);
        if (Files.isRegularFile(Path.of("recipe-diagnostics.fixture"))) RecipeProbe.run(server);
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        var carried = inventory(player);
        boolean first = !Files.exists(Path.of("first-join.json"));
        log("Login " + player.getGameProfile().getName() + " " + carried);
        var state =
                Map.of(
                        "name",
                        player.getGameProfile().getName(),
                        "items",
                        carried,
                        "op",
                        player.hasPermissions(2),
                        "creative",
                        player.isCreative(),
                        "position",
                        player.position().toString(),
                        "gameTime",
                        player.level().getGameTime(),
                        "allowCommands",
                        player.server.getWorldData().getAllowCommands());
        if (first) save("first-join.json", state);
        String personalFile = "first-join-" + player.getUUID() + ".json";
        if (!Files.exists(Path.of(personalFile))
                && (first || !player.getGameProfile().getName().equals("OrbitCaptain")))
            save(personalFile, state);
    }

    @SubscribeEvent
    public void craft(PlayerEvent.ItemCraftedEvent event) {
        if (enabled() && event.getEntity() instanceof ServerPlayer player)
            log(
                    player.level().getGameTime()
                            + " "
                            + player.getGameProfile().getName()
                            + " crafted "
                            + event.getCrafting());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (enabled() && event.phase == TickEvent.Phase.END) PowerProbe.tick(event.getServer());
        if (!enabled()
                || event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % 80 != 0) return;
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            var snapshot =
                    Map.of(
                            "tick",
                            event.getServer().getTickCount(),
                            "worldTime",
                            player.level().getGameTime(),
                            "world",
                            player.level().dimension().location().toString(),
                            "position",
                            player.position().toString(),
                            "health",
                            player.getHealth(),
                            "food",
                            player.getFoodData().getFoodLevel(),
                            "items",
                            inventory(player),
                            "slots", slots(player),
                            "creative",
                            player.isCreative(),
                            "op",
                            player.hasPermissions(2));
            save("survival-inventory-" + player.getUUID() + ".json", snapshot);
            if (player.getGameProfile().getName().equals("OrbitCaptain"))
                save("survival-inventory.json", snapshot);
        }
    }
}
