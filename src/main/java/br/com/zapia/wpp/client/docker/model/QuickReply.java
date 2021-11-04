package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

public class QuickReply extends WhatsAppObjectWithId<QuickReply> {

    private String shortcut;
    private String message;

    protected QuickReply(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
    }

    public String getShortcut() {
        return shortcut;
    }

    public String getMessage() {
        return message;
    }

    @Override
    void build() {
        super.build();
        shortcut = jsonObject.get("shortcut").getAsString();
        message = jsonObject.get("message").getAsString();
    }
}
