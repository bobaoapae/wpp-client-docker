package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.WebSocketRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequestPayLoad;
import br.com.zapia.wpp.api.model.payloads.WebSocketResponse;
import br.com.zapia.wpp.client.docker.model.Chat;
import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

class WhatsAppWsClient extends WebSocketClient {

    private Map<UUID, CompletableFuture<WebSocketResponse>> wsEvents;
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

    private List<Consumer<Chat>> newChatListeners;
    private List<Consumer<Chat>> updateChatListeners;
    private List<Consumer<Chat>> removeChatListeners;
    private List<Consumer<Message>> newMessageListeners;
    private List<Consumer<Message>> updateMessageListeners;
    private List<Consumer<Message>> removeMessageListeners;

    public WhatsAppWsClient(URI serverUri, WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService) {
        super(serverUri);
        wsEvents = new ConcurrentHashMap<>();
        objectMapper = new ObjectMapper();
        this.onInit = onInit;
        this.whatsAppClient = whatsAppClient;
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
        this.runnableFactory = runnableFactory;
        this.callableFactory = callableFactory;
        this.threadFactory = threadFactory;
        this.executorService = executorService;
        this.newChatListeners = new CopyOnWriteArrayList<>();
        this.updateChatListeners = new CopyOnWriteArrayList<>();
        this.removeChatListeners = new CopyOnWriteArrayList<>();
        this.newMessageListeners = new CopyOnWriteArrayList<>();
        this.updateMessageListeners = new CopyOnWriteArrayList<>();
        this.removeMessageListeners = new CopyOnWriteArrayList<>();
    }

    public CompletableFuture<WebSocketResponse> sendWsMessage(WebSocketRequestPayLoad payload) {
        try {
            UUID uuid = UUID.randomUUID();
            WebSocketRequest webSocketRequest = new WebSocketRequest();
            webSocketRequest.setTag(uuid.toString());
            webSocketRequest.setWebSocketRequestPayLoad(payload);
            String serialized = objectMapper.writeValueAsString(webSocketRequest);
            send(serialized);
            CompletableFuture<WebSocketResponse> response = new CompletableFuture<>();
            wsEvents.put(uuid, response);
            return response;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void processWsResponse(String tag, String payload) {
        UUID uuid = UUID.fromString(tag);
        if (wsEvents.containsKey(uuid)) {
            CompletableFuture<WebSocketResponse> webSocketResponseCompletableFuture = wsEvents.remove(uuid);
            try {
                webSocketResponseCompletableFuture.complete(objectMapper.readValue(payload, WebSocketResponse.class));
            } catch (IOException e) {
                webSocketResponseCompletableFuture.completeExceptionally(e);
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
                processWsResponse(split[0], split[1]);
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
}
