package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class BaseWhatsAppObject<T extends BaseWhatsAppObject<T>> {

    protected static final Logger logger = Logger.getLogger(BaseWhatsAppObject.class.getName());

    protected final WhatsAppClient client;
    protected JsonObject jsonObject;

    public static <K extends BaseWhatsAppObject<K>> K createWhatsAppObject(Class<K> kClass, WhatsAppClient whatsAppClient, JsonObject jsonObject) {
        try {
            var instance = kClass.getDeclaredConstructor(WhatsAppClient.class, JsonObject.class).newInstance(whatsAppClient, jsonObject);
            instance.buildFromJson();
            return instance;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "createWhatsAppObject", e);
        }

        return null;
    }

    protected BaseWhatsAppObject(WhatsAppClient client, JsonObject jsonObject) {
        this.client = client;
        this.jsonObject = jsonObject;
    }

    public WhatsAppClient getClient() {
        return client;
    }

    public JsonObject getJsonObject() {
        return jsonObject;
    }

    public void buildFromJson() {
        try {
            build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Build BaseWhatsAppObject", e);
        }
    }

    //TODO: update base properties

    public final void updateFromOther(T baseWhatsAppObject) {
        update(baseWhatsAppObject);
    }

    public final void updateFromJson(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
        update(jsonObject);
    }

    abstract void build();

    protected abstract void update(T baseWhatsAppObject);

    protected abstract void update(JsonObject jsonElement);
}