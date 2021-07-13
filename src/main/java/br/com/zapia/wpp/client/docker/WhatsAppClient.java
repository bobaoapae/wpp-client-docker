package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.api.model.payloads.StatsResponse;
import br.com.zapia.wpp.client.docker.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

public class WhatsAppClient {

    private static final Logger logger = Logger.getLogger(WhatsAppClient.class.getName());

    private final Runnable onInit;
    private final Consumer<String> onNeedQrCode;
    private final Consumer<DriverState> onUpdateDriverState;
    private final Consumer<Throwable> onError;
    private final Runnable onWsConnect;
    private final OnWsDisconnect onWsDisconnect;
    private final Consumer<Integer> onLowBattery;
    private final Runnable onPhoneDisconnect;
    private final Consumer<Long> onPing;
    private final Function<Runnable, Runnable> runnableFactory;
    private final Function<Callable, Callable> callableFactory;
    private final Function<Runnable, Thread> threadFactory;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;

    private WhatsAppWsClient whatsAppWsClient;
    private final BaseConfig baseConfig;
    private String localPort;

    private boolean successConnect;
    private long ping;
    private final ObjectMapper objectMapper;
    private ScheduledFuture<?> pingFuture;

    public WhatsAppClient(BaseConfig baseConfig, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Consumer<Integer> onLowBattery, Runnable onPhoneDisconnect, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory) {
        this.baseConfig = baseConfig;
        this.onInit = onInit;
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
        this.onLowBattery = onLowBattery;
        this.onPhoneDisconnect = onPhoneDisconnect;
        this.onPing = onPing;
        this.onWsConnect = onWsConnect;
        this.onWsDisconnect = (code, reason, remote) -> {
            if (successConnect) {
                onWsDisconnect.run(code, reason, remote);
            }
        };
        this.runnableFactory = runnableFactory;
        this.callableFactory = callableFactory;
        this.threadFactory = threadFactory;
        this.executorService = Executors.newCachedThreadPool(r -> threadFactory.apply(r));
        this.scheduledExecutorService = Executors.newScheduledThreadPool(20, r -> threadFactory.apply(r));
        this.objectMapper = new ObjectMapper();
    }

