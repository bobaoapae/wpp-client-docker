package br.com.zapia.wpp.client.docker.model;

import br.com.zapia.wpp.client.docker.WhatsAppClient;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.text.MaskFormatter;
import java.text.ParseException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Contact extends WhatsAppObjectWithId {

    private String phoneNumber;

    public Contact(WhatsAppClient client, JsonNode jsonNode) {
        super(client, jsonNode);
        String id = this.getId();
        String phoneTemp = id.split("@")[0];
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
    }

    public String getSafeName() {
        if (getPushName() != null) {
            return getPushName();
        } else if (getName() != null) {
            return getName();
        } else if (getFormattedName() != null) {
            return getFormattedName();
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

    public String getShortName() {
        if (getJsonNode().get("shortName") != null && getJsonNode().get("shortName").isTextual()) {
            return getJsonNode().get("shortName").asText();
        } else {
            return null;
        }
    }

    public String getName() {
        if (getJsonNode().get("name") != null && getJsonNode().get("name").isTextual()) {
            return getJsonNode().get("name").asText();
        } else {
            return null;
        }
    }

    public String getPushName() {
        if (getJsonNode().get("pushname") != null && getJsonNode().get("pushname").isTextual()) {
            return getJsonNode().get("pushname").asText();
        } else {
            return null;
        }
    }

    public String getFormattedName() {
        if (getJsonNode().get("formattedName") != null && getJsonNode().get("formattedName").isTextual()) {
            return getJsonNode().get("formattedName").asText();
        } else {
            return null;
        }
    }
}
