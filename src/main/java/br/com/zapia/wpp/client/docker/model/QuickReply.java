package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

public class QuickReply extends WhatsAppObjectWithId {
    public QuickReply(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }
}
