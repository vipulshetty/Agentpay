/**
 * AgentPay marketing surface.
 *
 * A light, editorial page: type, hairline rules and one green accent. There is exactly one
 * animated element — the system map that walks a request through the components — and everything
 * else is static. The only live interaction is the admin-token form, which opens the operator console.
 */

import "./site.css";
import { el } from "./dom";
import { logoMark } from "./logo";

export interface LandingOptions {
  onToken(token: string): void;
}

const reduced = (): boolean => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function goTo(id: string): void {
  document.getElementById(id)?.scrollIntoView({ behavior: reduced() ? "auto" : "smooth", block: "start" });
}

function jump(label: string, id: string, className = ""): HTMLElement {
  return el("a", {
    className,
    href: `#${id}`,
    text: label,
    onClick: (event) => {
      event.preventDefault();
      goTo(id);
    },
  });
}

/* ------------------------------------------------------------------ chrome */

function brand(): HTMLElement {
  return el(
    "a",
    {
      className: "brand",
      href: "#top",
      onClick: (event) => {
        event.preventDefault();
        window.scrollTo({ top: 0, behavior: reduced() ? "auto" : "smooth" });
      },
    },
    [
      el("span", { className: "brand-tile" }, [logoMark("brand-mark")]),
      el("span", { className: "brand-name" }, [
        document.createTextNode("Agent"),
        el("span", { className: "brand-name-accent", text: "Pay" }),
      ]),
    ],
  );
}

const NAV: Array<[string, string]> = [
  ["How it works", "how"],
  ["Policy", "policy"],
  ["Records", "records"],
  ["Console", "console"],
];

function siteHeader(): HTMLElement {
  const header = el("header", { className: "p-header" });
  const toggle = el("button", { className: "p-menu", type: "button", text: "Menu" }) as HTMLButtonElement;
  toggle.setAttribute("aria-expanded", "false");
  toggle.setAttribute("aria-controls", "p-nav");

  const close = (): void => {
    header.classList.remove("is-open");
    toggle.setAttribute("aria-expanded", "false");
    toggle.textContent = "Menu";
  };

  toggle.addEventListener("click", () => {
    const open = header.classList.toggle("is-open");
    toggle.setAttribute("aria-expanded", String(open));
    toggle.textContent = open ? "Close" : "Menu";
  });

  const nav = el(
    "nav",
    { className: "p-nav", ariaLabel: "Sections" },
    NAV.map(([label, id]) =>
      el("a", {
        href: `#${id}`,
        text: label,
        onClick: (event) => {
          event.preventDefault();
          close();
          goTo(id);
        },
      }),
    ),
  );
  nav.id = "p-nav";

  header.append(
    el("div", { className: "p-header-inner" }, [
      brand(),
      nav,
      el("span", { className: "p-header-gap" }),
      el(
        "a",
        {
          className: "p-btn p-btn-primary p-header-cta",
          href: "#access",
          onClick: (event) => {
            event.preventDefault();
            close();
            goTo("access");
          },
        },
        [document.createTextNode("Open console")],
      ),
      toggle,
    ]),
  );
  return header;
}

/* ------------------------------------------------------------------ the one animation */

const COMPONENTS = [
  { role: "agent", name: "Agent runtime", active: "signing", done: "intent signed", fail: "no intent" },
  { role: "gateway", name: "Gateway", active: "verifying", done: "signature valid", fail: "signature invalid" },
  { role: "policy", name: "Policy engine", active: "evaluating", done: "within limits", fail: "over the cap" },
  { role: "ledger", name: "Ledger", active: "posting", done: "entry balanced", fail: "not reached" },
  { role: "audit", name: "Audit chain", active: "appending", done: "0x5c77…1a sealed", fail: "not reached" },
  { role: "provider", name: "Provider", active: "capturing", done: "pay_8f21c4", fail: "not reached" },
] as const;

type Mode = "approved" | "denied";
type StepState = "idle" | "active" | "done" | "fail" | "skip";

const OUTCOME: Record<Mode, readonly ("done" | "fail" | "skip")[]> = {
  approved: ["done", "done", "done", "done", "done", "done"],
  denied: ["done", "done", "fail", "skip", "skip", "skip"],
};

const SCENARIO: Record<Mode, { request: string; verdict: string; code: string }> = {
  approved: { request: "INR 420.00 · blue_bottle · mnd_01", verdict: "approved", code: "pay_8f21c4 captured" },
  denied: { request: "INR 1,500.00 · blue_bottle · mnd_01", verdict: "denied", code: "AMOUNT_OVER_TX_LIMIT" },
};

