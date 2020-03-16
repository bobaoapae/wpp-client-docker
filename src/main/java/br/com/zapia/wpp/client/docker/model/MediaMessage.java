package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class MediaMessage extends Message {

    protected MediaMessage(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public String getCaption() {
        if (getJsonNode().hasNonNull("caption")) {
            return getJsonNode().get("caption").asText();
        } else {
            return "";
        }
    }

    public CompletableFuture<File> download() {
        return getClient().downloadMediaMessage(getId());
    }
}
