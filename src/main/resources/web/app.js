// State
let peers = [];
let localFilesList = [];
let vcsCommitsList = [];
let activityLogsList = [];
let currentVcsFilter = 'ALL';
let currentLogFilter = 'ALL';
let autoRefreshTimer = null;

// DOM Elements
const localFilesContainer = document.querySelector("#local-files");
const peersContainer = document.querySelector("#peers");
const messageEl = document.querySelector("#message");
const vcsTimelineContainer = document.querySelector("#vcs-timeline-container");
const activityLogsContainer = document.querySelector("#activity-logs-container");
const dashboardRecentLogs = document.querySelector("#dashboard-recent-logs");
const fileSearchInput = document.querySelector("#file-search-input");
const logSearchInput = document.querySelector("#log-search-input");

// Modals
const editModal = document.querySelector("#edit-modal");
const historyModal = document.querySelector("#history-modal");
const diffModal = document.querySelector("#diff-modal");
const viewContentModal = document.querySelector("#view-content-modal");

// Formatters & Helpers
function showMessage(text, isError = false) {
  if (!messageEl) return;
  messageEl.textContent = text;
  messageEl.className = isError ? "message error" : "message";
  if (!isError) {
    setTimeout(() => { if (messageEl.textContent === text) messageEl.textContent = ""; }, 5000);
  }
}

function escapeHtml(value) {
  if (value === null || value === undefined) return "";
  return String(value).replace(/[&<>"']/g, c => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;"
  }[c]));
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatDate(isoString) {
  if (!isoString) return '';
  try {
    const date = new Date(isoString);
    if (isNaN(date.getTime())) return isoString;
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) +
           ' · ' + date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  } catch (e) {
    return isoString;
  }
}

function getActionBadge(action) {
  const act = (action || '').toUpperCase();
  switch (act) {
    case 'ADDED':
      return `<span class="badge badge-added">＋ Added</span>`;
    case 'MODIFIED':
      return `<span class="badge badge-modified">✎ Modified</span>`;
    case 'REMOVED':
      return `<span class="badge badge-removed">✕ Removed</span>`;
    case 'RESTORED':
      return `<span class="badge badge-restored">↺ Restored</span>`;
    default:
      return `<span class="badge badge-system">${escapeHtml(act)}</span>`;
  }
}

function getCategoryBadge(category) {
  const cat = (category || '').toUpperCase();
  switch (cat) {
    case 'CONNECTION':
      return `<span class="badge badge-conn">⌁ Connected</span>`;
    case 'VCS':
      return `<span class="badge badge-vcs">🕒 VCS</span>`;
    case 'DOWNLOAD':
      return `<span class="badge badge-download">⬇ Transfer</span>`;
    default:
      return `<span class="badge badge-system">⚙ System</span>`;
  }
}

// Navigation & Tabs
function setupNavigation() {
  const navLinks = document.querySelectorAll(".nav-item");
  const sections = document.querySelectorAll(".view-section");
  const headingEl = document.querySelector("#page-heading");
  const currentTitleEl = document.querySelector("#current-section-title");

  function switchTab(sectionId) {
    navLinks.forEach(link => {
      const active = link.getAttribute("data-section") === sectionId;
      link.classList.toggle("active", active);
    });
    sections.forEach(sec => {
      sec.style.display = sec.id === `section-${sectionId}` ? "block" : "none";
    });

    const titleMap = {
      "dashboard": "Dashboard",
      "my-files": "My Files & Versions",
      "vcs-timeline": "Version History",
      "activity-logs": "Activity & Peer Logs",
      "connected-peers": "Connected Peers"
    };
    const title = titleMap[sectionId] || "Dashboard";
    if (headingEl) headingEl.textContent = title;
    if (currentTitleEl) currentTitleEl.textContent = title.toUpperCase();
  }

  navLinks.forEach(link => {
    link.addEventListener("click", e => {
      e.preventDefault();
      const sec = link.getAttribute("data-section");
      location.hash = sec;
      switchTab(sec);
    });
  });

  window.addEventListener("hashchange", () => {
    const hash = location.hash.replace("#", "") || "dashboard";
    switchTab(hash);
  });

  const initialHash = location.hash.replace("#", "") || "dashboard";
  switchTab(initialHash);
}

