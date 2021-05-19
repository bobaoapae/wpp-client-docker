package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WhatsAppObject {

    protected ObjectMapper objectMapper;
    protected JsonNode jsonNode;
    protected WhatsAppClient client;

    public WhatsAppObject(WhatsAppClient client, JsonNode jsonNode) {
        this.objectMapper = new ObjectMapper();
        this.client = client;
        this.setJsonNode(jsonNode);
    }

    protected void setJsonNode(JsonNode jsonNode) {
        this.jsonNode = jsonNode;
    }

    public JsonNode getJsonNode() {
        return jsonNode;
    }

    public WhatsAppClient getClient() {
        return client;
    }
}
