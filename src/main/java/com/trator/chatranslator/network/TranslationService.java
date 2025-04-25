package com.trator.chatranslator.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trator.chatranslator.ChatTranslatorMod;
import com.trator.chatranslator.config.ModConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class TranslationService {
    private static final String API_URL = "https://translate.appworlds.cn";
    private static final Gson GSON = new Gson();
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    
    // 用于限制API调用频率的时间戳
    private static long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL = 2000; // 2秒(免费用户限制)
    
    /**
     * 异步翻译文本
     * @param text 需要翻译的文本
     * @return 包含翻译结果的CompletableFuture
     */
    public static CompletableFuture<String> translateAsync(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (text == null || text.trim().isEmpty()) {
                    return text;
                }

                long currentTime = System.currentTimeMillis();
                long timeSinceLastRequest = currentTime - lastRequestTime;
                
                if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
                    Thread.sleep(MIN_REQUEST_INTERVAL - timeSinceLastRequest);
                }
                
                String result = translate(text);
                lastRequestTime = System.currentTimeMillis();

                ChatTranslatorMod.LOGGER.info("result: [{}] -> [{}]", text, result);
                
                return result;
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("something went wrong", e);
                return "something went wrong:" + e.getMessage();
            }
        }, EXECUTOR);
    }
    
    /**
     * 同步翻译文本
     * @param text 需要翻译的文本
     * @return 翻译后的文本
     */
    private static String translate(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        String sourceLanguage = ModConfig.getSourceLanguage();
        String targetLanguage = ModConfig.getTargetLanguage();
        
        // 确保目标语言是中文（如果配置有误）
        if (!"zh-CN".equals(targetLanguage)) {
            ChatTranslatorMod.LOGGER.warn("目标语言设置不是中文,已自动调整为中文");
            targetLanguage = "zh-CN";
        }
        
        // 编码参数
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString());
        String requestUrl = String.format("%s?text=%s&from=%s&to=%s", 
                API_URL, encodedText, sourceLanguage, targetLanguage);
        
        ChatTranslatorMod.LOGGER.info("post:{}", requestUrl);
        
        URL url = new URL(requestUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            String responseStr = response.toString();
            ChatTranslatorMod.LOGGER.info("API:{}", responseStr);
            
            JsonObject jsonResponse = GSON.fromJson(responseStr, JsonObject.class);
            int code = jsonResponse.get("code").getAsInt();
            
            if (code == 200) {
                return jsonResponse.get("data").getAsString();
            } else {
                String errorMsg = jsonResponse.get("msg").getAsString();
                ChatTranslatorMod.LOGGER.error("something went wrong: {}", errorMsg);
                return "something went wrong: " + errorMsg;
            }
        } else {
            throw new Exception("something went wrong: " + responseCode);
        }
    }
} 