// Load Local Files
async function loadLocalFiles() {
  try {
    const res = await fetch("/api/files");
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    localFilesList = Array.isArray(data.files) ? data.files : [];
    renderLocalFiles();
    updateStats();
  } catch (err) {
    if (localFilesContainer) {
      localFilesContainer.innerHTML = `<li class="muted">Could not load files: ${escapeHtml(err.message)}</li>`;
    }
  }
}

function renderLocalFiles() {
  if (!localFilesContainer) return;
  const filter = (fileSearchInput ? fileSearchInput.value : "").trim().toLowerCase();
  const filtered = localFilesList.filter(f => {
    const name = typeof f === 'object' ? f.name : f;
    return !filter || name.toLowerCase().includes(filter);
  });

  const countLabel = document.querySelector("#file-count-label");
  if (countLabel) countLabel.textContent = `${localFilesList.length} files`;

  if (!filtered.length) {
    localFilesContainer.innerHTML = `<li class="muted empty-state">${localFilesList.length === 0 ? "No shared files yet. Create or drop files into this peer's shared folder." : "No files matching search."}</li>`;
    return;
  }

  localFilesContainer.innerHTML = "";
  filtered.forEach(fileObj => {
    const name = typeof fileObj === 'object' ? fileObj.name : fileObj;
    const version = typeof fileObj === 'object' && fileObj.version ? fileObj.version : 1;
    const size = typeof fileObj === 'object' && fileObj.size !== undefined ? formatBytes(fileObj.size) : '--';
    const modified = typeof fileObj === 'object' && fileObj.lastModified ? formatDate(fileObj.lastModified) : '';

    const li = document.createElement("li");
    li.className = "file-card-item";
    li.innerHTML = `
      <div class="file-card-left">
        <div class="file-icon">▤</div>
        <div class="file-meta-col">
          <h4 title="${escapeHtml(name)}">${escapeHtml(name)}</h4>
          <div class="file-submeta">
            <span class="version-pill">v${version}</span>
            <span>${size}</span>
            <span>${modified}</span>
          </div>
        </div>
      </div>
      <div class="file-card-actions">
        <button class="action-btn btn-edit" title="Edit content & commit new version">✎ Edit</button>
        <button class="action-btn btn-history" title="View commit history & diffs">🕒 History</button>
        <a class="action-btn action-btn-primary" href="/api/download?name=${encodeURIComponent(name)}" download="${escapeHtml(name)}">⬇ Download</a>
        <button class="action-btn action-btn-danger btn-delete" title="Delete file">✕</button>
      </div>
    `;

    li.querySelector(".btn-edit").onclick = () => openEditModal(name);
    li.querySelector(".btn-history").onclick = () => openHistoryModal(name);
    li.querySelector(".btn-delete").onclick = () => confirmDeleteFile(name);

    localFilesContainer.appendChild(li);
  });
}

// Load Version Control History
async function loadVcsHistory() {
  try {
    const res = await fetch("/api/vcs/history");
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    vcsCommitsList = Array.isArray(data.commits) ? data.commits : [];
    renderVcsTimeline();
    updateStats();
  } catch (err) {
    if (vcsTimelineContainer) {
      vcsTimelineContainer.innerHTML = `<p class="muted">Could not load version history: ${escapeHtml(err.message)}</p>`;
    }
  }
}

