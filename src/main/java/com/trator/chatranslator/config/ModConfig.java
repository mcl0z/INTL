package com.trator.chatranslator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trator.chatranslator.ChatTranslatorMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("chatranslator.json").toFile();
    private static ConfigData configData;

    public static class ConfigData {
        public boolean translationEnabled = true;
        public String sourceLanguage = "auto";
        public String targetLanguage = "zh-CN";
        public boolean showOriginalMessage = true;
        public int translationDelay = 0; // 翻译延迟（毫秒）
    }

    public static void init() {
        if (!CONFIG_FILE.exists()) {
            configData = new ConfigData();
            save();
        } else {
            load();
        }
    }

    public static void load() {
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            configData = GSON.fromJson(reader, ConfigData.class);
        } catch (IOException e) {
            ChatTranslatorMod.LOGGER.error("failed to load config", e);
            configData = new ConfigData();
        }
    }

    public static void save() {
        try {
            if (!CONFIG_FILE.exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
                CONFIG_FILE.createNewFile();
            }
            
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(configData, writer);
            }
        } catch (IOException e) {
            ChatTranslatorMod.LOGGER.error("failed to save config", e);
        }
    }

    public static void resetToDefault() {
        configData = new ConfigData();
        save();
    }

    public static boolean isTranslationEnabled() {
        return configData.translationEnabled;
    }

    public static void toggleTranslation() {
        configData.translationEnabled = !configData.translationEnabled;
        save();
    }

    public static String getSourceLanguage() {
        return configData.sourceLanguage;
    }

    public static String getTargetLanguage() {
        return configData.targetLanguage;
    }

    public static boolean shouldShowOriginalMessage() {
        return configData.showOriginalMessage;
    }
    
    public static int getTranslationDelay() {
        return configData.translationDelay;
    }

    public static void setTranslationEnabled(boolean enabled) {
        configData.translationEnabled = enabled;
        save();
    }
    
    public static void setSourceLanguage(String language) {
        configData.sourceLanguage = language;
        save();
    }
    
    public static void setTargetLanguage(String language) {
        configData.targetLanguage = language;
        save();
    }
    
    public static void setShowOriginalMessage(boolean show) {
        configData.showOriginalMessage = show;
        save();
    }
    
    public static void setTranslationDelay(int delay) {
        configData.translationDelay = delay;
        save();
    }
} 