package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

public class SelfInfo extends WhatsAppObjectWithId {

    public SelfInfo(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
    }

    public String getPushName() {
        return jsonObject.get("pushName").getAsString();
    }

    public boolean isBusiness() {
        return jsonObject.get("isBusiness").getAsBoolean();
    }
}