function renderVcsTimeline() {
  if (!vcsTimelineContainer) return;
  const filtered = vcsCommitsList.filter(c => {
    if (currentVcsFilter === 'ALL') return true;
    return (c.action || '').toUpperCase() === currentVcsFilter;
  });

  if (!filtered.length) {
    vcsTimelineContainer.innerHTML = `<p class="muted empty-state">No version commits matching '${currentVcsFilter}'.</p>`;
    return;
  }

  vcsTimelineContainer.innerHTML = "";
  filtered.forEach(commit => {
    const item = document.createElement("div");
    item.className = "timeline-item";
    item.innerHTML = `
      <div class="timeline-header">
        <div class="timeline-title-area">
          ${getActionBadge(commit.action)}
          <span class="timeline-file">${escapeHtml(commit.fileName)}</span>
          <span class="version-pill">v${commit.version}</span>
        </div>
        <span class="timeline-meta">${formatDate(commit.timestamp)}</span>
      </div>
      <div class="timeline-message">
        "${escapeHtml(commit.message || 'No description provided')}"
      </div>
      <div class="timeline-footer">
        <span>Author: <strong>${escapeHtml(commit.author)}</strong></span>
        <div class="timeline-actions">
          ${commit.action !== 'REMOVED' ? `<button class="action-btn btn-view-snap">👁 View Snapshot</button>` : ''}
          ${commit.version > 1 ? `<button class="action-btn btn-diff-commit">⇄ Diff with Prev</button>` : ''}
          <button class="action-btn btn-history-file">All File Versions →</button>
        </div>
      </div>
    `;

    if (commit.action !== 'REMOVED') {
      const viewBtn = item.querySelector(".btn-view-snap");
      if (viewBtn) viewBtn.onclick = () => openViewSnapshotModal(commit.fileName, commit.version);
    }
    const diffBtn = item.querySelector(".btn-diff-commit");
    if (diffBtn) {
      diffBtn.onclick = () => openDiffModal(commit.fileName, commit.version - 1, commit.version);
    }
    const histBtn = item.querySelector(".btn-history-file");
    if (histBtn) {
      histBtn.onclick = () => openHistoryModal(commit.fileName);
    }

    vcsTimelineContainer.appendChild(item);
  });
}

// Load Activity and Peer Connection Logs
async function loadActivityLogs() {
  try {
    const res = await fetch("/api/logs");
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    activityLogsList = Array.isArray(data.logs) ? data.logs : [];
    renderActivityLogs();
    renderDashboardRecentLogs();
    updateStats();
  } catch (err) {
    if (activityLogsContainer) {
      activityLogsContainer.innerHTML = `<p class="muted">Could not load activity logs: ${escapeHtml(err.message)}</p>`;
    }
  }
}

function renderActivityLogs() {
  if (!activityLogsContainer) return;
  const search = (logSearchInput ? logSearchInput.value : "").trim().toLowerCase();
  const filtered = activityLogsList.filter(entry => {
    if (currentLogFilter !== 'ALL' && (entry.category || '').toUpperCase() !== currentLogFilter) {
      return false;
    }
    if (search) {
      const matchDetails = (entry.details || '').toLowerCase().includes(search);
      const matchPeer = (entry.peer || '').toLowerCase().includes(search);
      const matchAction = (entry.action || '').toLowerCase().includes(search);
      return matchDetails || matchPeer || matchAction;
    }
    return true;
  });

  if (!filtered.length) {
    activityLogsContainer.innerHTML = `<p class="muted empty-state">No activity logs recorded yet.</p>`;
    return;
  }

  activityLogsContainer.innerHTML = "";
  filtered.forEach(entry => {
    const div = document.createElement("div");
    div.className = "log-entry-item";
    div.innerHTML = `
      <div class="log-left">
        ${getCategoryBadge(entry.category)}
        <span class="log-details" title="${escapeHtml(entry.details)}">${escapeHtml(entry.details)}</span>
      </div>
      <div class="log-right">
        <span class="log-peer-pill">${escapeHtml(entry.peer)}</span>
        <span>${formatDate(entry.timestamp)}</span>
      </div>
    `;
    activityLogsContainer.appendChild(div);
  });
}

function renderDashboardRecentLogs() {
  if (!dashboardRecentLogs) return;
  const recent = activityLogsList.slice(0, 5);
  if (!recent.length) {
    dashboardRecentLogs.innerHTML = `<p class="muted">No recent activity logged yet.</p>`;
    return;
  }
  dashboardRecentLogs.innerHTML = "";
  recent.forEach(entry => {
    const div = document.createElement("div");
    div.className = "log-entry-item";
    div.innerHTML = `
      <div class="log-left">
        ${getCategoryBadge(entry.category)}
        <span class="log-details" title="${escapeHtml(entry.details)}">${escapeHtml(entry.details)}</span>
      </div>
      <div class="log-right">
        <span class="log-peer-pill">${escapeHtml(entry.peer)}</span>
        <span>${formatDate(entry.timestamp)}</span>
      </div>
    `;
    dashboardRecentLogs.appendChild(div);
  });
}