interface Trace {
  node: HTMLElement;
  start(): void;
}

/** One request walking the components, line by line. The only moving part on the page. */
function createMap(): Trace {
  let current: Mode = "approved";
  let started = false;
  let token = 0;
  let timer = 0;

  const root = el("section", { className: "p-map", ariaLabel: "System map: how a request moves through AgentPay" });
  const request = el("span", { className: "p-req", text: SCENARIO.approved.request });
  const verdict = el("span", { className: "p-verdict" });
  const verdictMain = el("span", { className: "p-verdict-main", text: "ready" });
  const verdictCode = el("span", { className: "p-verdict-code" });
  verdict.append(verdictMain, verdictCode);
  verdict.setAttribute("role", "status");
  verdict.setAttribute("aria-live", "polite");

  const items = COMPONENTS.map((component, index) => {
    const state = el("span", { className: "p-node-state", text: "waiting" });
    const item = el("li", { className: "p-node is-idle" }, [
      el("span", { className: "p-node-dot", ariaHidden: true }),
      el("span", { className: "p-node-line", ariaHidden: true }),
      el("span", { className: "p-node-body" }, [
        el("span", { className: "p-node-role", text: `${String(index + 1).padStart(2, "0")} · ${component.role}` }),
        el("span", { className: "p-node-name", text: component.name }),
        state,
      ]),
    ]);
    return { item, state };
  });

  const modeButtons: Array<{ button: HTMLButtonElement; mode: Mode }> = (["approved", "denied"] as Mode[]).map(
    (mode) => {
      const button = el("button", {
        className: "p-mode",
        type: "button",
        text: mode === "approved" ? "Approved" : "Over limit",
      }) as HTMLButtonElement;
      button.setAttribute("aria-pressed", String(mode === current));
      if (mode === current) button.classList.add("is-active");
      button.addEventListener("click", () => {
        current = mode;
        modeButtons.forEach((entry) => {
          entry.button.classList.toggle("is-active", entry.mode === mode);
          entry.button.setAttribute("aria-pressed", String(entry.mode === mode));
        });
        run();
      });
      return { button, mode };
    },
  );

  root.append(
    el("div", { className: "p-shell p-map-inner" }, [
      el("div", { className: "p-map-head" }, [
        el("span", { className: "p-map-label", text: "System map · one request, six components" }),
        el("div", { className: "p-modes" }, modeButtons.map((entry) => entry.button)),
      ]),
      el("ol", { className: "p-rail", ariaLabel: "Components in the request path" }, items.map((entry) => entry.item)),
      el("div", { className: "p-map-foot" }, [request, verdict]),
    ]),
  );

  function paint(states: readonly StepState[]): void {
    items.forEach((entry, index) => {
      const state = states[index] ?? "idle";
      entry.item.className = `p-node is-${state}`;
      const component = COMPONENTS[index]!;
      entry.state.textContent =
        state === "idle"
          ? "waiting"
          : state === "active"
            ? component.active
            : state === "done"
              ? component.done
              : state === "fail"
                ? component.fail
                : "not reached";
    });
  }

  function finish(copy: { verdict: string; code: string }): void {
    verdict.className = `p-verdict is-${current}`;
    verdictMain.textContent = copy.verdict;
    verdictCode.textContent = copy.code;
  }

  function run(): void {
    token += 1;
    const id = token;
    window.clearTimeout(timer);

    const outcome = OUTCOME[current];
    const copy = SCENARIO[current];
    const states: StepState[] = ["idle", "idle", "idle", "idle", "idle", "idle"];

    request.textContent = copy.request;
    verdict.className = "p-verdict";
    verdictMain.textContent = "checking";
    verdictCode.textContent = "";
    paint(states);

    if (reduced()) {
      outcome.forEach((state, index) => {
        states[index] = state;
      });
      paint(states);
      finish(copy);
      return;
    }

    const order = current === "denied" ? [0, 1, 2] : [0, 1, 2, 3, 4, 5];
    let cursor = 0;

    function advance(): void {
      if (id !== token) return;
      if (cursor >= order.length) {
        finish(copy);
        return;
      }
      const index = order[cursor]!;
      states[index] = "active";
      paint(states);
      timer = window.setTimeout(() => {
        if (id !== token) return;
        states[index] = outcome[index]!;
        if (outcome[index] === "fail") {
          for (let rest = index + 1; rest < states.length; rest += 1) states[rest] = "skip";
        }
        cursor += 1;
        paint(states);
        advance();
      }, 480);
    }

    advance();
  }

  function start(): void {
    if (started) return;
    started = true;
    if (reduced() || typeof IntersectionObserver !== "function") {
      run();
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        run();
      },
      { threshold: 0.3 },
    );
    observer.observe(root);
  }

  return { node: root, start };
}

