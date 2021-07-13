package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.handlersWebSocket.EventWebSocket;
import br.com.zapia.wpp.api.model.payloads.*;
import br.com.zapia.wpp.client.docker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.tika.Tika;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

class WhatsAppWsClient extends WebSocketClient {
    private final Map<UUID, WsMessageSend> wsEvents;
    private final Map<UUID, List<WebSocketResponseFrame>> wsPartialEvents;
    private final Map<UUID, Consumer<List<Message>>> chatsMessageListener;
    private final ObjectMapper objectMapper;

    private final Runnable onInit;
    private final WhatsAppClient whatsAppClient;
    private final Consumer<String> onNeedQrCode;
    private final Consumer<DriverState> onUpdateDriverState;
    private final Consumer<Throwable> onError;
    private final OnWsDisconnect onWsDisconnect;
    private final Runnable onWsConnect;
    private final Runnable onPhoneDisconnect;
    private final Consumer<Integer> onLowBattery;
    private final Function<Runnable, Runnable> runnableFactory;
    private final Function<Callable, Callable> callableFactory;
    private final Function<Runnable, Thread> threadFactory;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;

    private final List<Consumer<Chat>> newChatListeners;
    private final List<Consumer<Chat>> updateChatListeners;
    private final List<Consumer<Chat>> removeChatListeners;
    private final List<Consumer<List<Message>>> newMessageListeners;
    private final List<Consumer<List<Message>>> updateMessageListeners;
    private final List<Consumer<List<Message>>> removeMessageListeners;

    private final String endPointAddress;

    protected WhatsAppWsClient(URI serverUri, WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Consumer<Integer> onLowBattery, Runnable onPhoneDisconnect, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        super(serverUri);
        this.onLowBattery = onLowBattery;
        this.onPhoneDisconnect = onPhoneDisconnect;
        this.endPointAddress = uri.getHost();
        this.wsEvents = new ConcurrentHashMap<>();
        this.wsPartialEvents = new ConcurrentHashMap<>();
        this.chatsMessageListener = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.onInit = onInit;
        this.whatsAppClient = whatsAppClient;
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
        this.onWsConnect = onWsConnect;
        this.onWsDisconnect = onWsDisconnect;
        this.runnableFactory = runnableFactory;
        this.callableFactory = callableFactory;
        this.threadFactory = threadFactory;
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.newChatListeners = new CopyOnWriteArrayList<>();
        this.updateChatListeners = new CopyOnWriteArrayList<>();
        this.removeChatListeners = new CopyOnWriteArrayList<>();
        this.newMessageListeners = new CopyOnWriteArrayList<>();
        this.updateMessageListeners = new CopyOnWriteArrayList<>();
        this.removeMessageListeners = new CopyOnWriteArrayList<>();
    }

    protected CompletableFuture<WebSocketResponse> sendWsMessage(WebSocketRequestPayLoad payload) {
        return sendWsMessage(new WsMessageSend(payload, new CompletableFuture<>()));
    }

