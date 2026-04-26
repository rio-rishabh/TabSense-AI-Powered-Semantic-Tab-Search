import "./style.css";
import {
  searchRequest,
  syncTabsRequest,
  type Citation,
  type TabPayload,
} from "./graphql/client";

const STORAGE_KEY = "backendBaseUrl";
const SYNC_SCOPE_KEY = "syncScope";
const LAST_VIEW_KEY = "lastViewState";
const DEFAULT_BACKEND = "http://localhost:8080";

type SyncScope = "window" | "active";
type LastViewState = {
  question: string;
  answer: string;
  citations: Citation[];
  status: string;
};

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function getBackendUrl(): Promise<string> {
  const stored = await chrome.storage.local.get(STORAGE_KEY);
  const v = stored[STORAGE_KEY];
  return typeof v === "string" && v.trim() ? v.trim() : DEFAULT_BACKEND;
}

async function setBackendUrl(url: string): Promise<void> {
  await chrome.storage.local.set({ [STORAGE_KEY]: url.trim() });
}

async function getSyncScope(): Promise<SyncScope> {
  const stored = await chrome.storage.local.get(SYNC_SCOPE_KEY);
  return stored[SYNC_SCOPE_KEY] === "active" ? "active" : "window";
}

async function setSyncScope(scope: SyncScope): Promise<void> {
  await chrome.storage.local.set({ [SYNC_SCOPE_KEY]: scope });
}

async function getLastViewState(): Promise<LastViewState | null> {
  const stored = await chrome.storage.local.get(LAST_VIEW_KEY);
  const raw = stored[LAST_VIEW_KEY] as LastViewState | undefined;
  if (!raw || typeof raw !== "object") {
    return null;
  }
  return {
    question: typeof raw.question === "string" ? raw.question : "",
    answer: typeof raw.answer === "string" ? raw.answer : "",
    citations: Array.isArray(raw.citations) ? raw.citations : [],
    status: typeof raw.status === "string" ? raw.status : "",
  };
}

async function setLastViewState(state: LastViewState): Promise<void> {
  await chrome.storage.local.set({ [LAST_VIEW_KEY]: state });
}

async function queryTabsForSync(scope: SyncScope): Promise<chrome.tabs.Tab[]> {
  if (scope === "active") {
    return chrome.tabs.query({ active: true, currentWindow: true });
  }
  return chrome.tabs.query({ currentWindow: true });
}

function tabPayloadsFromQuery(tabs: chrome.tabs.Tab[]): TabPayload[] {
  const out: TabPayload[] = [];
  for (const t of tabs) {
    if (t.id == null || !t.url) continue;
    const u = t.url;
    if (!u.startsWith("http://") && !u.startsWith("https://")) continue;
    out.push({
      url: u,
      title: t.title ?? "(no title)",
      tabId: String(t.id),
    });
  }
  return out;
}

async function focusTab(tabIdStr: string, url?: string): Promise<boolean> {
  const id = Number.parseInt(tabIdStr, 10);
  if (Number.isNaN(id)) {
    if (url) {
      await chrome.tabs.create({ url });
      return true;
    }
    return false;
  }
  try {
    const tab = await chrome.tabs.get(id);
    await chrome.tabs.update(id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true });
    return true;
  } catch {
    // Tab might have been closed; try to find by URL, then open it.
    if (url) {
      const matches = await chrome.tabs.query({ url });
      if (matches.length > 0 && matches[0].id != null) {
        const existingId = matches[0].id;
        const windowId = matches[0].windowId;
        await chrome.tabs.update(existingId, { active: true });
        await chrome.windows.update(windowId, { focused: true });
        return true;
      }
      await chrome.tabs.create({ url });
      return true;
    }
    return false;
  }
}

