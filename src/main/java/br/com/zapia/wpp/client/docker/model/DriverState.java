package br.com.zapia.wpp.client.docker.model;

public enum DriverState {
    UNLOADED,
    CONNECTING,
    INITIALIZING,
    INIT_NEW_SESSION,
    RESTORING_OLD_SESSION,
    RESOLVING_CHALLENGE,
    WAITING_SYNC,
    CONNECTED
}
