package p2p;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public final class PeerServer {
    private final int port;
    private final Path sharedDirectory;
    private final Path webDirectory;
    private final HttpServer server;
    private final VersionControl versionControl;
    private final ActivityLogger activityLogger;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static final class PeerInfo {
        public final String id;
        public final String ip;
        public final int port;
        public final String label;
        public final String firstConnected;
        public volatile String lastSeen;
        public volatile boolean isOnline;

        public PeerInfo(String ip, int port, String label) {
            this.id = ip + ":" + port;
            this.ip = ip;
            this.port = port;
            this.label = label != null && !label.isBlank() ? label : this.id;
            this.firstConnected = Instant.now().toString();
            this.lastSeen = this.firstConnected;
            this.isOnline = true;
        }

        public String toJson() {
            return "{" +
                    "\"id\":" + JsonUtil.quote(id) + "," +
                    "\"ip\":" + JsonUtil.quote(ip) + "," +
                    "\"port\":" + port + "," +
                    "\"label\":" + JsonUtil.quote(label) + "," +
                    "\"firstConnected\":" + JsonUtil.quote(firstConnected) + "," +
                    "\"lastSeen\":" + JsonUtil.quote(lastSeen) + "," +
                    "\"isOnline\":" + isOnline +
                    "}";
        }
    }

    private final Map<String, PeerInfo> connectedPeers = new ConcurrentHashMap<>();

    private PeerServer(int port, Path sharedDirectory, Path webDirectory) throws IOException {
        this.port = port;
        this.sharedDirectory = sharedDirectory.toAbsolutePath().normalize();
        this.webDirectory = webDirectory.toAbsolutePath().normalize();
        Files.createDirectories(this.sharedDirectory);

        this.versionControl = new VersionControl(this.sharedDirectory);
        this.activityLogger = new ActivityLogger(this.versionControl.getVcsDirectory());

        // Perform initial disk scan
        this.versionControl.syncDiskFiles(this.activityLogger);

        // Schedule periodic disk scan every 2 seconds
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                versionControl.syncDiskFiles(activityLogger);
            } catch (Exception ignored) {
            }
        }, 2, 2, TimeUnit.SECONDS);

        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // File endpoints
        server.createContext("/api/files", new FilesHandler());
        server.createContext("/api/files/create", new CreateFileHandler());
        server.createContext("/api/files/edit", new EditFileHandler());
        server.createContext("/api/files/delete", new DeleteFileHandler());
        server.createContext("/api/download", new DownloadHandler());

        // Version Control endpoints
        server.createContext("/api/vcs/history", new VcsHistoryHandler());
        server.createContext("/api/vcs/file-history", new VcsFileHistoryHandler());
        server.createContext("/api/vcs/content", new VcsContentHandler());
        server.createContext("/api/vcs/diff", new VcsDiffHandler());
        server.createContext("/api/vcs/revert", new VcsRevertHandler());

        // Activity and Peer Logging endpoints
        server.createContext("/api/logs", new LogsHandler());
        server.createContext("/api/logs/record", new RecordLogHandler());
        server.createContext("/api/logs/clear", new ClearLogsHandler());
        server.createContext("/api/peers", new PeersHandler());
        server.createContext("/api/peers/register", new PeerRegisterHandler());
        server.createContext("/api/peers/disconnect", new PeerDisconnectHandler());

        // Static files
        server.createContext("/", new StaticHandler());

        server.setExecutor(Executors.newCachedThreadPool());

        activityLogger.log("SYSTEM", "SERVER_START", "Local", "LAN Share server started on port " + port);
    }

    private String getClientIdentifier(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null) {
            return "Local";
        }
        String host = exchange.getRemoteAddress().getAddress().getHostAddress();
        if ("127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            return "Local (" + host + ")";
        }
        return host + ":" + exchange.getRemoteAddress().getPort();
    }

    private final class FilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            // Sync any newly added/edited/deleted files on disk
            versionControl.syncDiskFiles(activityLogger);

            List<Path> filePaths;
            try (var paths = Files.list(sharedDirectory)) {
                filePaths = paths.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList());
            }

            List<String> filesJson = new ArrayList<>();
            for (Path path : filePaths) {
                String name = path.getFileName().toString();
                long size = Files.size(path);
                String lastModified = Files.getLastModifiedTime(path).toInstant().toString();
                var state = versionControl.getFileState(name);
                int version = state != null ? state.latestVersion : 1;
                String sha256 = state != null ? state.latestSha256 : "";

                filesJson.add("{" +
                        "\"name\":" + JsonUtil.quote(name) + "," +
                        "\"size\":" + size + "," +
                        "\"version\":" + version + "," +
                        "\"lastModified\":" + JsonUtil.quote(lastModified) + "," +
                        "\"sha256\":" + JsonUtil.quote(sha256) +
                        "}");
            }

            String body = "{\"files\":[" + String.join(",", filesJson) + "]}";
            sendJson(exchange, 200, body);
        }
    }

    private final class CreateFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> form = readParameters(exchange);
            String fileName = form.get("name");
            String content = form.getOrDefault("content", "");
            String message = form.getOrDefault("message", "Created via web interface");
            String author = form.getOrDefault("author", getClientIdentifier(exchange));

            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "File name is required");
                return;
            }
            if (fileName.contains("/") || fileName.contains("\\")
                    || ".".equals(fileName) || "..".equals(fileName)) {
                sendText(exchange, 400, "Only a file name is allowed");
                return;
            }

            try {
                versionControl.createFile(fileName, content, author, message, activityLogger);
                sendJson(exchange, 201, "{\"message\":\"File created\",\"fileName\":" + JsonUtil.quote(fileName) + ",\"version\":1}");
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, e.getMessage());
            }
        }
    }

    private final class EditFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> form = readParameters(exchange);
            String fileName = form.get("name");
            String content = form.getOrDefault("content", "");
            String message = form.getOrDefault("message", "Edited via web interface");
            String author = form.getOrDefault("author", getClientIdentifier(exchange));

            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "File name is required");
                return;
            }

            try {
                versionControl.editFile(fileName, content, author, message, activityLogger);
                var state = versionControl.getFileState(fileName);
                int ver = state != null ? state.latestVersion : 1;
                sendJson(exchange, 200, "{\"message\":\"File updated\",\"fileName\":" + JsonUtil.quote(fileName) + ",\"version\":" + ver + "}");
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, e.getMessage());
            }
        }
    }

    private final class DeleteFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> form = readParameters(exchange);
            String fileName = form.get("name");
            String message = form.getOrDefault("message", "Deleted via web interface");
            String author = form.getOrDefault("author", getClientIdentifier(exchange));

            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "File name is required");
                return;
            }

            try {
                versionControl.deleteFile(fileName, author, message, activityLogger);
                sendJson(exchange, 200, "{\"message\":\"File deleted\",\"fileName\":" + JsonUtil.quote(fileName) + "}");
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, e.getMessage());
            }
        }
    }

    private final class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> query = queryParameters(exchange);
            String fileName = query.get("name");
            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "Missing name query parameter");
                return;
            }

            int version = 0;
            if (query.containsKey("version")) {
                try {
                    version = Integer.parseInt(query.get("version"));
                } catch (NumberFormatException ignored) {
                }
            }

            Path requested = versionControl.resolveDownloadPath(fileName, version);
            if (!Files.isRegularFile(requested)) {
                sendText(exchange, 404, "File not found");
                return;
            }

            String client = getClientIdentifier(exchange);
            var state = versionControl.getFileState(fileName);
            int ver = version > 0 ? version : (state != null ? state.latestVersion : 1);
            activityLogger.logDownload(client, fileName, ver);

            String downloadName = version > 0 ? "v" + version + "_" + fileName : fileName;

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/octet-stream");
            headers.set("Content-Disposition", "attachment; filename=\"" + safeHeaderValue(downloadName) + "\"");
            headers.set("Content-Length", Long.toString(Files.size(requested)));
            exchange.sendResponseHeaders(200, Files.size(requested));
            try (OutputStream output = exchange.getResponseBody()) {
                Files.copy(requested, output);
            }
        }
    }

    // Version Control Handlers

    private final class VcsHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String historyJson = versionControl.getFullHistoryJson();
            sendJson(exchange, 200, "{\"commits\":" + historyJson + "}");
        }
    }

    private final class VcsFileHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String fileName = queryParameters(exchange).get("name");
            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "Missing name query parameter");
                return;
            }
            sendJson(exchange, 200, versionControl.getFileHistoryJson(fileName));
        }
    }

    private final class VcsContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            Map<String, String> query = queryParameters(exchange);
            String fileName = query.get("name");
            int version = 0;
            if (query.containsKey("version")) {
                try {
                    version = Integer.parseInt(query.get("version"));
                } catch (NumberFormatException ignored) {
                }
            }
            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "Missing name parameter");
                return;
            }
            try {
                String content = versionControl.getFileContentAtVersion(fileName, version);
                sendJson(exchange, 200, "{\"fileName\":" + JsonUtil.quote(fileName) +
                        ",\"version\":" + version +
                        ",\"content\":" + JsonUtil.quote(content) + "}");
            } catch (Exception e) {
                sendText(exchange, 404, e.getMessage());
            }
        }
    }

    private final class VcsDiffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            Map<String, String> query = queryParameters(exchange);
            String fileName = query.get("name");
            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "Missing name query parameter");
                return;
            }
            int v1 = 0;
            int v2 = 0;
            try {
                if (query.containsKey("v1")) v1 = Integer.parseInt(query.get("v1"));
                if (query.containsKey("v2")) v2 = Integer.parseInt(query.get("v2"));
            } catch (NumberFormatException ignored) {
            }
            String diffJson = versionControl.computeDiffJson(fileName, v1, v2);
            sendJson(exchange, 200, diffJson);
        }
    }

    private final class VcsRevertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> form = readParameters(exchange);
            String fileName = form.get("name");
            String versionStr = form.get("version");
            String author = form.getOrDefault("author", getClientIdentifier(exchange));

            if (fileName == null || versionStr == null) {
                sendText(exchange, 400, "name and version are required");
                return;
            }
            try {
                int ver = Integer.parseInt(versionStr);
                var commit = versionControl.revertFile(fileName, ver, author, activityLogger);
                sendJson(exchange, 200, "{\"message\":\"File reverted\",\"commit\":" + commit.toJson() + "}");
            } catch (Exception e) {
                sendText(exchange, 400, e.getMessage());
            }
        }
    }

    // Activity and Peer Logging Handlers

    private final class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            Map<String, String> query = queryParameters(exchange);
            String category = query.get("category");
            int limit = 100;
            if (query.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(query.get("limit"));
                } catch (NumberFormatException ignored) {
                }
            }
            String logsJson = activityLogger.toJson(category, limit);
            sendJson(exchange, 200, "{\"logs\":" + logsJson + "}");
        }
    }

    private final class RecordLogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> form = readParameters(exchange);
            String category = form.getOrDefault("category", "PEER_ACTIVITY");
            String action = form.getOrDefault("action", "INFO");
            String peer = form.getOrDefault("peer", getClientIdentifier(exchange));
            String details = form.getOrDefault("details", "");

            var entry = activityLogger.log(category, action, peer, details);
            sendJson(exchange, 201, "{\"message\":\"Logged\",\"entry\":" + entry.toJson() + "}");
        }
    }

    private final class ClearLogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            activityLogger.clear();
            activityLogger.log("SYSTEM", "LOGS_CLEARED", getClientIdentifier(exchange), "Activity log history cleared");
            sendJson(exchange, 200, "{\"message\":\"Logs cleared\"}");
        }
    }

    private final class PeersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String peersJson = connectedPeers.values().stream()
                    .map(PeerInfo::toJson)
                    .collect(Collectors.joining(",", "[", "]"));
            sendJson(exchange, 200, "{\"peers\":" + peersJson + "}");
        }
    }

    private final class PeerRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> form = readParameters(exchange);
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            int parsedPort = port;
            if (form.containsKey("port")) {
                try {
                    parsedPort = Integer.parseInt(form.get("port"));
                } catch (NumberFormatException ignored) {
                }
            }
            final int peerPort = parsedPort;
            String label = form.getOrDefault("label", "Peer @ " + clientIp + ":" + peerPort);
            String peerKey = clientIp + ":" + peerPort;

            PeerInfo peerInfo = connectedPeers.computeIfAbsent(peerKey, k -> new PeerInfo(clientIp, peerPort, label));
            peerInfo.lastSeen = Instant.now().toString();
            peerInfo.isOnline = true;

            activityLogger.logConnection(peerKey, "Peer [" + peerKey + "] (" + label + ") connected");
            sendJson(exchange, 200, "{\"message\":\"Peer registered\",\"peer\":" + peerInfo.toJson() + "}");
        }
    }

    private final class PeerDisconnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> form = readParameters(exchange);
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            int peerPort = port;
            if (form.containsKey("port")) {
                try {
                    peerPort = Integer.parseInt(form.get("port"));
                } catch (NumberFormatException ignored) {
                }
            }
            String peerKey = form.getOrDefault("peer", clientIp + ":" + peerPort);
            PeerInfo peer = connectedPeers.remove(peerKey);
            if (peer != null) {
                peer.isOnline = false;
            }
            activityLogger.logDisconnection(peerKey, "Peer [" + peerKey + "] disconnected");
            sendJson(exchange, 200, "{\"message\":\"Peer disconnected\"}");
        }
    }

    private final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            String relative = "/".equals(requestPath) ? "index.html" : requestPath.substring(1);
            Path requested = webDirectory.resolve(relative).normalize();
            String contentType = contentType(relative);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (requested.startsWith(webDirectory) && Files.isRegularFile(requested)) {
                exchange.sendResponseHeaders(200, Files.size(requested));
                try (OutputStream output = exchange.getResponseBody()) {
                    Files.copy(requested, output);
                }
                return;
            }
            String resourceName = "web/" + relative;
            try (var input = PeerServer.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) {
                    sendText(exchange, 404, "Not found");
                    return;
                }
                byte[] content = input.readAllBytes();
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(content);
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = readPort(args);
        Path shared = Paths.get(readOption(args, "--shared", "shared"));
        Path web = Paths.get(readOption(args, "--web", "src/main/resources/web"));
        PeerServer peer = new PeerServer(port, shared, web);
        peer.server.start();
        System.out.printf("LAN Share with Version Control & Activity Logger is running at http://localhost:%d%n", port);
        System.out.printf("Sharing files from: %s%n", peer.sharedDirectory);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static int readPort(String[] args) {
        String value = readOption(args, "--port", "8000");
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port must be between 1 and 65535: " + value);
        }
    }

    private static String readOption(String[] args, String option, String defaultValue) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return defaultValue;
    }

    private static Map<String, String> queryParameters(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return Map.of();
        }
        return java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(part -> part.length == 2)
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8),
                        (first, ignored) -> first));
    }

    private static Map<String, String> readParameters(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return Map.of();
        }
        if (contentType != null && contentType.contains("application/json")) {
            return JsonUtil.parseSimpleJson(body);
        }
        return java.util.Arrays.stream(body.split("&"))
                .map(part -> part.split("=", 2))
                .filter(part -> part.length == 2)
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8),
                        (first, ignored) -> first));
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body);
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String safeHeaderValue(String value) {
        return value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static String contentType(String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lowerPath.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lowerPath.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lowerPath.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