    protected CompletableFuture<WebSocketResponse> sendWsMessage(WsMessageSend wsMessageSend) {
        try {
            UUID uuid = UUID.randomUUID();
            WebSocketRequest webSocketRequest = new WebSocketRequest();
            webSocketRequest.setTag(uuid.toString());
            if (wsMessageSend.getPayLoad().getPayload() != null && !(wsMessageSend.getPayLoad().getPayload() instanceof String)) {
                wsMessageSend.getPayLoad().setPayload(objectMapper.writeValueAsString(wsMessageSend.getPayLoad().getPayload()));
            }
            webSocketRequest.setWebSocketRequestPayLoad(wsMessageSend.getPayLoad());
            String serialized = objectMapper.writeValueAsString(webSocketRequest);
            CompletableFuture<WebSocketResponse> response = wsMessageSend.getWsEvent();
            response.whenComplete((response1, throwable) -> {
                wsEvents.remove(uuid);
            });
            wsEvents.put(uuid, new WsMessageSend(wsMessageSend.getPayLoad(), response, wsMessageSend.getTries()));
            try {
                send(serialized);
            } catch (Exception e) {
                wsEvents.remove(uuid);
                if (wsMessageSend.getTries() < 3) {
                    wsMessageSend.setTries(wsMessageSend.getTries() + 1);
                    onError(new RuntimeException("Fail on send message to websocket, message wil be send again in 1 minute, tries remain {" + (3 - wsMessageSend.getTries()) + "}"));
                    scheduledExecutorService.schedule(() -> {
                        sendWsMessage(wsMessageSend);
                    }, 1, TimeUnit.MINUTES);
                } else {
                    return CompletableFuture.failedFuture(e);
                }
            }
            return response;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void processWsResponse(String tag, String payload) {
        UUID uuid = UUID.fromString(tag);
        if (wsEvents.containsKey(uuid)) {
            WsMessageSend wsMessageSend = wsEvents.get(uuid);
            try {
                WebSocketResponse response = objectMapper.readValue(payload, WebSocketResponse.class);
                if (response instanceof WebSocketResponseFrame) {
                    if (!wsPartialEvents.containsKey(uuid)) {
                        wsPartialEvents.put(uuid, new CopyOnWriteArrayList<>());
                    }
                    wsPartialEvents.get(uuid).add((WebSocketResponseFrame) response);
                    if (((WebSocketResponseFrame) response).getQtdFrames() == wsPartialEvents.get(uuid).size()) {
                        WebSocketResponse fullResponse = new WebSocketResponse();
                        fullResponse.setStatus(response.getStatus());
                        wsPartialEvents.get(uuid).sort(Comparator.comparingInt(WebSocketResponseFrame::getFrameId));
                        List<WebSocketResponseFrame> webSocketResponseFrames = wsPartialEvents.get(uuid);
                        StringBuilder stringBuilder = new StringBuilder();
                        webSocketResponseFrames.forEach(webSocketResponseFrame -> {
                            stringBuilder.append(webSocketResponseFrame.getResponse());
                        });
                        fullResponse.setResponse(stringBuilder.toString());
                        processWsResponse(wsMessageSend, fullResponse);
                    }
                } else {
                    processWsResponse(wsMessageSend, response);
                }
            } catch (IOException e) {
                wsMessageSend.getWsEvent().completeExceptionally(e);
            }
        } else if (chatsMessageListener.containsKey(uuid)) {
            try {
                chatsMessageListener.get(uuid).accept(Arrays.asList(Message.build(whatsAppClient, objectMapper.readTree(payload))));
            } catch (IOException e) {
                onError(new RuntimeException(e));
            }
        }
    }

    private void processWsResponse(WsMessageSend wsMessageSend, WebSocketResponse response) throws IOException {
        if (response.getResponse() instanceof LinkedHashMap || response.getResponse() instanceof List) {
            response.setResponse(objectMapper.readTree(objectMapper.writeValueAsString(response.getResponse())));
        } else if (response.getResponse() instanceof String) {
            try {
                JsonNode jsonNode = objectMapper.readTree((String) response.getResponse());
                response.setResponse(jsonNode);
            } catch (Exception e) {

            }
        }
        if (response.getStatus() == 200 || response.getStatus() == 201 || response.getStatus() == 404) {
            wsMessageSend.getWsEvent().complete(response);
        } else if ((response.getStatus() == 500 || response.getStatus() == 429) && wsMessageSend.getTries() < 3) {
            wsMessageSend.setTries(wsMessageSend.getTries() + 1);
            onError(new RuntimeException("Response for event {" + wsMessageSend.getPayLoad().getEvent() + "} failed with status {" + response.getStatus() + "} and message {" + response.getResponse() + "}, command will be send again in 1 minute, tries remain {" + (3 - wsMessageSend.getTries()) + "}"));
            scheduledExecutorService.schedule(() -> {
                sendWsMessage(wsMessageSend);
            }, 1, TimeUnit.MINUTES);
        } else {
            wsMessageSend.getWsEvent().completeExceptionally(new RuntimeException("Event {" + wsMessageSend.getPayLoad().getEvent() + "} failed with status {" + response.getStatus() + "} and message {" + response.getResponse() + "}"));
        }
    }

    protected CompletableFuture<StatsResponse> getStats() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int port = getRemotePort();
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url("http://" + endPointAddress + ":" + port + "/api/remoteManagement/stats").get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException(response.body().string());
                    } else {
                        return objectMapper.readValue(response.body().string(), StatsResponse.class);
                    }
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }

