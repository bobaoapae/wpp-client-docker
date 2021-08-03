package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

public class GroupParticipant extends WhatsAppObjectWithId {

    public GroupParticipant(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public CompletableFuture<Contact> getContact() {
        return client.findContactById(getId());
    }

    public boolean isAdmin() {
        if (getJsonNode().hasNonNull("isAdmin") && getJsonNode().get("isAdmin").isBoolean()) {
            return getJsonNode().get("isAdmin").asBoolean();
        } else {
            return false;
        }
    }

    public boolean isSuperAdmin() {
        if (getJsonNode().hasNonNull("isSuperAdmin") && getJsonNode().get("isSuperAdmin").isBoolean()) {
            return getJsonNode().get("isSuperAdmin").asBoolean();
        } else {
            return false;
        }
    }
}
