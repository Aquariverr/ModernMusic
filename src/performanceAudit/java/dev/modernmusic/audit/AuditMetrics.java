package dev.modernmusic.audit;

import com.google.gson.Gson;
import com.sun.management.OperatingSystemMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import java.lang.management.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import jdk.jfr.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Counts real encoded socket bytes, never packet bodies, URLs or credentials. */
final class AuditMetrics implements AutoCloseable {
    static final Gson JSON = new Gson();
    final Path directory = Path.of("").toAbsolutePath().normalize();
    final Path control = directory.resolveSibling("audit-server");
    final String role = System.getProperty("modernmusic.auditRole");
    static final boolean SHARED = Boolean.getBoolean("modernmusic.auditSharedAccount");
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final AtomicLong rx = new AtomicLong(), tx = new AtomicLong(), modRxPackets = new AtomicLong(), modTxPackets = new AtomicLong(), modTx = new AtomicLong();
    private final List<Double> work = new ArrayList<>(), intervals = new ArrayList<>();
    private final List<Double> tickIntervals = new ArrayList<>();
    private final List<Double> serverTickDurations = new ArrayList<>();
    private final List<Long> serverTickDurationEpochMs = new ArrayList<>();
    private final List<Long> frameEpochMs = new ArrayList<>(), workEpochMs = new ArrayList<>(), tickEpochMs = new ArrayList<>();
    private final Map<String, AtomicLong> packetCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicLong> packetBytes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Long> previousThreads = new HashMap<>();
    private final Recording recording;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("modernmusic-audit-writer").factory());
    private volatile RuntimeException writeFailure;
    private long previousTime = System.nanoTime(), previousCpu = os.getProcessCpuTime();
    private long previousRx, previousTx, previousModTx, previousModRxPackets, previousModTxPackets;
    private long previousGcCount = gcCount(), previousGcMs = gcMs();
    private long previousFrame, frameStart, previousTickStart, tickCountTotal;
    private String phase = "startup";
    private long phaseStart = System.currentTimeMillis();
    AuditMetrics() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("audit-pid.txt"), Long.toString(ProcessHandle.current().pid()));
        Files.writeString(directory.resolve("samples.jsonl"), "");
        Files.writeString(directory.resolve("audit-events.jsonl"), "");
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("role", role); env.put("pid", ProcessHandle.current().pid()); env.put("java", System.getProperty("java.runtime.version"));
        env.put("os", System.getProperty("os.name")); env.put("logicalProcessors", os.getAvailableProcessors());
        env.put("physicalMemoryBytes", os.getTotalMemorySize()); env.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        env.put("startedAt", Instant.now().toString());
        env.put("sharedAccountAudit", SHARED);
        env.put("baseline", Boolean.getBoolean("modernmusic.auditBaseline"));
        Files.writeString(directory.resolve("environment.json"), JSON.toJson(env));
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
        recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO);
        recording.setToDisk(true); recording.setMaxSize(256L * 1024 * 1024); recording.start();
    }
    void phase(String value, long started) throws Exception {
        boolean changed = !phase.equals(value) || phaseStart != started;
        phase = value; phaseStart = started;
        if (changed) event("audit_phase_started", Map.of("phaseStartEpochMs", started));
    }
    void framePre() {
        long now = System.nanoTime(); frameStart = now;
        if (previousFrame != 0) {
            intervals.add((now - previousFrame) / 1e6);
            frameEpochMs.add(System.currentTimeMillis());
        }
        previousFrame = now;
    }
    void framePost() {
        if (frameStart == 0) return;
        work.add((System.nanoTime() - frameStart) / 1e6); workEpochMs.add(System.currentTimeMillis());
        frameStart = 0;
    }
    void tickWork(long start) {
        if (start == 0) return;
        long now = System.nanoTime(), epoch = System.currentTimeMillis();
        work.add((now - start) / 1e6); workEpochMs.add(epoch); tickCountTotal++;
        if (previousTickStart != 0) {
            tickIntervals.add((start - previousTickStart) / 1e6);
            tickEpochMs.add(epoch - (now - start) / 1_000_000);
        }
        previousTickStart = start;
    }
    void completedServerTick(long durationNanos) {
        serverTickDurations.add(durationNanos / 1e6);
        serverTickDurationEpochMs.add(System.currentTimeMillis());
    }
    void event(String name, Map<String, Object> values) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("event", name); row.put("role", role); row.put("phase", phase);
        row.put("epochMs", System.currentTimeMillis()); row.putAll(values);
        append("audit-events.jsonl", row);
    }
    /** Rows already own copies of the mutable counters; disk and JSON work stay off game threads. */
    private void append(String filename, Map<String, Object> row) {
        if (writeFailure != null) throw writeFailure;
        writer.execute(() -> {
            try { Files.writeString(directory.resolve(filename), JSON.toJson(row) + "\n", StandardOpenOption.APPEND); }
            catch (Exception error) { writeFailure = new IllegalStateException("Audit evidence write failed: " + filename, error); }
        });
    }
    void attach(Connection connection) throws Exception {
        var field = Connection.class.getDeclaredField("channel"); field.setAccessible(true);
        Channel channel = (Channel) field.get(connection);
        if (channel == null) return;
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get("audit-wire") != null) return;
            // The packet handler wraps the synchronous outbound pipeline; the first handler sees
            // compressed, length-prefixed bytes. TCP/IP/Ethernet headers are outside this count.
            final String[] modPacket = {null};
            channel.pipeline().addFirst("audit-wire", new ChannelDuplexHandler() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof ByteBuf bytes) rx.addAndGet(bytes.readableBytes());
                    super.channelRead(ctx, msg);
                }
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (msg instanceof ByteBuf bytes) {
                        tx.addAndGet(bytes.readableBytes());
                        if (modPacket[0] != null) {
                            modTx.addAndGet(bytes.readableBytes());
                            packetBytes.computeIfAbsent(modPacket[0], k -> new AtomicLong()).addAndGet(bytes.readableBytes());
                        }
                    }
                    super.write(ctx, msg, promise);
                }
            });
            channel.pipeline().addBefore("packet_handler", "audit-packets", new ChannelDuplexHandler() {
                private String type(Object msg) {
                    CustomPacketPayload payload = msg instanceof ClientboundCustomPayloadPacket p ? p.payload()
                            : msg instanceof ServerboundCustomPayloadPacket p ? p.payload() : null;
                    return payload != null && payload.type().id().getNamespace().equals("modernmusic") ? payload.type().id().getPath() : null;
                }
                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    String type = type(msg);
                    if (type != null) { modRxPackets.incrementAndGet(); packetCounts.computeIfAbsent("in:" + type, k -> new AtomicLong()).incrementAndGet(); }
                    super.channelRead(ctx, msg);
                }
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    String type = type(msg);
                    if (type != null) { modTxPackets.incrementAndGet(); packetCounts.computeIfAbsent("out:" + type, k -> new AtomicLong()).incrementAndGet(); }
                    String previous = modPacket[0]; modPacket[0] = type;
                    try { super.write(ctx, msg, promise); } finally { modPacket[0] = previous; }
                }
            });
            try { Files.writeString(directory.resolve("pipeline.txt"), channel.pipeline().names().toString()); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        }).sync();
    }
    void sample(Map<String, Object> extra) throws Exception {
        long now = System.nanoTime(); double seconds = (now - previousTime) / 1e9;
        if (seconds < 1) return;
        long cpu = os.getProcessCpuTime(), gcCount = gcCount(), gcMs = gcMs();
        long rxNow = rx.get(), txNow = tx.get(), modTxNow = modTx.get(), inPackets = modRxPackets.get(), outPackets = modTxPackets.get();
        var row = new LinkedHashMap<String, Object>();
        row.put("time", Instant.now().toString()); row.put("epochMs", System.currentTimeMillis()); row.put("role", role);
        row.put("phase", phase); row.put("phaseStartEpochMs", phaseStart);
        row.put("phaseSeconds", (System.currentTimeMillis() - phaseStart) / 1000.0); row.put("seconds", seconds);
        row.put("cpuOneCorePercent", (cpu - previousCpu) / (seconds * 1e7));
        row.put("cpuMachinePercent", (cpu - previousCpu) / (seconds * 1e7 * os.getAvailableProcessors()));
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        row.put("heapUsedBytes", heap.getUsed()); row.put("heapCommittedBytes", heap.getCommitted());
        row.put("nonHeapUsedBytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        row.put("threads", threads.getThreadCount()); row.put("gcCount", gcCount - previousGcCount); row.put("gcMs", gcMs - previousGcMs);
        row.put("rxBytes", rxNow - previousRx); row.put("txBytes", txNow - previousTx); row.put("modTxBytes", modTxNow - previousModTx);
        row.put("modRxPackets", inPackets - previousModRxPackets); row.put("modTxPackets", outPackets - previousModTxPackets);
        row.put("workMs", new ArrayList<>(work)); row.put("frameIntervalsMs", new ArrayList<>(intervals));
        // Endpoint timestamps preserve cold frame gaps even when initialization runs in a tick
        // outside RenderFrameEvent, or when the one-second sample crosses a phase boundary.
        row.put("frameEpochMs", new ArrayList<>(frameEpochMs));
        row.put("workEpochMs", new ArrayList<>(workEpochMs));
        row.put("tickIntervalsMs", new ArrayList<>(tickIntervals)); row.put("tickEpochMs", new ArrayList<>(tickEpochMs));
        row.put("tickCountTotal", tickCountTotal);
        row.put("serverTickDurationsMs", new ArrayList<>(serverTickDurations));
        row.put("serverTickDurationEpochMs", new ArrayList<>(serverTickDurationEpochMs));
        var threadCpu = new LinkedHashMap<String, Double>();
        for (long id : threads.getAllThreadIds()) {
            var info = threads.getThreadInfo(id); if (info == null) continue;
            long value = threads.getThreadCpuTime(id); Long previous = previousThreads.put(id, value);
            if (previous != null && value >= previous && (info.getThreadName().startsWith("modernmusic")
                    || info.getThreadName().equals("Server thread") || info.getThreadName().equals("Render thread")))
                threadCpu.merge(info.getThreadName(), (value - previous) / (seconds * 1e7), Double::sum);
        }
        row.put("threadCpuOneCorePercent", threadCpu);
        var counts = new TreeMap<String, Long>(); packetCounts.forEach((k,v) -> counts.put(k, v.getAndSet(0))); row.put("modPacketTypes", counts);
        var bytesByType = new TreeMap<String, Long>(); packetBytes.forEach((k,v) -> bytesByType.put(k, v.getAndSet(0))); row.put("modTxBytesByType", bytesByType);
        row.putAll(extra);
        append("samples.jsonl", row);
        previousTime = now; previousCpu = cpu; previousRx = rxNow; previousTx = txNow; previousModTx = modTxNow;
        previousModRxPackets = inPackets; previousModTxPackets = outPackets; previousGcCount = gcCount; previousGcMs = gcMs;
        work.clear(); intervals.clear(); frameEpochMs.clear(); workEpochMs.clear(); tickIntervals.clear(); tickEpochMs.clear();
        serverTickDurations.clear(); serverTickDurationEpochMs.clear();
    }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum(); }
    private static long gcMs() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum(); }
    @Override public void close() throws Exception {
        writer.shutdown();
        if (!writer.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Audit writer did not drain");
        recording.stop(); recording.dump(directory.resolve("runtime.jfr")); recording.close();
        if (writeFailure != null) throw writeFailure;
    }
}
