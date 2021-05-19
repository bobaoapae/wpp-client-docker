package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

public class SelfInfo extends WhatsAppObject {

    public SelfInfo(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public Chat getMyChat() {
        return new Chat(client, jsonNode.get("self"));
    }

    public boolean isBusiness() {
        return jsonNode.get("isBusiness").asBoolean();
    }
}
