package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequestPayLoad;
import br.com.zapia.wpp.client.docker.model.*;
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
    private Runnable onWsConnect;
    private OnWsDisconnect onWsDisconnect;
    private Consumer<Long> onPing;
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

    private boolean successConnect;
    private long ping;
    private ObjectMapper objectMapper;
    private Map<String, List<Chat>> chatsAutoUpdate;
    private Map<String, List<Message>> messagesAutoUpdate;

    public WhatsAppClient(String dockerEndPoint, String identity, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory) {
        this.identity = identity;
        this.onInit = () -> {
            onInit();
            onInit.run();
        };
        this.onNeedQrCode = onNeedQrCode;
        this.onUpdateDriverState = onUpdateDriverState;
        this.onError = onError;
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
        scheduledExecutorService.scheduleWithFixedDelay(() -> {
            long pingStart = System.currentTimeMillis();
            WebSocketRequestPayLoad payLoad = new WebSocketRequestPayLoad();
            payLoad.setEvent("pong");
            try {
                whatsAppWsClient.sendWsMessage(payLoad).get(10, TimeUnit.SECONDS);
                ping = System.currentTimeMillis() - pingStart;
                onPing.accept(ping);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                executorService.submit(runnableFactory.apply(() -> {
                    if (onError != null) {
                        onError.accept(e);
                    }
                }));
            }
        }, 0, 10, TimeUnit.SECONDS);
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
                    this.successConnect = false;
                    Map<UUID, WhatsAppWsClient.WsMessageSend> wsEvents = null;
                    do {
                        if (whatsAppWsClient != null) {
                            wsEvents = whatsAppWsClient.getWsEvents();
                            if (!whatsAppWsClient.isClosed() && !whatsAppWsClient.isClosing()) {
                                whatsAppWsClient.close();
                            }
                        }
                        whatsAppWsClient = new WhatsAppWsClient(URI.create("ws://localhost:" + localPort + "/api/ws"), this, onInit, onNeedQrCode, onUpdateDriverState, onError, onWsConnect, onWsDisconnect, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService);
                        flag = whatsAppWsClient.connectBlocking(1, TimeUnit.MINUTES);
                        tries++;
                        Thread.sleep(100);
                    } while (!flag && tries <= 600);
                    this.successConnect = flag;
                    if (flag && wsEvents != null && !wsEvents.isEmpty()) {
                        for (WhatsAppWsClient.WsMessageSend value : wsEvents.values()) {
                            value.setTries(0);
                            whatsAppWsClient.sendWsMessage(value);
                        }
                    }
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

    public CompletableFuture<Boolean> addChatMessageListener(String chatId, boolean includeMe, Consumer<Message> messageConsumer, EventType eventType, String... properties) {
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
        try {
            String contentType = Files.probeContentType(file.toPath());
            byte[] data = Files.readAllBytes(file.toPath());
            String base64str = Base64.getEncoder().encodeToString(data);
            StringBuilder sb = new StringBuilder();
            sb.append("data:");
            sb.append(contentType);
            sb.append(";base64,");
            sb.append(base64str);
            return sendMessage(chatId, quotedId, sb.toString(), fileName, caption);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }


    public CompletableFuture<MediaMessage> sendMessage(String chatId, String quotedId, String fileBase64, String fileName, String caption) {
        SendMessageRequest sendMessageRequest = new SendMessageRequest();
        sendMessageRequest.setFileName(fileName);
        sendMessageRequest.setMedia(fileBase64);
        sendMessageRequest.setMessage(caption);
        sendMessageRequest.setChatId(chatId);
        sendMessageRequest.setQuotedMsg(quotedId);
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

    public CompletableFuture<Boolean> markChatRecording(String chatId) {
        return whatsAppWsClient.markChatRecording(chatId);
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

    public CompletableFuture<Boolean> deleteMessage(String msgId, boolean fromAll) {
        return whatsAppWsClient.deleteMessage(msgId, fromAll);
    }
}
