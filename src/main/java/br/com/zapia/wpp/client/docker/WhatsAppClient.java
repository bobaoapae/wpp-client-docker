package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.api.model.payloads.WebSocketRequestPayLoad;
import br.com.zapia.wpp.client.docker.model.EventType;
import br.com.zapia.wpp.client.docker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;

import java.io.Closeable;
import java.io.File;
import java.net.URI;
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
    private String dockerEndPoint;
    private String insideDockerHostVolumeLocation;
    private String identity;
    private DockerClientConfig config;
    private DockerClient dockerClient;
    private String localPort;

    private boolean successConnect;
    private long ping;
    private ObjectMapper objectMapper;
    private Map<String, List<Chat>> chatsAutoUpdate;
    private Map<String, List<Message>> messagesAutoUpdate;

    public WhatsAppClient(String dockerEndPoint, int dockerPort, boolean useTls, String insideDockerHostVolumeLocation, String identity, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory) {
        this.dockerEndPoint = dockerEndPoint;
        this.insideDockerHostVolumeLocation = insideDockerHostVolumeLocation;
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
                .withDockerHost("tcp://" + dockerEndPoint + ":" + dockerPort)
                .withDockerTlsVerify(useTls)
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
        addRemoveMessageListener(message -> {
            if (chatsAutoUpdate.containsKey(message.getContact().getId())) {
                List<Chat> chats = chatsAutoUpdate.get(message.getContact().getId());
                for (Chat chat1 : chats) {
                    int msgIndex = 0;
                    for (JsonNode msg : chat1.getJsonNode().get("msgs").deepCopy()) {
                        Message message1 = Message.build(this, msg);
                        if (message1.equals(message)) {
                            ((ArrayNode) chat1.getJsonNode().get("msgs")).remove(msgIndex);
                        }
                        msgIndex++;
                    }
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
                PullImageCmd pullImageCmd = dockerClient
                        .pullImageCmd("bobaoapae/whatsapp-api:latest")
                        .withAuthConfig(dockerClient.authConfig().withUsername("bobaoapae").withPassword("joao0123@"));

                pullImageCmd.exec(new PullImageResultCallback() {
                    @Override
                    public void onStart(Closeable stream) {
                        super.onStart(stream);
                        logger.log(Level.INFO, "Pull Image Start");
                    }

                    @Override
                    public void onComplete() {
                        super.onComplete();
                        logger.log(Level.INFO, "Pull Image Complete");
                    }
                }).awaitCompletion();
                List<Container> containerResult = dockerClient.listContainersCmd()
                        .withShowAll(true)
                        .withNameFilter(Arrays.asList("whatsapp-api-" + identity))
                        .exec();
                String containerId;
                InspectContainerResponse inspect;
                try {
                    if (containerResult.size() == 1) {
                        containerId = containerResult.get(0).getId();
                        inspect = dockerClient.inspectContainerCmd(containerId).exec();
                        if (inspect.getState().getRunning()) {
                            dockerClient.stopContainerCmd(containerId).exec();
                        }
                        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    }
                } catch (Exception ignore) {

                }
                Volume chromeCache = new Volume("/home/chrome/cacheWhatsApp");
                CreateContainerCmd containerCmd = dockerClient.createContainerCmd("bobaoapae/whatsapp-api:latest");
                containerCmd.withName("whatsapp-api-" + identity);
                containerCmd.withHostConfig(HostConfig.newHostConfig()
                        .withPublishAllPorts(true)
                        .withMemory(1024L * 1024L * 500L)
                        .withMemoryReservation(1024L * 1024L * 300L)
                        .withMemorySwap(1024L * 1024L * 700L)
                        .withCpuPercent(3L)
                        .withAutoRemove(true)
                        .withBinds(new Bind(insideDockerHostVolumeLocation + "/" + identity, chromeCache)));
                CreateContainerResponse exec = containerCmd.exec();
                containerId = exec.getId();
                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId);
                inspect = dockerClient.inspectContainerCmd(containerId).exec();
                Map<ExposedPort, Ports.Binding[]> bindings = inspect.getNetworkSettings().getPorts().getBindings();
                localPort = "";
                for (Map.Entry<ExposedPort, Ports.Binding[]> exposedPortEntry : bindings.entrySet()) {
                    if (exposedPortEntry.getKey().getPort() == 1100) {
                        localPort = exposedPortEntry.getValue()[0].getHostPortSpec();
                    } else if (exposedPortEntry.getKey().getPort() == 5005) {
                        logger.info("JVM Debugger Port: " + exposedPortEntry.getValue()[0].getHostPortSpec());
                    } else if (exposedPortEntry.getKey().getPort() == 9222) {
                        logger.info("Chromium Debugger Port: " + exposedPortEntry.getValue()[0].getHostPortSpec());
                    }
                }
                if (!localPort.isEmpty()) {
                    boolean flag;
                    int tries = 0;
                    this.successConnect = false;
                    Map<UUID, WhatsAppWsClient.WsMessageSend> wsEvents = null;
                    do {
                        if (whatsAppWsClient != null) {
                            if (!whatsAppWsClient.getWsEvents().isEmpty()) {
                                wsEvents = whatsAppWsClient.getWsEvents();
                            }
                            if (!whatsAppWsClient.isClosed() && !whatsAppWsClient.isClosing()) {
                                whatsAppWsClient.close();
                            }
                        }
                        whatsAppWsClient = new WhatsAppWsClient(URI.create("ws://" + dockerEndPoint + ":" + localPort + "/api/ws"), this, onInit, onNeedQrCode, onUpdateDriverState, onError, onWsConnect, onWsDisconnect, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService);
                        flag = whatsAppWsClient.connectBlocking(1, TimeUnit.MINUTES);
                        tries++;
                        Thread.sleep(100);
                    } while (!flag && tries <= 1200);
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

    public CompletableFuture<Boolean> clearChatMessages(String chatId, boolean keepFavorites) {
        return whatsAppWsClient.clearChatMessages(chatId, keepFavorites);
    }

    public CompletableFuture<Boolean> deleteMessage(String msgId, boolean fromAll) {
        return whatsAppWsClient.deleteMessage(msgId, fromAll);
    }
}