function renderCitations(container: HTMLElement, citations: Citation[]): void {
  container.replaceChildren();
  if (!citations.length) {
    container.appendChild(
      el("p", "text-xs text-slate-500", "No citations returned.")
    );
    return;
  }
  const title = el("h3", "text-xs font-semibold uppercase tracking-wide text-sky-300/90 mb-2", "Citations");
  container.appendChild(title);
  const list = el("ul", "space-y-2");
  for (const c of citations) {
    const li = el("li", "rounded-lg border border-slate-600/60 bg-slate-900/40 p-2");
    const row = el("div", "flex items-start justify-between gap-2");
    const snippet = el("p", "text-xs text-slate-300 leading-snug flex-1", c.snippet);
    const btn = el(
      "button",
      "shrink-0 rounded-md bg-sky-600 px-2 py-1 text-xs font-medium text-white hover:bg-sky-500"
    );
    btn.textContent = "Open tab";
    btn.addEventListener("click", () => void focusTab(c.tabId, c.url));
    row.appendChild(snippet);
    row.appendChild(btn);
    li.appendChild(row);
    const meta = el(
      "p",
      "mt-1 truncate text-[10px] text-slate-500",
      c.url
    );
    li.appendChild(meta);
    list.appendChild(li);
  }
  container.appendChild(list);
}

