package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

public class AudioMessage extends MediaMessage {

    protected AudioMessage(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public CompletableFuture<Boolean> markPlayed() {
        return getClient().markMessagePlayed(getId());
    }
}
