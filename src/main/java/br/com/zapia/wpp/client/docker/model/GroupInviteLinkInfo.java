package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

public class GroupInviteLinkInfo extends WhatsAppObjectWithId {

    private final String inviteCode;

    public GroupInviteLinkInfo(WhatsAppClient client, JsonNode jsonNode, String inviteCode) {
        super(client, jsonNode);
        this.inviteCode = inviteCode;
    }

    public String getSubject() {
        return jsonNode.get("subject").asText();
    }

    public LocalDateTime getCreation() {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(jsonNode.get("creation").asInt()), ZoneId.systemDefault());
    }

    public String getDesc() {
        return jsonNode.get("desc").asText();
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public CompletableFuture<Boolean> join() {
        return getClient().joinGroup(inviteCode);
    }
}
