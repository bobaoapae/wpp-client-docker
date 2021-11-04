package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public class GroupInviteLinkInfo extends WhatsAppObjectWithId {

    private final String inviteCode;

    public GroupInviteLinkInfo(WhatsAppClient client, JsonObject jsonObject, String inviteCode) {
        super(client, jsonObject);
        this.inviteCode = inviteCode;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public CompletableFuture<Boolean> join() {
        return getClient().joinGroup(inviteCode);
    }
}
