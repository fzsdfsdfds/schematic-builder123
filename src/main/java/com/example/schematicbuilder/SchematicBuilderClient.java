package com.example.schematicbuilder;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

public class SchematicBuilderClient implements ClientModInitializer {
    public static final Builder BUILDER = new Builder();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> BUILDER.tick());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("schem")
                .then(ClientCommandManager.literal("load")
                    .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                        .suggests((ctx, sb) -> CommandSource.suggestMatching(Builder.listFiles(), sb))
                        .executes(ctx -> {
                            BUILDER.load(StringArgumentType.getString(ctx, "file"));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("origin")
                    .executes(ctx -> {
                        var player = MinecraftClient.getInstance().player;
                        if (player != null) BUILDER.setOrigin(player.getBlockPos());
                        return 1;
                    })
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    BUILDER.setOrigin(new net.minecraft.util.math.BlockPos(
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "y"),
                                        IntegerArgumentType.getInteger(ctx, "z")));
                                    return 1;
                                })))))
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    BUILDER.start();
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    BUILDER.stop(true);
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    BUILDER.status();
                    return 1;
                }))
                .then(ClientCommandManager.literal("range")
                    .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(1.0, 5.0))
                        .executes(ctx -> {
                            BUILDER.range = DoubleArgumentType.getDouble(ctx, "value");
                            BUILDER.msg("Zasieg: " + BUILDER.range);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("speed")
                    .then(ClientCommandManager.argument("value", IntegerArgumentType.integer(1, 10))
                        .executes(ctx -> {
                            BUILDER.blocksPerTick = IntegerArgumentType.getInteger(ctx, "value");
                            BUILDER.msg("Bloki na tick: " + BUILDER.blocksPerTick);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("value", IntegerArgumentType.integer(0, 20))
                        .executes(ctx -> {
                            BUILDER.delay = IntegerArgumentType.getInteger(ctx, "value");
                            BUILDER.msg("Delay: " + BUILDER.delay + " tickow");
                            return 1;
                        })))
        ));
    }
}
