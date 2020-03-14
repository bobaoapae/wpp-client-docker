package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.AddChatMessageListenerRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequestPayLoad;
import br.com.zapia.wpp.api.model.payloads.WebSocketResponse;
import br.com.zapia.wpp.client.docker.model.Chat;
import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.EventType;
import br.com.zapia.wpp.client.docker.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

class WhatsAppWsClient extends WebSocketClient {

    private Map<UUID, WsMessageSend> wsEvents;
    private Map<UUID, Consumer<Message>> chatsMessageListener;
    private ObjectMapper objectMapper;
    private boolean waitingInit;

    private Runnable onInit;
    private WhatsAppClient whatsAppClient;
    private Consumer<String> onNeedQrCode;
    private Consumer<DriverState> onUpdateDriverState;
    private Consumer<Throwable> onError;
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

    public WhatsAppWsClient(URI serverUri, WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        super(serverUri);
        this.wsEvents = new ConcurrentHashMap<>();
        this.chatsMessageListener = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.onInit = onInit;
        this.whatsAppClient = whatsAppClient;
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
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

    private CompletableFuture<WebSocketResponse> sendWsMessage(WsMessageSend wsMessageSend) {
        try {
            UUID uuid = UUID.randomUUID();
            WebSocketRequest webSocketRequest = new WebSocketRequest();
            webSocketRequest.setTag(uuid.toString());
            if (wsMessageSend.getPayLoad().getPayload() != null && !(wsMessageSend.getPayLoad().getPayload() instanceof String)) {
                wsMessageSend.getPayLoad().setPayload(objectMapper.writeValueAsString(wsMessageSend.getPayLoad().getPayload()));
            }
            webSocketRequest.setWebSocketRequestPayLoad(wsMessageSend.getPayLoad());
            String serialized = objectMapper.writeValueAsString(webSocketRequest);
            send(serialized);
            CompletableFuture<WebSocketResponse> response = wsMessageSend.getWsEvent();
            wsEvents.put(uuid, new WsMessageSend(wsMessageSend.getPayLoad(), response, wsMessageSend.getTries()));
            return response;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void processWsResponse(String tag, String payload) {
        UUID uuid = UUID.fromString(tag);
        if (wsEvents.containsKey(uuid)) {
            WsMessageSend wsMessageSend = wsEvents.remove(uuid);
            try {
                WebSocketResponse response = objectMapper.readValue(payload, WebSocketResponse.class);
                if (response.getResponse() instanceof LinkedHashMap) {
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
                } else if ((response.getStatus() == 500 || response.getStatus() == 424 || response.getStatus() == 429) && wsMessageSend.getTries() < 3) {
                    wsMessageSend.setTries(wsMessageSend.getTries() + 1);
                    onError(new RuntimeException("Response for event {" + wsMessageSend.getPayLoad().getEvent() + "} with tag {" + tag + "} failed with status {" + response.getStatus() + "}, command will be send again in 1 minute, tries remain {" + (3 - wsMessageSend.getTries()) + "}"));
                    scheduledExecutorService.schedule(() -> {
                        sendWsMessage(wsMessageSend);
                    }, 1, TimeUnit.MINUTES);
                } else {
                    wsMessageSend.getWsEvent().completeExceptionally(new RuntimeException("Event {" + wsMessageSend.getPayLoad().getEvent() + "} with tag {" + tag + "} failed with status {" + response.getStatus() + "} and message {" + response.getResponse() + "}"));
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

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, Consumer<Message> messageConsumer, EventType eventType, String... properties) {
        return addChatMessageListener(chatId, false, messageConsumer, eventType, properties);
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


    @Override
    public void onOpen(ServerHandshake serverHandshake) {

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
                this.waitingInit = false;
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
                executorService.submit(runnableFactory.apply(() -> {
                    if (onError != null) {
                        onError.accept(new RuntimeException(split[1]));
                    }
                }));
                break;
            default:
                executorService.submit(runnableFactory.apply(() -> {
                    processWsResponse(split[0], split[1]);
                }));
                break;
        }
    }

    @Override
    public void onClose(int i, String s, boolean b) {

    }

    @Override
    public void onError(Exception e) {
        executorService.submit(runnableFactory.apply(() -> {
            onError.accept(e);
        }));
    }

    private class WsMessageSend {

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
