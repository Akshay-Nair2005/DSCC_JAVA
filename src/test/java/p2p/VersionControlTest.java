package p2p;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

public class VersionControlTest {
    public static void main(String[] args) throws Exception {
        Path testDir = Files.createTempDirectory("p2p-test-shared");
        try {
            System.out.println("Running tests in: " + testDir);

            VersionControl vcs = new VersionControl(testDir);
            ActivityLogger logger = new ActivityLogger(vcs.getVcsDirectory());

            // 1. Test Create File
            vcs.createFile("doc.txt", "Line 1\nLine 2\nLine 3", "Local", "Initial doc creation", logger);
            var state = vcs.getFileState("doc.txt");
            assertEq(state.latestVersion, 1, "Initial version should be 1");
            assertEq(state.lastAction, "ADDED", "Action should be ADDED");
            System.out.println("✓ Create file passed");

            // 2. Test Edit File
            vcs.editFile("doc.txt", "Line 1\nLine 2 updated\nLine 3\nLine 4 added", "127.0.0.1:8002", "Updated line 2", logger);
            state = vcs.getFileState("doc.txt");
            assertEq(state.latestVersion, 2, "Updated version should be 2");
            assertEq(state.lastAction, "MODIFIED", "Action should be MODIFIED");
            System.out.println("✓ Edit file passed");

            // 3. Test Diff
            String diffJson = vcs.computeDiffJson("doc.txt", 1, 2);
            assertContains(diffJson, "\"additions\":2", "Diff should have additions");
            assertContains(diffJson, "\"deletions\":1", "Diff should have deletions");
            assertContains(diffJson, "\"INSERT\"", "Diff should contain INSERT");
            assertContains(diffJson, "\"DELETE\"", "Diff should contain DELETE");
            System.out.println("✓ Diff calculation passed");

            // 4. Test Historical Version Retrieval
            String contentV1 = vcs.getFileContentAtVersion("doc.txt", 1);
            assertEq(contentV1, "Line 1\nLine 2\nLine 3", "Content at v1 must match snapshot");
            String contentV2 = vcs.getFileContentAtVersion("doc.txt", 2);
            assertEq(contentV2, "Line 1\nLine 2 updated\nLine 3\nLine 4 added", "Content at v2 must match snapshot");
            System.out.println("✓ Version snapshot reading passed");

            // 5. Test Revert
            var revertCommit = vcs.revertFile("doc.txt", 1, "Local", logger);
            assertEq(revertCommit.version, 3, "Reverted commit is v3");
            assertEq(revertCommit.action, "RESTORED", "Action should be RESTORED");
            String restoredContent = Files.readString(testDir.resolve("doc.txt"));
            assertEq(restoredContent, "Line 1\nLine 2\nLine 3", "Restored content matches v1");
            System.out.println("✓ Revert file passed");

            // 6. Test Delete File
            vcs.deleteFile("doc.txt", "Local", "Deleting doc", logger);
            state = vcs.getFileState("doc.txt");
            assertEq(state.lastAction, "REMOVED", "Action should be REMOVED");
            assertFalse(Files.exists(testDir.resolve("doc.txt")), "File must be deleted from shared dir");
            String v1StillExists = vcs.getFileContentAtVersion("doc.txt", 1);
            assertEq(v1StillExists, "Line 1\nLine 2\nLine 3", "v1 snapshot preserved even after delete");
            System.out.println("✓ Delete file & preservation passed");

            // 7. Test External Disk Change Detection (Auto-sync)
            Files.writeString(testDir.resolve("external.txt"), "External file written directly to disk");
            vcs.syncDiskFiles(logger);
            var extState = vcs.getFileState("external.txt");
            assertEq(extState.latestVersion, 1, "External file auto-detected as v1");
            assertEq(extState.lastAction, "ADDED", "Action should be ADDED");

            // Modify external file on disk
            Files.writeString(testDir.resolve("external.txt"), "External file changed on disk!");
            vcs.syncDiskFiles(logger);
            extState = vcs.getFileState("external.txt");
            assertEq(extState.latestVersion, 2, "External file modification auto-detected as v2");
            assertEq(extState.lastAction, "MODIFIED", "Action should be MODIFIED");

            // Remove external file on disk
            Files.delete(testDir.resolve("external.txt"));
            vcs.syncDiskFiles(logger);
            extState = vcs.getFileState("external.txt");
            assertEq(extState.lastAction, "REMOVED", "External file deletion auto-detected as REMOVED");
            System.out.println("✓ Disk change detection passed");

            // 8. Test Activity Logger
            logger.logConnection("192.168.1.15:8002", "Peer connected to node");
            logger.logDownload("192.168.1.15:8002", "doc.txt", 1);
            String logsJson = logger.toJson("ALL", 50);
            assertContains(logsJson, "PEER_CONNECTED", "Logs must contain PEER_CONNECTED");
            assertContains(logsJson, "FILE_DOWNLOAD", "Logs must contain FILE_DOWNLOAD");
            assertContains(logsJson, "VCS", "Logs must contain VCS category");
            System.out.println("✓ Activity logging passed");

            System.out.println("\n==========================================");
            System.out.println("ALL VERSION CONTROL & LOGGER TESTS PASSED!");
            System.out.println("==========================================");
        } finally {
            Files.walk(testDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
        }
    }

    private static void assertEq(Object actual, Object expected, String msg) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(msg + " [Expected: " + expected + ", Actual: " + actual + "]");
        }
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) {
            throw new AssertionError(msg);
        }
    }

    private static void assertContains(String text, String sub, String msg) {
        if (!text.contains(sub)) {
            throw new AssertionError(msg + " [Did not find: " + sub + " in " + text + "]");
        }
    }
}