function mount(): void {
  const root = document.getElementById("app");
  if (!root) return;

  const header = el("header", "mb-3");
  header.appendChild(
    el("h1", "text-lg font-semibold tracking-tight text-white", "TabSense: AI-Powered-Semantic-Tab-Search")
  );
  header.appendChild(
    el(
      "p",
      "text-xs text-slate-400",
      "Pick what to sync (whole window or only the tab you’re on), then search."
    )
  );

  const urlRow = el("div", "mb-2");
  urlRow.appendChild(
    el("label", "mb-1 block text-[10px] font-medium uppercase text-slate-500", "Backend URL")
  );
  const urlInput = el("input", "w-full rounded border border-slate-600 bg-slate-950/80 px-2 py-1.5 text-xs text-slate-100 outline-none focus:border-sky-500") as HTMLInputElement;
  urlInput.type = "url";
  urlInput.placeholder = DEFAULT_BACKEND;
  urlRow.appendChild(urlInput);

  const scopeRow = el("fieldset", "mt-3 rounded-lg border border-slate-600/50 bg-slate-950/30 p-2");
  const scopeLegend = el(
    "legend",
    "px-1 text-[10px] font-medium uppercase text-slate-500",
    "Sync scope"
  );
  scopeRow.appendChild(scopeLegend);

  const radioWindow = el("input", "accent-sky-500") as HTMLInputElement;
  radioWindow.type = "radio";
  radioWindow.name = "syncScope";
  radioWindow.value = "window";
  const labelWindow = el("label", "mt-1 flex cursor-pointer items-center gap-2 text-xs text-slate-300");
  labelWindow.appendChild(radioWindow);
  labelWindow.appendChild(
    el("span", "", "All HTTP(S) tabs in this window")
  );

  const radioActive = el("input", "accent-sky-500") as HTMLInputElement;
  radioActive.type = "radio";
  radioActive.name = "syncScope";
  radioActive.value = "active";
  const labelActive = el("label", "mt-2 flex cursor-pointer items-center gap-2 text-xs text-slate-300");
  labelActive.appendChild(radioActive);
  labelActive.appendChild(
    el("span", "", "Only the active tab (the one you’re viewing)")
  );

  scopeRow.appendChild(labelWindow);
  scopeRow.appendChild(labelActive);

  const syncBtn = el(
    "button",
    "mt-2 w-full rounded-lg bg-slate-700 py-2 text-sm font-medium text-white hover:bg-slate-600"
  ) as HTMLButtonElement;
  syncBtn.type = "button";
  syncBtn.textContent = "Sync now";

  const searchBtn = el(
    "button",
    "w-full rounded-lg bg-sky-600 py-2 text-sm font-medium text-white hover:bg-sky-500"
  ) as HTMLButtonElement;
  searchBtn.type = "button";
  searchBtn.textContent = "Search";

  const status = el("p", "mt-2 min-h-[1rem] text-xs text-amber-200/90");
  const question = el(
    "textarea",
    "mt-3 h-20 w-full resize-none rounded border border-slate-600 bg-slate-950/80 px-2 py-1.5 text-xs text-slate-100 outline-none focus:border-sky-500"
  ) as HTMLTextAreaElement;
  question.placeholder = "e.g. Which tab mentions pricing?";

  const answerBox = el(
    "div",
    "mt-3 max-h-40 overflow-y-auto whitespace-pre-wrap rounded border border-slate-700/80 bg-slate-950/50 p-2 text-xs leading-relaxed text-slate-200"
  );

  const citationsBox = el("div", "mt-3");

  root.appendChild(header);
  root.appendChild(urlRow);
  root.appendChild(scopeRow);
  root.appendChild(syncBtn);
  root.appendChild(question);
  root.appendChild(searchBtn);
  root.appendChild(status);
  root.appendChild(el("h2", "mt-3 text-xs font-semibold text-slate-400", "Answer"));
  root.appendChild(answerBox);
  root.appendChild(citationsBox);

  let currentCitations: Citation[] = [];

  const persistView = () =>
    setLastViewState({
      question: question.value,
      answer: answerBox.textContent ?? "",
      citations: currentCitations,
      status: status.textContent ?? "",
    });

  void Promise.all([getBackendUrl(), getSyncScope(), getLastViewState()]).then(
    ([u, scope, last]) => {
      urlInput.value = u;
      if (scope === "active") {
        radioActive.checked = true;
      } else {
        radioWindow.checked = true;
      }
      if (last) {
        question.value = last.question;
        answerBox.textContent = last.answer;
        status.textContent = last.status;
        currentCitations = last.citations;
        renderCitations(citationsBox, last.citations);
      }
    }
  );

  urlInput.addEventListener("change", () => {
    void setBackendUrl(urlInput.value || DEFAULT_BACKEND);
  });

  radioWindow.addEventListener("change", () => {
    if (radioWindow.checked) void setSyncScope("window");
  });
  radioActive.addEventListener("change", () => {
    if (radioActive.checked) void setSyncScope("active");
  });

  const setBusy = (busy: boolean, btn: HTMLButtonElement) => {
    btn.disabled = busy;
    btn.style.opacity = busy ? "0.65" : "1";
  };

  syncBtn.addEventListener("click", async () => {
    status.textContent = "";
    await persistView();
    const base = (urlInput.value || DEFAULT_BACKEND).trim();
    await setBackendUrl(base);
    setBusy(true, syncBtn);
    try {
      const scope: SyncScope = radioActive.checked ? "active" : "window";
      await setSyncScope(scope);
      const tabs = await queryTabsForSync(scope);
      const payload = tabPayloadsFromQuery(tabs);
      if (!payload.length) {
        status.textContent =
          scope === "active"
            ? "Active tab is not HTTP(S) (e.g. chrome:// or the new-tab page). Switch to a normal web page and try again."
            : "No http(s) tabs in this window to sync.";
        return;
      }
      status.textContent = `Syncing ${payload.length} tab(s)…`;
      await syncTabsRequest(base, payload);
      status.textContent = `Synced ${payload.length} tab(s). You can search now.`;
      await persistView();
    } catch (e) {
      status.textContent =
        e instanceof Error ? e.message : "Sync failed.";
      await persistView();
    } finally {
      setBusy(false, syncBtn);
    }
  });

  searchBtn.addEventListener("click", async () => {
    status.textContent = "";
    const base = (urlInput.value || DEFAULT_BACKEND).trim();
    await setBackendUrl(base);
    const q = question.value.trim();
    if (!q) {
      status.textContent = "Enter a question.";
      await persistView();
      return;
    }
    setBusy(true, searchBtn);
    answerBox.textContent = "";
    citationsBox.replaceChildren();
    currentCitations = [];
    try {
      status.textContent = "Searching…";
      await persistView();
      const result = await searchRequest(base, q);
      answerBox.textContent = result.answer;
      renderCitations(citationsBox, result.citations);
      currentCitations = result.citations;
      status.textContent = "Done.";
      await setLastViewState({
        question: question.value,
        answer: answerBox.textContent ?? "",
        citations: result.citations,
        status: status.textContent ?? "",
      });
    } catch (e) {
      status.textContent =
        e instanceof Error ? e.message : "Search failed.";
      await persistView();
    } finally {
      setBusy(false, searchBtn);
    }
  });
}

mount();
