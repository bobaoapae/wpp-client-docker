package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

public class Message extends WhatsAppObjectWithId {

    private Contact contact;
    private String oldId;

    protected Message(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public static Message build(WhatsAppClient client, JsonNode jsonNode) {
        String type = jsonNode.get("type") == null ? "" : jsonNode.get("type").asText();
        switch (type) {
            case "image":
            case "sticker":
            case "video":
            case "document":
                return new MediaMessage(client, jsonNode);
            case "audio":
            case "ptt":
                return new AudioMessage(client, jsonNode);
            case "location":
                return new GeoMessage(client, jsonNode);
            case "vcard":
                return new VCardMessage(client, jsonNode);
            default:
                return new Message(client, jsonNode);

        }
    }

    public String getOldId() {
        return oldId;
    }

    public String getSenderId() {
        if (jsonNode.has("author")) {
            return jsonNode.get("author").asText();
        }

        return jsonNode.get("from").asText();
    }

    public CompletableFuture<Contact> getContact() {
        if (contact != null) {
            return CompletableFuture.completedFuture(contact);
        }

        return client.findContactById(getSenderId()).thenApply(contact1 -> {
            contact = contact1;
            return contact;
        });
    }

    public String getBody() {
        return getJsonNode().get("body") == null ? "" : getJsonNode().get("body").asText();
    }

    public String getType() {
        return getJsonNode().get("type") == null ? "" : getJsonNode().get("type").asText();
    }

    public boolean isNew() {
        return getJsonNode().get("isNew") != null && getJsonNode().get("isNew").asBoolean(false);
    }

    public boolean isRevoked() {
        return getType().equals("revoked");
    }

    public CompletableFuture<Boolean> delete() {
        return getClient().deleteMessage(getId(), false);
    }

    public CompletableFuture<Boolean> revoke() {
        return getClient().deleteMessage(getId(), true);
    }

    public CompletableFuture<Void> update() {
        return getClient().findMessage(getId()).thenAccept(this::update);
    }

    public void update(Message message) {
        this.setJsonNode(message.getJsonNode());
    }

    @Override
    protected void setJsonNode(JsonNode jsonNode) {
        super.setJsonNode(jsonNode);
        JsonNode oldId = jsonNode.get("oldId");
        if (oldId != null) {
            if (oldId.get("_serialized") != null) {
                this.oldId = oldId.get("_serialized").asText();
            } else {
                this.oldId = oldId.asText();
            }
        }
    }
}
