package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.*;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(value = MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatsAppClientTest {

    private JLabel label;
    private WhatsAppClient whatsAppClient;
    private Chat chatTest;
    private Contact contactTest;

    @Test
    @Order(-1)
    void initClient() {
        CompletableFuture<Void> init = new CompletableFuture<>();
        CompletableFuture<Void> qrCode = new CompletableFuture<>();
        CompletableFuture<Void> driverUpdate = new CompletableFuture<>();

        Runnable onInit = () -> {
            System.out.println("init");
            init.complete(null);
        };

        Consumer<String> onNeedQrCode = (base64) -> {
            System.out.println(base64);
            if (label == null) {
                label = new JLabel("", JLabel.LEFT);
                label.setPreferredSize(new Dimension(500, 500));
                try {
                    JOptionPane.showMessageDialog(
                            null,
                            label,
                            "SCAN QR CODE", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    e.printStackTrace();
                    label = null;
                }
            }
            if (base64 != null && base64.contains(",")) {
                try {
                    byte[] btDataFile = Base64.getDecoder().decode(base64.split(",")[1]);
                    BufferedImage image;
                    image = ImageIO.read(new ByteArrayInputStream(btDataFile));
                    ImageIcon icon = new ImageIcon(image.getScaledInstance(500, 500, Image.SCALE_DEFAULT));
                    label.setIcon(icon);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            qrCode.complete(null);
        };

        Consumer<DriverState> onUpdateDriverState = (driverState) -> {
            System.out.println(driverState);
            driverUpdate.complete(null);
        };

        WhatsAppClientBuilder builder = new WhatsAppClientBuilder(new DockerConfigBuilder("teste", "localhost").withAutoUpdateBaseImage(false).withMaxMemoryMB(700).build());
        builder.onInit(onInit)
                .onUpdateDriverState(onUpdateDriverState)
                .onNeedQrCode(onNeedQrCode);
        whatsAppClient = builder.builder();
        assertTrue(whatsAppClient.start().orTimeout(1, TimeUnit.MINUTES).join());
        CompletableFuture.allOf(init, CompletableFuture.anyOf(init, qrCode), driverUpdate).orTimeout(2, TimeUnit.MINUTES).join();
    }

    @BeforeEach
    void updateTestChat() {
        if (chatTest != null) {
            chatTest.update().orTimeout(30, TimeUnit.SECONDS).join();
        }
    }

    @Test
    @Order(0)
    void getTestChat() {
        chatTest = whatsAppClient.findChatByNumber("5544997258328").orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(chatTest);
    }

    @Test
    @Order(1)
    void getContactTest() {
        contactTest = chatTest.getContact().orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(contactTest);
        assertEquals("4497258328", contactTest.getPhoneNumberNoFormatted());
    }

    @Test
    @Order(2)
    void sendSimpleMsg() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test")).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(3)
    void sendSimpleMsgWithMention() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test").withMentionToContact(contactTest.getId())).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(4)
    void sendSimpleMsgQuoted() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test").withQuotedMsg(chatTest.getLastMsg().getId())).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(5)
    void sendLocation() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353)).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(6)
    void sendLocationWithName() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353, locationBuilder -> locationBuilder.withName("name"))).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(7)
    void sendLocationWithNameAndDescription() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353, locationBuilder -> locationBuilder.withName("name").withDescription("description"))).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(8)
    void sendVCard() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withVCard("João", "5544997258328")).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof VCardMessage);
    }

    @Test
    @Order(9)
    void sendFile() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID)).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(10)
    void sendFileWithCaption() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID, fileBuilder -> fileBuilder.withCaption("caption"))).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
        assertEquals("caption", ((MediaMessage) sendMsg).getCaption());
    }

    @Test
    @Order(11)
    void sendFileAsDocument() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID, fileBuilder -> fileBuilder.withForceDocument(true))).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(12)
    void sendFileAsSticker() {
        var uploadedUUID = whatsAppClient.uploadFile("image.webp", new File("filesTest/image.png")).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID)).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(13)
    void sendWebSite() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withWebSite("https://zapia.com.br")).join();
        assertNotNull(sendMsg);
    }

    @Test
    @Order(14)
    void sendButtons() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withButtons("Title", "Footer", buttonsBuilder -> buttonsBuilder.withButton("Button 1").withButton("Button 2").withButton("Button 3")).withText("Content")).join();
        assertNotNull(sendMsg);
    }

    @Test
    @Order(15)
    void sendList() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withList(listBuilder -> {
            listBuilder
                    .withTitle("Title")
                    .withDescription("Description")
                    .withFooter("Footer")
                    .withButtonText("Button Text");
            for (int x = 0; x < 10; x++) {
                listBuilder.withSection("Section " + x, sectionBuilder -> {
                    for (int y = 0; y < 20; y++) {
                        sectionBuilder.withRow("Row " + y);
                    }
                });
            }
        })).join();
        assertNotNull(sendMsg);
    }

    @Test
    @Order(99)
    void clearChat() {
        assertTrue(chatTest.clearMessages(false).join());
    }

    @Test
    @Order(100)
    void checkClearChat() {
        assertTrue(chatTest.getAllMessages().size() <= 3);
    }

    @Test
    @Order(101)
    void deleteChat() {
        assertTrue(chatTest.delete().orTimeout(30, TimeUnit.SECONDS).join());
    }

   /* @Test
    void start() {

        assertDoesNotThrow(() -> {
            if (initReceived.get()) {
                Chat chat = whatsAppClient.findChatByNumber("5544997258328").join();
                AtomicBoolean newChatMsg = new AtomicBoolean();
                AtomicBoolean updateChatMsg = new AtomicBoolean();
                AtomicBoolean removeChatMsg = new AtomicBoolean();
                assertNotNull(chat);
                var contact = chat.getContact().join();
                assertNotNull(contact);
                assertEquals("4497258328", contact.getPhoneNumberNoFormatted());
                assertTrue(chat.addMessageListener(true, message -> {
                    newChatMsg.set(true);
                }, EventType.ADD).join());
                assertTrue(chat.addMessageListener(true, message -> {
                    updateChatMsg.set(true);
                }, EventType.CHANGE, "ack").join());
                assertTrue(chat.addMessageListener(true, message -> {
                    removeChatMsg.set(true);
                }, EventType.REMOVE).join());
                Message message = chat.sendMessage(messageBuilder -> messageBuilder.withText("test")).join();
                assertNotNull(message);
                assertEquals("test", message.getBody());
                Message reply = chat.sendMessage(messageBuilder -> messageBuilder.withQuotedMsg(message.getId()).withText("reply")).join();
                assertNotNull(reply);
                assertEquals("reply", reply.getBody());
                assertTrue(message.revoke().join());
                Thread.sleep(5000);
                assertTrue(message.delete().join());
                Thread.sleep(5000);
                assertTrue(chat.delete().join());
                Thread.sleep(5000);
                CompletableFuture<Void> newChatMsg2 = new CompletableFuture<>();
                CompletableFuture<Void> newChatMsg3 = new CompletableFuture<>();
                whatsAppClient.addNewChatListener(chat1 -> {
                    assertDoesNotThrow(() -> {
                        var contactChat = chat1.getContact().join();
                        if (contactChat.getPhoneNumberNoFormatted().equals("4497258328")) {
                            chat1.update().join();
                            Message lastMsg = chat1.getLastMsg();
                            assertNotNull(lastMsg);
                            assertEquals("test", lastMsg.getBody());
                            var idFileUploaded = whatsAppClient.uploadFile(new File("pom.xml")).join();
                            Message caption = chat1.sendMessage(messageBuilder -> messageBuilder.withQuotedMsg(lastMsg.getId()).withFile(idFileUploaded, fileBuilder -> fileBuilder.withCaption("caption"))).join();
                            assertTrue(caption instanceof MediaMessage);
                            assertEquals("caption", ((MediaMessage) caption).getCaption());
                            var result = chat1.addMessageListener(messages -> {
                                var message1 = messages.get(messages.size() - 1);
                                if (message1 instanceof MediaMessage) {
                                    File join = ((MediaMessage) message1).download().join();
                                    var id = whatsAppClient.uploadFile(join.getName().split("#")[0], join).join();
                                    assertNotNull(join);
                                    Message join1 = chat1.sendMessage(messageBuilder -> messageBuilder.withQuotedMsg(message1.getId()).withFile(id)).join();
                                    assertNotNull(join1);
                                    newChatMsg3.complete(null);
                                } else {
                                    assertEquals("teste", message1.getBody());
                                    Message join = chat1.sendMessage(messageBuilder -> messageBuilder.withQuotedMsg(message1.getId()).withText("Send a file to test download functions")).join();
                                    assertNotNull(join);
                                    assertEquals("Send a file to test download functions", join.getBody());
                                    newChatMsg2.complete(null);
                                }
                            }, EventType.ADD).join();
                            assertTrue(result);
                        }
                    });
                });
                assertDoesNotThrow(() -> {
                    newChatMsg2.get(3, TimeUnit.MINUTES);
                    newChatMsg3.get(3, TimeUnit.MINUTES);
                });
                contact = chat.getContact().join();
                File profilePic = contact.getProfilePic().join();
                File profilePicFull = contact.getProfilePic(true).join();
                var idProfilePicUpload = whatsAppClient.uploadFile(profilePic).join();
                var idProfilePicFullUpload = whatsAppClient.uploadFile(profilePicFull).join();
                assertNotNull(profilePic);
                assertNotNull(profilePicFull);
                assertNotNull(chat.sendMessage(messageBuilder -> messageBuilder.withFile(idProfilePicUpload, fileBuilder -> fileBuilder.withCaption("Profile Pic"))).join());
                assertNotNull(chat.sendMessage(messageBuilder -> messageBuilder.withFile(idProfilePicFullUpload, fileBuilder -> fileBuilder.withCaption("Profile Pic Full"))).join());
                assertTrue(whatsAppClient.getAllChats().join().size() >= 1);
                assertTrue(whatsAppClient.getAllContacts().join().size() >= 1);
                Thread.sleep(3000);
                assertTrue(chat.clearMessages(false).join());
                chat.update().join();
                Thread.sleep(5000);
                assertTrue(chat.getAllMessages().size() <= 3);
                assertTrue(chat.delete().join());
                Thread.sleep(5000);
                assertTrue(newChatMsg.get());
                assertTrue(updateChatMsg.get());
                assertTrue(removeChatMsg.get());
                assertTrue(newChat.get());
                assertTrue(updateChat.get());
                assertTrue(removeChat.get());
                assertTrue(newMessage.get());
                assertTrue(updateMessage.get());
                assertTrue(removeMessage.get());
            }
        });
    }*/
}