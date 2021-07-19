package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.api.model.payloads.SendMessageRequest;
import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class Chat extends WhatsAppObjectWithId {

    private Contact contact;

    protected Chat(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
    }

    public static Chat build(WhatsAppClient client, JsonNode jsonNode) {
        switch (jsonNode.get("kind").textValue()) {
            case "group":
                return new GroupChat(client, jsonNode);
            default:
                return new Chat(client, jsonNode);
        }
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

    public List<Message> getAllMessages() {
        List<Message> msgs = new ArrayList<>();
        for (JsonNode msg : getJsonNode().get("msgs")) {
            msgs.add(Message.build(getClient(), msg));
        }
        return Collections.unmodifiableList(msgs);
    }

    public Message getLastMsg() {
        List<Message> allMessages = getAllMessages();
        if (!allMessages.isEmpty()) {
            return allMessages.get(allMessages.size() - 1);
        }
        return null;
    }

    public String getFormattedTitle() {
        return getJsonNode().get("formattedTitle").asText();
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

    public CompletableFuture<Boolean> addMessageListener(Consumer<List<Message>> messageConsumer, EventType eventType, String... properties) {
        return addMessageListener(false, messageConsumer, eventType, properties);
    }

    public CompletableFuture<Boolean> addMessageListener(boolean includeMe, Consumer<List<Message>> messageConsumer, EventType eventType, String... properties) {
        return getClient().addChatMessageListener(getId(), includeMe, messageConsumer, eventType, properties);
    }

    public CompletableFuture<Void> update() {
        return getClient().findChatById(getId()).thenAccept(this::update);
    }

    public void update(Chat chat) {
        this.setJsonNode(chat.getJsonNode());
    }

    @Override
    protected void setJsonNode(JsonNode jsonNode) {
        super.setJsonNode(jsonNode);
    }
}
