function api() {
  if (!window.go) throw new Error("Wails runtime not found");
  if (window.go.main && window.go.main.App) return window.go.main.App;
  for (const v of Object.values(window.go)) {
    if (v && v.App) return v.App;
  }
  throw new Error("App binding not found");
}

function val(id) {
  const el = document.getElementById(id);
  return el ? el.value : "";
}

function setVal(id, value) {
  const el = document.getElementById(id);
  if (el) el.value = value || "";
}

function showToast(msg, ok = false) {
  const text = String(msg || "").trim();
  if (!text) return;

  const container = document.getElementById("toastContainer");
  if (!container) return;

  const toast = document.createElement("div");
  toast.className = `toast ${ok ? "success" : "error"}`;
  toast.textContent = text;
  container.appendChild(toast);

  requestAnimationFrame(() => {
    toast.classList.add("show");
  });

  setTimeout(() => {
    toast.classList.remove("show");
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 180);
  }, 2200);
}

function setMsg(msg, ok = false) {
  if (!msg) return;
  showToast(msg, ok);
}

let currentRunning = false;

function renderStatus(st) {
  currentRunning = !!st.running;
  const t = st.startedAt ? new Date(st.startedAt * 1000).toLocaleString() : "-";
  document.getElementById(
    "statusText",
  ).textContent = `状态: ${st.running ? "已连接" : "已断开"} | 启动时间: ${t}${st.lastError ? ` | 最近错误: ${st.lastError}` : ""}`;
  const btn = document.getElementById("btnToggle");
  btn.textContent = st.running ? "断开连接" : "连接";
}

async function loadConfig() {
  const cfg = await api().LoadConfig();
//  document.getElementById("configPath").textContent = `配置文件: ${cfg.configPath}`;
  setVal("serverAddr", cfg.serverAddr);
  setVal("username", cfg.username);
  setVal("password", cfg.password);
}

async function refreshStatus() {
  const st = await api().GetStatus();
  renderStatus(st);
}

async function connect() {
  const st = await api().Connect({
    server: val("serverAddr"),
    user: val("username"),
    pass: val("password"),
  });
  renderStatus(st);
}

async function disconnect() {
  await api().StopAgent();
  const st = await api().GetStatus();
  renderStatus(st);
}

async function toggleConnect() {
  try {
    if (currentRunning) {
      await disconnect();
      setMsg("已断开", true);
      return;
    }
    await connect();
    setMsg("已连接", true);
  } catch (e) {
    setMsg(String(e));
    await refreshStatus();
  }
}

async function tryAutoConnect() {
  try {
    const st = await api().AutoConnect();
    renderStatus(st);
    if (st.running) {
      setMsg("检测到有效 token，已自动连接", true);
    }
  } catch (_) {
    // ignore auto-connect failures; user can click connect manually.
  }
}

document.getElementById("btnToggle").addEventListener("click", toggleConnect);
document.getElementById("btnRefresh").addEventListener("click", async () => {
  try {
    await refreshStatus();
  } catch (e) {
    setMsg(`刷新失败: ${e}`);
  }
});

(async () => {
  try {
    await loadConfig();
    await refreshStatus();
    if (!currentRunning) {
      await tryAutoConnect();
    }
  } catch (e) {
    setMsg(`初始化失败: ${e}`);
  }
})();

setInterval(async () => {
  try {
    await refreshStatus();
  } catch (_) {}
}, 3000);