/* ------------------------------------------------------------------ hero */

const FACTS: Array<[string, string]> = [
  ["Mandates", "Ed25519, issuer-signed"],
  ["Policy", "Deny by default"],
  ["Ledger", "Double-entry, balanced"],
  ["Audit", "Hash-chained events"],
];

function hero(): { node: HTMLElement; trace: Trace } {
  const trace = createMap();

  const node = el("section", { className: "p-hero", id: "top", ariaLabel: "AgentPay introduction" }, [
    el("div", { className: "p-shell" }, [
      el("p", { className: "p-eyebrow", text: "Payment infrastructure for AI agents" }),
      el("h1", { className: "p-title" }, [
        el("span", { text: "The agent asks." }),
        el("span", {}, [document.createTextNode("The gateway "), el("span", { className: "p-accent", text: "decides." })]),
      ]),
      el("div", { className: "p-hero-grid" }, [
        el("div", { className: "p-hero-lede" }, [
          el("p", {
            className: "p-lede",
            text: "Signed spending mandates, deterministic policy checks and a double-entry ledger. The control plane between an agent's intent and a merchant's payment.",
          }),
          el("div", { className: "p-actions" }, [
            el(
              "a",
              {
                className: "p-btn p-btn-primary",
                href: "#access",
                onClick: (event) => {
                  event.preventDefault();
                  goTo("access");
                },
              },
              [document.createTextNode("Open the console")],
            ),
            jump("How it works", "how", "p-btn p-btn-ghost"),
          ]),
          el("p", { className: "p-note", text: "Simulation environment — no real money moves." }),
        ]),
        el(
          "dl",
          { className: "p-facts" },
          FACTS.flatMap(([term, detail]) => [
            el("div", { className: "p-fact" }, [el("dt", { text: term }), el("dd", { text: detail })]),
          ]),
        ),
      ]),
    ]),
    trace.node,
  ]);

  return { node, trace };
}

/* ------------------------------------------------------------------ sections */

function howSection(): HTMLElement {
  const rows: Array<[string, string, string]> = [
    [
      "1",
      "Mandate",
      "A principal signs one. It names the agent, allowed merchants and categories, per-transaction, daily and lifetime caps, and a validity window.",
    ],
    [
      "2",
      "Check",
      "The gateway verifies the signature and mandate state, then runs limits, allowlists, expiry and nonce in a fixed order. The first failure is the reason code.",
    ],
    [
      "3",
      "Capture",
      "An approval reserves the amount, writes an outbox event and calls the provider. Retries reuse one idempotency key; an unknown result waits for reconciliation.",
    ],
    [
      "4",
      "Record",
      "Capture posts a balanced journal entry and appends a hash-chained audit event. A denial is recorded with its reason, and nothing moves.",
    ],
  ];

  return el("section", { className: "p-section", id: "how", ariaLabel: "How AgentPay works" }, [
    el("div", { className: "p-shell p-how" }, [
      el("div", { className: "p-how-aside" }, [
        el("p", { className: "p-eyebrow", text: "How it works" }),
        el("h2", { className: "p-h2", text: "Authority, checked before money moves." }),
        el("p", {
          className: "p-copy",
          text: "Four steps between an agent's intent and a settled payment. Each one is deterministic, recorded, and reversible only by reconciliation.",
        }),
      ]),
      el(
        "div",
        { className: "p-rows" },
        rows.map(([number, title, copy]) =>
          el("div", { className: "p-row" }, [
            el("span", { className: "p-row-n", text: number }),
            el("div", {}, [el("h3", { className: "p-row-title", text: title }), el("p", { className: "p-row-copy", text: copy })]),
          ]),
        ),
      ),
    ]),
  ]);
}

function codeLine(parts: Array<[string, string?]>): HTMLElement {
  const line = el("div", { className: "p-code-line" });
  parts.forEach(([text, className]) => {
    line.append(el("span", { className: className ?? "", text }));
  });
  return line;
}

