package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public class Message extends WhatsAppObjectWithId<Message> {

    private boolean fromMe;
    private String remoteJid;
    private String participant;
    private AckType ackType;
    private LocalDateTime timeStamp;
    private boolean starred;
    private MessageContent messageContent;

    protected Message(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
    }

    public boolean isFromMe() {
        return fromMe;
    }

    public String getRemoteJid() {
        return remoteJid;
    }

    public String getParticipant() {
        return participant;
    }

    public AckType getAckType() {
        return ackType;
    }

    public LocalDateTime getTimeStamp() {
        return timeStamp;
    }

    public boolean isStarred() {
        return starred;
    }

    public MessageContent getMessageContent() {
        return messageContent;
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

    @Override
    void build() {
        super.build();
        remoteJid = jsonObject.get("remoteJid").getAsString();
        participant = jsonObject.get("participant").getAsString();
        fromMe = jsonObject.get("fromMe").getAsBoolean();
        ackType = AckType.valueOf(jsonObject.get("ackType").getAsString().toUpperCase());
        starred = jsonObject.get("starred").getAsBoolean();
        timeStamp = LocalDateTime.parse(jsonObject.get("timeStamp").getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        messageContent = MessageContent.build(this, jsonObject.getAsJsonObject("messageContent"));
    }

    @Override
    protected void update(Message baseWhatsAppObject) {
        super.update(baseWhatsAppObject);
    }

    public static abstract class MessageContent {

        protected transient final Message message;
        private final MessageType messageType;

        protected MessageContent(Message message, MessageType messageType) {
            this.message = message;
            this.messageType = messageType;
        }

        public MessageType getMessageType() {
            return messageType;
        }

        public Message getMessage() {
            return message;
        }

        public static MessageContent build(Message message, JsonObject jsonObject) {
            var msgType = MessageType.valueOf(jsonObject.get("messageType").getAsString());
            switch (msgType) {
                case TEXT -> {
                    return new MessageTextContent(message, jsonObject);
                }
            }
            return new NotSupportedMessageContent(message, jsonObject);
        }

        public static class NotSupportedMessageContent extends MessageContent {

            private final JsonObject jsonObject;

            public NotSupportedMessageContent(Message message, JsonObject jsonObject) {
                super(message, MessageType.UNKNOWN);
                this.jsonObject = jsonObject;
            }

            public JsonObject getJsonObject() {
                return jsonObject;
            }
        }

        public static class MessageTextContent extends MessageContent {

            private final String text;

            protected MessageTextContent(Message message, JsonObject jsonObject) {
                super(message, MessageType.TEXT);
                this.text = jsonObject.get("text").getAsString();
            }

            public String getText() {
                return text;
            }
        }
    }

    public enum AckType {
        ERROR,
        PENDING,
        SERVER_ACK,
        DELIVERY_ACK,
        READ,
        PLAYED
    }

    public enum MessageType {
        TEXT,
        EXTENDED_TEXT,
        LOCATION,
        LIVE_LOCATION,
        CONTACT,
        CONTACTS_ARRAY,
        GROUP_INVITE_MESSAGE,
        LIST_MESSAGE,
        BUTTONS_MESSAGE,
        IMAGE,
        STICKER,
        DOCUMENT,
        VIDEO,
        AUDIO,
        UNKNOWN
    }
}
