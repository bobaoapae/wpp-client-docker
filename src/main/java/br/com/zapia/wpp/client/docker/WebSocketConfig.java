package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.OnWsDisconnect;

import java.net.URI;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class WebSocketConfig extends BaseConfig {

    private final String remoteAddress;
    private final int remotePort;
    private WhatsAppWsClient whatsAppWsClient;

    public WebSocketConfig(String identity, String remoteAddress, int remotePort) {
        super(identity);
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
    }

    @Override
    public CompletableFuture<WhatsAppWsClient> getWsClient(WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Consumer<Integer> onLowBattery, Runnable onPhoneDisconnect, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                for (int tries = 0; tries < 1200; tries++) {
                    var whatsAppWsClient = new WhatsAppWsClient(URI.create("ws://" + remoteAddress + ":" + remotePort + "/api/ws"), whatsAppClient, onInit, onNeedQrCode, onUpdateDriverState, onError, onLowBattery, onPhoneDisconnect, onWsConnect, onWsDisconnect, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService);
                    if (whatsAppWsClient.connectBlocking(1, TimeUnit.MINUTES)) {
                        this.whatsAppWsClient = whatsAppWsClient;
                        return whatsAppWsClient;
                    }
                    Thread.sleep(100);
                }
                return null;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public void stop() {
        if (whatsAppWsClient != null) {
            whatsAppWsClient.close();
            whatsAppWsClient = null;
        }
    }
}
