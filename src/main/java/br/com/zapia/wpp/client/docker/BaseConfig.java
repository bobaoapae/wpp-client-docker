package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.OnWsDisconnect;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BaseConfig {

    protected abstract CompletableFuture<WhatsAppWsClient> getWsClient(WhatsAppClient whatsAppClient,
                                                                       Runnable onInit,
                                                                       Consumer<String> onNeedQrCode,
                                                                       Consumer<DriverState> onUpdateDriverState,
                                                                       Consumer<Throwable> onError,
                                                                       Consumer<Integer> onLowBattery,
                                                                       Runnable onPhoneDisconnect,
                                                                       Runnable onWsConnect,
                                                                       OnWsDisconnect onWsDisconnect,
                                                                       Consumer<Long> onPing,
                                                                       Function<Runnable, Runnable> runnableFactory,
                                                                       Function<Callable, Callable> callableFactory,
                                                                       Function<Runnable, Thread> threadFactory,
                                                                       ExecutorService executorService,
                                                                       ScheduledExecutorService scheduledExecutorService);

    protected abstract void ping(ExecutorService executorService);

    protected abstract void stop();
}