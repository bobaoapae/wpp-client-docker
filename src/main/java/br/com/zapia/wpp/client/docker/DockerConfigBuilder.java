package br.com.zapia.wpp.client.docker;

public class DockerConfigBuilder {

    private final String identity;
    private final String dockerImageName;
    private String dockerUserName;
    private String dockerPassword;
    private String remoteAddress;
    private int remotePort;
    private String insideDockerHostVolumeLocation;
    private int maxMemoryMB;
    private boolean autoUpdateBaseImage;
    private boolean autoRemoveContainer;

    public DockerConfigBuilder(String identity, String dockerImageName, String remoteAddress) {
        this.identity = identity;
        this.dockerImageName = dockerImageName;
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

    public DockerConfigBuilder withAutoRemoveContainer(boolean autoRemoveContainer) {
        this.autoRemoveContainer = autoRemoveContainer;
        return this;
    }

    public DockerConfigBuilder withDockerUserName(String dockerUserName) {
        this.dockerUserName = dockerUserName;
        return this;
    }

    public DockerConfigBuilder withDockerPassword(String dockerPassword) {
        this.dockerPassword = dockerPassword;
        return this;
    }

    public DockerConfig build() {
        return new DockerConfig(identity, dockerImageName, dockerUserName, dockerPassword, remoteAddress, remotePort, insideDockerHostVolumeLocation, maxMemoryMB, autoUpdateBaseImage, autoRemoveContainer);
    }
}