// Update Topbar Stats
function updateStats() {
  const filesCountEl = document.querySelector("#stat-files-count");
  if (filesCountEl) filesCountEl.textContent = localFilesList.length;

  const commitsCountEl = document.querySelector("#stat-commits-count");
  if (commitsCountEl) commitsCountEl.textContent = vcsCommitsList.length;

  const peersCountEl = document.querySelector("#stat-peers-count");
  if (peersCountEl) peersCountEl.textContent = peers.length;

  const logsCountEl = document.querySelector("#stat-logs-count");
  if (logsCountEl) logsCountEl.textContent = activityLogsList.length;
}

// Modals Handling
async function openEditModal(fileName) {
  document.querySelector("#edit-file-name").value = fileName;
  document.querySelector("#edit-modal-title").textContent = `Edit File: ${fileName}`;
  document.querySelector("#edit-file-message").value = "";
  const contentEl = document.querySelector("#edit-file-content");
  contentEl.value = "Loading content...";
  editModal.style.display = "grid";

  try {
    const res = await fetch(`/api/vcs/content?name=${encodeURIComponent(fileName)}&version=0`);
    if (!res.ok) throw new Error("Could not load content");
    const data = await res.json();
    contentEl.value = data.content !== undefined ? data.content : "";
    contentEl.focus();
  } catch (err) {
    contentEl.value = `// Error loading file content: ${err.message}`;
  }
}

document.querySelector("#edit-modal-close").onclick = () => editModal.style.display = "none";
document.querySelector("#edit-modal-cancel").onclick = () => editModal.style.display = "none";

document.querySelector("#edit-file-form").onsubmit = async event => {
  event.preventDefault();
  const name = document.querySelector("#edit-file-name").value;
  const content = document.querySelector("#edit-file-content").value;
  const message = document.querySelector("#edit-file-message").value.trim() || "Edited via web";

  try {
    const res = await fetch("/api/files/edit", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ name, content, message })
    });
    if (!res.ok) throw new Error(await res.text());
    editModal.style.display = "none";
    showMessage(`File '${name}' updated with new commit.`);
    await loadLocalFiles();
    await loadVcsHistory();
    await loadActivityLogs();
  } catch (err) {
    alert("Error updating file: " + err.message);
  }
};

// File Deletion
async function confirmDeleteFile(fileName) {
  if (!confirm(`Are you sure you want to delete '${fileName}'?\nAll versions are preserved in Version Control.`)) {
    return;
  }
  try {
    const res = await fetch("/api/files/delete", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ name: fileName, message: "Deleted via web interface" })
    });
    if (!res.ok) throw new Error(await res.text());
    showMessage(`File '${fileName}' removed.`);
    await loadLocalFiles();
    await loadVcsHistory();
    await loadActivityLogs();
  } catch (err) {
    alert("Could not delete file: " + err.message);
  }
}

