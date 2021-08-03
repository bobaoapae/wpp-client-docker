package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GroupChat extends Chat {

    protected GroupChat(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public CompletableFuture<List<GroupParticipant>> getAllParticipants() {
        return client.getGroupParticipants(getId());
    }
}
