package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.DriverState;
import br.com.zapia.wpp.client.docker.model.OnWsDisconnect;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class WppCloneConfig extends BaseConfig {

    private final String login;
    private final String password;
    private final String wppRemoteAddress;
    private WebSocketConfig webSocketConfig;
    private final ObjectMapper objectMapper;

    public WppCloneConfig(String login, String password, String wppRemoteAddress) {
        this.login = login;
        this.password = password;
        this.wppRemoteAddress = wppRemoteAddress;
        objectMapper = new ObjectMapper();
    }

    public CompletableFuture<String> getToken(ExecutorService executorService) {
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                RequestBody formBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("login", login)
                        .addFormDataPart("senha", password)
                        .build();

                Request request = new Request.Builder().url(wppRemoteAddress + "/api/auth/login").post(formBody).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        completableFuture.completeExceptionally(new RuntimeException(response.body().string()));
                    } else {
                        completableFuture.complete(objectMapper.readTree(response.body().string()).get("token").textValue());
                    }
                }
            } catch (Exception e) {
                completableFuture.completeExceptionally(e);
            }
        });
        return completableFuture;
    }

    private CompletableFuture<String> getWebSocketAddress(ExecutorService executorService) {
        return getToken(executorService).thenApply(token -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .addHeader("Authorization", "Bearer " + token)
                    .url(wppRemoteAddress + "/api/remoteWppApi/webSocketAddress").get().build();


            var maxTries = 120;
            while (true) {
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        maxTries--;
                        if (maxTries < 0) {
                            throw new RuntimeException(response.body().string());
                        } else {
                            Thread.sleep(1000);
                        }
                    } else {
                        return response.body().string();
                    }
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }

        });
    }

    @Override
    public CompletableFuture<WhatsAppWsClient> getWsClient(WhatsAppClient whatsAppClient, Runnable onInit, Consumer<String> onNeedQrCode, Consumer<DriverState> onUpdateDriverState, Consumer<Throwable> onError, Consumer<Integer> onLowBattery, Runnable onPhoneDisconnect, Runnable onWsConnect, OnWsDisconnect onWsDisconnect, Consumer<Long> onPing, Function<Runnable, Runnable> runnableFactory, Function<Callable, Callable> callableFactory, Function<Runnable, Thread> threadFactory, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        return getWebSocketAddress(executorService).thenCompose(webSocketAddress -> {
            webSocketConfig = new WebSocketConfig(webSocketAddress.split(":")[0], Integer.parseInt(webSocketAddress.split(":")[1]));
            return webSocketConfig.getWsClient(whatsAppClient, onInit, onNeedQrCode, onUpdateDriverState, onError, onLowBattery, onPhoneDisconnect, onWsConnect, onWsDisconnect, onPing, runnableFactory, callableFactory, threadFactory, executorService, scheduledExecutorService);
        });
    }

    @Override
    protected void ping(ExecutorService executorService) {
        if (webSocketConfig != null) {
            webSocketConfig.ping(executorService);
        }
        getToken(executorService).thenAccept(token -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .addHeader("Authorization", "Bearer " + token)
                    .url(wppRemoteAddress + "/api/remoteWppApi/ping").get().build();
            try {
                client.newCall(request).execute();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public void stop() {
        if (webSocketConfig != null) {
            webSocketConfig.stop();
        }
    }
}