function policySection(): HTMLElement {
  const rules = [
    "Per-transaction, daily and lifetime caps",
    "Merchant and category allowlists",
    "Expiry, not-before and nonce replay checks",
    "Wallet identity and currency must match",
  ];

  const example = el("figure", { className: "p-example" }, [
    el("figcaption", {}, [
      el("span", { text: "POST /v1/payments" }),
      el("span", { className: "p-fig-status", text: "403 · refused" }),
    ]),
    el("div", { className: "p-code" }, [
      codeLine([["{"]]),
      codeLine([['  "agent_id"', "p-k"], [": ", "p-p"], ['"agt_7f2c"', "p-s"], [","]]),
      codeLine([['  "mandate_id"', "p-k"], [": ", "p-p"], ['"mnd_01"', "p-s"], [","]]),
      codeLine([['  "merchant"', "p-k"], [": ", "p-p"], ['"blue_bottle"', "p-s"], [","]]),
      codeLine([['  "amount_minor"', "p-k"], [": ", "p-p"], ["150000", "p-n"], [","]]),
      codeLine([['  "currency"', "p-k"], [": ", "p-p"], ['"INR"', "p-s"]]),
      codeLine([["}"]]),
    ]),
    el("div", { className: "p-code p-code-response" }, [
      codeLine([["403  ", "p-bad"], ["AMOUNT_OVER_TX_LIMIT", "p-bad"]]),
      codeLine([["mandate cap   ", "p-dim"], ["1,000.00"]]),
      codeLine([["requested     ", "p-dim"], ["1,500.00"]]),
    ]),
    el("p", { className: "p-caption", text: "Illustrative response. Reason codes come back from the gateway unchanged." }),
  ]);

  return el("section", { className: "p-section", id: "policy", ariaLabel: "Policy engine" }, [
    el("div", { className: "p-shell p-policy" }, [
      el("div", {}, [
        el("p", { className: "p-eyebrow", text: "Policy" }),
        el("h2", { className: "p-h2", text: "A mandate is a contract, not a prompt." }),
        el("p", {
          className: "p-copy",
          text: "The engine is deterministic. The same request against the same mandate returns the same answer, with the rule that failed and the numbers behind it.",
        }),
        el(
          "ul",
          { className: "p-rulelist" },
          rules.map((rule) => el("li", { text: rule })),
        ),
      ]),
      example,
    ]),
  ]);
}

function recordPanel(label: string, chip: string, rows: Array<[string, string, string]>): HTMLElement {
  return el("div", { className: "p-panel" }, [
    el("div", { className: "p-panel-head" }, [el("span", { text: label }), el("span", { className: "p-chip", text: chip })]),
    el(
      "div",
      { className: "p-panel-rows" },
      rows.map(([name, value, tone]) =>
        el("div", { className: "p-panel-row" }, [el("span", { text: name }), el("span", { className: `p-num ${tone}`, text: value })]),
      ),
    ),
  ]);
}

function recordsSection(): HTMLElement {
  return el("section", { className: "p-section", id: "records", ariaLabel: "Ledger and audit records" }, [
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-section-head" }, [
        el("div", {}, [
          el("p", { className: "p-eyebrow", text: "Records" }),
          el("h2", { className: "p-h2", text: "It happened. Here is the proof." }),
        ]),
        el("p", {
          className: "p-section-copy",
          text: "A capture writes a balanced entry and a sealed audit event in one transaction. A denial is recorded with its reason and leaves the ledger untouched.",
        }),
      ]),
      el("div", { className: "p-panels" }, [
        recordPanel("Ledger · capture pay_8f21c4", "Balanced", [
          ["Merchant payable", "+ 420.00", "p-pos"],
          ["Agent wallet", "− 420.00", "p-neg"],
          ["Imbalance", "0.00", "p-dim"],
        ]),
        recordPanel("Audit · event chain", "Verified", [
          ["pay.requested", "0x8a41…c2", "p-dim"],
          ["policy.allowed", "0x19e0…7b", "p-dim"],
          ["pay.captured", "0x5c77…1a", "p-dim"],
        ]),
      ]),
    ]),
  ]);
}

