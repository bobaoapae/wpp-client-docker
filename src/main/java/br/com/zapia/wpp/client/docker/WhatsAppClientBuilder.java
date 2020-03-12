package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;

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
        return new WhatsAppClient(dockerEndPoint, identity, onInit, onNeedQrCode, onUpdateDriverState, onError, runnableFactory, callableFactory, threadFactory);
    }
}