// History Modal
async function openHistoryModal(fileName, remoteBaseUrl = "") {
  document.querySelector("#history-filename").textContent = fileName;
  const listEl = document.querySelector("#file-history-list");
  listEl.innerHTML = `<p class="muted">Loading history for ${escapeHtml(fileName)}...</p>`;
  historyModal.style.display = "grid";

  try {
    const url = remoteBaseUrl
      ? `${remoteBaseUrl}/api/vcs/file-history?name=${encodeURIComponent(fileName)}`
      : `/api/vcs/file-history?name=${encodeURIComponent(fileName)}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    const commits = Array.isArray(data.commits) ? data.commits : [];

    if (!commits.length) {
      listEl.innerHTML = `<p class="muted empty-state">No version history records found for ${escapeHtml(fileName)}.</p>`;
      return;
    }

    listEl.innerHTML = "";
    commits.forEach(commit => {
      const item = document.createElement("div");
      item.className = "file-history-item";
      item.innerHTML = `
        <div class="history-info">
          <div class="history-title-row">
            ${getActionBadge(commit.action)}
            <span class="version-pill">v${commit.version}</span>
            <strong class="history-msg">"${escapeHtml(commit.message || 'No description')}"</strong>
          </div>
          <div class="history-meta">
            <span>By ${escapeHtml(commit.author)}</span> ·
            <span>${formatDate(commit.timestamp)}</span> ·
            <span>Size: ${formatBytes(commit.size)}</span>
            ${commit.sha256 ? ` · <code title="${escapeHtml(commit.sha256)}">${escapeHtml(commit.sha256.substring(0, 10))}...</code>` : ''}
          </div>
        </div>
        <div class="history-actions">
          ${commit.action !== 'REMOVED' ? `<button class="action-btn btn-view-snap">👁 View</button>` : ''}
          ${commit.version > 1 ? `<button class="action-btn btn-diff-v">⇄ Diff</button>` : ''}
          ${!remoteBaseUrl && commit.action !== 'REMOVED' ? `<button class="action-btn btn-revert">↺ Restore</button>` : ''}
          <a class="action-btn" href="${remoteBaseUrl}/api/download?name=${encodeURIComponent(fileName)}&version=${commit.version}" download="v${commit.version}_${escapeHtml(fileName)}">⬇ v${commit.version}</a>
        </div>
      `;

      if (commit.action !== 'REMOVED') {
        const viewBtn = item.querySelector(".btn-view-snap");
        if (viewBtn) {
          viewBtn.onclick = () => openViewSnapshotModal(fileName, commit.version, remoteBaseUrl);
        }
      }
      const diffBtn = item.querySelector(".btn-diff-v");
      if (diffBtn) {
        diffBtn.onclick = () => openDiffModal(fileName, commit.version - 1, commit.version, remoteBaseUrl);
      }
      const revertBtn = item.querySelector(".btn-revert");
      if (revertBtn) {
        revertBtn.onclick = () => revertToVersion(fileName, commit.version);
      }

      listEl.appendChild(item);
    });
  } catch (err) {
    listEl.innerHTML = `<p class="muted">Could not load history: ${escapeHtml(err.message)}</p>`;
  }
}

document.querySelector("#history-modal-close").onclick = () => historyModal.style.display = "none";
document.querySelector("#history-modal-close-btn").onclick = () => historyModal.style.display = "none";

// Revert to Version
async function revertToVersion(fileName, version) {
  if (!confirm(`Rollback '${fileName}' to version v${version}? A new RESTORED commit will be created.`)) {
    return;
  }
  try {
    const res = await fetch("/api/vcs/revert", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ name: fileName, version: version })
    });
    if (!res.ok) throw new Error(await res.text());
    historyModal.style.display = "none";
    showMessage(`File '${fileName}' restored to version v${version}.`);
    await loadLocalFiles();
    await loadVcsHistory();
    await loadActivityLogs();
  } catch (err) {
    alert("Could not restore version: " + err.message);
  }
}

// Diff Modal
async function openDiffModal(fileName, v1, v2, remoteBaseUrl = "") {
  document.querySelector("#diff-file-tag").textContent = fileName;
  document.querySelector("#diff-v1-tag").textContent = `v${v1}`;
  document.querySelector("#diff-v2-tag").textContent = `v${v2}`;
  const contentEl = document.querySelector("#diff-content");
  contentEl.innerHTML = `<p class="muted" style="padding: 16px;">Comparing version v${v1} with v${v2}...</p>`;
  diffModal.style.display = "grid";

  try {
    const url = remoteBaseUrl
      ? `${remoteBaseUrl}/api/vcs/diff?name=${encodeURIComponent(fileName)}&v1=${v1}&v2=${v2}`
      : `/api/vcs/diff?name=${encodeURIComponent(fileName)}&v1=${v1}&v2=${v2}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();

    document.querySelector("#diff-stat-add").textContent = `+${data.stats ? data.stats.additions : 0}`;
    document.querySelector("#diff-stat-del").textContent = `-${data.stats ? data.stats.deletions : 0}`;

    if (data.isBinary) {
      contentEl.innerHTML = `<p class="muted" style="padding: 16px;">${escapeHtml(data.message || 'Binary files differ')}</p>`;
      return;
    }

    if (!data.diff || !data.diff.length) {
      contentEl.innerHTML = `<p class="muted" style="padding: 16px;">No differences between v${v1} and v${v2}.</p>`;
      return;
    }

    contentEl.innerHTML = "";
    data.diff.forEach(item => {
      const lineDiv = document.createElement("div");
      const type = (item.type || 'EQUAL').toLowerCase();
      lineDiv.className = `diff-line ${type}`;

      const sign = type === 'insert' ? '+' : (type === 'delete' ? '-' : ' ');
      lineDiv.innerHTML = `
        <span class="diff-gutter">
          <span>${item.oldLine || ''}</span>
          <span>${item.newLine || ''}</span>
        </span>
        <span class="diff-sign">${sign}</span>
        <span class="diff-text">${escapeHtml(item.text)}</span>
      `;
      contentEl.appendChild(lineDiv);
    });
  } catch (err) {
    contentEl.innerHTML = `<p class="muted" style="padding: 16px;">Error calculating diff: ${escapeHtml(err.message)}</p>`;
  }
}

