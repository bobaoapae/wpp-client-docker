package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.DeleteMessageRequest;
import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequestPayLoad;
import br.com.zapia.wpp.api.model.payloads.WebSocketResponse;
import br.com.zapia.wpp.client.docker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WhatsAppClient {

    private static Logger logger = Logger.getLogger(WhatsAppClient.class.getName());

    private Runnable onInit;
    private Consumer<String> onNeedQrCode;
    private Consumer<DriverState> onUpdateDriverState;
    private Consumer<Throwable> onError;
    private Function<Runnable, Runnable> runnableFactory;
    private Function<Callable, Callable> callableFactory;
    private Function<Runnable, Thread> threadFactory;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    private WhatsAppWsClient whatsAppWsClient;
    private String identity;
    private DockerClientConfig config;
    private DockerClient dockerClient;
    private String localPort;

    private ObjectMapper objectMapper;
    private Map<String, List<Chat>> chatsAutoUpdate;
    private Map<String, List<Message>> messagesAutoUpdate;

    public WhatsAppClient(String dockerEndPoint, String identity, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory) {
        this.identity = identity;
        this.onInit = () -> {
            onInit();
            onInit.run();
        };
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
        this.runnableFactory = runnableFactory;
        this.callableFactory = callableFactory;
        this.threadFactory = threadFactory;
        this.config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://" + dockerEndPoint + ":2375")
                .withDockerTlsVerify(false)
                .build();
        this.dockerClient = DockerClientBuilder.getInstance(config)
                .build();
        this.executorService = Executors.newCachedThreadPool(r -> threadFactory.apply(r));
        this.scheduledExecutorService = Executors.newScheduledThreadPool(20, r -> threadFactory.apply(r));
        this.objectMapper = new ObjectMapper();
        this.chatsAutoUpdate = new ConcurrentHashMap<>();
        this.messagesAutoUpdate = new ConcurrentHashMap<>();
    }

    private void onInit() {
        addUpdateChatListener(chat -> {
            if (chatsAutoUpdate.containsKey(chat.getId())) {
                List<Chat> chats = chatsAutoUpdate.get(chat.getId());
                for (Chat chat1 : chats) {
                    chat1.update(chat);
                }
            }
        });
        addUpdateMessageListener(message -> {
            if (messagesAutoUpdate.containsKey(message.getId())) {
                List<Message> messages = messagesAutoUpdate.get(message.getId());
                for (Message message1 : messages) {
                    message1.update(message);
                }
            }
            if (messagesAutoUpdate.containsKey(message.getOldId())) {
                List<Message> messages = messagesAutoUpdate.get(message.getOldId());
                for (Message message1 : messages) {
                    message1.update(message);
                }
            }
        });
    }

    public CompletableFuture<Boolean> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Container> containerResult = dockerClient.listContainersCmd()
                        .withShowAll(true)
                        .withNameFilter(Arrays.asList(identity))
                        .exec();
                String containerId;
                if (containerResult.size() == 1) {
                    containerId = containerResult.get(0).getId();
                    if (dockerClient.inspectContainerCmd(containerId).exec().getState().getRunning()) {
                        dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
                    }
                } else {
                    CreateContainerCmd containerCmd = dockerClient.createContainerCmd("whatsapp-api");
                    containerCmd.withName(identity);
                    containerCmd.withHostConfig(HostConfig.newHostConfig()
                            .withPublishAllPorts(true)
                            .withMemory(1024L * 1024L * 700L));
                    CreateContainerResponse exec = containerCmd.exec();
                    containerId = exec.getId();
                }
                InspectContainerResponse exec = dockerClient.inspectContainerCmd(containerId).exec();
                if (!exec.getState().getRunning()) {
                    dockerClient.startContainerCmd(containerId).exec();
                }
                dockerClient.waitContainerCmd(containerId);
                exec = dockerClient.inspectContainerCmd(containerId).exec();
                Map<ExposedPort, Ports.Binding[]> bindings = exec.getNetworkSettings().getPorts().getBindings();
                localPort = "";
                for (Map.Entry<ExposedPort, Ports.Binding[]> exposedPortEntry : bindings.entrySet()) {
                    if (exposedPortEntry.getKey().getPort() == 1100) {
                        localPort = exposedPortEntry.getValue()[0].getHostPortSpec();
                    }
                }
                if (!localPort.isEmpty()) {
                    boolean flag;
                    int tries = 0;
                    do {
                        whatsAppWsClient = new WhatsAppWsClient(URI.create("ws://localhost:" + localPort + "/api/ws"), this, onInit, onNeedQrCode, onUpdateDriverState, onError, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService);
                        flag = whatsAppWsClient.connectBlocking(1, TimeUnit.MINUTES);
                        tries++;
                        Thread.sleep(100);
                    } while (!flag && tries <= 600);
                    return flag;
                } else {
                    return false;
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Start Docker Container", e);
                return false;
            }
        });
    }

    public CompletableFuture<WebSocketResponse> sendWsMessage(WebSocketRequestPayLoad payLoad) {
        return whatsAppWsClient.sendWsMessage(payLoad);
    }

    public void addChatAutoUpdate(Chat chat) {
        if (!chatsAutoUpdate.containsKey(chat.getId())) {
            chatsAutoUpdate.put(chat.getId(), new CopyOnWriteArrayList<>());
        }
        chatsAutoUpdate.get(chat.getId()).add(chat);
    }

    public void addMessageAutoUpdate(Message message) {
        if (!messagesAutoUpdate.containsKey(message.getId())) {
            messagesAutoUpdate.put(message.getId(), new CopyOnWriteArrayList<>());
        }
        messagesAutoUpdate.get(message.getId()).add(message);
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

    public void addNewMessageListener(Consumer<Message> messageConsumer) {
        whatsAppWsClient.addNewMessageListener(messageConsumer);
    }

    public void addUpdateMessageListener(Consumer<Message> messageConsumer) {
        whatsAppWsClient.addUpdateMessageListener(messageConsumer);
    }

    public void addRemoveMessageListener(Consumer<Message> messageConsumer) {
        whatsAppWsClient.addRemoveMessageListener(messageConsumer);
    }

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, Consumer<Message> messageConsumer, EventType eventType, String... properties) {
        return addChatMessageListener(chatId, false, messageConsumer, eventType, properties);
    }

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, boolean includeMe, Consumer<Message> messageConsumer, EventType eventType, String... properties) {
        return whatsAppWsClient.addChatMessageListener(chatId, includeMe, messageConsumer, eventType, properties);
    }

    public CompletableFuture<Chat> findChatById(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findChat");
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Chat.build(this, (JsonNode) webSocketResponse.getResponse());
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
                return Chat.build(this, (JsonNode) webSocketResponse.getResponse());
            } else {
                return null;
            }
        });
    }

    public CompletableFuture<Message> findMessage(String id) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("findMessage");
        payLoad.setPayload(id);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(this, (JsonNode) webSocketResponse.getResponse());
            } else {
                return null;
            }
        });
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
        SendMessageRequest sendMessageRequest = new SendMessageRequest();
        sendMessageRequest.setFileName(file.getName());
        sendMessageRequest.setMessage(caption);
        try {
            String contentType = Files.probeContentType(file.toPath());
            byte[] data = Files.readAllBytes(file.toPath());
            String base64str = Base64.getEncoder().encodeToString(data);
            StringBuilder sb = new StringBuilder();
            sb.append("data:");
            sb.append(contentType);
            sb.append(";base64,");
            sb.append(base64str);
            sendMessageRequest.setMedia(sb.toString());
            return sendMessage(sendMessageRequest).thenApply(message -> {
                return (MediaMessage) message;
            });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Message> sendMessage(SendMessageRequest sendMessageRequest) {
        WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
        payLoad.setEvent("sendMessage");
        payLoad.setPayload(sendMessageRequest);
        return sendWsMessage(payLoad).thenApply(webSocketResponse -> {
            if (webSocketResponse.getStatus() == 200) {
                return Message.build(this, (JsonNode) webSocketResponse.getResponse());
            } else {
                throw new RuntimeException(String.valueOf(webSocketResponse.getStatus()));
            }
        });
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
                    messages.add(Message.build(this, node));
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
}
