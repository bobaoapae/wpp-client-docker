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

    private static final String numberChatTest = "5544991050665";

    private JLabel label;
    private WhatsAppClient whatsAppClient;
    private Chat chatTest;
    private Contact contactTest;
    private GroupChat groupChatTest;

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

        WhatsAppClientBuilder builder = new WhatsAppClientBuilder(new DockerConfigBuilder("teste", "bobaoapae/whatsapp-api:latest","localhost").withDockerUserName("bobaoapae").withDockerUserName("joao0123@").withAutoUpdateBaseImage(false).withMaxMemoryMB(700).build());
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
        chatTest = whatsAppClient.findChatByNumber(numberChatTest).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(chatTest);
    }

    @Test
    @Order(1)
    void getContactTest() {
        contactTest = chatTest.getContact().orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(contactTest);
    }

    @Test
    @Order(2)
    void sendSimpleMsg() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test")).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(3)
    void sendSimpleMsgWithMention() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test").withMentionToContact(contactTest.getId())).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(4)
    void sendSimpleMsgQuoted() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withText("test").withQuotedMsg(chatTest.getLastMsg().getId())).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertEquals("test", sendMsg.getBody());
    }

    @Test
    @Order(5)
    void sendLocation() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353)).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(6)
    void sendLocationWithName() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353, locationBuilder -> locationBuilder.withName("name"))).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(7)
    void sendLocationWithNameAndDescription() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withLocation(-24.403799, -53.523353, locationBuilder -> locationBuilder.withName("name").withDescription("description"))).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof GeoMessage);
    }

    @Test
    @Order(8)
    void sendVCard() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withVCard("João", "5544997258328")).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof VCardMessage);
    }

    @Test
    @Order(9)
    void sendFile() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).orTimeout(10, TimeUnit.SECONDS).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID)).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(10)
    void sendFileWithCaption() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).orTimeout(10, TimeUnit.SECONDS).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID, fileBuilder -> fileBuilder.withCaption("caption"))).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
        assertEquals("caption", ((MediaMessage) sendMsg).getCaption());
    }

    @Test
    @Order(11)
    void sendFileAsDocument() {
        var uploadedUUID = whatsAppClient.uploadFile(new File("filesTest/image.png")).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID, fileBuilder -> fileBuilder.withForceDocument(true))).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(12)
    void sendFileAsSticker() {
        var uploadedUUID = whatsAppClient.uploadFile("image.png", new File("filesTest/image.png")).orTimeout(10, TimeUnit.SECONDS).join();
        var sendMsg = chatTest.sendMessage(builder -> builder.withFile(uploadedUUID, fileBuilder -> fileBuilder.withForceSticker("Zapiá", "Zapiá"))).join();
        assertNotNull(sendMsg);
        assertTrue(sendMsg instanceof MediaMessage);
    }

    @Test
    @Order(13)
    void sendWebSite() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withWebSite("https://zapia.com.br")).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
    }

    @Test
    @Order(14)
    void sendButtons() {
        var sendMsg = chatTest.sendMessage(builder -> builder.withButtons("Title", "Footer", buttonsBuilder -> buttonsBuilder.withButton("Button 1").withButton("Button 2").withButton("Button 3")).withText("Content")).orTimeout(10, TimeUnit.SECONDS).join();
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
        })).orTimeout(10, TimeUnit.SECONDS).join();
        assertNotNull(sendMsg);
    }

    @Test
    @Order(99)
    void clearChat() {
        assertTrue(chatTest.clearMessages(false).orTimeout(10, TimeUnit.SECONDS).join());
    }

    @Test
    @Order(100)
    void checkClearChat() {
        assertTrue(chatTest.getAllMessages().size() <= 3);
    }

    @Test
    @Order(101)
    void deleteChat() {
        assertTrue(chatTest.delete().orTimeout(10, TimeUnit.SECONDS).join());
    }
}