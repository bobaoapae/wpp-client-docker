package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public class WhatsAppObjectWithId extends WhatsAppObject {


    private String id;

    public WhatsAppObjectWithId(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public String getId() {
        return id;
    }

    @Override
    protected void setJsonNode(JsonNode jsonNode) {
        super.setJsonNode(jsonNode);
        JsonNode id = jsonNode.get("id");
        if (id != null) {
            if (id.get("_serialized") != null) {
                this.id = id.get("_serialized").asText();
            } else {
                this.id = id.asText();
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WhatsAppObjectWithId that = (WhatsAppObjectWithId) o;
        return id.equals(that.id) &&
                client.equals(that.client);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, client);
    }
}
