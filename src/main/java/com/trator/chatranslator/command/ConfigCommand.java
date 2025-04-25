package com.trator.chatranslator.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trator.chatranslator.ChatTranslatorMod;
import com.trator.chatranslator.config.ModConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ConfigCommand {
    private static final Set<String> VALID_LANGUAGES = new HashSet<>(Arrays.asList(
            "auto", "zh-CN", "en", "ja", "ko", "fr", "de", "es", "it", "ru"));

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            ChatTranslatorMod.LOGGER.info("registering command of translator...");
            registerCommands(dispatcher);
            ChatTranslatorMod.LOGGER.info("translator command registered");
        });
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> translatorCommand = literal("translator");

        // 查看当前配置
        translatorCommand.then(literal("status")
                .executes(context -> {
                    FabricClientCommandSource source = context.getSource();
                    source.sendFeedback(Text.literal("§6===== 聊天翻译配置 ====="));
                    source.sendFeedback(Text.literal("§e翻译状态:§f" + (ModConfig.isTranslationEnabled() ? "§a已启用" : "§c已禁用")));
                    source.sendFeedback(Text.literal("§e源语言:§f" + getLanguageName(ModConfig.getSourceLanguage())));
                    source.sendFeedback(Text.literal("§e目标语言:§f" + getLanguageName(ModConfig.getTargetLanguage())));
                    source.sendFeedback(Text.literal("§e显示原文:§f" + (ModConfig.shouldShowOriginalMessage() ? "§a是" : "§c否")));
                    return 1;
                }));
        translatorCommand.then(literal("toggle")
                .executes(context -> {
                    ModConfig.toggleTranslation();
                    boolean enabled = ModConfig.isTranslationEnabled();
                    context.getSource().sendFeedback(Text.literal(
                            enabled ? "§a聊天翻译ON" : "§c聊天翻译OFF"));
                    return 1;
                }));

        translatorCommand.then(literal("source")
                .then(argument("language", StringArgumentType.word())
                        .executes(context -> {
                            String language = StringArgumentType.getString(context, "language");
                            if (VALID_LANGUAGES.contains(language)) {
                                ModConfig.setSourceLanguage(language);
                                context.getSource().sendFeedback(Text.literal(
                                        "§a源语言已设置为:" + getLanguageName(language)));
                            } else {
                                context.getSource().sendFeedback(Text.literal(
                                        "§c无效的语言代码,可用:" + String.join(", ", VALID_LANGUAGES)));
                            }
                            return 1;
                        })));

        translatorCommand.then(literal("target")
                .then(argument("language", StringArgumentType.word())
                        .executes(context -> {
                            String language = StringArgumentType.getString(context, "language");
                            if (VALID_LANGUAGES.contains(language) && !language.equals("auto")) {
                                ModConfig.setTargetLanguage(language);
                                context.getSource().sendFeedback(Text.literal(
                                        "§a目标语言已设置为:" + getLanguageName(language)));
                            } else if (language.equals("auto")) {
                                context.getSource().sendFeedback(Text.literal(
                                        "§c目标语言不能设置为'auto'"));
                            } else {
                                context.getSource().sendFeedback(Text.literal(
                                        "§c无效的语言代码,可用: " + String.join(", ", VALID_LANGUAGES)));
                            }
                            return 1;
                        })));

        // 设置是否显示原文
        translatorCommand.then(literal("showOriginal")
                .then(argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean value = BoolArgumentType.getBool(context, "value");
                            ModConfig.setShowOriginalMessage(value);
                            context.getSource().sendFeedback(Text.literal(
                                    "§a显示原文已" + (value ? "启用" : "禁用")));
                            return 1;
                        })));

        // 设置翻译延迟
        translatorCommand.then(literal("delay")
                .then(argument("ms", IntegerArgumentType.integer(0, 10000))
                        .executes(context -> {
                            int delay = IntegerArgumentType.getInteger(context, "ms");
                            ModConfig.setTranslationDelay(delay);
                            context.getSource().sendFeedback(Text.literal(
                                    "§a翻译延迟已设置 " + delay + "ms"));
                            return 1;
                        })));

        // 重置所有配置到默认值
        translatorCommand.then(literal("reset")
                .executes(context -> {
                    ModConfig.resetToDefault();
                    context.getSource().sendFeedback(Text.literal(
                            "§a配置已重置"));
                    return 1;
                }));
                
        // 帮助命令
        translatorCommand.then(literal("help")
                .executes(context -> {
                    FabricClientCommandSource source = context.getSource();
                    source.sendFeedback(Text.literal("§6===== 聊天翻译命令帮助 ====="));
                    source.sendFeedback(Text.literal("§e/translator status §f- 查看当前配置"));
                    source.sendFeedback(Text.literal("§e/translator toggle §f- 开启/关闭翻译功能"));
                    source.sendFeedback(Text.literal("§e/translator source <语言> §f- 设置源语言"));
                    source.sendFeedback(Text.literal("§e/translator target <语言> §f- 设置目标语言"));
                    source.sendFeedback(Text.literal("§e/translator showOriginal <true|false> §f- 设置是否显示原文"));
                    source.sendFeedback(Text.literal("§e/translator reset §f- 重置所有配置"));
                    source.sendFeedback(Text.literal("§e/translator help §f- 显示此帮助"));
                    return 1;
                }));

        // 根命令（不带参数）默认显示帮助
        translatorCommand.executes(context -> {
            FabricClientCommandSource source = context.getSource();
            source.sendFeedback(Text.literal("§6===== 聊天翻译命令帮助 ====="));
            source.sendFeedback(Text.literal("§e/translator status §f- 查看当前配置"));
            source.sendFeedback(Text.literal("§e/translator toggle §f- 开启/关闭翻译功能"));
            source.sendFeedback(Text.literal("§e/translator help §f- 显示更多命令"));
            return 1;
        });

        dispatcher.register(translatorCommand);
    }

    // 获取语言的可读名称
    private static String getLanguageName(String code) {
        switch (code) {
            case "auto":
                return "自动检测";
            case "zh-CN":
                return "简体中文";
            case "en":
                return "英语";
            case "ja":
                return "日语";
            case "ko":
                return "韩语";
            case "fr":
                return "法语";
            case "de":
                return "德语";
            case "es":
                return "西班牙语";
            case "it":
                return "意大利语";
            case "ru":
                return "俄语";
            default:
                return code;
        }
    }
}