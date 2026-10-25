const peers = [];
const discoveredPeers = document.querySelector("#discovered-peers");
const localFiles = document.querySelector("#local-files");
const peersContainer = document.querySelector("#peers");
const message = document.querySelector("#message");

function showMessage(text, isError = false) {
  message.textContent = text;
  message.className = isError ? "message error" : "message";
}

function renderFiles(list, target, baseUrl = "") {
  target.innerHTML = "";
  if (!list.length) {
    target.innerHTML = '<li class="muted">No files in this shared directory.</li>';
    return;
  }
  list.forEach(name => {
    const item = document.createElement("li");
    item.innerHTML = `<span class="file-name">${escapeHtml(name)}</span>`;
    const link = document.createElement("a");
    link.textContent = "Download";
    link.href = `${baseUrl}/api/download?name=${encodeURIComponent(name)}`;
    link.download = name;
    item.append(link);
    target.append(item);
  });
}

async function loadLocalFiles() {
  try {
    const response = await fetch("/api/files");
    if (!response.ok) throw new Error("Server returned " + response.status);
    renderFiles((await response.json()).files, localFiles);
  } catch (error) {
    localFiles.innerHTML = `<li class="muted">Could not load files: ${escapeHtml(error.message)}</li>`;
  }
}

function renderPeers() {
  document.querySelector("#peer-count").textContent = peers.length;
  peersContainer.innerHTML = "";
  if (!peers.length) {
    peersContainer.innerHTML = '<p class="muted">No peers added yet.</p>';
    return;
  }
  peers.forEach(peer => {
    const section = document.createElement("section");
    section.className = "peer";
    section.innerHTML = `<div><strong>${escapeHtml(peer.label)}</strong><ul class="peer-files"><li class="muted">Loading...</li></ul></div><button class="secondary">Remove</button>`;
    section.querySelector("button").onclick = () => {
      peers.splice(peers.indexOf(peer), 1);
      renderPeers();
    };
    peersContainer.append(section);
    fetch(`${peer.baseUrl}/api/files`)
      .then(response => { if (!response.ok) throw new Error("HTTP " + response.status); return response.json(); })
      .then(data => renderFiles(data.files, section.querySelector(".peer-files"), peer.baseUrl))
      .catch(error => section.querySelector(".peer-files").innerHTML = `<li class="muted">Unavailable: ${escapeHtml(error.message)}</li>`);
  });
}

function peerUrl(peer) {
  return `http://${peer.host}:${peer.port}`;
}

function renderDiscoveredPeers(list) {
  discoveredPeers.innerHTML = "";
  if (!list.length) {
    discoveredPeers.innerHTML = '<p class="muted">No LAN Share devices found. Make sure another device is running the app.</p>';
    return;
  }
  list.forEach(peer => {
    const section = document.createElement("section");
    section.className = "discovered-peer";
    section.innerHTML = `<div><strong>${escapeHtml(peer.label)}</strong><span class="device-address">${escapeHtml(peer.host)}:${peer.port}</span></div><button>Connect</button>`;
    section.querySelector("button").onclick = () => connectToPeer(peer);
    discoveredPeers.append(section);
  });
}

async function scanForPeers() {
  discoveredPeers.innerHTML = '<p class="muted">Scanning the local network...</p>';
  try {
    const response = await fetch("/api/peers");
    if (!response.ok) throw new Error("Server returned " + response.status);
    renderDiscoveredPeers((await response.json()).peers);
  } catch (error) {
    discoveredPeers.innerHTML = `<p class="muted">Could not scan for devices: ${escapeHtml(error.message)}</p>`;
  }
}

async function connectToPeer(peer) {
  const baseUrl = peerUrl(peer);
  if (!window.confirm(`Connect to ${peer.label} at ${peer.host}:${peer.port}?`)) return;
  try {
    const response = await fetch(`${baseUrl}/api/files`);
    if (!response.ok) throw new Error("HTTP " + response.status);
    if (!peers.some(item => item.baseUrl === baseUrl)) {
      peers.push({ label: peer.label, baseUrl });
    }
    renderPeers();
    showMessage(`Connected to ${peer.label}. Select a file to download it.`);
  } catch (error) {
    showMessage(`Could not connect to ${baseUrl}: ${error.message}`, true);
  }
}

document.querySelector("#peer-form").onsubmit = async event => {
  event.preventDefault();
  const ip = document.querySelector("#peer-ip").value.trim();
  const port = document.querySelector("#peer-port").value;
  const baseUrl = `http://${ip}:${port}`;
  try {
    const response = await fetch(`${baseUrl}/api/files`);
    if (!response.ok) throw new Error("HTTP " + response.status);
    await connectToPeer({ label: `${ip}:${port}`, host: ip, port });
    event.target.reset();
    document.querySelector("#peer-port").value = port;
  } catch (error) {
    showMessage(`Could not connect to ${baseUrl}: ${error.message}`, true);
  }
};

document.querySelector("#refresh-local").onclick = loadLocalFiles;
document.querySelector("#refresh-peers").onclick = scanForPeers;
function escapeHtml(value) {
  return value.replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[character]));
}
loadLocalFiles();
scanForPeers();
