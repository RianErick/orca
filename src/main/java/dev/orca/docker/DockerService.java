package dev.orca.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PruneResponse;
import com.github.dockerjava.api.model.PruneType;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.command.PullImageResultCallback;
import dev.orca.model.ContainerStatsView;
import dev.orca.model.ContainerView;
import dev.orca.model.DependencyGraph;
import dev.orca.model.DependencyKind;
import dev.orca.model.DependencyLink;
import dev.orca.model.ImageView;
import dev.orca.model.MountView;
import dev.orca.model.NetworkView;
import dev.orca.model.PrunePreview;
import dev.orca.model.PruneResult;
import dev.orca.model.VolumeView;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DockerService implements Closeable {

    private static final int STATS_POOL_SIZE = 4;
    private static final long STATS_TIMEOUT_MS = 900;

    private final DockerClient client;
    private final ConcurrentHashMap<String, ContainerStatsView> statsCache = new ConcurrentHashMap<>();
    private final AtomicLong statsEpoch = new AtomicLong();
    private final ExecutorService statsPool = Executors.newFixedThreadPool(STATS_POOL_SIZE, runnable -> {
        Thread thread = new Thread(runnable, "orca-stats");
        thread.setDaemon(true);
        return thread;
    });

    public DockerService(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public void ping() {
        client.pingCmd().exec();
    }

    /**
     * Fast container list. Applies any cached live stats without blocking on the Docker stats API.
     */
    public List<ContainerView> listContainers(boolean all) {
        List<Container> containers = client.listContainersCmd().withShowAll(all).exec();
        List<ContainerView> views = new ArrayList<>(containers.size());
        Set<String> seen = new HashSet<>(containers.size() * 2);
        for (Container container : containers) {
            String name = firstName(container.getNames());
            String ports = formatPorts(container.getPorts());
            String status = container.getStatus() != null ? container.getStatus() : "";
            boolean running = "running".equalsIgnoreCase(container.getState());
            String id = container.getId();
            seen.add(id);
            ContainerStatsView stats = running ? statsCache.get(id) : null;
            views.add(new ContainerView(
                    id,
                    shortId(id),
                    name,
                    nullToEmpty(container.getImage()),
                    status,
                    ports,
                    running,
                    stats
            ));
        }
        statsCache.keySet().removeIf(id -> !seen.contains(id));
        return views;
    }

    public ContainerStatsView cachedStats(String containerId) {
        return containerId == null ? null : statsCache.get(containerId);
    }

    /**
     * Fetches live stats in the background. Invokes {@code onProgress} on worker threads
     * (throttled); the caller should hop to the GUI thread. Never blocks the caller.
     */
    public void refreshStatsAsync(List<String> runningIds, Runnable onProgress) {
        if (runningIds == null || runningIds.isEmpty()) {
            return;
        }
        long epoch = statsEpoch.incrementAndGet();
        AtomicInteger remaining = new AtomicInteger(runningIds.size());
        AtomicLong lastNotifyNanos = new AtomicLong(0);
        for (String id : runningIds) {
            statsPool.execute(() -> {
                if (epoch != statsEpoch.get()) {
                    return;
                }
                try {
                    ContainerStatsView sample = statsOnce(id);
                    if (sample != null && epoch == statsEpoch.get()) {
                        statsCache.put(id, sample);
                    }
                } finally {
                    int left = remaining.decrementAndGet();
                    if (epoch != statsEpoch.get() || onProgress == null) {
                        return;
                    }
                    long now = System.nanoTime();
                    long prev = lastNotifyNanos.get();
                    boolean due = left == 0 || now - prev >= 120_000_000L;
                    if (due && lastNotifyNanos.compareAndSet(prev, now)) {
                        onProgress.run();
                    } else if (left == 0) {
                        onProgress.run();
                    }
                }
            });
        }
    }

    private static final Set<String> BUILT_IN_NETWORKS = Set.of("bridge", "host", "none");

    /**
     * Dry-run style preview of what {@link #pruneUnused()} would remove on this host.
     * Used so the UI can warn clearly that prune is global — not limited to the selected row.
     */
    public PrunePreview previewPrune() {
        List<String> stopped = new ArrayList<>();
        for (Container container : client.listContainersCmd().withShowAll(true).exec()) {
            if ("running".equalsIgnoreCase(container.getState())) {
                continue;
            }
            String name = firstName(container.getNames());
            if (name.isBlank()) {
                name = shortId(container.getId());
            }
            stopped.add(name);
        }
        stopped.sort(String.CASE_INSENSITIVE_ORDER);

        int danglingImages = client.listImagesCmd().withDanglingFilter(true).exec().size();

        int unusedNetworks = 0;
        for (Network network : client.listNetworksCmd().exec()) {
            String name = nullToEmpty(network.getName());
            if (BUILT_IN_NETWORKS.contains(name)) {
                continue;
            }
            try {
                Network inspected = client.inspectNetworkCmd()
                        .withNetworkId(network.getId() != null ? network.getId() : name)
                        .exec();
                Map<String, Network.ContainerNetworkConfig> attached = inspected.getContainers();
                if (attached == null || attached.isEmpty()) {
                    unusedNetworks++;
                }
            } catch (Exception ignored) {
                // Skip networks we cannot inspect; better under-count than block prune preview.
            }
        }

        int unusedVolumes = 0;
        for (VolumeView volume : listVolumes(true)) {
            if (volume.useCount() == 0) {
                unusedVolumes++;
            }
        }

        return PrunePreview.of(stopped, danglingImages, unusedNetworks, unusedVolumes);
    }

    /**
     * Removes stopped containers, dangling images, unused networks and unused volumes.
     * Host-wide — not limited to any UI selection. Returns bytes reclaimed per resource type
     * (Docker does not report deleted IDs).
     */
    public PruneResult pruneUnused() {
        long containers = spaceReclaimed(client.pruneCmd(PruneType.CONTAINERS).exec());
        long images = spaceReclaimed(client.pruneCmd(PruneType.IMAGES).withDangling(true).exec());
        long networks = spaceReclaimed(client.pruneCmd(PruneType.NETWORKS).exec());
        long volumes = spaceReclaimed(client.pruneCmd(PruneType.VOLUMES).exec());
        return new PruneResult(containers, images, networks, volumes);
    }

    public ContainerStatsView statsOnce(String containerId) {
        String id = requireNonBlank(containerId, "Container id is required");
        AtomicReference<Statistics> sample = new AtomicReference<>();
        try {
            client.statsCmd(id)
                    .withNoStream(true)
                    .exec(new ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            if (statistics != null) {
                                sample.set(statistics);
                            }
                        }
                    })
                    .awaitCompletion(STATS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
        return toStatsView(sample.get());
    }

    private static ContainerStatsView toStatsView(Statistics statistics) {
        if (statistics == null) {
            return null;
        }
        double cpu = cpuPercent(statistics);
        MemoryStatsConfig memory = statistics.getMemoryStats();
        long memUsage = memory != null && memory.getUsage() != null ? memory.getUsage() : -1L;
        long memLimit = memory != null && memory.getLimit() != null ? memory.getLimit() : -1L;

        long rx = 0;
        long tx = 0;
        boolean hasNet = false;
        Map<String, StatisticNetworksConfig> networks = statistics.getNetworks();
        if (networks == null || networks.isEmpty()) {
            networks = statistics.getNetwork();
        }
        if (networks != null) {
            for (StatisticNetworksConfig net : networks.values()) {
                if (net == null) {
                    continue;
                }
                hasNet = true;
                rx += net.getRxBytes() != null ? net.getRxBytes() : 0L;
                tx += net.getTxBytes() != null ? net.getTxBytes() : 0L;
            }
        }
        return new ContainerStatsView(
                cpu,
                memUsage,
                memLimit,
                hasNet ? rx : -1L,
                hasNet ? tx : -1L
        );
    }

    private static double cpuPercent(Statistics statistics) {
        CpuStatsConfig cpu = statistics.getCpuStats();
        CpuStatsConfig pre = statistics.getPreCpuStats();
        if (cpu == null || pre == null) {
            return -1;
        }
        CpuUsageConfig usage = cpu.getCpuUsage();
        CpuUsageConfig preUsage = pre.getCpuUsage();
        if (usage == null || preUsage == null
                || usage.getTotalUsage() == null || preUsage.getTotalUsage() == null
                || cpu.getSystemCpuUsage() == null || pre.getSystemCpuUsage() == null) {
            return -1;
        }
        long cpuDelta = usage.getTotalUsage() - preUsage.getTotalUsage();
        long systemDelta = cpu.getSystemCpuUsage() - pre.getSystemCpuUsage();
        if (cpuDelta <= 0 || systemDelta <= 0) {
            return 0;
        }
        long online = cpu.getOnlineCpus() != null && cpu.getOnlineCpus() > 0
                ? cpu.getOnlineCpus()
                : (usage.getPercpuUsage() != null ? usage.getPercpuUsage().size() : 1L);
        if (online <= 0) {
            online = 1;
        }
        return (cpuDelta / (double) systemDelta) * online * 100.0;
    }

    private static long spaceReclaimed(PruneResponse response) {
        if (response == null || response.getSpaceReclaimed() == null) {
            return 0L;
        }
        return Math.max(0L, response.getSpaceReclaimed());
    }

    /** Human-readable byte size for status messages. */
    public static String formatBytes(long bytes) {
        return formatSize(bytes);
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

    public List<VolumeView> listVolumes() {
        return listVolumes(true);
    }

    /**
     * @param includeUsage when {@code false}, skips scanning container mounts (fast KPI refresh)
     */
    public List<VolumeView> listVolumes(boolean includeUsage) {
        ListVolumesResponse response = client.listVolumesCmd().exec();
        List<InspectVolumeResponse> volumes = response.getVolumes();
        if (volumes == null) {
            return List.of();
        }
        Map<String, List<String>> usageByVolume = includeUsage ? volumeUsageIndex() : Map.of();
        List<VolumeView> views = new ArrayList<>(volumes.size());
        for (InspectVolumeResponse volume : volumes) {
            String name = nullToEmpty(volume.getName());
            List<String> usages = usageByVolume.getOrDefault(name, List.of());
            views.add(new VolumeView(
                    name,
                    nullToEmpty(volume.getDriver()),
                    nullToEmpty(volume.getMountpoint()),
                    usages.size(),
                    List.copyOf(usages)
            ));
        }
        views.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return views;
    }

    public String createVolume(String name, String driver) {
        String volumeName = requireNonBlank(name, "Volume name is required");
        String volumeDriver = (driver == null || driver.isBlank()) ? "local" : driver.trim();
        CreateVolumeResponse response = client.createVolumeCmd()
                .withName(volumeName)
                .withDriver(volumeDriver)
                .exec();
        return response.getName() != null ? response.getName() : volumeName;
    }

    public void removeVolume(String name) {
        client.removeVolumeCmd(requireNonBlank(name, "Volume name is required")).exec();
    }

    /**
     * Ego-graph for a container: attached networks plus volume/bind mounts (1 hop).
     */
    public DependencyGraph graphForContainer(String containerId) {
        String id = requireNonBlank(containerId, "Container id is required");
        InspectContainerResponse inspected = client.inspectContainerCmd(id).exec();
        String name = inspected.getName() != null
                ? firstName(new String[]{inspected.getName()})
                : shortId(id);
        boolean running = inspected.getState() != null && Boolean.TRUE.equals(inspected.getState().getRunning());
        String image = inspected.getConfig() != null ? nullToEmpty(inspected.getConfig().getImage()) : "";
        String focusDetail = (running ? "running" : "stopped")
                + (image.isBlank() ? "" : "  ·  " + image)
                + "  ·  " + shortId(id);

        List<DependencyLink> links = new ArrayList<>();
        NetworkSettings networks = inspected.getNetworkSettings();
        if (networks != null && networks.getNetworks() != null) {
            for (Map.Entry<String, ContainerNetwork> entry : networks.getNetworks().entrySet()) {
                String networkName = nullToEmpty(entry.getKey());
                if (networkName.isBlank()) {
                    continue;
                }
                ContainerNetwork endpoint = entry.getValue();
                String networkId = endpoint != null ? nullToEmpty(endpoint.getNetworkID()) : "";
                String detail = formatNetworkEndpoint(endpoint);
                links.add(new DependencyLink(
                        DependencyKind.NETWORK,
                        networkId.isBlank() ? networkName : networkId,
                        networkName,
                        detail
                ));
            }
        }

        if (inspected.getMounts() != null) {
            for (InspectContainerResponse.Mount mount : inspected.getMounts()) {
                links.add(mountLink(mount));
            }
        }

        return new DependencyGraph(DependencyKind.CONTAINER, id, name, focusDetail, links);
    }

    /**
     * Ego-graph for a network: containers attached to it (1 hop).
     */
    public DependencyGraph graphForNetwork(String networkIdOrName) {
        String key = requireNonBlank(networkIdOrName, "Network id is required");
        Network network = client.inspectNetworkCmd().withNetworkId(key).exec();
        String name = nullToEmpty(network.getName());
        String id = nullToEmpty(network.getId());
        String focusDetail = nullToEmpty(network.getDriver())
                + (network.getScope() == null || network.getScope().isBlank() ? "" : "  ·  " + network.getScope())
                + "  ·  " + shortId(id);

        List<DependencyLink> links = new ArrayList<>();
        Map<String, Network.ContainerNetworkConfig> attached = network.getContainers();
        if (attached != null) {
            for (Map.Entry<String, Network.ContainerNetworkConfig> entry : attached.entrySet()) {
                String containerId = nullToEmpty(entry.getKey());
                Network.ContainerNetworkConfig cfg = entry.getValue();
                String containerName = cfg != null && cfg.getName() != null && !cfg.getName().isBlank()
                        ? firstName(new String[]{cfg.getName()})
                        : shortId(containerId);
                String ip = cfg != null ? nullToEmpty(cfg.getIpv4Address()) : "";
                String detail = ip.isBlank() ? "" : "ip " + ip;
                links.add(new DependencyLink(
                        DependencyKind.CONTAINER,
                        containerId,
                        containerName,
                        detail
                ));
            }
        }

        return new DependencyGraph(
                DependencyKind.NETWORK,
                id.isBlank() ? key : id,
                name.isBlank() ? key : name,
                focusDetail,
                links
        );
    }

    /**
     * Ego-graph for a named volume: containers that mount it (1 hop).
     */
    public DependencyGraph graphForVolume(String volumeName) {
        String name = requireNonBlank(volumeName, "Volume name is required");
        InspectVolumeResponse volume = client.inspectVolumeCmd(name).exec();
        String focusDetail = nullToEmpty(volume.getDriver())
                + (volume.getMountpoint() == null || volume.getMountpoint().isBlank()
                ? ""
                : "  ·  " + volume.getMountpoint());

        List<DependencyLink> links = new ArrayList<>();
        for (MountView mount : listAllMounts()) {
            if (!name.equals(mount.name())) {
                continue;
            }
            String detail = mount.destination()
                    + " (" + mount.access() + ")"
                    + (mount.mode().isBlank() ? "" : "  " + mount.mode());
            links.add(new DependencyLink(
                    DependencyKind.CONTAINER,
                    mount.containerName(),
                    mount.containerName(),
                    detail
            ));
        }

        return new DependencyGraph(DependencyKind.VOLUME, name, name, focusDetail, links);
    }

    private static DependencyLink mountLink(InspectContainerResponse.Mount mount) {
        String destination = mount.getDestination() != null
                ? nullToEmpty(mount.getDestination().getPath())
                : "";
        boolean rw = mount.getRW() == null || Boolean.TRUE.equals(mount.getRW());
        boolean namedVolume = mount.getName() != null && !mount.getName().isBlank();
        String label = namedVolume ? mount.getName() : nullToEmpty(mount.getSource());
        String id = namedVolume ? mount.getName() : nullToEmpty(mount.getSource());
        String detail = (destination.isBlank() ? "" : "→ " + destination + " ")
                + "(" + (rw ? "rw" : "ro") + ")"
                + (mount.getMode() == null || mount.getMode().isBlank() ? "" : "  " + mount.getMode());
        return new DependencyLink(
                namedVolume ? DependencyKind.VOLUME : DependencyKind.BIND,
                id,
                label,
                detail.trim()
        );
    }

    private static String formatNetworkEndpoint(ContainerNetwork endpoint) {
        if (endpoint == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String ip = nullToEmpty(endpoint.getIpAddress());
        if (!ip.isBlank()) {
            Integer prefix = endpoint.getIpPrefixLen();
            parts.add("ip " + ip + (prefix != null && prefix > 0 ? "/" + prefix : ""));
        }
        String mac = nullToEmpty(endpoint.getMacAddress());
        if (!mac.isBlank()) {
            parts.add("mac " + mac);
        }
        return String.join("  ·  ", parts);
    }

    /**
     * Mounts attached to a container (named volumes and bind mounts).
     */
    public List<MountView> listContainerMounts(String containerId) {
        String id = requireNonBlank(containerId, "Container id is required");
        InspectContainerResponse inspected = client.inspectContainerCmd(id).exec();
        String containerName = inspected.getName() != null
                ? firstName(new String[]{inspected.getName()})
                : shortId(id);

        List<MountView> mounts = new ArrayList<>();
        if (inspected.getMounts() != null) {
            for (InspectContainerResponse.Mount mount : inspected.getMounts()) {
                String destination = mount.getDestination() != null
                        ? nullToEmpty(mount.getDestination().getPath())
                        : "";
                boolean rw = mount.getRW() == null || Boolean.TRUE.equals(mount.getRW());
                mounts.add(new MountView(
                        containerName,
                        mount.getName() != null && !mount.getName().isBlank() ? "volume" : "bind",
                        nullToEmpty(mount.getName()),
                        nullToEmpty(mount.getSource()),
                        destination,
                        nullToEmpty(mount.getMode()),
                        rw
                ));
            }
        }
        return mounts;
    }

    /**
     * Every mount across all containers — useful for volume "who uses this" detail.
     */
    public List<MountView> listAllMounts() {
        List<MountView> mounts = new ArrayList<>();
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            String containerName = firstName(container.getNames());
            if (containerName.isBlank()) {
                containerName = shortId(container.getId());
            }
            if (container.getMounts() == null) {
                continue;
            }
            for (ContainerMount mount : container.getMounts()) {
                boolean rw = mount.getRw() == null || Boolean.TRUE.equals(mount.getRw());
                mounts.add(new MountView(
                        containerName,
                        mount.getName() != null && !mount.getName().isBlank() ? "volume" : "bind",
                        nullToEmpty(mount.getName()),
                        nullToEmpty(mount.getSource()),
                        nullToEmpty(mount.getDestination()),
                        nullToEmpty(mount.getMode()),
                        rw
                ));
            }
        }
        return mounts;
    }

    private Map<String, List<String>> volumeUsageIndex() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (MountView mount : listAllMounts()) {
            if (mount.name() == null || mount.name().isBlank()) {
                continue;
            }
            String line = mount.containerName() + " → " + mount.destination()
                    + " (" + mount.access() + ")";
            index.computeIfAbsent(mount.name(), key -> new ArrayList<>()).add(line);
        }
        return index;
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
        statsEpoch.incrementAndGet();
        statsPool.shutdownNow();
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
