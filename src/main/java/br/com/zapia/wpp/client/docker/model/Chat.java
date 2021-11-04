package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class Chat extends WhatsAppObjectWithId<Chat> {

    private String name;
    private int unreadMessages;
    private int mute;
    private int pin;
    private boolean isReadOnly;
    private final Map<String, Message> messages;

    private Contact contact;

    public static Chat build(WhatsAppClient client, JsonObject jsonObject) {
        if (jsonObject.get("id").getAsString().endsWith("@g.us")) {
            //TODO: Group Chat
            return BaseWhatsAppObject.createWhatsAppObject(Chat.class, client, jsonObject);
        } else {
            return BaseWhatsAppObject.createWhatsAppObject(Chat.class, client, jsonObject);
        }
    }

    protected Chat(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
        messages = new LinkedHashMap<>();
    }

    public CompletableFuture<Contact> getContact() {
        if (contact != null) {
            return CompletableFuture.completedFuture(contact);
        }

        return client.findContactById(this.getId()).thenApply(contact1 -> {
            this.contact = contact1;
            return this.contact;
        });
    }

    public Map<String, Message> getMessages() {
        synchronized (messages) {
            return messages;
        }
    }

    public Message getLastMsg() {
        synchronized (messages) {
            var allMessages = messages.values().stream().toList();
            if (!allMessages.isEmpty()) {
                return allMessages.get(allMessages.size() - 1);
            }
            return null;
        }
    }

    public String getFormattedTitle() {
        return getJsonObject().get("name").getAsString();
    }

    public CompletableFuture<Message> sendMessage(Consumer<SendMessageRequest.Builder> sendMessageRequestConsumer) {
        var builder = new SendMessageRequest.Builder(getId());
        sendMessageRequestConsumer.accept(builder);
        return sendMessage(builder.build());
    }

    public CompletableFuture<Message> sendMessage(SendMessageRequest sendMessageRequest) {
        return getClient().sendMessage(sendMessageRequest);
    }

    public CompletableFuture<Boolean> forwardMessages(Chat[] chats, Message[] messages) {
        return getClient().forwardMessages(Arrays.stream(chats).map(WhatsAppObjectWithId::getId).toArray(String[]::new), Arrays.stream(messages).map(WhatsAppObjectWithId::getId).toArray(String[]::new));
    }

    public CompletableFuture<Boolean> sendSee() {
        return getClient().seeChat(getId());
    }

    public CompletableFuture<Boolean> markComposing() {
        return getClient().markChatComposing(getId());
    }

    public CompletableFuture<Boolean> markRead() {
        return getClient().markChatRead(getId());
    }

    public CompletableFuture<Boolean> markRecording() {
        return getClient().markChatRecording(getId());
    }

    public CompletableFuture<Boolean> markUnRead() {
        return getClient().markChatUnRead(getId());
    }

    public CompletableFuture<Boolean> markPaused() {
        return getClient().markChatPaused(getId());
    }

    public CompletableFuture<Boolean> subscribePresence() {
        return getClient().subscribeChatPresence(getId());
    }

    public CompletableFuture<Boolean> pinChat() {
        return getClient().pinChat(getId());
    }

    public CompletableFuture<Boolean> unPinChat() {
        return getClient().unPinChat(getId());
    }

    public CompletableFuture<List<Message>> loadEarly() {
        return getClient().loadEarlyMessagesChat(getId());
    }

    public CompletableFuture<Boolean> delete() {
        return getClient().deleteChat(getId());
    }

    public CompletableFuture<Boolean> clearMessages(boolean keepFavorites) {
        return getClient().clearChatMessages(getId(), keepFavorites);
    }

    public CompletableFuture<Void> update() {
        return getClient().findChatById(getId()).thenAccept(this::updateFromOther);
    }

    @Override
    void build() {
        super.build();
        name = jsonObject.get("name").getAsString();
        unreadMessages = jsonObject.get("unreadMessages").getAsInt();
        mute = jsonObject.get("unreadMessages").getAsInt();
        pin = jsonObject.get("unreadMessages").getAsInt();
        isReadOnly = jsonObject.get("isReadOnly").getAsBoolean();
        var msgs = getJsonObject().get("messages").getAsJsonObject();
        synchronized (messages) {
            for (String msgId : msgs.keySet()) {
                messages.put(msgId, new Message(getClient(), msgs.get(msgId).getAsJsonObject()));
            }
        }
    }

    @Override
    protected void update(Chat baseWhatsAppObject) {
        super.update(baseWhatsAppObject);
        name = baseWhatsAppObject.name;
        unreadMessages = baseWhatsAppObject.unreadMessages;
        mute = baseWhatsAppObject.mute;
        pin = baseWhatsAppObject.pin;
        isReadOnly = baseWhatsAppObject.isReadOnly;
        synchronized (messages) {
            messages.clear();
            messages.putAll(baseWhatsAppObject.messages);
        }
    }
}
