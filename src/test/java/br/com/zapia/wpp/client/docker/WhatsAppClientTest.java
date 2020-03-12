package br.com.zapia.wpp.client.docker;

import br.com.zapia.wpp.client.docker.model.Chat;
import br.com.zapia.wpp.client.docker.model.DriverState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppClientTest {

    private JLabel label;


    @Test
    void start() {
        CompletableFuture<Void> init = new CompletableFuture();
        CompletableFuture<Void> qrCode = new CompletableFuture();
        CompletableFuture<Void> driverUpdate = new CompletableFuture();
        CompletableFuture<Void> error = new CompletableFuture();
        AtomicBoolean initReceived = new AtomicBoolean();

        Runnable onInit = () -> {
            System.out.println("init");
            init.complete(null);
            initReceived.set(true);
        };

        Consumer<String> onNeedQrCode = (base64) -> {
            System.out.println(base64);
            if (label == null) {
                label = new JLabel("", JLabel.LEFT);
                label.setPreferredSize(new Dimension(500, 500));
                JOptionPane.showMessageDialog(
                        null,
                        label,
                        "SCAN QR CODE", JOptionPane.INFORMATION_MESSAGE);
            }
            try {
                byte[] btDataFile = Base64.getDecoder().decode(base64.split(",")[1]);
                BufferedImage image;
                image = ImageIO.read(new ByteArrayInputStream(btDataFile));
                ImageIcon icon = new ImageIcon(image.getScaledInstance(500, 500, Image.SCALE_DEFAULT));
                label.setIcon(icon);
            } catch (Exception ex) {
                ex.printStackTrace();
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


        WhatsAppClientBuilder builder = new WhatsAppClientBuilder("localhost", "teste");
        builder.onInit(onInit)
                .onError(onError)
                .onUpdateDriverState(onUpdateDriverState)
                .onNeedQrCode(onNeedQrCode);
        WhatsAppClient whatsAppClient = builder.builder();
        assertTrue(whatsAppClient.start().orTimeout(3, TimeUnit.MINUTES).join());
        assertDoesNotThrow(() -> {
            CompletableFuture.allOf(CompletableFuture.anyOf(init, qrCode), driverUpdate).orTimeout(5, TimeUnit.MINUTES).join();
        });
        assertDoesNotThrow(() -> {
            if (initReceived.get()) {
                Chat chat = whatsAppClient.findChatById("554491050665@c.us").join();
                assertNotNull(chat);
                assertNotNull(chat.getContact());
                assertEquals("4491050665", chat.getContact().getPhoneNumberNoFormatted());
            }
        });
    }
}