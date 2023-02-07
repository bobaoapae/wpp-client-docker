package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.OnWsDisconnect;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DockerConfig extends BaseConfig {

    private static final Logger logger = Logger.getLogger(DockerConfig.class.getName());

    private final String identity;
    private String dockerImageName;
    private String dockerUserName;
    private String dockerPassword;
    private final String remoteAddress;
    private final int remotePort;
    private final String insideDockerHostVolumeLocation;
    private final int maxMemoryMB;
    private final boolean autoUpdateBaseImage;
    private final boolean autoRemoveContainer;

    private WebSocketConfig webSocketConfig;

    private final DockerClient dockerClient;

    public DockerConfig(String identity, String dockerImageName, String dockerUserName, String dockerPassword, String remoteAddress, int remotePort, String insideDockerHostVolumeLocation, int maxMemoryMB, boolean autoUpdateBaseImage, boolean autoRemoveContainer) {
        this.identity = identity;
        this.dockerImageName = dockerImageName;
        this.dockerUserName = dockerUserName;
        this.dockerPassword = dockerPassword;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.insideDockerHostVolumeLocation = insideDockerHostVolumeLocation;
        this.maxMemoryMB = maxMemoryMB;
        this.autoUpdateBaseImage = autoUpdateBaseImage;
        this.autoRemoveContainer = autoRemoveContainer;
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://" + remoteAddress + ":" + remotePort)
                .withDockerTlsVerify(false)
                .build();
        this.dockerClient = DockerClientBuilder.getInstance(dockerConfig)
                .build();
    }


    @Override
    public CompletableFuture<WhatsAppWsClient> getWsClient(WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Consumer<Integer> onLowBattery, Runnable onPhoneDisconnect, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        stop();
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (autoUpdateBaseImage) {
                    try {
                        RemoveImageCmd removeImageCmd = dockerClient
                                .removeImageCmd(dockerImageName)
                                .withForce(true);

                        removeImageCmd.exec();
                    } catch (NotFoundException ignore) {

                    }

                    PullImageCmd pullImageCmd = dockerClient
                            .pullImageCmd(dockerImageName)
                            .withAuthConfig(dockerClient.authConfig().withUsername(dockerUserName).withPassword(dockerPassword));

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
                }
                stop();
                Volume chromeCache = new Volume("/home/chrome/cacheWhatsApp");
                CreateContainerCmd containerCmd = dockerClient.createContainerCmd(dockerImageName);
                containerCmd.withName("whatsapp-api-" + identity);
                containerCmd.withHostConfig(HostConfig.newHostConfig()
                        .withPublishAllPorts(true)
                        .withMemory(1024L * 1024L * maxMemoryMB)
                        .withMemoryReservation((long) (1024L * 1024L * (maxMemoryMB * 0.8)))
                        .withMemorySwap((long) (1024L * 1024L * (maxMemoryMB * 1.3)))
                        .withAutoRemove(autoRemoveContainer)
                        .withBinds(new Bind(insideDockerHostVolumeLocation + "/" + identity, chromeCache)));
                CreateContainerResponse exec = containerCmd.exec();
                String containerId = exec.getId();
                dockerClient.startContainerCmd(containerId).exec();
                dockerClient.waitContainerCmd(containerId);
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                Map<ExposedPort, Ports.Binding[]> bindings = inspect.getNetworkSettings().getPorts().getBindings();
                var localPort = "";
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
                    webSocketConfig = new WebSocketConfig(remoteAddress, Integer.parseInt(localPort));
                    return webSocketConfig.getWsClient(whatsAppClient, onInit, onNeedQrCode, onUpdateDriverState, onError, onLowBattery, onPhoneDisconnect, onWsConnect, onWsDisconnect, onPing, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService).get();
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Start Docker Container", e);
            }
            return null;
        });
    }

    @Override
    protected void ping(ExecutorService executorService) {
        webSocketConfig.ping(executorService);
    }

    @Override
    public void stop() {
        if (webSocketConfig != null) {
            webSocketConfig.stop();
            webSocketConfig = null;
        }
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
    }
}