document.querySelector("#diff-modal-close").onclick = () => diffModal.style.display = "none";
document.querySelector("#diff-modal-close-btn").onclick = () => diffModal.style.display = "none";

// View Snapshot Content Modal
async function openViewSnapshotModal(fileName, version, remoteBaseUrl = "") {
  document.querySelector("#view-content-modal-title").textContent = `Snapshot: ${fileName} (v${version})`;
  const pre = document.querySelector("#view-snapshot-content");
  pre.textContent = "Loading snapshot content...";
  viewContentModal.style.display = "grid";

  try {
    const url = remoteBaseUrl
      ? `${remoteBaseUrl}/api/vcs/content?name=${encodeURIComponent(fileName)}&version=${version}`
      : `/api/vcs/content?name=${encodeURIComponent(fileName)}&version=${version}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    pre.textContent = data.content !== undefined ? data.content : "";
  } catch (err) {
    pre.textContent = `Error loading snapshot: ${err.message}`;
  }
}

document.querySelector("#view-content-modal-close").onclick = () => viewContentModal.style.display = "none";
document.querySelector("#view-content-modal-close-btn").onclick = () => viewContentModal.style.display = "none";

// Create File Form
const toggleCreateBtn = document.querySelector("#toggle-create-btn");
const createContainer = document.querySelector("#create-file-container");
const cancelCreateBtn = document.querySelector("#cancel-create-btn");

if (toggleCreateBtn && createContainer) {
  toggleCreateBtn.onclick = () => {
    const isHidden = createContainer.style.display === "none";
    createContainer.style.display = isHidden ? "block" : "none";
    if (isHidden) document.querySelector("#file-name").focus();
  };
}

if (cancelCreateBtn && createContainer) {
  cancelCreateBtn.onclick = () => {
    createContainer.style.display = "none";
    document.querySelector("#create-file-form").reset();
  };
}

document.querySelector("#create-file-form").onsubmit = async event => {
  event.preventDefault();
  const name = document.querySelector("#file-name").value.trim();
  const message = document.querySelector("#file-create-msg").value.trim() || "Initial file creation";
  const content = document.querySelector("#file-content").value;

  try {
    const res = await fetch("/api/files/create", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ name, content, message })
    });
    if (!res.ok) throw new Error(await res.text());
    event.target.reset();
    createContainer.style.display = "none";
    showMessage(`File '${name}' created and committed as v1.`);
    await loadLocalFiles();
    await loadVcsHistory();
    await loadActivityLogs();
  } catch (err) {
    showMessage(`Could not create file: ${err.message}`, true);
  }
};

// Peer Connection & Activity Logging
function loadPersistedPeers() {
  try {
    const stored = localStorage.getItem("lan_share_peers");
    if (stored) {
      peers = JSON.parse(stored);
    }
  } catch (ignored) {}
}

function savePersistedPeers() {
  try {
    localStorage.setItem("lan_share_peers", JSON.stringify(peers));
  } catch (ignored) {}
}

async function renderPeers() {
  updateStats();
  if (!peersContainer) return;

  if (!peers.length) {
    peersContainer.innerHTML = '<p class="muted empty-state">No peers connected yet. Enter a peer IP &amp; port above to connect.</p>';
    return;
  }

  peersContainer.innerHTML = "";
  peers.forEach(peer => {
    const card = document.createElement("div");
    card.className = "peer-card";
    card.innerHTML = `
      <div class="peer-card-header">
        <div>
          <span class="peer-badge-online"><span class="online-dot"></span> CONNECTED</span>
          <h3 style="margin-top: 6px;">${escapeHtml(peer.label)}</h3>
        </div>
        <button class="danger-outline-button btn-disconnect">Disconnect</button>
      </div>
      <div>
        <p class="section-label">SHARED FILES ON THIS PEER</p>
        <ul class="peer-files-list"><li class="muted">Querying peer files...</li></ul>
      </div>
    `;

    card.querySelector(".btn-disconnect").onclick = async () => {
      await disconnectPeer(peer);
    };

    peersContainer.appendChild(card);

    const filesList = card.querySelector(".peer-files-list");
    fetch(`${peer.baseUrl}/api/files`)
      .then(res => {
        if (!res.ok) throw new Error("HTTP " + res.status);
        return res.json();
      })
      .then(data => {
        const files = Array.isArray(data.files) ? data.files : [];
        if (!files.length) {
          filesList.innerHTML = `<li class="muted">No files shared by this peer.</li>`;
          return;
        }
        filesList.innerHTML = "";
        files.forEach(fileObj => {
          const name = typeof fileObj === 'object' ? fileObj.name : fileObj;
          const version = typeof fileObj === 'object' && fileObj.version ? fileObj.version : 1;
          const size = typeof fileObj === 'object' && fileObj.size !== undefined ? formatBytes(fileObj.size) : '--';

          const row = document.createElement("li");
          row.className = "peer-file-row";
          row.innerHTML = `
            <div>
              <strong>${escapeHtml(name)}</strong>
              <span class="version-pill" style="margin-left: 6px;">v${version}</span>
              <span class="muted" style="margin-left: 6px;">${size}</span>
            </div>
            <div style="display: flex; gap: 8px;">
              <button class="action-btn btn-peer-hist">🕒 Peer History</button>
              <a class="action-btn action-btn-primary btn-peer-download" href="${peer.baseUrl}/api/download?name=${encodeURIComponent(name)}" download="${escapeHtml(name)}">⬇ Download</a>
            </div>
          `;

          row.querySelector(".btn-peer-hist").onclick = () => {
            openHistoryModal(name, peer.baseUrl);
          };

          row.querySelector(".btn-peer-download").onclick = () => {
            // Log remote download in local server
            fetch("/api/logs/record", {
              method: "POST",
              headers: { "Content-Type": "application/x-www-form-urlencoded" },
              body: new URLSearchParams({
                category: "DOWNLOAD",
                action: "FILE_DOWNLOAD",
                peer: peer.label,
                details: `Downloaded '${name}' from peer ${peer.label}`
              })
            }).then(() => loadActivityLogs()).catch(() => {});
          };

          filesList.appendChild(row);
        });
      })
      .catch(err => {
        filesList.innerHTML = `<li class="muted">Peer unreachable (${escapeHtml(err.message)})</li>`;
      });
  });
}

async function connectToPeer(ip, port) {
  const baseUrl = `http://${ip}:${port}`;
  const label = `${ip}:${port}`;

  if (peers.some(p => p.baseUrl === baseUrl)) {
    showMessage(`Already connected to ${label}.`);
    return;
  }

  showMessage(`Connecting to peer node at ${label}...`);
  try {
    // 1. Verify remote peer is running and reach files
    const res = await fetch(`${baseUrl}/api/files`);
    if (!res.ok) throw new Error("Peer returned HTTP " + res.status);

    // 2. Announce presence to remote peer
    try {
      await fetch(`${baseUrl}/api/peers/register`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          label: "Peer Node",
          port: location.port || "8000"
        })
      });
    } catch (ignored) {}

    // 3. Record connection in local activity log
    await fetch("/api/logs/record", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        category: "CONNECTION",
        action: "PEER_CONNECTED",
        peer: label,
        details: `Connected to peer node [${label}]`
      })
    });

    peers.push({ label, baseUrl, ip, port });
    savePersistedPeers();
    renderPeers();
    showMessage(`Connected successfully to ${label}.`);
    await loadActivityLogs();
  } catch (err) {
    showMessage(`Could not connect to peer ${baseUrl}: ${err.message}`, true);
  }
}

