package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.*;
import br.com.zapia.wpp.client.docker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.tika.Tika;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

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

    private Map<UUID, WsMessageSend> wsEvents;
    private Map<UUID, List<WebSocketResponseFrame>> wsPartialEvents;
    private Map<UUID, Consumer<Message>> chatsMessageListener;
    private ObjectMapper objectMapper;

    private Runnable onInit;
    private WhatsAppClient whatsAppClient;
    private Consumer<String> onNeedQrCode;
    private Consumer<DriverState> onUpdateDriverState;
    private Consumer<Throwable> onError;
    private OnWsDisconnect onWsDisconnect;
    private Runnable onWsConnect;
    private Function<Runnable, Runnable> runnableFactory;
    private Function<Callable, Callable> callableFactory;
    private Function<Runnable, Thread> threadFactory;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    private List<Consumer<Chat>> newChatListeners;
    private List<Consumer<Chat>> updateChatListeners;
    private List<Consumer<Chat>> removeChatListeners;
    private List<Consumer<Message>> newMessageListeners;
    private List<Consumer<Message>> updateMessageListeners;
    private List<Consumer<Message>> removeMessageListeners;

    public WhatsAppWsClient(URI serverUri, WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        super(serverUri);
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

    public CompletableFuture<WebSocketResponse> sendWsMessage(WebSocketRequestPayLoad payload) {
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
                        processWsResponse(wsMessageSend, response);
                    }
                } else {
                    processWsResponse(wsMessageSend, response);
                }
            } catch (IOException e) {
                wsMessageSend.getWsEvent().completeExceptionally(e);
            }
        } else if (chatsMessageListener.containsKey(uuid)) {
            try {
                chatsMessageListener.get(uuid).accept(Message.build(whatsAppClient, objectMapper.readTree(payload)));
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
        if (response.getStatus() == 200 || response.getStatus() == 201) {
            wsMessageSend.getWsEvent().complete(response);
        } else if ((response.getStatus() == 500 || response.getStatus() == 429) && wsMessageSend.getTries() < 3) {
            wsMessageSend.setTries(wsMessageSend.getTries() + 1);
            onError(new RuntimeException("Response for event {" + wsMessageSend.getPayLoad().getEvent() + "} failed with status {" + response.getStatus() + "}, command will be send again in 1 minute, tries remain {" + (3 - wsMessageSend.getTries()) + "}"));
            scheduledExecutorService.schedule(() -> {
                sendWsMessage(wsMessageSend);
            }, 1, TimeUnit.MINUTES);
        } else {
            wsMessageSend.getWsEvent().completeExceptionally(new RuntimeException("Event {" + wsMessageSend.getPayLoad().getEvent() + "} failed with status {" + response.getStatus() + "} and message {" + response.getResponse() + "}"));
        }
    }

    public void addNewChatListener(Consumer<Chat> chatConsumer) {
        this.newChatListeners.add(chatConsumer);
    }

    public void addUpdateChatListener(Consumer<Chat> chatConsumer) {
        this.updateChatListeners.add(chatConsumer);
    }

    public void addRemoveChatListener(Consumer<Chat> chatConsumer) {
        this.removeChatListeners.add(chatConsumer);
    }

    public void addNewMessageListener(Consumer<Message> messageConsumer) {
        this.newMessageListeners.add(messageConsumer);
    }

    public void addUpdateMessageListener(Consumer<Message> messageConsumer) {
        this.updateMessageListeners.add(messageConsumer);
    }

    public void addRemoveMessageListener(Consumer<Message> messageConsumer) {
        this.removeMessageListeners.add(messageConsumer);
    }

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, boolean includeMe, Consumer<Message> messageConsumer, EventType eventType, String... properties) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("addChatMessageListener");
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
            } else {
                return false;
            }
        });
    }

    public CompletableFuture<Chat> findChatById(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findChat");
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Chat.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            } else {
                return null;
            }
        });
    }

    public CompletableFuture<Chat> findChatByNumber(String number) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findChatByNumber");
        payLoad.setPayload(number);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Chat.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            } else {
                return null;
            }
        });
    }

    public CompletableFuture<List<Chat>> getAllChats() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("getAllChats");
        return sendWsMessage(payLoad).thenApply(response -> {
            List<Chat> chats = new ArrayList<>();
            JsonNode jsonNode = (JsonNode) response.getResponse();
            jsonNode.forEach(jsonNode1 -> {
                chats.add(Chat.build(whatsAppClient, jsonNode1));
            });
            return chats;
        });
    }

    public CompletableFuture<List<Contact>> getAllContacts() {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("getAllContacts");
        return sendWsMessage(payLoad).thenApply(response -> {
            List<Contact> contacts = new ArrayList<>();
            JsonNode jsonNode = (JsonNode) response.getResponse();
            jsonNode.forEach(jsonNode1 -> {
                contacts.add(new Contact(whatsAppClient, jsonNode1));
            });
            return contacts;
        });
    }

    public CompletableFuture<Message> findMessage(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findMessage");
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            } else {
                return null;
            }
        });
    }

    public CompletableFuture<Message> sendMessage(SendMessageRequest sendMessageRequest) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("sendMessage");
        payLoad.setPayload(sendMessageRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(whatsAppClient, (JsonNode) webSocketResponse.getResponse());
            } else {
                throw new RuntimeException(String.valueOf(webSocketResponse.getStatus()));
            }
        });
    }

    public CompletableFuture<File> downloadMediaMessage(String msgId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("downloadMedia");
        payLoad.setPayload(msgId);
        return sendWsMessage(payLoad).thenCompose(response -> {
            return downloadFile((String) response.getResponse());
        });
    }

    public CompletableFuture<File> getProfilePic(String contactId, boolean full) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findPicture");
        FindPictureRequest findPictureRequest = new FindPictureRequest();
        findPictureRequest.setId(contactId);
        findPictureRequest.setFull(full);
        payLoad.setPayload(findPictureRequest);
        return sendWsMessage(payLoad).thenCompose(response -> {
            return downloadFile((String) response.getResponse());
        });
    }

    public CompletableFuture<File> downloadFile(String key) {
        CompletableFuture<File> completableFuture = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                int port = getConnection().getRemoteSocketAddress().getPort();
                URL url = new URL("http://localhost:" + port + "/api/downloadFile/" + key);
                URLConnection urlConnection = url.openConnection();
                String filename = URLDecoder.decode(urlConnection.getHeaderField("Filename"), StandardCharsets.UTF_8);
                ReadableByteChannel readableByteChannel = Channels.newChannel(urlConnection.getInputStream());
                File tempFile = File.createTempFile(filename + "#", "." + filename.split(".", 2)[1]);
                FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
                FileChannel fileChannel = fileOutputStream.getChannel();
                fileChannel
                        .transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                fileChannel.close();
                fileOutputStream.close();
                readableByteChannel.close();
                completableFuture.complete(tempFile);
            } catch (Exception e) {
                completableFuture.completeExceptionally(e);
            }
        });
        return completableFuture;
    }

    public CompletableFuture<String> uploadFile(String name, String base64) {
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

    public CompletableFuture<String> uploadFile(String name, File file) {
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                RequestBody formBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", name,
                                RequestBody.create(file, MediaType.parse(new Tika().detect(file))))
                        .build();

                int port = getConnection().getRemoteSocketAddress().getPort();


                Request request = new Request.Builder().url("http://localhost:" + port + "/api/uploadFile/").post(formBody).build();

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

    public CompletableFuture<Boolean> seeChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("seeChat");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> pinChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("pinChat");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> unPinChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("unPinChat");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> markChatComposing(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("markComposing");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> markChatPaused(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("markPaused");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> markChatRecording(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("markRecording");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> markMessagePlayed(String msgId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("markPlayed");
        payLoad.setPayload(msgId);
        return sendWsMessage(payLoad).thenApply(response -> {
            return response.getStatus() == 200;
        });
    }

    public CompletableFuture<Boolean> subscribeChatPresence(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("subscribePresence");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200;
        });
    }

    public CompletableFuture<List<Message>> loadEarlyMessagesChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("loadEarly");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            List<Message> messages = new ArrayList<>();
            if (webSocketResponse.getStatus() == 200) {
                JsonNode jsonNode = (JsonNode) webSocketResponse.getResponse();
                for (JsonNode node : jsonNode) {
                    messages.add(Message.build(whatsAppClient, node));
                }
            }
            return messages;
        });
    }

    public CompletableFuture<Boolean> deleteChat(String chatId) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("deleteChat");
        payLoad.setPayload(chatId);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200 || webSocketResponse.getStatus() == 404;
        });
    }

    public CompletableFuture<Boolean> deleteMessage(String msgId, boolean fromAll) {
        DeleteMessageRequest deleteMessageRequest = new DeleteMessageRequest();
        deleteMessageRequest.setFromAll(fromAll);
        deleteMessageRequest.setMsgId(msgId);
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("deleteMessage");
        payLoad.setPayload(deleteMessageRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            return webSocketResponse.getStatus() == 200 || webSocketResponse.getStatus() == 404;
        });
    }

    public Map<UUID, WsMessageSend> getWsEvents() {
        return wsEvents;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        executorService.submit(runnableFactory.apply(onWsConnect));
    }

    @Override
    public void onMessage(String s) {
        String[] split = s.split(",", 2);
        switch (split[0]) {
            case "need-qrcode":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onNeedQrCode != null) {
                        onNeedQrCode.accept(split[1]);
                    }
                }));
                break;
            case "update-estado":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onUpdateDriverState != null) {
                        onUpdateDriverState.accept(DriverState.valueOf(split[1]));
                    }
                }));
                break;
            case "init":
                executorService.submit(runnableFactory.apply(() -> {
                    if (onInit != null) {
                        onInit.run();
                    }
                }));
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
                for (Consumer<Message> removeMessageListener : removeMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            removeMessageListener.accept(Message.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "new-msg":
                for (Consumer<Message> newMessageListener : newMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            newMessageListener.accept(Message.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
                break;
            case "update-msg":
                for (Consumer<Message> updateMessageListener : updateMessageListeners) {
                    executorService.submit(runnableFactory.apply(() -> {
                        try {
                            updateMessageListener.accept(Message.build(whatsAppClient, objectMapper.readTree(split[1])));
                        } catch (IOException e) {
                            onError(e);
                        }
                    }));
                }
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

        public WsMessageSend() {
        }

        public WsMessageSend(WebSocketRequestPayLoad payLoad) {
            this.payLoad = payLoad;
        }

        public WsMessageSend(WebSocketRequestPayLoad payLoad, CompletableFuture<WebSocketResponse> wsEvent) {
            this.payLoad = payLoad;
            this.wsEvent = wsEvent;
        }

        public WsMessageSend(WebSocketRequestPayLoad payLoad, CompletableFuture<WebSocketResponse> wsEvent, int tries) {
            this.payLoad = payLoad;
            this.wsEvent = wsEvent;
            this.tries = tries;
        }

        public WebSocketRequestPayLoad getPayLoad() {
            return payLoad;
        }

        public CompletableFuture<WebSocketResponse> getWsEvent() {
            return wsEvent;
        }

        public int getTries() {
            return tries;
        }

        public WsMessageSend setTries(int tries) {
            this.tries = tries;
            return this;
        }
    }
}
