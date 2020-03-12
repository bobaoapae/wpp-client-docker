package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

public class GroupChat extends Chat {

    protected GroupChat(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }
}
