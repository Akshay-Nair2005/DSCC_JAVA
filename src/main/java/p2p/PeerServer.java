package p2p;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class PeerServer {
    private static final int DISCOVERY_PORT = 39001;
    private static final String DISCOVERY_REQUEST = "LAN_SHARE_DISCOVER";
    private final Path sharedDirectory;
    private final Path webDirectory;
    private final HttpServer server;
    private final int port;

    private PeerServer(int port, Path sharedDirectory, Path webDirectory) throws IOException {
        this.port = port;
        this.sharedDirectory = sharedDirectory.toAbsolutePath().normalize();
        this.webDirectory = webDirectory.toAbsolutePath().normalize();
        Files.createDirectories(this.sharedDirectory);

        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/files", new FilesHandler());
        server.createContext("/api/download", new DownloadHandler());
        server.createContext("/api/peers", new PeersHandler());
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        startDiscoveryResponder();
    }

    private void startDiscoveryResponder() {
        final DatagramSocket socket;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
        } catch (SocketException exception) {
            System.err.println("Peer discovery responder unavailable: " + exception.getMessage());
            return;
        }
        Thread responder = new Thread(() -> {
            byte[] buffer = new byte[128];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);
                    String message = new String(request.getData(), request.getOffset(),
                            request.getLength(), StandardCharsets.UTF_8);
                    if (DISCOVERY_REQUEST.equals(message)) {
                        byte[] response = ("LAN_SHARE_PEER|" + port + "|" + hostName()).getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(response, response.length,
                                request.getAddress(), request.getPort()));
                    }
                } catch (IOException exception) {
                    if (!socket.isClosed()) {
                        System.err.println("Peer discovery responder stopped: " + exception.getMessage());
                    }
                    return;
                }
            }
        }, "lan-share-discovery");
        responder.setDaemon(true);
        responder.start();
    }

    private final class PeersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            List<String> peers = discoverPeers();
            sendJson(exchange, 200, peers.stream().collect(Collectors.joining(",", "{\"peers\":[", "]}")));
        }
    }

    private List<String> discoverPeers() throws IOException {
        List<String> peers = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(700);
            byte[] request = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            for (InetAddressBroadcast broadcast : broadcastAddresses()) {
                socket.send(new DatagramPacket(request, request.length, broadcast.address(), DISCOVERY_PORT));
            }

            byte[] buffer = new byte[256];
            while (true) {
                try {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String[] fields = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.UTF_8).split("\\|", 3);
                    if (fields.length == 3 && "LAN_SHARE_PEER".equals(fields[0])) {
                        int peerPort;
                        try {
                            peerPort = Integer.parseInt(fields[1]);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (peerPort != port || !isLocalAddress(response.getAddress())) {
                            String host = response.getAddress().getHostAddress();
                            peers.add("{\"host\":" + jsonString(host) + ",\"port\":" + peerPort
                                    + ",\"label\":" + jsonString(fields[2]) + "}");
                        }
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
            }
        }
        return peers.stream().distinct().sorted().collect(Collectors.toList());
    }

    private List<InetAddressBroadcast> broadcastAddresses() throws SocketException {
        List<InetAddressBroadcast> addresses = new ArrayList<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            networkInterface.getInterfaceAddresses().forEach(address -> {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null) {
                    addresses.add(new InetAddressBroadcast(address.getBroadcast()));
                }
            });
        }
        if (addresses.isEmpty()) {
            try {
                addresses.add(new InetAddressBroadcast(java.net.InetAddress.getByName("255.255.255.255")));
            } catch (java.net.UnknownHostException exception) {
                throw new SocketException("Could not resolve LAN broadcast address");
            }
        }
        return addresses;
    }

    private boolean isLocalAddress(java.net.InetAddress address) {
        if (address.isLoopbackAddress()) {
            return true;
        }
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var networkInterface = interfaces.nextElement();
                var addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement().equals(address)) {
                        return true;
                    }
                }
            }
        } catch (SocketException ignored) {
            return false;
        }
        return false;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException exception) {
            return "LAN device";
        }
    }

    private record InetAddressBroadcast(java.net.InetAddress address) {}

    public static void main(String[] args) throws IOException {
        int port = readPort(args);
        Path shared = Paths.get(readOption(args, "--shared", "shared"));
        Path web = Paths.get(readOption(args, "--web", "src/main/resources/web"));
        PeerServer peer = new PeerServer(port, shared, web);
        peer.server.start();
        System.out.printf("LAN Share is running at http://localhost:%d%n", port);
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

            List<String> files;
            try (var paths = Files.list(sharedDirectory)) {
                files = paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.toList());
            }
            String body = files.stream()
                    .map(PeerServer::jsonString)
                    .collect(Collectors.joining(",", "{\"files\":[", "]}"));
            sendJson(exchange, 200, body);
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
            String fileName = queryParameters(exchange).get("name");
            if (fileName == null || fileName.isBlank()) {
                sendText(exchange, 400, "Missing name query parameter");
                return;
            }

            Path requested = sharedDirectory.resolve(fileName).normalize();
            if (!requested.getParent().equals(sharedDirectory) || !Files.isRegularFile(requested)) {
                sendText(exchange, 404, "File not found");
                return;
            }

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/octet-stream");
            headers.set("Content-Disposition", "attachment; filename=\"" + safeHeaderValue(requested.getFileName().toString()) + "\"");
            headers.set("Content-Length", Long.toString(Files.size(requested)));
            exchange.sendResponseHeaders(200, Files.size(requested));
            try (OutputStream output = exchange.getResponseBody()) {
                Files.copy(requested, output);
            }
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

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body);
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
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

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
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
        return "application/octet-stream";
    }
}
