package com.trator.chatranslator;

import com.trator.chatranslator.config.ModConfig;
import com.trator.chatranslator.network.TranslationService;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatMessageListener {
    // 匹配系统消息、已翻译消息等不需要处理的内容
    private static final Pattern SYSTEM_MESSAGE_PATTERN = Pattern.compile("^\\[系统\\]|^\\[译|^\\[原文]|\\[(.+)加入了游戏\\]|\\[(.+)离开了游戏\\]");
    
    // 玩家聊天消息模式 - 包括普通聊天(<Player> message)和安全聊天格式
    private static final Pattern PLAYER_MESSAGE_PATTERN = Pattern.compile("<([^>]+)>\\s+(.+)");
    
    // 命令消息格式 - 用于过滤掉命令
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^/.*");
    
    // 备用玩家消息格式，用于匹配可能的不同格式
    private static final Pattern ALT_PLAYER_MESSAGE_PATTERN = Pattern.compile("\\[CHAT\\]\\s+<([^>]+)>\\s+(.+)");
    
    // 检测中文字符的正则表达式
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");
    
    // 常见中文网络缩写
    private static final Set<String> CHINESE_ABBREVIATIONS = new HashSet<>(Arrays.asList(
        "gg", "nb", "xswl", "nmsl", "sb", "lz", "fvv", "fw", "233"
    ));
    
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    
    // API调用频率限制 - 每次调用之间的最小间隔（毫秒）
    private static final long API_RATE_LIMIT_MS = 1300; // 1.3秒
    
    // 上次API调用的时间
    private static final AtomicLong lastApiCallTime = new AtomicLong(0);
    
    // 待翻译消息队列
    private static final Queue<TranslationRequest> translationQueue = new ConcurrentLinkedQueue<>();
    
    // 使用线程安全的Set跟踪正在翻译的内容，防止重复翻译
    private static final Set<String> pendingTranslations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // 存储消息与发送者的关系
    private static final ConcurrentHashMap<String, String> messageSenders = new ConcurrentHashMap<>();
    
    // 翻译请求类
    private static class TranslationRequest {
        final String content;
        final boolean immediate;
        
        TranslationRequest(String content, boolean immediate) {
            this.content = content;
            this.immediate = immediate;
        }
    }
    
    // 检查消息是否包含中文字符
    private static boolean containsChineseCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return CHINESE_PATTERN.matcher(text).find();
    }
    
    // 检查消息是否是中文网络缩写
    private static boolean isChineseAbbreviation(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        // 将消息转换为小写并清除空格进行比较
        String normalized = text.toLowerCase().trim();
        
        // 检查完整消息是否匹配
        if (CHINESE_ABBREVIATIONS.contains(normalized)) {
            return true;
        }
        
        // 检查消息是否只包含这些缩写
        for (String abbr : CHINESE_ABBREVIATIONS) {
            // 如果消息只包含缩写加一些标点或空格
            if (normalized.matches("\\s*" + Pattern.quote(abbr) + "\\s*[!?,.。！？，]*\\s*")) {
                return true;
            }
        }
        
        return false;
    }
    
    // 检查消息是否应该被跳过翻译（包含中文或是中文缩写）
    private static boolean shouldSkipTranslation(String content) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        
        // 检查是否包含中文
        boolean containsChinese = containsChineseCharacters(content);
        if (containsChinese) {
            ChatTranslatorMod.LOGGER.info("跳过包含中文的消息: '{}'", content);
            return true;
        }
        
        // 检查是否是常见中文网络缩写
        boolean isAbbreviation = isChineseAbbreviation(content);
        if (isAbbreviation) {
            ChatTranslatorMod.LOGGER.info("跳过中文网络缩写: '{}'", content);
            return true;
        }
        
        return false;
    }
    
    public static void register() {
        //直接捕获聊天
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            try {
                String textContent = message.getString();
                ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 捕获聊天消息: '{}'", textContent);
                
                // 只处理包含玩家名称格式的消息，排除命令
                if (ModConfig.isTranslationEnabled() && !isOwnMessage(textContent) && !isCommand(textContent)) {
                    // 直接从聊天消息中提取内容并翻译
                    Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(textContent);
                    if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
                        String playerName = playerMatcher.group(1);
                        String content = playerMatcher.group(2).trim();
                        
                        // 检查是否是当前玩家发送的消息
                        if (isCurrentPlayer(playerName)) {
                            ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 跳过当前玩家消息: '{}'", content);
                            return true;
                        }
                        
                        // 检查是否应该跳过翻译（中文或中文缩写）
                        if (shouldSkipTranslation(content)) {
                            ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 跳过中文内容或中文缩写: '{}'", content);
                            return true;
                        }
                        
                        // 再次检查内容是否是命令
                        if (!isCommand(content)) {
                            ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 提取玩家消息: '{}' 说: '{}'", playerName, content);
                            
                            // 记录消息发送者
                            messageSenders.put(content, playerName);
                            
                            // 添加到翻译队列，优先级高
                            if (!pendingTranslations.contains(content)) {
                                pendingTranslations.add(content);
                                ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 将消息添加到翻译队列: '{}'", content);
                                enqueueTranslation(content, true);
                            }
                        } else {
                            ChatTranslatorMod.LOGGER.info("[ALLOW_CHAT] 跳过命令消息: '{}'", content);
                        }
                    }
                }
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("处理ALLOW_CHAT消息时发生错误", e);
            }
            return true; // 继续显示原始消息
        });
        
        // 注册所有消息事件监听器，确保捕获任何形式的游戏消息
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            try {
                String rawText = message.getString();
                // 跳过命令和系统消息
                if (isCommand(rawText) || SYSTEM_MESSAGE_PATTERN.matcher(rawText).find()) {
                    return true;
                }
                
                ChatTranslatorMod.LOGGER.info("[ALLOW_GAME] 收到消息: '{}'", rawText);
                
                if (ModConfig.isTranslationEnabled() && !isOwnMessage(rawText)) {
                    // 检查是否是玩家聊天消息
                    if (rawText.contains("<") && rawText.contains(">") && !rawText.startsWith("/")) {
                        // 尝试提取发送者信息
                        Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(rawText);
                        if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
                            String playerName = playerMatcher.group(1);
                            
                            // 检查是否是当前玩家发送的消息
                            if (isCurrentPlayer(playerName)) {
                                ChatTranslatorMod.LOGGER.info("[ALLOW_GAME] 跳过当前玩家消息");
                                return true;
                            }
                            
                            String content = playerMatcher.group(2).trim();
                            
                            // 检查是否应该跳过翻译（中文或中文缩写）
                            if (shouldSkipTranslation(content)) {
                                ChatTranslatorMod.LOGGER.info("[ALLOW_GAME] 跳过中文内容或中文缩写: '{}'", content);
                                return true;
                            }
                            
                            // 记录消息发送者
                            messageSenders.put(content, playerName);
                        }
                        
                        processMessageLater(rawText);
                    }
                }
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("处理ALLOW_GAME消息时发生错误", e);
            }
            // 必须返回true，不要阻止消息显示
            return true;
        });
        
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                String rawText = message.getString();
                // 跳过命令和系统消息
                if (isCommand(rawText) || SYSTEM_MESSAGE_PATTERN.matcher(rawText).find()) {
                    return;
                }
                
                ChatTranslatorMod.LOGGER.info("[GAME] 收到消息: '{}'", rawText);
                
                if (!overlay && ModConfig.isTranslationEnabled() && !isOwnMessage(rawText)) {
                    // 尝试提取发送者信息
                    Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(rawText);
                    if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
                        String playerName = playerMatcher.group(1);
                        
                        // 检查是否是当前玩家发送的消息
                        if (isCurrentPlayer(playerName)) {
                            ChatTranslatorMod.LOGGER.info("[GAME] 跳过当前玩家消息");
                            return;
                        }
                        
                        String content = playerMatcher.group(2).trim();
                        
                        // 检查是否应该跳过翻译（中文或中文缩写）
                        if (shouldSkipTranslation(content)) {
                            ChatTranslatorMod.LOGGER.info("[GAME] 跳过中文内容或中文缩写: '{}'", content);
                            return;
                        }
                        
                        // 记录消息发送者
                        messageSenders.put(content, playerName);
                    }
                    
                    processMessage(message, rawText);
                }
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("处理GAME消息时发生错误", e);
            }
        });
        
        // 启动翻译队列处理器
        startTranslationQueueProcessor();
        
        ChatTranslatorMod.LOGGER.info("聊天翻译监听器已注册! 等待玩家发送消息...");
        ChatTranslatorMod.LOGGER.info("特别提示：尝试使用/translator status查看当前配置状态");
    }
    
    // 启动翻译队列处理器
    private static void startTranslationQueueProcessor() {
        SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                processTranslationQueue();
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("处理翻译队列时发生错误", e);
            }
        }, 0, 100, TimeUnit.MILLISECONDS); // 每100毫秒检查一次队列
    }
    
    // 处理翻译队列
    private static void processTranslationQueue() {
        if (translationQueue.isEmpty()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long lastCall = lastApiCallTime.get();
        long timeSinceLastCall = currentTime - lastCall;
        
        // 检查是否满足API调用频率限制
        if (lastCall > 0 && timeSinceLastCall < API_RATE_LIMIT_MS) {
            // 未满足频率限制，稍后再尝试
            return;
        }
        
        // 从队列中取出一个请求
        TranslationRequest request = translationQueue.poll();
        if (request == null) {
            return;
        }
        
        // 检查是否应该跳过翻译（中文或中文缩写）
        if (shouldSkipTranslation(request.content)) {
            ChatTranslatorMod.LOGGER.info("[翻译队列] 跳过包含中文或中文缩写的内容: '{}'", request.content);
            pendingTranslations.remove(request.content);
            return;
        }
        
        // 更新最后调用时间
        lastApiCallTime.set(currentTime);
        
        // 执行翻译
        if (request.immediate) {
            translateMessageWithRateLimit(request.content);
        } else {
            translateAndSendWithRateLimit(request.content);
        }
    }
    
    // 将消息添加到翻译队列
    private static void enqueueTranslation(String content, boolean immediate) {
        translationQueue.add(new TranslationRequest(content, immediate));
    }
    
    // 检查消息是否是命令
    private static boolean isCommand(String text) {
        return text.startsWith("/") || COMMAND_PATTERN.matcher(text).matches();
    }
    
    // 检查是否是当前玩家发送的消息
    private static boolean isCurrentPlayer(String playerName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && playerName != null) {
            String currentPlayerName = client.player.getName().getString();
            return playerName.equalsIgnoreCase(currentPlayerName);
        }
        return false;
    }
    
    private static void translateMessageWithRateLimit(String content) {
        CompletableFuture<String> futureTranslation = TranslationService.translateAsync(content);
        futureTranslation.thenAccept(translatedText -> {
            try {
                pendingTranslations.remove(content);
                
                if (translatedText != null && !translatedText.trim().isEmpty() && !translatedText.equals(content)) {
                    // 检查是否包含API限制错误
                    if (translatedText.contains("免费用户接口访问频率") || translatedText.contains("something went wrong")) {
                        ChatTranslatorMod.LOGGER.info("[翻译队列] 检测到API频率限制，将消息重新排队: '{}'", content);
                        // 重新入队，设置为非立即处理，让其按队列顺序处理
                        pendingTranslations.add(content);
                        enqueueTranslation(content, false);
                        return;
                    }
                    
                    ChatTranslatorMod.LOGGER.info("[翻译队列] 翻译结果: '{}' -> '{}'", content, translatedText);
                    
                    // 获取消息发送者
                    String sender = messageSenders.getOrDefault(content, "未知玩家");
                    messageSenders.remove(content); // 使用后移除，避免内存泄漏
                    
                    MutableText translatedMessage = createTranslatedMessage(content, translatedText, sender);
                    
                    // 在游戏中显示翻译结果
                    MinecraftClient.getInstance().execute(() -> {
                        sendTranslationToChat(translatedMessage);
                    });
                }
            } catch (Exception e) {
                ChatTranslatorMod.LOGGER.error("处理翻译结果时发生错误", e);
            }
        }).exceptionally(e -> {
            pendingTranslations.remove(content);
            ChatTranslatorMod.LOGGER.error("翻译过程中发生错误", e);
            return null;
        });
    }
    
    private static void processMessageLater(String textContent) {
        // 如果是命令或系统消息，直接跳过
        if (isCommand(textContent) || SYSTEM_MESSAGE_PATTERN.matcher(textContent).find()) {
            return;
        }
        
        // 不需要太多处理，主要是捕获玩家聊天消息格式
        if (textContent.contains("<") && textContent.contains(">")) {
            // 检查是否为当前玩家消息
            Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(textContent);
            if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
                String playerName = playerMatcher.group(1);
                if (isCurrentPlayer(playerName)) {
                    ChatTranslatorMod.LOGGER.info("[延迟处理] 跳过当前玩家消息");
                    return;
                }
            }
            
            // 以延迟方式处理，避免与主线程冲突
            SCHEDULER.schedule(() -> {
                if (!pendingTranslations.contains(textContent)) {
                    ChatTranslatorMod.LOGGER.info("[延迟处理] 检测到可能的玩家消息: '{}'", textContent);
                    processRawMessage(textContent);
                }
            }, 100, TimeUnit.MILLISECONDS);
        }
    }
    
    private static void processRawMessage(String rawText) {
        // 如果是命令或系统消息，直接跳过
        if (isCommand(rawText) || SYSTEM_MESSAGE_PATTERN.matcher(rawText).find()) {
            return;
        }
        
        // 尝试匹配玩家消息格式
        Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(rawText);
        if (!playerMatcher.find()) {
            playerMatcher = ALT_PLAYER_MESSAGE_PATTERN.matcher(rawText);
        }
        
        if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
            String playerName = playerMatcher.group(1);
            
            // 检查是否是当前玩家
            if (isCurrentPlayer(playerName)) {
                ChatTranslatorMod.LOGGER.info("[原始处理] 跳过当前玩家消息");
                return;
            }
            
            String content = playerMatcher.group(2).trim();
            
            // 再次检查内容是否是命令
            if (isCommand(content)) {
                ChatTranslatorMod.LOGGER.info("[原始处理] 跳过命令: '{}'", content);
                return;
            }
            
            ChatTranslatorMod.LOGGER.info("[原始处理] 提取玩家消息: '{}' 说: '{}'", playerName, content);
            
            // 记录消息发送者
            messageSenders.put(content, playerName);
            
            if (!pendingTranslations.contains(content)) {
                pendingTranslations.add(content);
                enqueueTranslation(content, false);
            }
        }
    }
    
    private static boolean isOwnMessage(String content) {
        return content.contains("[原文]") || 
               content.contains("[译文]") || 
               content.contains("[译]");
    }
    
    private static void processMessage(Text message, String textContent) {
        // 如果是命令或系统消息，直接跳过
        if (isCommand(textContent) || SYSTEM_MESSAGE_PATTERN.matcher(textContent).find()) {
            return;
        }
        
        ChatTranslatorMod.LOGGER.info("处理消息: '{}'", textContent);
        
        // 尝试匹配标准玩家消息格式
        Matcher playerMatcher = PLAYER_MESSAGE_PATTERN.matcher(textContent);
        // 如果标准格式不匹配，尝试备用格式
        if (!playerMatcher.find()) {
            playerMatcher = ALT_PLAYER_MESSAGE_PATTERN.matcher(textContent);
        }
        
        if (playerMatcher.find() && playerMatcher.groupCount() >= 2) {
            String playerName = playerMatcher.group(1);
            
            // 检查是否是当前玩家
            if (isCurrentPlayer(playerName)) {
                ChatTranslatorMod.LOGGER.info("跳过当前玩家消息");
                return;
            }
            
            String content = playerMatcher.group(2).trim();
            
            // 再次检查内容是否是命令
            if (isCommand(content)) {
                ChatTranslatorMod.LOGGER.info("跳过命令: '{}'", content);
                return;
            }
            
            ChatTranslatorMod.LOGGER.info("检测到玩家消息: '{}' 说: '{}'", playerName, content);
            
            // 记录消息发送者
            messageSenders.put(content, playerName);
            
            // 避免重复翻译
            if (!pendingTranslations.contains(content)) {
                pendingTranslations.add(content);
                enqueueTranslation(content, false);
            } else {
                ChatTranslatorMod.LOGGER.info("跳过已在处理的消息: '{}'", content);
            }
        } else if (!SYSTEM_MESSAGE_PATTERN.matcher(textContent).find()) {
            // 检查是否是命令
            if (isCommand(textContent)) {
                ChatTranslatorMod.LOGGER.info("跳过命令: '{}'", textContent);
                return;
            }
            
            // 未匹配到玩家聊天格式，但也不是系统消息，可能是其他格式的聊天
            ChatTranslatorMod.LOGGER.info("检测到其他消息格式: '{}'", textContent);
            
            // 检查是否包含 "[CHAT]" 或 "<>"，这可能表示是玩家聊天但格式不同
            if (textContent.contains("[CHAT]") || (textContent.contains("<") && textContent.contains(">"))) {
                ChatTranslatorMod.LOGGER.info("疑似聊天消息: '{}'", textContent);
                
                // 尝试提取尖括号中的内容和之后的文本
                Pattern extractPattern = Pattern.compile(".*<([^>]+)>\\s*(.+)");
                Matcher extractMatcher = extractPattern.matcher(textContent);
                
                if (extractMatcher.find()) {
                    String playerName = extractMatcher.group(1);
                    
                    // 检查是否是当前玩家
                    if (isCurrentPlayer(playerName)) {
                        ChatTranslatorMod.LOGGER.info("跳过当前玩家消息");
                        return;
                    }
                    
                    String content = extractMatcher.group(2).trim();
                    
                    // 再次检查内容是否是命令
                    if (isCommand(content)) {
                        ChatTranslatorMod.LOGGER.info("跳过命令: '{}'", content);
                        return;
                    }
                    
                    ChatTranslatorMod.LOGGER.info("提取到玩家 '{}' 的消息: '{}'", playerName, content);
                    
                    // 记录消息发送者
                    messageSenders.put(content, playerName);
                    
                    if (!pendingTranslations.contains(content)) {
                        pendingTranslations.add(content);
                        enqueueTranslation(content, false);
                    }
                    return;
                }
            }
            
            // 避免重复翻译，同时排除非玩家消息和命令
            if (!pendingTranslations.contains(textContent) && !textContent.contains("/")) {
                pendingTranslations.add(textContent);
                enqueueTranslation(textContent, false);
            } else {
                ChatTranslatorMod.LOGGER.info("跳过已在处理的消息或非玩家消息: '{}'", textContent);
            }
        } else {
            ChatTranslatorMod.LOGGER.info("跳过系统消息: '{}'", textContent);
        }
    }

    private static void translateAndSendWithRateLimit(String content) {
        if (content == null || content.trim().isEmpty()) {
            ChatTranslatorMod.LOGGER.debug("空内容，跳过翻译");
            pendingTranslations.remove(content);
            return;
        }
        
        // 检查是否是命令
        if (isCommand(content)) {
            ChatTranslatorMod.LOGGER.info("跳过命令: '{}'", content);
            pendingTranslations.remove(content);
            return;
        }
        
        ChatTranslatorMod.LOGGER.info("正在翻译: '{}'", content);
        CompletableFuture<String> futureTranslation = TranslationService.translateAsync(content);
        int translationDelay = ModConfig.getTranslationDelay();
        
        futureTranslation.thenAccept(translatedText -> {
            // 从待处理集合中移除
            pendingTranslations.remove(content);
            
            if (translatedText != null && !translatedText.trim().isEmpty() && !translatedText.equals(content)) {
                // 检查是否包含API限制错误
                if (translatedText.contains("免费用户接口访问频率") || translatedText.contains("something went wrong")) {
                    ChatTranslatorMod.LOGGER.info("[翻译队列] 检测到API频率限制，将消息重新排队: '{}'", content);
                    // 重新入队，让其按队列顺序处理
                    pendingTranslations.add(content);
                    enqueueTranslation(content, false);
                    return;
                }
                
                ChatTranslatorMod.LOGGER.info("翻译结果: '{}' -> '{}'", content, translatedText);
                
                // 获取消息发送者
                String sender = messageSenders.getOrDefault(content, "未知玩家");
                messageSenders.remove(content); // 使用后移除，避免内存泄漏
                
                MutableText translatedMessage = createTranslatedMessage(content, translatedText, sender);
                
                if (translationDelay > 0) {
                    SCHEDULER.schedule(() -> {
                        MinecraftClient.getInstance().execute(() -> {
                            sendTranslationToChat(translatedMessage);
                        });
                    }, translationDelay, TimeUnit.MILLISECONDS);
                } else {
                    MinecraftClient.getInstance().execute(() -> {
                        sendTranslationToChat(translatedMessage);
                    });
                }
            } else {
                ChatTranslatorMod.LOGGER.info("跳过翻译: '{}'", content);
            }
        }).exceptionally(e -> {
            // 从待处理集合中移除
            pendingTranslations.remove(content);
            
            ChatTranslatorMod.LOGGER.error("翻译过程中发生错误", e);
            return null;
        });
    }

    private static MutableText createTranslatedMessage(String original, String translated, String sender) {
        MutableText messageText;
        
        if (ModConfig.shouldShowOriginalMessage()) {
            // 显示原文和译文，同时显示发送者
            messageText = Text.literal("<" + sender + "> ").formatted(Formatting.AQUA)
                    .append(Text.literal("[原文] ").formatted(Formatting.GRAY))
                    .append(Text.literal(original).formatted(Formatting.WHITE))
                    .append(Text.literal("\n<" + sender + "> ").formatted(Formatting.AQUA))
                    .append(Text.literal("[译文] ").formatted(Formatting.GOLD))
                    .append(Text.literal(translated).formatted(Formatting.WHITE));
        } else {
            // 仅显示翻译，但也显示发送者
            messageText = Text.literal("<" + sender + "> ").formatted(Formatting.AQUA)
                    .append(Text.literal("[译] ").formatted(Formatting.GOLD))
                    .append(Text.literal(translated).formatted(Formatting.WHITE));
        }
        
        return messageText;
    }

    private static void sendTranslationToChat(Text translatedMessage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            ChatTranslatorMod.LOGGER.info("发送翻译消息到聊天框: '{}'", translatedMessage.getString());
            client.inGameHud.getChatHud().addMessage(translatedMessage);
        } else {
            ChatTranslatorMod.LOGGER.warn("无法发送翻译消息：玩家对象为空");
        }
    }
} 