function paymentsTable(): HTMLElement {
  const rows: Array<[string, string, string, string, string]> = [
    ["pay_8f21c4", "blue_bottle", "420.00", "approved", "is-ok"],
    ["pay_2b9d11", "vercel", "2,400.00", "approved", "is-ok"],
    ["pay_77ac02", "casino_rewards", "900.00", "denied", "is-no"],
    ["pay_31de88", "hosting_co", "1,200.00", "unresolved", "is-unk"],
    ["pay_90bb13", "blue_bottle", "380.00", "approved", "is-ok"],
  ];

  const head = el("thead", {}, [
    el("tr", {}, ["Payment", "Merchant", "Amount", "Status"].map((label) => el("th", { text: label }))),
  ]);

  const body = el(
    "tbody",
    {},
    rows.map(([id, merchant, amount, status, tone]) =>
      el("tr", {}, [
        el("td", { className: "p-mono", text: id }),
        el("td", { text: merchant }),
        el("td", { className: "p-amount", text: `INR ${amount}` }),
        el("td", {}, [
          el("span", { className: `p-status ${tone}` }, [el("span", { className: "p-dot", ariaHidden: true }), status]),
        ]),
      ]),
    ),
  );

  return el("div", { className: "p-table" }, [el("table", {}, [head, body])]);
}

function consoleSection(): HTMLElement {
  return el("section", { className: "p-section", id: "console", ariaLabel: "Operator console" }, [
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-section-head" }, [
        el("div", {}, [
          el("p", { className: "p-eyebrow", text: "Console" }),
          el("h2", { className: "p-h2", text: "The operator's side." }),
        ]),
        el("p", {
          className: "p-section-copy",
          text: "Payments, live budgets, balanced journals and the audit chain — read from your gateway's admin API. No mock data in the real console.",
        }),
      ]),
      paymentsTable(),
      el("p", { className: "p-note p-table-note", text: "Sample rows from the simulation lab." }),
    ]),
  ]);
}

function accessSection(options: LandingOptions): HTMLElement {
  const input = el("input", {
    className: "p-input",
    type: "password",
    placeholder: "Admin token",
    autocomplete: "current-password",
    required: true,
    ariaLabel: "Gateway admin token",
  }) as HTMLInputElement;

  const form = el("form", { className: "p-access-form" }, [
    el("label", { className: "p-label", text: "Gateway admin token" }),
    el("div", { className: "p-access-row" }, [
      input,
      el("button", { className: "p-btn p-btn-light", type: "submit", text: "Open console" }),
    ]),
    el("p", {
      className: "p-hint",
      text: "Kept in this browser session. Local demo token: demo-admin-token.",
    }),
  ]);

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const value = input.value.trim();
    if (!value) {
      input.focus();
      return;
    }
    options.onToken(value);
  });

  return el("section", { className: "p-access", id: "access", ariaLabel: "Open the operator console" }, [
    el("div", { className: "p-shell p-access-grid" }, [
      el("div", {}, [
        el("p", { className: "p-eyebrow", text: "Access" }),
        el("h2", { className: "p-h2", text: "Open the console." }),
        el("p", {
          className: "p-access-copy",
          text: "Paste the admin token for your gateway. It is sent only to the gateway you configured and stored nowhere else.",
        }),
      ]),
      form,
    ]),
  ]);
}

function footer(): HTMLElement {
  return el("footer", { className: "p-footer" }, [
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-footer-top" }, [
        el("div", { className: "p-footer-brand" }, [
          brand(),
          el("p", { text: "Payment infrastructure for autonomous agents. Authority, enforced before the money moves." }),
        ]),
        el(
          "nav",
          { className: "p-footer-nav", ariaLabel: "Footer" },
          NAV.map(([label, id]) => jump(label, id)),
        ),
      ]),
      el("div", { className: "p-footer-bottom" }, [
        el("span", { text: "© 2026 AgentPay" }),
        el("span", { text: "Simulation-only environment. No real money moves." }),
      ]),
    ]),
  ]);
}

/* ------------------------------------------------------------------ page */

let startTrace: (() => void) | null = null;

export function landingScreen(options: LandingOptions): HTMLElement {
  const heroBlock = hero();
  startTrace = heroBlock.trace.start;

  return el("div", { className: "landing p-site" }, [
    el("a", { className: "p-skip", href: "#how", text: "Skip to content" }),
    siteHeader(),
    el("main", {}, [heroBlock.node, howSection(), policySection(), recordsSection(), consoleSection(), accessSection(options)]),
    footer(),
  ]);
}

export function startLanding(_root: HTMLElement): void {
  startTrace?.();
}
