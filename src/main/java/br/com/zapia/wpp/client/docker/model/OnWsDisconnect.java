package br.com.zapia.wpp.client.docker.model;

public interface OnWsDisconnect {

    void run(int code, String reason, boolean remote);

}
