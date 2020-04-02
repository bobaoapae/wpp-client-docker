package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.*;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppClientTest {

    private JLabel label;
    private WhatsAppClient whatsAppClient;


    @Test
    void start() {
        CompletableFuture<Void> init = new CompletableFuture();
        CompletableFuture<Void> qrCode = new CompletableFuture();
        CompletableFuture<Void> driverUpdate = new CompletableFuture();
        CompletableFuture<Void> error = new CompletableFuture();

        AtomicBoolean newChat = new AtomicBoolean();
        AtomicBoolean removeChat = new AtomicBoolean();
        AtomicBoolean updateChat = new AtomicBoolean();
        AtomicBoolean removeMessage = new AtomicBoolean();
        AtomicBoolean newMessage = new AtomicBoolean();
        AtomicBoolean updateMessage = new AtomicBoolean();

        AtomicBoolean initReceived = new AtomicBoolean();

        Runnable onInit = () -> {
            System.out.println("init");
            init.complete(null);
            initReceived.set(true);
            whatsAppClient.addUpdateMessageListener(message -> {
                System.out.println("updateMsg");
                updateMessage.set(true);
            });
            whatsAppClient.addRemoveMessageListener(message -> {
                System.out.println("removeMsg");
                removeMessage.set(true);
            });
            whatsAppClient.addNewMessageListener(message -> {
                System.out.println("newMsg");
                newMessage.set(true);
            });

            whatsAppClient.addUpdateChatListener(chat -> {
                System.out.println("updateChat");
                updateChat.set(true);
            });
            whatsAppClient.addNewChatListener(chat -> {
                System.out.println("newChat");
                newChat.set(true);
            });
            whatsAppClient.addRemoveChatListener(chat -> {
                System.out.println("removeChat");
                removeChat.set(true);
            });
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

        Consumer<Throwable> onError = (throwable) -> {
            throwable.printStackTrace();
            error.complete(null);
        };


        WhatsAppClientBuilder builder = new WhatsAppClientBuilder("localhost", 2375, false, "teste");
        builder.onInit(onInit)
                .onError(onError)
                .onUpdateDriverState(onUpdateDriverState)
                .onNeedQrCode(onNeedQrCode)
                .onWsDisconnect((code, reason, remote) -> {
                    if (remote) {
                        assertTrue(whatsAppClient.start().orTimeout(3, TimeUnit.MINUTES).join());
                    }
                });
        whatsAppClient = builder.builder();
        assertTrue(whatsAppClient.start().orTimeout(3, TimeUnit.MINUTES).join());
        assertDoesNotThrow(() -> {
            CompletableFuture.allOf(init, CompletableFuture.anyOf(init, qrCode), driverUpdate).orTimeout(5, TimeUnit.MINUTES).join();
        });
        assertDoesNotThrow(() -> {
            if (initReceived.get()) {
                Chat chat = whatsAppClient.findChatById("554491050665@c.us").join();
                AtomicBoolean newChatMsg = new AtomicBoolean();
                AtomicBoolean updateChatMsg = new AtomicBoolean();
                AtomicBoolean removeChatMsg = new AtomicBoolean();
                assertNotNull(chat);
                assertNotNull(chat.getContact());
                assertEquals("4491050665", chat.getContact().getPhoneNumberNoFormatted());
                assertTrue(chat.addMessageListener(true, message -> {
                    newChatMsg.set(true);
                }, EventType.ADD).join());
                assertTrue(chat.addMessageListener(true, message -> {
                    updateChatMsg.set(true);
                }, EventType.CHANGE, "ack").join());
                assertTrue(chat.addMessageListener(true, message -> {
                    removeChatMsg.set(true);
                }, EventType.REMOVE).join());
                Message message = chat.sendMessage("teste").join();
                assertNotNull(message);
                assertEquals("teste", message.getBody());
                Message reply = message.reply("reply").join();
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
                        Message lastMsg = chat1.getLastMsg();
                        assertNotNull(lastMsg);
                        assertEquals("teste", lastMsg.getBody());
                        MediaMessage caption = lastMsg.reply(new File("pom.xml"), "caption").join();
                        assertNotNull(caption);
                        assertEquals("caption", caption.getCaption());
                        chat1.addMessageListener(message1 -> {
                            if (message1 instanceof MediaMessage) {
                                File join = ((MediaMessage) message1).download().join();
                                assertNotNull(join);
                                MediaMessage join1 = message1.reply(join, join.getName().split("#")[0], "").join();
                                assertNotNull(join1);
                                newChatMsg3.complete(null);
                            } else {
                                assertEquals("teste", message1.getBody());
                                Message join = message1.reply("Envie um arquivo para testar o download").join();
                                assertNotNull(join);
                                assertEquals("Envie um arquivo para testar o download", join.getBody());
                                newChatMsg2.complete(null);
                            }
                        }, EventType.ADD);
                    });
                });
                assertDoesNotThrow(() -> {
                    newChatMsg2.get(3, TimeUnit.MINUTES);
                    newChatMsg3.get(3, TimeUnit.MINUTES);
                });
                File profilePic = chat.getContact().getProfilePic().join();
                File profilePicFull = chat.getContact().getProfilePic(true).join();
                assertNotNull(profilePic);
                assertNotNull(profilePicFull);
                assertNotNull(chat.sendMessage(profilePic, "Foto de Perfil").join());
                assertNotNull(chat.sendMessage(profilePicFull, "Foto de Perfil Full").join());
                assertTrue(whatsAppClient.getAllChats().join().size() >= 1);
                assertTrue(whatsAppClient.getAllContacts().join().size() >= 1);
                Thread.sleep(3000);
                assertTrue(chat.clearMessages(false).join());
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
    }
}