import "./style.css";
import { AdminApi, ApiError } from "./api";
import { el } from "./dom";
import { auditView, mandatesView, overviewView, paymentsView } from "./views";

type Tab = "overview" | "payments" | "mandates" | "audit";

interface State {
  token: string;
  tab: Tab;
  paymentId: string;
  error: string;
  autoRefresh: boolean;
}

const TABS: Tab[] = ["overview", "payments", "mandates", "audit"];

const state: State = {
  token: sessionStorage.getItem("apg.adminToken") ?? "",
  tab: "overview",
  paymentId: "",
  error: "",
  autoRefresh: false,
};

const app = document.getElementById("app");
if (!app) {
  throw new Error("console shell is missing #app");
}

function readHash(): void {
  const match = window.location.hash.match(/^#\/(overview|payments|mandates|audit)(?:\/(.+))?$/);
  if (!match) {
    state.tab = "overview";
    state.paymentId = "";
    return;
  }
  state.tab = match[1] as Tab;
  state.paymentId = match[2] ? decodeURIComponent(match[2]) : "";
}

function api(): AdminApi {
  return new AdminApi(import.meta.env.VITE_GATEWAY_URL ?? "", state.token);
}

function header(): HTMLElement {
  const input = el("input", {
    type: "password",
    placeholder: "admin token",
    value: state.token,
    onInput: (event) => {
      state.token = (event.target as HTMLInputElement).value;
    },
  });
  const refresh = el("input", {
    type: "checkbox",
    checked: state.autoRefresh,
    onClick: (event) => {
      state.autoRefresh = (event.target as HTMLInputElement).checked;
      schedule();
    },
  });
  return el("header", {}, [
    el("h1", { text: "AgentPayGate" }),
    el("span", { className: "hint", text: "simulated payments · mandate-enforced · double-entry" }),
    el("span", { className: "spacer" }),
    refresh,
    el("span", { className: "hint", text: "auto-refresh" }),
    input,
    el("button", {
      className: "primary",
      text: "Connect",
      onClick: () => {
        sessionStorage.setItem("apg.adminToken", state.token);
        state.error = "";
        void render();
      },
    }),
  ]);
}

function nav(): HTMLElement {
  return el(
    "nav",
    {},
    TABS.map((tab) =>
      el("a", {
        href: `#/${tab}`,
        className: tab === state.tab ? "active" : "",
        text: tab,
      }),
    ),
  );
}

async function renderContent(container: HTMLElement): Promise<void> {
  if (!state.token) {
    container.replaceChildren(
      el("div", { className: "empty" }, [
        el("p", { text: "Paste the operator admin token to connect." }),
        el("p", {
          className: "hint",
          text: "The demo stack uses the value of GATEWAY_ADMIN_TOKEN; scripts/demo-setup.sh writes the demo agent key, not this one.",
        }),
      ]),
    );
    return;
  }
  const client = api();
  try {
    let node: HTMLElement;
    switch (state.tab) {
      case "payments":
        node = await paymentsView(client, state.paymentId);
        break;
      case "mandates":
        node = await mandatesView(client);
        break;
      case "audit":
        node = await auditView(client);
        break;
      default:
        node = await overviewView(client);
    }
    container.replaceChildren(node);
  } catch (error) {
    const message =
      error instanceof ApiError
        ? `${error.code} (${error.status}): ${error.message}`
        : error instanceof Error
          ? error.message
          : String(error);
    container.replaceChildren(
      el("div", { className: "error", text: `Could not load ${state.tab}: ${message}` }),
      el("p", { className: "hint", text: "A 401 means the admin token is missing or wrong. The default demo token is demo-admin-token." }),
    );
  }
}

async function render(): Promise<void> {
  readHash();
  const content = el("main");
  app!.replaceChildren(header(), nav(), content);
  await renderContent(content);
}

let timer: number | undefined;

function schedule(): void {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
  if (state.autoRefresh) {
    timer = window.setInterval(() => void render(), 5000);
  }
}

window.addEventListener("hashchange", () => void render());
void render();
schedule();