    protected void addNewChatListener(Consumer<Chat> chatConsumer) {
        this.newChatListeners.add(chatConsumer);
    }

    protected void addUpdateChatListener(Consumer<Chat> chatConsumer) {
        this.updateChatListeners.add(chatConsumer);
    }

    protected void addRemoveChatListener(Consumer<Chat> chatConsumer) {
        this.removeChatListeners.add(chatConsumer);
    }

    protected void addNewMessageListener(Consumer<List<Message>> messageConsumer) {
        this.newMessageListeners.add(messageConsumer);
    }

    protected void addUpdateMessageListener(Consumer<List<Message>> messageConsumer) {
        this.updateMessageListeners.add(messageConsumer);
    }

    protected void addRemoveMessageListener(Consumer<List<Message>> messageConsumer) {
        this.removeMessageListeners.add(messageConsumer);
    }

    protected CompletableFuture<Boolean> addChatMessageListener(String chatId, boolean includeMe, Consumer<List<Message>> messageConsumer, EventType eventType, String... properties) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.AddChatMessageListener);
        AddChatMessageListenerRequest request = new AddChatMessageListenerRequest();
        request.setChatId(chatId);
        request.setIncludeMe(includeMe);
        request.setEventType(eventType.name());
        request.setProperties(properties);
        payLoad.setPayload(request);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                this.chatsMessageListener.put(UUID.fromString((String) webSocketResponse.getResponse()), messageConsumer);
                return true;
            }

            return false;
        });
    }

    protected CompletableFuture<Chat> findChatById(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.FindChat);
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Chat.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            }

            return null;
        });
    }

    protected CompletableFuture<Chat> findChatByNumber(String number) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.FindChatByNumber);
        payLoad.setPayload(number);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Chat.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            }

            return null;
        });
    }

    protected CompletableFuture<List<Chat>> getAllChats() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetAllChats);
        return sendWsMessage(payLoad).thenApply(response -> {
            List<Chat> chats = new ArrayList<>();
            JsonNode jsonNode = (JsonNode) response.getResponse();
            jsonNode.forEach(jsonNode1 -> {
                chats.add(Chat.build(whatsAppClient, jsonNode1));
            });
            return chats;
        });
    }

    protected CompletableFuture<List<Contact>> getAllContacts() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetAllContacts);
        return sendWsMessage(payLoad).thenApply(response -> {
            List<Contact> contacts = new ArrayList<>();
            JsonNode jsonNode = (JsonNode) response.getResponse();
            jsonNode.forEach(jsonNode1 -> {
                contacts.add(new Contact(whatsAppClient, jsonNode1));
            });
            return contacts;
        });
    }

    protected CompletableFuture<List<QuickReply>> getAllQuickReplies() {
        var payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetAllQuickReplies);
        return sendWsMessage(payLoad).thenApply(response -> {
            var result = new ArrayList<QuickReply>();
            JsonNode jsonNode = (JsonNode) response.getResponse();
            jsonNode.forEach(jsonNode1 -> {
                result.add(new QuickReply(whatsAppClient, jsonNode1));
            });
            return result;
        });
    }

    protected CompletableFuture<Message> findMessage(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.FindMessage);
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            }

            return null;
        });
    }

    protected CompletableFuture<Message> sendMessage(SendMessageRequest sendMessageRequest) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.SendMessage);
        payLoad.setPayload(sendMessageRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            }

            return null;
        });
    }

    protected CompletableFuture<File> downloadMediaMessage(String msgId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.DownloadMedia);
        payLoad.setPayload(msgId);
        return sendWsMessage(payLoad).thenCompose(response -> {
            if (response.getStatus() == 200) {
                return downloadFile((String) response.getResponse());
            }

            return CompletableFuture.completedFuture(null);
        });
    }

    protected CompletableFuture<File> getProfilePic(String contactId, boolean full) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.FindPicture);
        FindPictureRequest findPictureRequest = new FindPictureRequest();
        findPictureRequest.setId(contactId);
        findPictureRequest.setFull(full);
        payLoad.setPayload(findPictureRequest);
        return sendWsMessage(payLoad).thenCompose(response -> {
            if (response.getStatus() == 200) {
                return downloadFile((String) response.getResponse());
            }

            return CompletableFuture.completedFuture(null);
        });
    }

    protected CompletableFuture<File> downloadFile(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int port = getRemotePort();
                URL url = new URL("http://" + endPointAddress + ":" + port + "/api/downloadFile/" + key);
                URLConnection urlConnection = url.openConnection();
                String filename = URLDecoder.decode(urlConnection.getHeaderField("Filename"), StandardCharsets.UTF_8);
                ReadableByteChannel readableByteChannel = Channels.newChannel(urlConnection.getInputStream());
                File tempFile = File.createTempFile(filename + "#", "." + filename.split("\\.", 2)[1]);
                FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
                FileChannel fileChannel = fileOutputStream.getChannel();
                fileChannel
                        .transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                fileChannel.close();
                fileOutputStream.close();
                readableByteChannel.close();
                return tempFile;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }

    protected CompletableFuture<String> uploadFile(String name, String base64) {
        byte[] dearr = Base64.getDecoder().decode(base64);
        try {
            File f = File.createTempFile(name, ".tmp");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(dearr);
            }
            return uploadFile(name, f);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    protected CompletableFuture<String> uploadFile(String name, File file) {
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                RequestBody formBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", name,
                                RequestBody.create(MediaType.parse(new Tika().detect(file)), file))
                        .build();

                int port = getConnection().getRemoteSocketAddress().getPort();


                Request request = new Request.Builder().url("http://" + endPointAddress + ":" + port + "/api/uploadFile/").post(formBody).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        completableFuture.completeExceptionally(new RuntimeException(response.body().string()));
                    } else {
                        completableFuture.complete(response.body().string());
                    }
                }
            } catch (Exception e) {
                completableFuture.completeExceptionally(e);
            }
        });
        return completableFuture;
    }

    protected CompletableFuture<Boolean> seeChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.SeeChat);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> pinChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.PinChat);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> unPinChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.UnPinChat);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> markChatComposing(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkComposing);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> markChatPaused(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkPaused);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> markChatRead(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkRead);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> markChatRecording(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkRecording);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }


    protected CompletableFuture<Boolean> markChatUnRead(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkUnRead);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> markMessagePlayed(String msgId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.MarkPlayed);
        payLoad.setPayload(msgId);
        return sendWsMessage(payLoad).thenApply(response -> {
            return response.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> subscribeChatPresence(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.SubscribePresence);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<List<Message>> loadEarlyMessagesChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.LoadEarly);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            List<Message> messages = new ArrayList<>();
            if (webSocketResponse.getStatus() == 200 && webSocketResponse.getResponse() != null) {
                JsonNode jsonNode = (JsonNode) webSocketResponse.getResponse();
                if (!jsonNode.isEmpty()) {
                    for (JsonNode node : jsonNode) {
                        messages.add(Message.build(whatsAppClient, node));
                    }
                }
            }
            return messages;
        });
    }

    protected CompletableFuture<Boolean> deleteChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.DeleteChat);
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200 || webSocketResponse.getStatus() == 404;
        });
    }

    protected CompletableFuture<Boolean> clearChatMessages(String chatId, boolean keepFavorites) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.ClearChat);
        ClearChatRequest clearChatRequest = new ClearChatRequest();
        clearChatRequest.setChatId(chatId);
        clearChatRequest.setChatId(chatId);
        clearChatRequest.setKeepFavorites(keepFavorites);
        payLoad.setPayload(clearChatRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> deleteMessage(String msgId, boolean fromAll) {
        DeleteMessageRequest deleteMessageRequest = new DeleteMessageRequest();
        deleteMessageRequest.setFromAll(fromAll);
        deleteMessageRequest.setMsgId(msgId);
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.DeleteMessage);
        payLoad.setPayload(deleteMessageRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200 || webSocketResponse.getStatus() == 404;
        });
    }

    protected CompletableFuture<DriverState> getDriverState() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetDriverState);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            JsonNode jsonNode = (JsonNode) webSocketResponse.getResponse();
            return DriverState.valueOf(jsonNode.get("status").asText());
        });
    }

    protected CompletableFuture<SelfInfo> getSelfInfo() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetSelfInfo);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return new SelfInfo(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
        });
    }

    protected CompletableFuture<Boolean> sendPresenceAvailable() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.SendPresenceAvailable);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> sendPresenceUnavailable() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.SendPresenceUnavailable);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<Boolean> forwardMessages(String[] chatIds, String[] msgIds) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.ForwardMessage);
        ForwardMessagesRequest request = new ForwardMessagesRequest();
        request.setIdsChats(chatIds);
        request.setIdsMsgs(msgIds);
        payLoad.setPayload(request);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<GroupInviteLinkInfo> findGroupInviteInfo(String inviteCode) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetGroupInviteInfo);
        if (!inviteCode.toLowerCase().startsWith("https://chat.whatsapp.com/")) {
            payLoad.setPayload("https://chat.whatsapp.com/" + inviteCode);
        } else {
            payLoad.setPayload(inviteCode);
        }
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return new GroupInviteLinkInfo(whatsAppClient, (JsonNode) webSocketResponse.getResponse(), inviteCode.replaceAll("(?i)https://chat.whatsapp.com/", ""));
        });
    }

    protected CompletableFuture<Boolean> joinGroup(String inviteCode) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.JoinGroupByInviteLink);
        if (!inviteCode.toLowerCase().startsWith("https://chat.whatsapp.com/")) {
            payLoad.setPayload("https://chat.whatsapp.com/" + inviteCode);
        } else {
            payLoad.setPayload(inviteCode);
        }
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected CompletableFuture<String> getQrCode() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.GetQrCode);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return (String) webSocketResponse.getResponse();
            }
            return "";
        });
    }

    protected CompletableFuture<Boolean> logout() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent(EventWebSocket.Logout);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    protected Map<UUID, WsMessageSend> getWsEvents() {
        return wsEvents;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        executorService.submit(runnableFactory.apply(onWsConnect));
    }

    @Override
    public void onMessage(String s) {
        String[] split = s.split(",", 2);
        if (split.length > 1) {
            try {
                split[1] = Utils.decompressB64(split[1]);
            } catch (IOException e) {
                onError(e);
            }
        }
        switch (split[0]) {
            case "need-qrcode":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onNeedQrCode != null) {
                        onNeedQrCode.accept(split[1]);
                    }
                }));
                break;
            case "update-state":
                DriverState driverState = DriverState.valueOf(split[1]);
                executorService.submit(runnableFactory.apply(() -> {
                    if (onUpdateDriverState != null) {
                        onUpdateDriverState.accept(driverState);
                    }
                }));
                if (driverState == DriverState.LOGGED) {
                    executorService.submit(runnableFactory.apply(() -> {
                        resetListeners();
                        if (onInit != null) {
                            onInit.run();
                        }
                    }));
                }
                break;
            case "new-chat":
                for (Consumer<Chat> newChatListener : newChatListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            newChatListener.accept(Chat.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "update-chat":
                for (Consumer<Chat> updateChatListener : updateChatListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            updateChatListener.accept(Chat.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "remove-chat":
                for (Consumer<Chat> removeChatListener : removeChatListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            removeChatListener.accept(Chat.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "remove-msg":
                for (var removeMessageListener : removeMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            var jsonNode = objectMapper.readTree(split[1]);
                            removeMessageListener.accept(buildMessages(jsonNode));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "new-msg":
                for (var newMessageListener : newMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            var jsonNode = objectMapper.readTree(split[1]);
                            newMessageListener.accept(buildMessages(jsonNode));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "update-msg":
                for (var updateMessageListener : updateMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            var jsonNode = objectMapper.readTree(split[1]);
                            updateMessageListener.accept(buildMessages(jsonNode));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "low-battery":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onLowBattery != null) {
                        onLowBattery.accept(Integer.valueOf(split[1]));
                    }
                }));
                break;
            case "disconnect":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onPhoneDisconnect != null) {
                        onPhoneDisconnect.run();
                    }
                }));
                break;
            case "error":
                onError(new RuntimeException(split[1]));
                break;
            default:
                executorService.submit(runnableFactory.apply(() -> {
                    processWsResponse(split[0], split[1]);
                }));
                break;
        }
    }

    private List<Message> buildMessages(JsonNode jsonNode) {
        var msgs = new ArrayList<Message>();
        if (jsonNode.isArray()) {
            jsonNode.forEach(jsonNode1 -> {
                msgs.add(Message.build(whatsAppClient, jsonNode1));
            });
        } else {
            msgs.add(Message.build(whatsAppClient, jsonNode));
        }
        return msgs;
    }

    protected void resetListeners() {
        newChatListeners.clear();
        updateChatListeners.clear();
        removeChatListeners.clear();
        removeMessageListeners.clear();
        newMessageListeners.clear();
        updateMessageListeners.clear();
    }

    protected int getRemotePort() {
        return getConnection().getRemoteSocketAddress().getPort();
    }

    protected String getRemoteEndPoint() {
        return endPointAddress;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        executorService.submit(runnableFactory.apply(() -> {
            onWsDisconnect.run(code, reason, remote);
        }));
    }

    @Override
    public void onError(Exception e) {
        executorService.submit(runnableFactory.apply(() -> {
            onError.accept(e);
        }));
    }

    class WsMessageSend {

        private WebSocketRequestPayLoad payLoad;
        private CompletableFuture<WebSocketResponse> wsEvent;
        private int tries;

        protected WsMessageSend() {
        }

        protected WsMessageSend(WebSocketRequestPayLoad payLoad) {
            this.payLoad = payLoad;
        }

        protected WsMessageSend(WebSocketRequestPayLoad payLoad, CompletableFuture<WebSocketResponse> wsEvent) {
            this.payLoad = payLoad;
            this.wsEvent = wsEvent;
        }

        protected WsMessageSend(WebSocketRequestPayLoad payLoad, CompletableFuture<WebSocketResponse> wsEvent, int tries) {
            this.payLoad = payLoad;
            this.wsEvent = wsEvent;
            this.tries = tries;
        }

        protected WebSocketRequestPayLoad getPayLoad() {
            return payLoad;
        }

        protected CompletableFuture<WebSocketResponse> getWsEvent() {
            return wsEvent;
        }

        protected int getTries() {
            return tries;
        }

        protected WsMessageSend setTries(int tries) {
            this.tries = tries;
            return this;
        }
    }
}
