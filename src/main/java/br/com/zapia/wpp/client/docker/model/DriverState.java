package br.com.zapia.wpp.client.docker.model;

public enum DriverState {
    UNLOADED,
    LOADING,
    WAITING_QR_CODE_SCAN,
    QR_CODE_SCANNED,
    LOADING_STORE,
    LOGIN_FAILED,
    LOGGED,
    STORE_FAILED,
    WAITING_SYNC
}