    public CompletableFuture<Boolean> start() {
        return stop().thenCompose(unused -> {
            return baseConfig.getWsClient(this, onInit, onNeedQrCode, onUpdateDriverState, onError, onLowBattery, onPhoneDisconnect, onWsConnect, onWsDisconnect, onPing, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService).thenApply(whatsAppWsClient1 -> {
                if (whatsAppWsClient1 != null) {
                    whatsAppWsClient = whatsAppWsClient1;
                    successConnect = true;
                    pingFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
                        baseConfig.ping(executorService);
                    }, 0, 10, TimeUnit.SECONDS);
                    return true;
                }
                return false;
            });
        });
    }

    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            successConnect = false;
            if (pingFuture != null) {
                pingFuture.cancel(true);
            }
            baseConfig.stop();
        }, executorService);
    }

    public CompletableFuture<StatsResponse> getStats() {
        return whatsAppWsClient.getStats();
    }

    public void addNewChatListener(Consumer<Chat> chatConsumer) {
        whatsAppWsClient.addNewChatListener(chatConsumer);
    }

    public void addUpdateChatListener(Consumer<Chat> chatConsumer) {
        whatsAppWsClient.addUpdateChatListener(chatConsumer);
    }

    public void addRemoveChatListener(Consumer<Chat> chatConsumer) {
        whatsAppWsClient.addRemoveChatListener(chatConsumer);
    }

    public void addNewMessageListener(Consumer<List<Message>> messageConsumer) {
        whatsAppWsClient.addNewMessageListener(messageConsumer);
    }

    public void addUpdateMessageListener(Consumer<List<Message>> messageConsumer) {
        whatsAppWsClient.addUpdateMessageListener(messageConsumer);
    }

    public void addRemoveMessageListener(Consumer<List<Message>> messageConsumer) {
        whatsAppWsClient.addRemoveMessageListener(messageConsumer);
    }

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, boolean includeMe, Consumer<List<Message>> messageConsumer, EventType eventType, String... properties) {
        return whatsAppWsClient.addChatMessageListener(chatId, includeMe, messageConsumer, eventType, properties);
    }

    public CompletableFuture<Chat> findChatById(String id) {
        return whatsAppWsClient.findChatById(id);
    }

    public CompletableFuture<Chat> findChatByNumber(String number) {
        return whatsAppWsClient.findChatByNumber(number);
    }

    public CompletableFuture<List<Chat>> getAllChats() {
        return whatsAppWsClient.getAllChats();
    }

    public CompletableFuture<List<Contact>> getAllContacts() {
        return whatsAppWsClient.getAllContacts();
    }

    public CompletableFuture<List<QuickReply>> getAllQuickReplies() {
        return whatsAppWsClient.getAllQuickReplies();
    }

    public CompletableFuture<Message> findMessage(String id) {
        return whatsAppWsClient.findMessage(id);
    }

    public CompletableFuture<Message> sendMessage(String chatId, String text) {
        return sendMessage(chatId, "", text);
    }

    public CompletableFuture<Message> sendMessage(String chatId, String quotedId, String text) {
        SendMessageRequest sendMessageRequest = new SendMessageRequest();
        sendMessageRequest.setMessage(text);
        sendMessageRequest.setChatId(chatId);
        sendMessageRequest.setQuotedMsg(quotedId);
        return sendMessage(sendMessageRequest);
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, File file) {
        return sendMessage(chatId, "", file, "");
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, File file, String caption) {
        return sendMessage(chatId, "", file, caption);
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, File file) {
        return sendMessage(chatId, quotedId, file, "");
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, File file, String caption) {
        return sendMessage(chatId, quotedId, file, file.getName(), caption);
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, File file, String fileName, String caption) {
        return whatsAppWsClient.uploadFile(fileName, file).thenCompose(s -> {
            return sendMessage(chatId, quotedId, s, caption).thenApply(message -> {
                return message;
            });
        });
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, String fileBase64, String fileName, String caption) {
        return whatsAppWsClient.uploadFile(fileName, fileBase64).thenCompose(s -> {
            return sendMessage(chatId, quotedId, s, caption).thenApply(message -> {
                return message;
            });
        });
    }

    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, String uploadedUUID, String caption) {
        SendMessageRequest sendMessageRequest = new SendMessageRequest();
        sendMessageRequest.setMessage(caption);
        sendMessageRequest.setChatId(chatId);
        sendMessageRequest.setQuotedMsg(quotedId);
        sendMessageRequest.setFileUUID(uploadedUUID);
        return sendMessage(sendMessageRequest).thenApply(message -> {
            return (MediaMessage) message;
        });
    }

    public CompletableFuture<Message> sendMessage(SendMessageRequest sendMessageRequest) {
        return whatsAppWsClient.sendMessage(sendMessageRequest);
    }

    public CompletableFuture<File> downloadMediaMessage(String msgId) {
        return whatsAppWsClient.downloadMediaMessage(msgId);
    }

    public CompletableFuture<File> getProfilePic(String contactId) {
        return getProfilePic(contactId, false);
    }

    public CompletableFuture<File> getProfilePic(String contactId, boolean full) {
        return whatsAppWsClient.getProfilePic(contactId, full);
    }

    public CompletableFuture<Boolean> seeChat(String chatId) {
        return whatsAppWsClient.seeChat(chatId);
    }

    public CompletableFuture<Boolean> pinChat(String chatId) {
        return whatsAppWsClient.pinChat(chatId);
    }

    public CompletableFuture<Boolean> unPinChat(String chatId) {
        return whatsAppWsClient.unPinChat(chatId);
    }

    public CompletableFuture<Boolean> markChatComposing(String chatId) {
        return whatsAppWsClient.markChatComposing(chatId);
    }

    public CompletableFuture<Boolean> markChatPaused(String chatId) {
        return whatsAppWsClient.markChatPaused(chatId);
    }

    public CompletableFuture<Boolean> markChatRead(String chatId) {
        return whatsAppWsClient.markChatRead(chatId);
    }

    public CompletableFuture<Boolean> markChatRecording(String chatId) {
        return whatsAppWsClient.markChatRecording(chatId);
    }

    public CompletableFuture<Boolean> markChatUnRead(String chatId) {
        return whatsAppWsClient.markChatUnRead(chatId);
    }

    public CompletableFuture<Boolean> markMessagePlayed(String msgId) {
        return whatsAppWsClient.markMessagePlayed(msgId);
    }

    public CompletableFuture<Boolean> subscribeChatPresence(String chatId) {
        return whatsAppWsClient.subscribeChatPresence(chatId);
    }

    public CompletableFuture<List<Message>> loadEarlyMessagesChat(String chatId) {
        return whatsAppWsClient.loadEarlyMessagesChat(chatId);
    }

    public CompletableFuture<Boolean> deleteChat(String chatId) {
        return whatsAppWsClient.deleteChat(chatId);
    }

    public CompletableFuture<Boolean> clearChatMessages(String chatId, boolean keepFavorites) {
        return whatsAppWsClient.clearChatMessages(chatId, keepFavorites);
    }

    public CompletableFuture<Boolean> deleteMessage(String msgId, boolean fromAll) {
        return whatsAppWsClient.deleteMessage(msgId, fromAll);
    }

    public CompletableFuture<SelfInfo> getSelfInfo() {
        return whatsAppWsClient.getSelfInfo();
    }

    public CompletableFuture<DriverState> getDriverState() {
        if (whatsAppWsClient == null || !whatsAppWsClient.isOpen()) {
            return CompletableFuture.completedFuture(DriverState.UNLOADED);
        }
        return whatsAppWsClient.getDriverState();
    }

    public CompletableFuture<Boolean> sendPresenceUnavailable() {
        return whatsAppWsClient.sendPresenceUnavailable();
    }

    public CompletableFuture<Boolean> sendPresenceAvailable() {
        return whatsAppWsClient.sendPresenceAvailable();
    }

    public CompletableFuture<Boolean> forwardMessages(String[] chatIds, String[] msgIds) {
        return whatsAppWsClient.forwardMessages(chatIds, msgIds);
    }

    public CompletableFuture<GroupInviteLinkInfo> findGroupInviteInfo(String inviteCode) {
        return whatsAppWsClient.findGroupInviteInfo(inviteCode);
    }

    public CompletableFuture<Boolean> joinGroup(String inviteCode) {
        return whatsAppWsClient.joinGroup(inviteCode);
    }

    public CompletableFuture<String> getQrCode() {
        return whatsAppWsClient.getQrCode();
    }

    public CompletableFuture<Boolean> logout() {
        return whatsAppWsClient.logout();
    }

    public int getRemotePort() {
        return whatsAppWsClient.getRemotePort();
    }

    public String getRemoteEndPoint() {
        return whatsAppWsClient.getRemoteEndPoint();
    }

    public boolean isOpen() {
        return whatsAppWsClient != null && whatsAppWsClient.isOpen();
    }
}
