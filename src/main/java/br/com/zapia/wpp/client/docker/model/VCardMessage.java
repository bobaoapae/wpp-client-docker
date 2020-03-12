package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

public class VCardMessage extends Message {

    protected VCardMessage(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }
}