async function disconnectPeer(peer) {
  try {
    // Notify remote peer of disconnect
    fetch(`${peer.baseUrl}/api/peers/disconnect`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ peer: `${location.hostname}:${location.port || '8000'}` })
    }).catch(() => {});

    // Record disconnect in local log
    await fetch("/api/logs/record", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        category: "CONNECTION",
        action: "PEER_DISCONNECTED",
        peer: peer.label,
        details: `Disconnected from peer node [${peer.label}]`
      })
    });
  } catch (ignored) {}

  peers = peers.filter(p => p.baseUrl !== peer.baseUrl);
  savePersistedPeers();
  renderPeers();
  showMessage(`Disconnected from ${peer.label}.`);
  await loadActivityLogs();
}

document.querySelector("#peer-form").onsubmit = async event => {
  event.preventDefault();
  const ip = document.querySelector("#peer-ip").value.trim();
  const port = document.querySelector("#peer-port").value.trim();
  if (ip && port) {
    await connectToPeer(ip, port);
  }
};

// Filter Buttons Wiring
document.querySelectorAll("#vcs-filter-buttons .filter-btn").forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll("#vcs-filter-buttons .filter-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    currentVcsFilter = btn.getAttribute("data-filter");
    renderVcsTimeline();
  };
});

document.querySelectorAll("#log-filter-buttons .filter-btn").forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll("#log-filter-buttons .filter-btn").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    currentLogFilter = btn.getAttribute("data-filter");
    renderActivityLogs();
  };
});

