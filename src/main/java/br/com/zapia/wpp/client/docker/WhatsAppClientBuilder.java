package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.OnWsDisconnect;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WhatsAppClientBuilder {

    private static Logger logger = Logger.getLogger(WhatsAppClient.class.getName());

    private String dockerEndPoint;
    private String identity;
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

    public WhatsAppClientBuilder(String dockerEndPoint, String identity) {
        this.dockerEndPoint = dockerEndPoint;
        this.identity = identity;
        this.onInit = () -> {
            logger.log(Level.INFO, "init");
        };
        this.onNeedQrCode = (base64) -> {
            logger.log(Level.INFO, "need-qrCode", base64);
        };
        this.onUpdateDriverState = (driverState) -> {
            logger.log(Level.INFO, "updateDriverState", driverState);
        };
        this.runnableFactory = runnable -> () -> runnable.run();
        this.callableFactory = callable -> () -> callable.call();
        this.threadFactory = runnable -> new Thread(runnable);
        this.onError = throwable -> {
            logger.log(Level.SEVERE, "WhatsAppClient", throwable);
        };
        this.onWsConnect = () -> {
            logger.log(Level.INFO, "WsConnect");
        };
        this.onWsDisconnect = (code, reason, remote) -> {
            logger.log(Level.SEVERE, "WsDisconnect with code {" + code + "} and reason {" + reason + "}");
        };
        this.onPing = (ping) -> {
            logger.log(Level.INFO, "Ping::" + ping + "ms");
        };
    }

    public WhatsAppClientBuilder onInit(Runnable onInit) {
        this.onInit = onInit;
        return this;
    }

    public WhatsAppClientBuilder onNeedQrCode(Consumer<String> onNeedQrCode) {
        this.onNeedQrCode = onNeedQrCode;
        return this;
    }

    public WhatsAppClientBuilder onUpdateDriverState(Consumer<DriverState> onUpdateDriverState) {
        this.onUpdateDriverState = onUpdateDriverState;
        return this;
    }

    public WhatsAppClientBuilder onError(Consumer<Throwable> onError) {
        this.onError = onError;
        return this;
    }

    public WhatsAppClientBuilder onWsConnect(Runnable onWsConnect) {
        this.onWsConnect = onWsConnect;
        return this;
    }

    public WhatsAppClientBuilder onWsDisconnect(OnWsDisconnect onWsDisconnect) {
        this.onWsDisconnect = onWsDisconnect;
        return this;
    }

    public WhatsAppClientBuilder onPing(Consumer<Long> onPing) {
        this.onPing = onPing;
        return this;
    }

    public WhatsAppClientBuilder runnableFactory(Function<Runnable, Runnable> runnableFactory) {
        this.runnableFactory = runnableFactory;
        return this;
    }

    public WhatsAppClientBuilder callableFactory(Function<Callable, Callable> callableFactory) {
        this.callableFactory = callableFactory;
        return this;
    }

    public WhatsAppClientBuilder threadFactory(Function<Runnable, Thread> threadFactory) {
        this.threadFactory = threadFactory;
        return this;
    }

    public WhatsAppClient builder() {
        return new WhatsAppClient(dockerEndPoint, identity, onInit, onNeedQrCode, onUpdateDriverState, onError, onWsConnect, onWsDisconnect, onPing, runnableFactory, callableFactory, threadFactory);
    }
}
