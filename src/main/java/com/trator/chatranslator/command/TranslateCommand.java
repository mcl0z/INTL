package com.trator.chatranslator.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.trator.chatranslator.ChatTranslatorMod;
import com.trator.chatranslator.network.TranslationService;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class TranslateCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("translate")
                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                    .executes(context -> executeTranslate(context, StringArgumentType.getString(context, "text"))))
        );
        
        ChatTranslatorMod.LOGGER.info("command registered");
    }
    
    private static int executeTranslate(CommandContext<FabricClientCommandSource> context, String text) {
        FabricClientCommandSource source = context.getSource();
        
        source.sendFeedback(Text.literal("traslating:").formatted(Formatting.GRAY)
                .append(Text.literal(text).formatted(Formatting.WHITE)));
        
        TranslationService.translateAsync(text).thenAccept(translatedText -> {
            if (translatedText != null && !translatedText.isEmpty()) {
                MutableText resultText = Text.literal("result:").formatted(Formatting.GOLD)
                        .append(Text.literal(translatedText).formatted(Formatting.WHITE));
                source.sendFeedback(resultText);
            } else {
                source.sendError(Text.literal("something went wrong").formatted(Formatting.RED));
            }
        }).exceptionally(e -> {
            source.sendError(Text.literal("something went wrong" + e.getMessage()).formatted(Formatting.RED));
            ChatTranslatorMod.LOGGER.error("something went wrong", e);
            return null;
        });
        
        return 1;
    }
} 