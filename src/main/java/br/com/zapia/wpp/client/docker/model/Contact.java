package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.google.gson.JsonObject;

import javax.swing.text.MaskFormatter;
import java.io.File;
import java.text.ParseException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Contact extends WhatsAppObjectWithId<Contact> {

    private String name;
    private String shortName;
    private String pushName;
    private String phoneNumber;

    protected Contact(WhatsAppClient client, JsonObject jsonObject) {
        super(client, jsonObject);
    }

    public CompletableFuture<File> getProfilePic() {
        return getProfilePic(false);
    }

    public CompletableFuture<File> getProfilePic(boolean full) {
        return getClient().getProfilePic(getId(), full);
    }

    public String getSafeName() {
        if (!getPushName().isEmpty()) {
            return getPushName();
        } else if (!getName().isEmpty()) {
            return getName();
        } else {
            return "Name Error";
        }
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getPhoneNumberNoFormatted() {
        return phoneNumber.replaceAll("[^0-9]+", "");
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getPushName() {
        return pushName;
    }

    @Override
    void build() {
        super.build();
        String phoneTemp = getId().split("@")[0];
        phoneTemp = phoneTemp.substring(2);
        if (phoneTemp.length() == 10) {
            try {
                MaskFormatter formatador = new MaskFormatter("(##) ####-####");
                formatador.setValueContainsLiteralCharacters(false);
                phoneTemp = formatador.valueToString(phoneTemp);
            } catch (ParseException ex) {
                Logger.getLogger(Contact.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else if (phoneTemp.length() == 11) {
            try {
                MaskFormatter formatador = new MaskFormatter("(##) #####-####");
                formatador.setValueContainsLiteralCharacters(false);
                phoneTemp = formatador.valueToString(phoneTemp);
            } catch (ParseException ex) {
                Logger.getLogger(Contact.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        phoneNumber = phoneTemp;
        name = jsonObject.get("name").getAsString();
        shortName = jsonObject.get("shortName").getAsString();
        pushName = jsonObject.get("pushName").getAsString();
    }

    @Override
    protected void update(Contact baseWhatsAppObject) {
        super.update(baseWhatsAppObject);
        name = baseWhatsAppObject.name;
        shortName = baseWhatsAppObject.shortName;
        pushName = baseWhatsAppObject.pushName;
    }
}
