package dev.orca.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.command.PullImageResultCallback;
import dev.orca.model.ContainerView;
import dev.orca.model.ImageView;
import dev.orca.model.NetworkView;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DockerService implements Closeable {

    private final DockerClient client;

    public DockerService(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public void ping() {
        client.pingCmd().exec();
    }

    public List<ContainerView> listContainers(boolean all) {
        List<Container> containers = client.listContainersCmd().withShowAll(all).exec();
        List<ContainerView> views = new ArrayList<>(containers.size());
        for (Container container : containers) {
            String name = firstName(container.getNames());
            String ports = formatPorts(container.getPorts());
            String status = container.getStatus() != null ? container.getStatus() : "";
            boolean running = "running".equalsIgnoreCase(container.getState());
            views.add(new ContainerView(
                    container.getId(),
                    shortId(container.getId()),
                    name,
                    nullToEmpty(container.getImage()),
                    status,
                    ports,
                    running
            ));
        }
        return views;
    }

    public String createAndStart(String name, String image, String portsSpec, String envSpec, String cmdSpec) {
        String imageRef = requireNonBlank(image, "Image is required");
        CreateContainerCmd cmd = client.createContainerCmd(imageRef);

        if (name != null && !name.isBlank()) {
            cmd.withName(name.trim());
        }

        List<String> env = parseCsv(envSpec);
        if (!env.isEmpty()) {
            cmd.withEnv(env);
        }

        List<String> command = parseCommand(cmdSpec);
        if (!command.isEmpty()) {
            cmd.withCmd(command);
        }

        PortBindingSpec portBindings = parsePorts(portsSpec);
        if (!portBindings.exposed().isEmpty()) {
            cmd.withExposedPorts(portBindings.exposed());
            HostConfig hostConfig = HostConfig.newHostConfig().withPortBindings(portBindings.bindings());
            cmd.withHostConfig(hostConfig);
        }

        CreateContainerResponse created = cmd.exec();
        client.startContainerCmd(created.getId()).exec();
        return created.getId();
    }

    public void start(String id) {
        try {
            client.startContainerCmd(id).exec();
        } catch (NotModifiedException ignored) {
            // Already running — treat as success so the UI does not flash a cryptic 304.
        }
    }

    public void stop(String id) {
        try {
            client.stopContainerCmd(id).exec();
        } catch (NotModifiedException ignored) {
            // Already stopped — treat as success so the UI does not flash a cryptic 304.
        }
    }

    public void restart(String id) {
        try {
            client.restartContainerCmd(id).exec();
        } catch (NotModifiedException ignored) {
            // Engine reported no change; leave quietly.
        }
    }

    public void removeContainer(String id, boolean force) {
        client.removeContainerCmd(id).withForce(force).exec();
    }

    public String logs(String id, int tail) {
        StringBuilder out = new StringBuilder();
        try {
            client.logContainerCmd(id)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTimestamps(false)
                    .withTail(tail)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame != null && frame.getPayload() != null) {
                                out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerServiceException("Interrupted while reading logs", e);
        }
        return sanitizeLogs(out.toString());
    }

    public InspectContainerResponse inspectContainer(String id) {
        return client.inspectContainerCmd(id).exec();
    }

    public List<ImageView> listImages() {
        List<Image> images = client.listImagesCmd().withShowAll(true).exec();
        List<ImageView> views = new ArrayList<>();
        for (Image image : images) {
            String[] tags = image.getRepoTags();
            if (tags == null || tags.length == 0) {
                views.add(new ImageView(
                        image.getId(),
                        shortImageId(image.getId()),
                        "<none>:<none>",
                        formatSize(image.getSize()),
                        sizeOrZero(image.getSize())
                ));
            } else {
                for (String tag : tags) {
                    views.add(new ImageView(
                            image.getId(),
                            shortImageId(image.getId()),
                            tag,
                            formatSize(image.getSize()),
                            sizeOrZero(image.getSize())
                    ));
                }
            }
        }
        return views;
    }

    public void pull(String imageRef, Consumer<String> progress) {
        String ref = requireNonBlank(imageRef, "Image name is required");
        try {
            client.pullImageCmd(ref)
                    .exec(new PullImageResultCallback() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            super.onNext(item);
                            if (progress != null && item != null) {
                                String status = item.getStatus() != null ? item.getStatus() : "";
                                String id = item.getId() != null ? item.getId() : "";
                                progress.accept((id + " " + status).trim());
                            }
                        }
                    })
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerServiceException("Interrupted while pulling image", e);
        }
    }

    public void removeImage(String id, boolean force) {
        client.removeImageCmd(id).withForce(force).exec();
    }

    public List<NetworkView> listNetworks() {
        List<Network> networks = client.listNetworksCmd().exec();
        List<NetworkView> views = new ArrayList<>(networks.size());
        for (Network network : networks) {
            views.add(new NetworkView(
                    network.getId(),
                    shortId(network.getId()),
                    nullToEmpty(network.getName()),
                    nullToEmpty(network.getDriver()),
                    nullToEmpty(network.getScope())
            ));
        }
        return views;
    }

    public String createNetwork(String name, String driver) {
        String networkName = requireNonBlank(name, "Network name is required");
        String networkDriver = (driver == null || driver.isBlank()) ? "bridge" : driver.trim();
        CreateNetworkResponse response = client.createNetworkCmd()
                .withName(networkName)
                .withDriver(networkDriver)
                .exec();
        return response.getId();
    }

    public void removeNetwork(String id) {
        client.removeNetworkCmd(id).exec();
    }

    public static String friendlyMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }

        if (current instanceof NotModifiedException) {
            return "Nothing to do — the container is already in that state (Docker 304).";
        }
        if (current instanceof NotFoundException) {
            return "Not found — it may have been removed already.";
        }
        if (current instanceof ConflictException) {
            String detail = current.getMessage();
            return "Conflict — Docker refused the change"
                    + (detail == null || detail.isBlank() ? "." : ": " + shortDockerDetail(detail));
        }
        if (current instanceof DockerException dockerException) {
            int status = dockerException.getHttpStatus();
            String detail = shortDockerDetail(dockerException.getMessage());
            return switch (status) {
                case 304 -> "Nothing to do — already in that state (HTTP 304).";
                case 404 -> "Not found — it may have been removed already.";
                case 409 -> "Conflict — Docker refused the change: " + detail;
                case 500, 502, 503 -> "Docker engine error (" + status + "): " + detail;
                default -> status > 0
                        ? "Docker error " + status + ": " + detail
                        : detail;
            };
        }
        if (current instanceof DockerServiceException && current.getMessage() != null) {
            return current.getMessage();
        }

        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "Docker is not reachable. Is the daemon running?";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied")
                || lower.contains("connection refused")
                || lower.contains("no such file")
                || lower.contains("docker.sock")) {
            return "Docker is not accessible (" + message + "). Check DOCKER_HOST / docker.sock permissions.";
        }
        if (lower.contains("304") || lower.contains("not modified")) {
            return "Nothing to do — the container is already in that state.";
        }
        return message;
    }

    /**
     * Makes container logs safe for a terminal TextBox: strip ANSI, normalize newlines,
     * drop control characters and cap absurdly long lines.
     */
    public static String sanitizeLogs(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String noAnsi = raw.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("\u001B\\].*?\u0007", "")
                .replace("\u001B", "");
        String normalized = noAnsi.replace("\r\n", "\n").replace('\r', '\n');

        StringBuilder cleaned = new StringBuilder(normalized.length());
        int lineLength = 0;
        final int maxLine = 400;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\n') {
                cleaned.append('\n');
                lineLength = 0;
                continue;
            }
            if (ch == '\t') {
                cleaned.append("    ");
                lineLength += 4;
                continue;
            }
            if (ch < 32 || ch == 127) {
                continue;
            }
            if (lineLength >= maxLine) {
                if (lineLength == maxLine) {
                    cleaned.append('…');
                    lineLength++;
                }
                continue;
            }
            cleaned.append(ch);
            lineLength++;
        }
        return cleaned.toString();
    }

    private static String shortDockerDetail(String message) {
        if (message == null) {
            return "unknown error";
        }
        String trimmed = message.replace('\n', ' ').trim();
        // docker-java often prefixes "Status 304: {" ...
        trimmed = trimmed.replaceFirst("(?i)^Status\\s+\\d+:\\s*", "");
        if (trimmed.length() > 160) {
            return trimmed.substring(0, 157) + "...";
        }
        return trimmed.isBlank() ? "unknown error" : trimmed;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // best-effort close
        }
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0) {
            return "";
        }
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String formatPorts(ContainerPort[] ports) {
        if (ports == null || ports.length == 0) {
            return "";
        }
        return Arrays.stream(ports)
                .map(port -> {
                    String privatePort = port.getPrivatePort() != null ? String.valueOf(port.getPrivatePort()) : "";
                    String publicPort = port.getPublicPort() != null ? String.valueOf(port.getPublicPort()) : "";
                    String type = port.getType() != null ? port.getType() : "tcp";
                    if (!publicPort.isEmpty()) {
                        String ip = port.getIp() != null && !port.getIp().isBlank() ? port.getIp() + ":" : "";
                        return ip + publicPort + "->" + privatePort + "/" + type;
                    }
                    return privatePort + "/" + type;
                })
                .collect(Collectors.joining(", "));
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.length() <= 12 ? id : id.substring(0, 12);
    }

    private static String shortImageId(String id) {
        if (id == null) {
            return "";
        }
        String value = id.startsWith("sha256:") ? id.substring("sha256:".length()) : id;
        return shortId(value);
    }

    private static long sizeOrZero(Long bytes) {
        return bytes == null || bytes < 0 ? 0L : bytes;
    }

    private static String formatSize(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "";
        }
        double value = bytes.doubleValue();
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? String.format(Locale.ROOT, "%.0f %s", value, units[unit])
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DockerServiceException(message);
        }
        return value.trim();
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static List<String> parseCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static PortBindingSpec parsePorts(String raw) {
        List<ExposedPort> exposed = new ArrayList<>();
        Ports bindings = new Ports();
        if (raw == null || raw.isBlank()) {
            return new PortBindingSpec(exposed, bindings);
        }

        Map<Integer, ExposedPort> byPrivate = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String mapping = part.trim();
            if (mapping.isEmpty()) {
                continue;
            }
            String[] sides = mapping.split(":");
            if (sides.length == 1) {
                int containerPort = Integer.parseInt(sides[0].trim());
                ExposedPort exposedPort = ExposedPort.tcp(containerPort);
                byPrivate.putIfAbsent(containerPort, exposedPort);
                bindings.bind(exposedPort, Ports.Binding.bindPort(containerPort));
            } else if (sides.length == 2) {
                int hostPort = Integer.parseInt(sides[0].trim());
                int containerPort = Integer.parseInt(sides[1].trim());
                ExposedPort exposedPort = ExposedPort.tcp(containerPort);
                byPrivate.putIfAbsent(containerPort, exposedPort);
                bindings.bind(exposedPort, Ports.Binding.bindPort(hostPort));
            } else {
                throw new DockerServiceException("Invalid port mapping: " + mapping + " (use host:container or container)");
            }
        }
        exposed.addAll(byPrivate.values());
        return new PortBindingSpec(exposed, bindings);
    }

    private record PortBindingSpec(List<ExposedPort> exposed, Ports bindings) {
    }

    public static class DockerServiceException extends RuntimeException {
        public DockerServiceException(String message) {
            super(message);
        }

        public DockerServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
