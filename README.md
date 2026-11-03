# LAN Share with Version Control & Peer Activity Logging

LAN Share is a peer-to-peer file sharing and decentralized version control application.
Every running instance is both a web server and a peer node. You can connect to other devices
on the LAN, track file modifications, inspect line diffs, roll back changes, and maintain an audit
trail of all peer connections and activities.

## Features

- **Decentralized Version Control (VCS)**:
  - **Full Lifecycle Tracking**: Automatically tracks when files are **Added** (v1), **Modified** (vN), **Removed**, or **Restored**.
  - **Revisions & Snapshots**: Every revision is permanently snapshotted in `.vcs/versions/<fileName>/v<num>`. Files are never lost, even if deleted from the shared folder.
  - **Line-by-Line Diffs**: Visual diff comparison between any two versions or with the previous commit.
  - **1-Click Rollback / Restore**: Revert any file back to an earlier version with automatic `RESTORED` commit recording.
  - **Auto-Sync / Disk Change Detection**: Background scanner periodically detects files added, modified, or deleted directly on disk (e.g. via Windows Explorer or external editors) and commits them to version control.
  - **File Editing & Deletion via UI**: In-browser code/text editor with commit messages.
- **Peer Connection & Activity Audit Logging**:
  - **Persistent Event Log**: Records all events into `.vcs/activity_logs.json` so audit history survives node restarts.
  - **Connection Tracking**: Logs when peers connect, register, or disconnect with IP and port identification.
  - **File Transfer Tracking**: Logs which peer downloaded what file and specific version.
  - **Peer Mesh VCS Browsing**: Inspect version histories, compare diffs, and download files directly from connected peer nodes.

## Requirements

- Java 17 or newer
- Two or more devices on the same Wi-Fi or wired LAN (or multiple ports on localhost)

## Run with the JDK only (Windows)

From this project directory, run:

```powershell
.\run.ps1
```

The script compiles the Java source into `out\classes`, creates `shared`, and
starts the server at `http://localhost:8000`. To use another port and shared folder:

```powershell
.\run.ps1 -Port 8001 -SharedDirectory shared-peer-1
```

If PowerShell blocks local scripts, run this once for the current terminal:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

Open `http://localhost:8000` in a browser. Put files in `shared`, or use the **＋ Add / Create File** button in the web UI.

## Run Multiple Local Peers

To start five peers at once in separate PowerShell windows, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-peers.ps1
```

The sample peer directories and ports:

| Shared directory | Port |
|---|---:|
| `shared-peer-1` | `8001` |
| `shared-peer-2` | `8002` |
| `shared-peer-3` | `8003` |
| `shared-peer-4` | `8004` |
| `shared-peer-5` | `8005` |

## Connecting to Another Device on the LAN

1. Open `http://localhost:8001` on Peer 1.
2. In **Connect to a peer node**, enter the target peer's IP address and Port (e.g., `192.168.1.25` and `8002`), then click **Connect Peer**.
3. Both peers will log the connection event in their persistent Activity Log.
4. You can browse the peer's files, inspect their revision history, compare diffs, and download files. Every download is logged with the requesting peer's IP and file version.

## API Endpoints

- `GET /api/files`: Lists files with name, size, latest version, last modified, and SHA-256 hash.
- `POST /api/files/create`: Creates a new file and commits version 1 (`name`, `content`, `message`).
- `POST /api/files/edit`: Updates file content with a new commit version (`name`, `content`, `message`).
- `POST /api/files/delete`: Deletes a file and records a `REMOVED` commit (`name`, `message`).
- `GET /api/download?name=...[&version=vN]`: Streams the live file or a historical version snapshot.
- `GET /api/vcs/history`: Returns full timeline of all commits across all files.
- `GET /api/vcs/file-history?name=...`: Returns version commit history for a specific file.
- `GET /api/vcs/content?name=...&version=...`: Returns the content of a file at a specific version.
- `GET /api/vcs/diff?name=...&v1=1&v2=2`: Computes line-by-line diff between two versions.
- `POST /api/vcs/revert`: Reverts file to a target version (`name`, `version`).
- `GET /api/logs`: Returns activity and connection logs.
- `POST /api/logs/record`: Records an activity event (`category`, `action`, `peer`, `details`).
- `POST /api/logs/clear`: Clears activity log history.
- `POST /api/peers/register`: Registers a peer node connection.
- `POST /api/peers/disconnect`: Disconnects a peer node.
