package p2p;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class VersionControl {

    public static final class Commit {
        public final String id;
        public final String fileName;
        public final String action; // ADDED, MODIFIED, REMOVED, RESTORED
        public final int version;
        public final String timestamp;
        public final String author;
        public final String message;
        public final long size;
        public final String sha256;

        public Commit(String id, String fileName, String action, int version,
                      String timestamp, String author, String message, long size, String sha256) {
            this.id = id;
            this.fileName = fileName;
            this.action = action;
            this.version = version;
            this.timestamp = timestamp;
            this.author = author != null && !author.isBlank() ? author : "Local";
            this.message = message != null ? message : "";
            this.size = size;
            this.sha256 = sha256 != null ? sha256 : "";
        }

        public String toJson() {
            return "{" +
                    "\"id\":" + JsonUtil.quote(id) + "," +
                    "\"fileName\":" + JsonUtil.quote(fileName) + "," +
                    "\"action\":" + JsonUtil.quote(action) + "," +
                    "\"version\":" + version + "," +
                    "\"timestamp\":" + JsonUtil.quote(timestamp) + "," +
                    "\"author\":" + JsonUtil.quote(author) + "," +
                    "\"message\":" + JsonUtil.quote(message) + "," +
                    "\"size\":" + size + "," +
                    "\"sha256\":" + JsonUtil.quote(sha256) +
                    "}";
        }
    }

    public static final class FileState {
        public final String fileName;
        public int latestVersion;
        public String latestSha256;
        public long latestSize;
        public String lastAction;
        public final List<Commit> commits = new ArrayList<>();

        public FileState(String fileName) {
            this.fileName = fileName;
        }
    }

    private final Path sharedDirectory;
    private final Path vcsDirectory;
    private final Path versionsDirectory;
    private final Path historyFile;

    private final List<Commit> allCommits = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, FileState> fileStates = new ConcurrentHashMap<>();

    public VersionControl(Path sharedDirectory) throws IOException {
        this.sharedDirectory = sharedDirectory;
        this.vcsDirectory = sharedDirectory.resolve(".vcs");
        this.versionsDirectory = vcsDirectory.resolve("versions");
        this.historyFile = vcsDirectory.resolve("history.json");

        Files.createDirectories(this.versionsDirectory);
        loadHistory();
    }

    public Path getSharedDirectory() {
        return sharedDirectory;
    }

    public Path getVcsDirectory() {
        return vcsDirectory;
    }

    private synchronized void loadHistory() {
        if (!Files.isRegularFile(historyFile)) {
            return;
        }
        try {
            String json = Files.readString(historyFile, StandardCharsets.UTF_8);
            if (json.isBlank() || !json.trim().startsWith("[")) {
                return;
            }
            Pattern pattern = Pattern.compile("\\{([^{}]+)\\}");
            Matcher matcher = pattern.matcher(json);
            while (matcher.find()) {
                String obj = "{" + matcher.group(1) + "}";
                var map = JsonUtil.parseSimpleJson(obj);
                try {
                    String id = map.getOrDefault("id", "rev-0");
                    String fileName = map.getOrDefault("fileName", "");
                    String action = map.getOrDefault("action", "ADDED");
                    int version = Integer.parseInt(map.getOrDefault("version", "1"));
                    String timestamp = map.getOrDefault("timestamp", Instant.now().toString());
                    String author = map.getOrDefault("author", "Local");
                    String message = map.getOrDefault("message", "");
                    long size = Long.parseLong(map.getOrDefault("size", "0"));
                    String sha256 = map.getOrDefault("sha256", "");

                    if (!fileName.isBlank()) {
                        Commit commit = new Commit(id, fileName, action, version, timestamp, author, message, size, sha256);
                        allCommits.add(commit);
                        FileState state = fileStates.computeIfAbsent(fileName, FileState::new);
                        state.commits.add(commit);
                        if (version >= state.latestVersion) {
                            state.latestVersion = version;
                            state.latestSha256 = sha256;
                            state.latestSize = size;
                            state.lastAction = action;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void saveHistory() {
        try {
            Files.createDirectories(historyFile.getParent());
            List<Commit> copy;
            synchronized (allCommits) {
                copy = new ArrayList<>(allCommits);
            }
            String json = copy.stream()
                    .map(Commit::toJson)
                    .collect(Collectors.joining(",", "[", "]"));
            Files.writeString(historyFile, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private String nextCommitId() {
        return "rev-" + (allCommits.size() + 1) + "-" + System.currentTimeMillis() % 10000;
    }

    private Path getVersionPath(String fileName, int version) {
        return versionsDirectory.resolve(fileName).resolve("v" + version);
    }

    public synchronized Commit recordChange(String fileName, String action, String author, String message) throws IOException {
        Path filePath = sharedDirectory.resolve(fileName).normalize();
        FileState state = fileStates.computeIfAbsent(fileName, FileState::new);

        int newVersion = "REMOVED".equalsIgnoreCase(action) ? state.latestVersion : state.latestVersion + 1;
        long size = 0;
        String sha256 = "";

        if (!"REMOVED".equalsIgnoreCase(action)) {
            if (Files.isRegularFile(filePath)) {
                size = Files.size(filePath);
                sha256 = computeSha256(filePath);
                Path versionPath = getVersionPath(fileName, newVersion);
                Files.createDirectories(versionPath.getParent());
                Files.copy(filePath, versionPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String id = nextCommitId();
        Commit commit = new Commit(id, fileName, action, newVersion, timestamp, author, message, size, sha256);

        allCommits.add(commit);
        state.commits.add(commit);
        state.latestVersion = newVersion;
        state.latestSha256 = sha256;
        state.latestSize = size;
        state.lastAction = action;

        saveHistory();
        return commit;
    }

    public synchronized void createFile(String fileName, String content, String author, String message, ActivityLogger logger) throws IOException {
        Path target = sharedDirectory.resolve(fileName).normalize();
        if (!target.getParent().equals(sharedDirectory)) {
            throw new IllegalArgumentException("Invalid file name");
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("A file with that name already exists");
        }
        Files.writeString(target, content != null ? content : "", StandardCharsets.UTF_8);
        Commit commit = recordChange(fileName, "ADDED", author, message != null && !message.isBlank() ? message : "Initial creation");
        if (logger != null) {
            logger.logVcs("ADDED", fileName, commit.version, author, commit.message);
        }
    }

    public synchronized void editFile(String fileName, String content, String author, String message, ActivityLogger logger) throws IOException {
        Path target = sharedDirectory.resolve(fileName).normalize();
        if (!target.getParent().equals(sharedDirectory) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("File not found");
        }
        Files.writeString(target, content != null ? content : "", StandardCharsets.UTF_8);
        Commit commit = recordChange(fileName, "MODIFIED", author, message != null && !message.isBlank() ? message : "Updated content");
        if (logger != null) {
            logger.logVcs("MODIFIED", fileName, commit.version, author, commit.message);
        }
    }

    public synchronized void deleteFile(String fileName, String author, String message, ActivityLogger logger) throws IOException {
        Path target = sharedDirectory.resolve(fileName).normalize();
        if (!target.getParent().equals(sharedDirectory) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("File not found");
        }
        Files.delete(target);
        Commit commit = recordChange(fileName, "REMOVED", author, message != null && !message.isBlank() ? message : "File deleted");
        if (logger != null) {
            logger.logVcs("REMOVED", fileName, commit.version, author, commit.message);
        }
    }

    public synchronized Commit revertFile(String fileName, int targetVersion, String author, ActivityLogger logger) throws IOException {
        Path target = sharedDirectory.resolve(fileName).normalize();
        if (!target.getParent().equals(sharedDirectory)) {
            throw new IllegalArgumentException("Invalid file name");
        }
        Path versionPath = getVersionPath(fileName, targetVersion);
        if (!Files.isRegularFile(versionPath)) {
            throw new IllegalArgumentException("Version v" + targetVersion + " not found for file " + fileName);
        }
        Files.copy(versionPath, target, StandardCopyOption.REPLACE_EXISTING);
        String commitMsg = "Reverted to version v" + targetVersion;
        Commit commit = recordChange(fileName, "RESTORED", author, commitMsg);
        if (logger != null) {
            logger.logVcs("RESTORED", fileName, commit.version, author, commitMsg);
        }
        return commit;
    }

    public synchronized void syncDiskFiles(ActivityLogger logger) {
        try {
            if (!Files.exists(sharedDirectory)) {
                return;
            }
            Set<String> diskFiles = new HashSet<>();
            try (var stream = Files.list(sharedDirectory)) {
                List<Path> paths = stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .collect(Collectors.toList());

                for (Path path : paths) {
                    String fileName = path.getFileName().toString();
                    diskFiles.add(fileName);
                    long size = Files.size(path);
                    String sha256 = computeSha256(path);

                    FileState state = fileStates.get(fileName);
                    if (state == null || "REMOVED".equalsIgnoreCase(state.lastAction)) {
                        // New file detected on disk!
                        String action = state == null ? "ADDED" : "RESTORED";
                        Commit commit = recordChange(fileName, action, "Disk / External", "Detected on disk");
                        if (logger != null) {
                            logger.logVcs(action, fileName, commit.version, "Disk", "Auto-detected file addition on disk");
                        }
                    } else if (!sha256.equals(state.latestSha256)) {
                        // File modified on disk!
                        Commit commit = recordChange(fileName, "MODIFIED", "Disk / External", "Detected modification on disk");
                        if (logger != null) {
                            logger.logVcs("MODIFIED", fileName, commit.version, "Disk", "Auto-detected file modification on disk");
                        }
                    }
                }
            }

            // Check if any tracked file was deleted outside the app
            for (Map.Entry<String, FileState> entry : fileStates.entrySet()) {
                String fileName = entry.getKey();
                FileState state = entry.getValue();
                if (!diskFiles.contains(fileName) && !"REMOVED".equalsIgnoreCase(state.lastAction)) {
                    Commit commit = recordChange(fileName, "REMOVED", "Disk / External", "Detected deletion from disk");
                    if (logger != null) {
                        logger.logVcs("REMOVED", fileName, commit.version, "Disk", "Auto-detected file deletion from disk");
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized String getFileContentAtVersion(String fileName, int version) throws IOException {
        Path path;
        if (version <= 0) {
            path = sharedDirectory.resolve(fileName).normalize();
        } else {
            path = getVersionPath(fileName, version);
        }
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException("File " + fileName + " version " + version + " not found");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public Path resolveDownloadPath(String fileName, int version) {
        if (version <= 0) {
            return sharedDirectory.resolve(fileName).normalize();
        }
        return getVersionPath(fileName, version);
    }

    public synchronized String getFullHistoryJson() {
        List<Commit> copy;
        synchronized (allCommits) {
            copy = new ArrayList<>(allCommits);
        }
        Collections.reverse(copy); // most recent first
        return copy.stream()
                .map(Commit::toJson)
                .collect(Collectors.joining(",", "[", "]"));
    }

    public synchronized String getFileHistoryJson(String fileName) {
        FileState state = fileStates.get(fileName);
        if (state == null) {
            return "{\"fileName\":" + JsonUtil.quote(fileName) + ",\"latestVersion\":0,\"commits\":[]}";
        }
        List<Commit> copy = new ArrayList<>(state.commits);
        Collections.reverse(copy);
        String commitsJson = copy.stream().map(Commit::toJson).collect(Collectors.joining(",", "[", "]"));
        return "{\"fileName\":" + JsonUtil.quote(fileName) +
                ",\"latestVersion\":" + state.latestVersion +
                ",\"lastAction\":" + JsonUtil.quote(state.lastAction) +
                ",\"commits\":" + commitsJson + "}";
    }

    public synchronized FileState getFileState(String fileName) {
        return fileStates.get(fileName);
    }

    public static String computeSha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return "";
        }
    }

    public synchronized String computeDiffJson(String fileName, int v1, int v2) {
        try {
            String content1 = getFileContentAtVersion(fileName, v1);
            String content2 = getFileContentAtVersion(fileName, v2);

            // Check if binary
            if (isBinaryString(content1) || isBinaryString(content2)) {
                return "{" +
                        "\"fileName\":" + JsonUtil.quote(fileName) + "," +
                        "\"v1\":" + v1 + "," +
                        "\"v2\":" + v2 + "," +
                        "\"isBinary\":true," +
                        "\"message\":\"Binary file differences cannot be displayed as text diff.\"," +
                        "\"diff\":[]," +
                        "\"stats\":{\"additions\":0,\"deletions\":0,\"unchanged\":0}" +
                        "}";
            }

            List<String> lines1 = Arrays.asList(content1.split("\r?\n", -1));
            List<String> lines2 = Arrays.asList(content2.split("\r?\n", -1));

            // Myers / LCS algorithm for line diff
            int m = lines1.size();
            int n = lines2.size();
            int[][] dp = new int[m + 1][n + 1];

            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    if (lines1.get(i).equals(lines2.get(j))) {
                        dp[i + 1][j + 1] = dp[i][j] + 1;
                    } else {
                        dp[i + 1][j + 1] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                    }
                }
            }

            // Backtrack to build diff
            List<String> diffItems = new ArrayList<>();
            int i = m, j = n;
            int additions = 0;
            int deletions = 0;
            int unchanged = 0;

            List<String[]> reversedDiff = new ArrayList<>();
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && lines1.get(i - 1).equals(lines2.get(j - 1))) {
                    reversedDiff.add(new String[]{"EQUAL", lines1.get(i - 1), String.valueOf(i), String.valueOf(j)});
                    unchanged++;
                    i--;
                    j--;
                } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                    reversedDiff.add(new String[]{"INSERT", lines2.get(j - 1), "", String.valueOf(j)});
                    additions++;
                    j--;
                } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                    reversedDiff.add(new String[]{"DELETE", lines1.get(i - 1), String.valueOf(i), ""});
                    deletions++;
                    i--;
                }
            }
            Collections.reverse(reversedDiff);

            for (String[] item : reversedDiff) {
                String lineJson = "{" +
                        "\"type\":" + JsonUtil.quote(item[0]) + "," +
                        "\"text\":" + JsonUtil.quote(item[1]) + "," +
                        "\"oldLine\":" + JsonUtil.quote(item[2]) + "," +
                        "\"newLine\":" + JsonUtil.quote(item[3]) +
                        "}";
                diffItems.add(lineJson);
            }

            return "{" +
                    "\"fileName\":" + JsonUtil.quote(fileName) + "," +
                    "\"v1\":" + v1 + "," +
                    "\"v2\":" + v2 + "," +
                    "\"isBinary\":false," +
                    "\"diff\":[" + String.join(",", diffItems) + "]," +
                    "\"stats\":{" +
                    "\"additions\":" + additions + "," +
                    "\"deletions\":" + deletions + "," +
                    "\"unchanged\":" + unchanged +
                    "}" +
                    "}";
        } catch (Exception e) {
            return "{" +
                    "\"fileName\":" + JsonUtil.quote(fileName) + "," +
                    "\"v1\":" + v1 + "," +
                    "\"v2\":" + v2 + "," +
                    "\"error\":" + JsonUtil.quote(e.getMessage()) + "," +
                    "\"diff\":[]" +
                    "}";
        }
    }

    private static boolean isBinaryString(String s) {
        if (s == null) return false;
        int checkLen = Math.min(s.length(), 4096);
        for (int i = 0; i < checkLen; i++) {
            if (s.charAt(i) == 0) return true;
        }
        return false;
    }
}