// Search input listeners
if (fileSearchInput) fileSearchInput.oninput = renderLocalFiles;
if (logSearchInput) logSearchInput.oninput = renderActivityLogs;

// Refresh Buttons
const refreshLocalBtn = document.querySelector("#refresh-local");
if (refreshLocalBtn) refreshLocalBtn.onclick = () => { loadLocalFiles(); loadVcsHistory(); };

const refreshVcsBtn = document.querySelector("#refresh-vcs");
if (refreshVcsBtn) refreshVcsBtn.onclick = loadVcsHistory;

const refreshLogsBtn = document.querySelector("#refresh-logs");
if (refreshLogsBtn) refreshLogsBtn.onclick = loadActivityLogs;

const refreshPeersBtn = document.querySelector("#refresh-peers-btn");
if (refreshPeersBtn) refreshPeersBtn.onclick = renderPeers;

// Clear Logs
const clearLogsBtn = document.querySelector("#clear-logs-btn");
if (clearLogsBtn) {
  clearLogsBtn.onclick = async () => {
    if (!confirm("Are you sure you want to clear activity log history?")) return;
    try {
      await fetch("/api/logs/clear", { method: "POST" });
      await loadActivityLogs();
      showMessage("Activity logs cleared.");
    } catch (err) {
      alert("Could not clear logs: " + err.message);
    }
  };
}

// Auto Refresh
function setupAutoRefresh() {
  const autoCheckbox = document.querySelector("#auto-refresh-logs");
  function startTimer() {
    if (autoRefreshTimer) clearInterval(autoRefreshTimer);
    autoRefreshTimer = setInterval(() => {
      loadActivityLogs();
      loadVcsHistory();
      loadLocalFiles();
    }, 2500);
  }
  function stopTimer() {
    if (autoRefreshTimer) clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }

  if (autoCheckbox) {
    autoCheckbox.onchange = () => {
      if (autoCheckbox.checked) startTimer();
      else stopTimer();
    };
  }
  startTimer();
}

// Initialization
async function init() {
  setupNavigation();
  loadPersistedPeers();
  await loadLocalFiles();
  await loadVcsHistory();
  await loadActivityLogs();
  renderPeers();
  setupAutoRefresh();
}

init();
