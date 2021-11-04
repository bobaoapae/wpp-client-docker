package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import java.util.Objects;

public class WhatsAppObjectWithId<T extends WhatsAppObjectWithId<T>> extends BaseWhatsAppObject<T> {

    private String id;

    protected WhatsAppObjectWithId(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
    }

    public String getId() {
        return id;
    }

    @Override
    void build() {
        id = jsonObject.get("id").getAsString();
    }

    @Override
    protected void update(T baseWhatsAppObject) {
        id = baseWhatsAppObject.getId();
    }

    @Override
    protected void update(JsonObject jsonObject) {
        id = jsonObject.get("id").getAsString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        T that = (T) o;
        return id.equals(that.getId()) &&
                client.equals(that.client);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, client);
    }
}
