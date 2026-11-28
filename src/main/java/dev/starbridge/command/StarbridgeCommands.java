package dev.starbridge.command;

import com.google.gson.Gson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.server.AgentHttpServer;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.network.PilotModule;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.starbridge.text.Lang;

public final class StarbridgeCommands {
    private static final Gson JSON = new Gson();
    private StarbridgeCommands() {}
    @FunctionalInterface private interface CoreAction { void run(ShipCoreBlockEntity core) throws Exception; }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("starbridge")
            .then(Commands.literal("pilot").executes(c -> {
                var core = selected(c.getSource());
                try { PilotModule.open(c.getSource().getPlayerOrException(), core); return 1; }
                catch (RuntimeException failure) { throw error(failure); }
            }))
            .then(Commands.literal("status").executes(c -> {
                var core = selected(c.getSource());
                c.getSource().sendSuccess(() -> Component.literal(JSON.toJson(core.status())), false);
                return 1;
            }))
            .then(Commands.literal("navigate")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-29_000_000, 29_000_000))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-8192, 8192))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-29_000_000, 29_000_000))
                .executes(c -> act(c, core -> core.navigate(new Vec3d(d(c,"x"), d(c,"y"), d(c,"z")))))))))
            .then(Commands.literal("hold").executes(c -> act(c, ShipCoreBlockEntity::hold)))
            .then(Commands.literal("launch").executes(c -> act(c, ShipCoreBlockEntity::launch)))
            .then(Commands.literal("jump").executes(c -> act(c, core -> core.jump(c.getSource().getPlayerOrException()))))
            .then(Commands.literal("stop").executes(c -> act(c, ShipCoreBlockEntity::stop)))
            .then(Commands.literal("release").executes(c -> act(c, ShipCoreBlockEntity::release)))
            .then(Commands.literal("jog")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-3, 3))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-3, 3))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-3, 3))
                .executes(c -> act(c, core -> core.jog(new Vec3d(d(c,"x"), d(c,"y"), d(c,"z")), 20)))))))
            .then(Commands.literal("gravity").requires(source -> source.hasPermission(2))
                .then(Commands.argument("compensation", DoubleArgumentType.doubleArg(0, 30))
                .executes(c -> act(c, core -> core.setGravityCompensation(d(c,"compensation"))))))
            .then(Commands.literal("computer")
                .then(Commands.literal("pair").then(Commands.argument("id", IntegerArgumentType.integer(0))
                    .executes(c -> act(c, core -> core.pairComputer(IntegerArgumentType.getInteger(c,"id"))))))
                .then(Commands.literal("unpair").executes(c -> act(c, core -> core.pairComputer(-1)))))
            .then(Commands.literal("agent")
                .then(Commands.literal("allow").then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(c -> act(c, core -> core.setAgentAllowed(BoolArgumentType.getBool(c,"enabled"))))))
                .then(Commands.literal("start").requires(source -> source.hasPermission(2)).executes(c -> {
                    try {
                        var path = AgentHttpServer.start(c.getSource().getServer());
                        c.getSource().sendSuccess(() -> Lang.msg("agent_started", path.toString()), false);
                        return 1;
                    } catch (Exception failure) { throw error(failure); }
                }))
                .then(Commands.literal("shutdown").requires(source -> source.hasPermission(2)).executes(c -> {
                    AgentHttpServer.stop();
                    c.getSource().sendSuccess(() -> Lang.msg("agent_stopped"), true);
                    return 1;
                })))
        );
    }
    private static double d(CommandContext<CommandSourceStack> context, String key) { return DoubleArgumentType.getDouble(context, key); }
    private static int act(CommandContext<CommandSourceStack> context, CoreAction action) throws CommandSyntaxException {
        ShipCoreBlockEntity core = selected(context.getSource());
        try {
            action.run(core);
            context.getSource().sendSuccess(() -> Component.literal(core.shortStatus()), false);
            return 1;
        } catch (Exception failure) { throw error(failure); }
    }
    public static ShipCoreBlockEntity selected(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var data = player.getPersistentData();
        if (!data.hasUUID("starbridge:selected_core")) throw error(Lang.msg("select_core_first"));
        ShipCoreBlockEntity core = CoreRegistry.find(data.getUUID("starbridge:selected_core")).orElseThrow(() -> error(Lang.msg("selected_unloaded")));
        if (!core.authorized(player)) throw error(Component.translatable("message.starbridge.not_owner"));
        return core;
    }
    private static CommandSyntaxException error(Throwable failure) { return error(Lang.of(failure)); }
    private static CommandSyntaxException error(Component text) {
        return new SimpleCommandExceptionType(text).create();
    }
}
