# LAN Share

LAN Share is a small peer-to-peer file sharing application. Every running
instance is both a web server and a peer. Connect to another device by
entering its local IP address and port.
Files are served only from that peer's configured `shared` directory.

## Requirements

- Java 17 or newer
- Two devices on the same Wi-Fi or wired LAN

## Run with Maven (optional)

If Maven is installed, run from this project directory:

```text
mvn clean package
New-Item -ItemType Directory -Force shared
java -jar target\lan-share-1.0.0.jar --port 8080 --shared shared
```

`mvn : The term 'mvn' is not recognized` means Maven is not installed or is
not on `PATH`. The JDK-only method below does not require Maven.

## Run with the JDK only (Windows)

From this project directory, run:

```powershell
.\run.ps1
```

The script compiles the Java source into `out\classes`, creates `shared`, and
starts the server at `http://localhost:8080`. To use another port:

```powershell
.\run.ps1 -Port 8081 -SharedDirectory shared-peer-2
```

If PowerShell blocks local scripts, run this once for the current terminal:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

Open `http://localhost:8080` in a browser. Put files in `shared`; use
**Refresh** to see them.

To use a second peer on the same computer, use a different port and directory:

```text
mkdir shared-peer-2
java -jar target\lan-share-1.0.0.jar --port 8081 --shared shared-peer-2
```

The five sample peer directories use these separate ports:

| Shared directory | Port |
|---|---:|
| `shared-peer-1` | `8001` |
| `shared-peer-2` | `8002` |
| `shared-peer-3` | `8003` |
| `shared-peer-4` | `8004` |
| `shared-peer-5` | `8005` |

To start all five peers at once in separate PowerShell windows, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-peers.ps1
```

Stop any previously running peer windows before running this command again.
Each peer must be started with its matching shared directory; otherwise all
ports will serve the same files.

On another device, replace `localhost` with the host computer's LAN address,
for example `http://192.168.1.25:8080`. Enter that IP address and port in
**Add a peer**, select **Connect**, confirm the prompt, and then select
**Download** beside a shared file.

To find the host's Windows LAN address, run `ipconfig` and use the IPv4
address of the active adapter. Allow Java through Windows Firewall when
prompted, or create an inbound TCP rule for the selected port on a trusted
private network.

## How it works

- `PeerServer` binds to `0.0.0.0`, so devices on the LAN can reach it.
- `GET /api/files` returns the regular files in the shared directory as JSON.
- `GET /api/download?name=...` streams one file as an attachment.
- The static frontend calls those endpoints directly. A download travels
  from the remote peer to the browser; there is no central server.
- File names are normalized and constrained to the shared directory, which
  prevents path traversal.

This application does not provide authentication or encryption, so use it only
on a trusted LAN.
