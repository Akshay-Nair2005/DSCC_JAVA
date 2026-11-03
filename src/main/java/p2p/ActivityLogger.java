package p2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ActivityLogger {
    public static final class LogEntry {
        public final long id;
        public final String timestamp;
        public final String category; // CONNECTION, VCS, DOWNLOAD, SYNC
        public final String action;   // PEER_CONNECTED, PEER_DISCONNECTED, FILE_ADDED, FILE_MODIFIED, FILE_REMOVED, FILE_RESTORED, FILE_DOWNLOAD
        public final String peer;     // e.g. "127.0.0.1:8002", "Local", etc.
        public final String details;  // Human-readable description

        public LogEntry(long id, String timestamp, String category, String action, String peer, String details) {
            this.id = id;
            this.timestamp = timestamp;
            this.category = category;
            this.action = action;
            this.peer = peer != null ? peer : "Local";
            this.details = details != null ? details : "";
        }

        public String toJson() {
            return "{" +
                    "\"id\":" + id + "," +
                    "\"timestamp\":" + JsonUtil.quote(timestamp) + "," +
                    "\"category\":" + JsonUtil.quote(category) + "," +
                    "\"action\":" + JsonUtil.quote(action) + "," +
                    "\"peer\":" + JsonUtil.quote(peer) + "," +
                    "\"details\":" + JsonUtil.quote(details) +
                    "}";
        }
    }

    private final Path logFile;
    private final List<LogEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong idCounter = new AtomicLong(1);
    private static final int MAX_ENTRIES = 1000;

    public ActivityLogger(Path vcsDirectory) {
        this.logFile = vcsDirectory.resolve("activity_logs.json");
        loadLogs();
    }

    private synchronized void loadLogs() {
        if (!Files.isRegularFile(logFile)) {
            return;
        }
        try {
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            if (content.isBlank() || !content.trim().startsWith("[")) {
                return;
            }
            // Parse array of log entries using regex
            Pattern pattern = Pattern.compile("\\{([^{}]+)\\}");
            Matcher matcher = pattern.matcher(content);
            long maxId = 0;
            while (matcher.find()) {
                String obj = "{" + matcher.group(1) + "}";
                var map = JsonUtil.parseSimpleJson(obj);
                try {
                    long id = Long.parseLong(map.getOrDefault("id", "0"));
                    String timestamp = map.getOrDefault("timestamp", Instant.now().toString());
                    String category = map.getOrDefault("category", "SYSTEM");
                    String action = map.getOrDefault("action", "UNKNOWN");
                    String peer = map.getOrDefault("peer", "Local");
                    String details = map.getOrDefault("details", "");
                    entries.add(new LogEntry(id, timestamp, category, action, peer, details));
                    if (id > maxId) {
                        maxId = id;
                    }
                } catch (Exception ignored) {
                }
            }
            idCounter.set(maxId + 1);
        } catch (IOException ignored) {
        }
    }

    private synchronized void saveLogs() {
        try {
            Files.createDirectories(logFile.getParent());
            String json = toJson(null, 0);
            Files.writeString(logFile, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public synchronized LogEntry log(String category, String action, String peer, String details) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        LogEntry entry = new LogEntry(idCounter.getAndIncrement(), timestamp, category, action, peer, details);
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        saveLogs();
        return entry;
    }

    public void logConnection(String peer, String details) {
        log("CONNECTION", "PEER_CONNECTED", peer, details);
    }

    public void logDisconnection(String peer, String details) {
        log("CONNECTION", "PEER_DISCONNECTED", peer, details);
    }

    public void logDownload(String peer, String fileName, int version) {
        log("DOWNLOAD", "FILE_DOWNLOAD", peer, "Downloaded '" + fileName + "' (v" + version + ")");
    }

    public void logVcs(String action, String fileName, int version, String peer, String message) {
        String detail = "File '" + fileName + "' (v" + version + ") " + action.toLowerCase() +
                (message != null && !message.isBlank() ? ": " + message : "");
        log("VCS", "FILE_" + action.toUpperCase(), peer, detail);
    }

    public synchronized void clear() {
        entries.clear();
        saveLogs();
    }

    public synchronized String toJson(String categoryFilter, int limit) {
        List<LogEntry> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        // Most recent first
        Collections.reverse(copy);
        var stream = copy.stream();
        if (categoryFilter != null && !categoryFilter.isBlank() && !"ALL".equalsIgnoreCase(categoryFilter)) {
            stream = stream.filter(e -> e.category.equalsIgnoreCase(categoryFilter));
        }
        if (limit > 0) {
            stream = stream.limit(limit);
        }
        List<LogEntry> filtered = stream.collect(Collectors.toList());
        return filtered.stream()
                .map(LogEntry::toJson)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
