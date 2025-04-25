package com.trator.chatranslator;

import com.trator.chatranslator.command.ConfigCommand;
import com.trator.chatranslator.config.ModConfig;
import com.trator.chatranslator.network.TranslationService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatTranslatorMod implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "chatranslator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static KeyBinding toggleTranslationKey;
    
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Chat Translator Mod");
        
        // 初始化配置
        ModConfig.init();
        
        // 注册配置命令
        ConfigCommand.register();
        
        LOGGER.info("Chat Translator Mod initialized successfully!");
    }
    
    @Override
    public void onInitializeClient() {
        // 注册聊天消息监听器
        ChatMessageListener.register();
        
        // 初始化按键绑定
        initKeyBindings();
        
        LOGGER.info("translator client initialized successfully by kmno4");
    }
    
    private void initKeyBindings() {
        toggleTranslationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chatranslator.toggle",
                GLFW.GLFW_KEY_T,
                "key.categories.chatranslator"
        ));
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleTranslationKey.wasPressed()) {
                ModConfig.toggleTranslation();
                if (client.player != null) {
                    boolean enabled = ModConfig.isTranslationEnabled();
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                            enabled ? "聊天翻译ON" : "聊天翻译OFF"
                    ), false);
                }
            }
        });
    }
} 