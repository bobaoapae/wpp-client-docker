package br.com.zapia.wpp.client.docker;

public class DockerConfigBuilder {

    private final String identity;
    private final String remoteAddress;
    private int remotePort;
    private String insideDockerHostVolumeLocation;
    private int maxMemoryMB;
    private boolean autoUpdateBaseImage;

    public DockerConfigBuilder(String identity, String remoteAddress) {
        this.identity = identity;
        this.remoteAddress = remoteAddress;
        this.remotePort = 2375;
        this.insideDockerHostVolumeLocation = "/home/docker/zapia/wpp-client-docker/caches";
        this.maxMemoryMB = 500;
        this.autoUpdateBaseImage = true;
    }

    public DockerConfigBuilder withRemotePort(int remotePort) {
        this.remotePort = remotePort;
        return this;
    }

    public DockerConfigBuilder withInsideDockerHostVolumeLocation(String insideDockerHostVolumeLocation) {
        this.insideDockerHostVolumeLocation = insideDockerHostVolumeLocation;
        return this;
    }

    public DockerConfigBuilder withMaxMemoryMB(int maxMemoryMB) {
        this.maxMemoryMB = maxMemoryMB;
        return this;
    }

    public DockerConfigBuilder withAutoUpdateBaseImage(boolean autoUpdateBaseImage) {
        this.autoUpdateBaseImage = autoUpdateBaseImage;
        return this;
    }

    public DockerConfig build() {
        return new DockerConfig(identity, remoteAddress, remotePort, insideDockerHostVolumeLocation, maxMemoryMB, autoUpdateBaseImage);
    }
}
