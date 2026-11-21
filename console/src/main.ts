import "./style.css";
import { AdminApi, ApiError } from "./api";
import { el } from "./dom";
import { landingScreen, startLanding } from "./landing";
import { logoMark } from "./logo";
import { auditView, mandatesView, overviewView, paymentsView } from "./views";

type Tab = "overview" | "payments" | "mandates" | "audit";

interface State {
  token: string;
  tab: Tab;
  paymentId: string;
  autoRefresh: boolean;
}

const TABS: Tab[] = ["overview", "payments", "mandates", "audit"];

const state: State = {
  token: sessionStorage.getItem("apg.adminToken") ?? "",
  tab: "overview",
  paymentId: "",
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

function brand(): HTMLElement {
  return el("a", { className: "brand", href: "#/overview" }, [
    logoMark(),
    el("span", { className: "brand-name" }, [
      document.createTextNode("Agent"),
      el("span", { className: "brand-name-accent", text: "Pay" }),
    ]),
  ]);
}

function connect(token: string): void {
  state.token = token;
  sessionStorage.setItem("apg.adminToken", token);
  void render();
}

/* ------------------------------------------------------------------ console chrome */

function header(): HTMLElement {
  const input = el("input", {
    type: "password",
    placeholder: "Admin token",
    value: state.token,
    autocomplete: "current-password",
    ariaLabel: "Gateway admin token",
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
  const tokenForm = el("form", { className: "token-control" }, [
    input,
    el("button", { className: "btn btn-primary btn-small", type: "submit", text: "Apply" }),
  ]) as HTMLFormElement;
  tokenForm.addEventListener("submit", (event) => {
    event.preventDefault();
    sessionStorage.setItem("apg.adminToken", state.token);
    void render();
  });

  return el("header", { className: "app-header" }, [
    brand(),
    el("div", { className: "header-context" }, [
      el("span", { className: "status-dot", ariaHidden: true }),
      el("span", { text: "SIMULATED GATEWAY" }),
    ]),
    el("span", { className: "spacer" }),
    el("label", { className: "refresh-control" }, [refresh, el("span", { text: "Auto refresh" })]),
    tokenForm,
    el("button", {
      className: "btn btn-ghost btn-small",
      type: "button",
      text: "Disconnect",
      onClick: () => {
        state.token = "";
        sessionStorage.removeItem("apg.adminToken");
        void render();
      },
    }),
  ]);
}

function nav(): HTMLElement {
  const labels: Record<Tab, string> = {
    overview: "Overview",
    payments: "Payments",
    mandates: "Mandates",
    audit: "Audit log",
  };
  return el("nav", { className: "app-nav", ariaLabel: "Main navigation" }, [
    el(
      "div",
      { className: "nav-inner" },
      TABS.map((tab) =>
        el("a", {
          href: `#/${tab}`,
          className: tab === state.tab ? "active" : "",
          text: labels[tab],
          ariaCurrent: tab === state.tab ? "page" : undefined,
        }),
      ),
    ),
  ]);
}

/* ------------------------------------------------------------------ content */

/** Placeholder that mirrors the real page, so a slow gateway does not leave a blank canvas. */
function loadingSkeleton(): HTMLElement {
  return el("div", { className: "loading-view" }, [
    el("div", { className: "skeleton skeleton-eyebrow" }),
    el("div", { className: "skeleton skeleton-title" }),
    el("div", { className: "skeleton skeleton-line" }),
    el("div", { className: "skeleton-grid" }, Array.from({ length: 6 }, () => el("div", { className: "skeleton-cell" }))),
    el("div", { className: "skeleton skeleton-panel" }),
  ]);
}

async function renderContent(container: HTMLElement): Promise<void> {
  const client = api();
  const skeletonTimer = window.setTimeout(() => container.replaceChildren(loadingSkeleton()), 120);
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
    window.clearTimeout(skeletonTimer);
    container.replaceChildren(node);
  } catch (error) {
    window.clearTimeout(skeletonTimer);
    const message =
      error instanceof ApiError
        ? `${error.code} (${error.status}): ${error.message}`
        : error instanceof Error
          ? error.message
          : String(error);
    container.replaceChildren(
      el("div", { className: "error-panel" }, [
        el("span", { className: "error-mark", text: "!" }),
        el("div", {}, [
          el("h2", { text: `Could not load ${state.tab}` }),
          el("p", { text: message }),
          el("p", {
            className: "hint",
            text: "Check that the gateway is running and your admin token is correct. The default demo token is demo-admin-token.",
          }),
        ]),
      ]),
    );
  }
}

async function render(): Promise<void> {
  readHash();
  if (!state.token) {
    const landing = landingScreen({ onToken: connect });
    app!.replaceChildren(landing);
    startLanding(landing);
    return;
  }
  const content = el("main", { className: "app-main" });
  app!.replaceChildren(
    el("div", { className: "app-shell" }, [
      header(),
      nav(),
      content,
      el("footer", { className: "app-footer" }, [
        el("span", { text: "AGENTPAY" }),
        el("span", { text: "SIMULATION ONLY · NO REAL MONEY MOVES" }),
      ]),
    ]),
  );
  await renderContent(content);
}

let timer: number | undefined;

function schedule(): void {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
  if (state.autoRefresh && state.token) {
    timer = window.setInterval(() => void render(), 5000);
  }
}

/** Headers only separate from the page once content scrolls under them. */
function watchHeader(): void {
  const update = () => {
    const scrolled = window.scrollY > 6;
    document.querySelector(".app-header")?.classList.toggle("is-scrolled", scrolled);
    document.querySelector(".site-header")?.classList.toggle("is-scrolled", scrolled);
  };
  update();
  window.addEventListener("scroll", update, { passive: true });
}

window.addEventListener("hashchange", () => void render());
void render();
schedule();
watchHeader();
