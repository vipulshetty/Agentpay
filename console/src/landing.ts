/**
 * The AgentPay marketing surface.
 *
 * Restrained by design: a typographic page with a single animated element (the
 * authorization trace). Everything else is static content. The only live
 * interaction is the admin-token form, which opens the real operator console.
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
      logoMark(),
      el("span", { className: "brand-name" }, [
        document.createTextNode("Agent"),
        el("span", { className: "brand-name-accent", text: "Pay" }),
      ]),
    ],
  );
}

function siteHeader(): HTMLElement {
  const header = el("header", { className: "site-header p-header" });
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

  const links: Array<[string, string]> = [
    ["How it works", "how"],
    ["Policy", "policy"],
    ["Records", "records"],
    ["Console", "console"],
  ];

  const nav = el(
    "nav",
    { className: "p-nav", ariaLabel: "Sections" },
    links.map(([label, id]) =>
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
        [document.createTextNode("Open console"), el("span", { className: "p-arrow", ariaHidden: true, text: "→" })],
      ),
      toggle,
    ]),
  );
  return header;
}

/* ------------------------------------------------------------------ hero */

const STEPS = [
  { name: "Request", active: "receiving", done: "signed body", fail: "rejected" },
  { name: "Mandate", active: "verifying", done: "signature valid", fail: "invalid" },
  { name: "Policy", active: "evaluating", done: "within all limits", fail: "denied · over cap" },
  { name: "Capture", active: "reserving", done: "pay_8f21c4", fail: "not reached" },
  { name: "Record", active: "writing", done: "balanced + sealed", fail: "not reached" },
] as const;

type Mode = "approved" | "denied";
type StepState = "idle" | "active" | "done" | "fail" | "skip";

const OUTCOME: Record<Mode, readonly ("done" | "fail" | "skip")[]> = {
  approved: ["done", "done", "done", "done", "done"],
  denied: ["done", "done", "fail", "skip", "skip"],
};

const SCENARIO: Record<Mode, { request: string; verdict: string; code: string }> = {
  approved: { request: "INR 420.00 · blue_bottle · mnd_01", verdict: "APPROVED", code: "pay_8f21c4 captured" },
  denied: { request: "INR 1,500.00 · blue_bottle · mnd_01", verdict: "DENIED", code: "AMOUNT_OVER_TX_LIMIT" },
};

interface Pipeline {
  node: HTMLElement;
  start(): void;
}

/** The one system animation on the page: a request moving through five checks. */
function createPipeline(): Pipeline {
  let current: Mode = "approved";
  let started = false;
  let token = 0;
  let timer = 0;

  const root = el("section", { className: "p-pipe", ariaLabel: "Authorization trace" });
  const request = el("span", { className: "p-pipe-req", text: SCENARIO.approved.request });
  const verdict = el("span", { className: "p-pipe-verdict" });
  const verdictMain = el("span", { className: "p-verdict-main", text: "READY" });
  const verdictCode = el("span", { className: "p-verdict-code" });
  verdict.append(verdictMain, verdictCode);
  verdict.setAttribute("role", "status");
  verdict.setAttribute("aria-live", "polite");

  const items = STEPS.map((step, index) => {
    const state = el("span", { className: "p-step-state", text: "waiting" });
    const item = el("li", { className: "p-step is-idle" }, [
      el("span", { className: "p-step-n", text: String(index + 1).padStart(2, "0") }),
      el("span", { className: "p-step-name", text: step.name }),
      state,
    ]);
    return { item, state };
  });

  const fill = el("span", { className: "p-pipe-fill" });

  const modeButtons: Array<{ button: HTMLButtonElement; mode: Mode }> = (["approved", "denied"] as Mode[]).map((mode) => {
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
  });

  root.append(
    el("div", { className: "p-pipe-head" }, [
      el("span", { className: "p-pipe-label", text: "Authorization trace" }),
      el("div", { className: "p-modes" }, modeButtons.map((entry) => entry.button)),
    ]),
    el("ol", { className: "p-steps" }, items.map((entry) => entry.item)),
    el("div", { className: "p-pipe-track" }, [fill]),
    el("div", { className: "p-pipe-foot" }, [request, verdict]),
  );

  function paint(states: readonly StepState[]): void {
    items.forEach((entry, index) => {
      const state = states[index] ?? "idle";
      entry.item.className = `p-step is-${state}`;
      const step = STEPS[index]!;
      entry.state.textContent =
        state === "idle"
          ? "waiting"
          : state === "active"
            ? step.active
            : state === "done"
              ? step.done
              : state === "fail"
                ? step.fail
                : "not reached";
    });
    const complete = states.filter((state) => state === "done" || state === "fail").length;
    fill.style.width = `${(complete / STEPS.length) * 100}%`;
  }

  function run(): void {
    token += 1;
    const id = token;
    window.clearTimeout(timer);

    const outcome = OUTCOME[current];
    const copy = SCENARIO[current];
    const states: StepState[] = ["idle", "idle", "idle", "idle", "idle"];

    root.classList.toggle("is-denied", current === "denied");
    request.textContent = copy.request;
    verdict.className = "p-pipe-verdict";
    verdictMain.textContent = "CHECKING";
    verdictCode.textContent = "";
    paint(states);

    if (reduced()) {
      outcome.forEach((state, index) => {
        states[index] = state;
      });
      paint(states);
      finish();
      return;
    }

    const order = current === "denied" ? [0, 1, 2] : [0, 1, 2, 3, 4];
    let cursor = 0;

    function finish(): void {
      verdict.className = `p-pipe-verdict is-${current === "approved" ? "approved" : "denied"}`;
      verdictMain.textContent = copy.verdict;
      verdictCode.textContent = copy.code;
    }

    function advance(): void {
      if (id !== token) return;
      if (cursor >= order.length) {
        finish();
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
      }, 560);
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
      { threshold: 0.35 },
    );
    observer.observe(root);
  }

  return { node: root, start };
}

function hero(): { node: HTMLElement; pipeline: Pipeline } {
  const pipeline = createPipeline();

  const facts = ["Ed25519 mandates", "Deny by default", "Double-entry ledger", "Hash-chained audit"];

  const node = el("section", { className: "p-hero", id: "top", ariaLabel: "AgentPay introduction" }, [
    el("div", { className: "p-shell p-hero-grid" }, [
      el("div", { className: "p-hero-main" }, [
        el("p", { className: "p-eyebrow", text: "Payment infrastructure for AI agents" }),
        el("h1", { className: "p-title" }, [
          el("span", { text: "The agent asks." }),
          el("span", {}, [document.createTextNode("The gateway "), el("span", { className: "p-accent", text: "decides." })]),
        ]),
      ]),
      el("div", { className: "p-hero-side" }, [
        el("p", {
          className: "p-lede",
          text: "Signed spending mandates, deterministic policy checks and a double-entry ledger. The control plane between an agent's intent and a merchant's payment.",
        }),
        el("div", { className: "p-actions" }, [
          el(
            "a",
            {
              className: "p-btn p-btn-primary",
              href: "#how",
              onClick: (event) => {
                event.preventDefault();
                goTo("how");
              },
            },
            [document.createTextNode("Explore the infrastructure")],
          ),
          el(
            "a",
            {
              className: "p-btn p-btn-ghost",
              href: "#access",
              onClick: (event) => {
                event.preventDefault();
                goTo("access");
              },
            },
            [document.createTextNode("Open operator console")],
          ),
        ]),
      ]),
    ]),
    el("div", { className: "p-shell" }, [pipeline.node]),
    el("div", { className: "p-shell" }, [
      el(
        "div",
        { className: "p-facts" },
        facts.map((fact) => el("span", { className: "p-fact", text: fact })),
      ),
    ]),
  ]);

  return { node, pipeline };
}

/* ------------------------------------------------------------------ sections */

function howSection(): HTMLElement {
  const rows: Array<[string, string, string]> = [
    [
      "01",
      "Mandate",
      "A principal signs one. It names the agent, allowed merchants and categories, per-transaction, daily and lifetime caps, and a validity window. The signature is Ed25519.",
    ],
    [
      "02",
      "Check",
      "The gateway verifies the signature and mandate state, then runs limits, allowlists, expiry and nonce in a fixed order. The first failure becomes the reason code.",
    ],
    [
      "03",
      "Capture",
      "An approval reserves the amount, writes an outbox event and calls the provider. Retries reuse one idempotency key; an unknown result stays unresolved until reconciled.",
    ],
    [
      "04",
      "Record",
      "Capture posts a balanced journal entry and appends a hash-chained audit event. A denial is recorded with its reason, and nothing moves.",
    ],
  ];

  return el("section", { className: "p-section", id: "how", ariaLabel: "How AgentPay works" }, [
    el("div", { className: "p-shell p-how" }, [
      el("div", { className: "p-how-aside" }, [
        el("h2", { className: "p-h2", text: "Authority, checked before money moves." }),
        el("p", {
          text: "Four steps between an agent's intent and a settled payment. Each one is deterministic, recorded and reversible only by reconciliation.",
        }),
      ]),
      el(
        "div",
        { className: "p-rows" },
        rows.map(([number, title, copy]) =>
          el("div", { className: "p-row" }, [
            el("span", { className: "p-row-n", text: number }),
            el("div", {}, [
              el("h3", { className: "p-row-title", text: title }),
              el("p", { className: "p-row-copy", text: copy }),
            ]),
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

  const code = el("div", { className: "p-code" }, [
    el("div", { className: "p-code-head" }, [
      el("span", { text: "POST /v1/payments" }),
      el("span", { text: "application/json" }),
    ]),
    el("div", { className: "p-code-body" }, [
      codeLine([["{ "]]),
      codeLine([['  "agent_id"', "p-key"], [": ", "p-punc"], ['"agt_7f2c"', "p-str"], [","]]),
      codeLine([['  "mandate_id"', "p-key"], [": ", "p-punc"], ['"mnd_01"', "p-str"], [","]]),
      codeLine([['  "merchant"', "p-key"], [": ", "p-punc"], ['"blue_bottle"', "p-str"], [","]]),
      codeLine([['  "amount_minor"', "p-key"], [": ", "p-punc"], ["150000", "p-num"], [","]]),
      codeLine([['  "currency"', "p-key"], [": ", "p-punc"], ['"INR"', "p-str"]]),
      codeLine([["}"]]),
      el("div", { className: "p-code-rule" }),
      codeLine([["403  ", "p-bad"], ["AMOUNT_OVER_TX_LIMIT", "p-bad"]]),
      codeLine([["mandate cap   ", "p-dim"], ["1,000.00", "p-str"]]),
      codeLine([["requested     ", "p-dim"], ["1,500.00", "p-str"]]),
    ]),
  ]);

  return el("section", { className: "p-section", id: "policy", ariaLabel: "Policy engine" }, [
    el("div", { className: "p-shell p-policy" }, [
      el("div", { className: "p-policy-copy" }, [
        el("h2", { className: "p-h2", text: "A mandate is a contract, not a prompt." }),
        el("p", {
          text: "The engine is deterministic. The same request against the same mandate returns the same answer, with the rule that failed and the numbers behind it.",
        }),
        el(
          "ul",
          { className: "p-rulelist" },
          rules.map((rule) => el("li", { text: rule })),
        ),
      ]),
      el("div", {}, [
        code,
        el("p", { className: "p-caption", text: "Illustrative response. Reason codes come back from the gateway unchanged." }),
      ]),
    ]),
  ]);
}

function recordPanel(
  label: string,
  chip: string,
  chipClass: string,
  rows: Array<[string, string, string]>,
): HTMLElement {
  return el("div", { className: "p-panel" }, [
    el("div", { className: "p-panel-head" }, [
      el("span", { text: label }),
      el("span", { className: `p-chip ${chipClass}`, text: chip }),
    ]),
    ...rows.map(([name, value, tone]) =>
      el("div", { className: "p-panel-row" }, [
        el("span", { text: name }),
        el("span", { className: tone, text: value }),
      ]),
    ),
  ]);
}

function recordsSection(): HTMLElement {
  return el("section", { className: "p-section", id: "records", ariaLabel: "Ledger and audit records" }, [
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-section-head" }, [
        el("h2", { className: "p-h2", text: "It happened. Here is the proof." }),
        el("p", {
          className: "p-section-copy",
          text: "A capture writes a balanced entry and a sealed audit event in one transaction. A denial is recorded with its reason and leaves the ledger untouched.",
        }),
      ]),
      el("div", { className: "p-panels" }, [
        recordPanel("Ledger · capture:pay_8f21c4", "BALANCED", "is-ok", [
          ["MERCHANT_PAYABLE", "+ 420.00", "p-pos"],
          ["AGENT_WALLET", "− 420.00", "p-neg"],
          ["imbalance", "0.00", "p-dim"],
        ]),
        recordPanel("Audit · event chain", "VERIFIED", "is-ok", [
          ["pay.requested", "0x8a41…c2", "p-dim"],
          ["policy.allowed", "0x19e0…7b", "p-dim"],
          ["pay.captured", "0x5c77…1a", "p-dim"],
        ]),
      ]),
    ]),
  ]);
}

function consoleSection(): HTMLElement {
  const rows: Array<[string, string, string, string, string]> = [
    ["pay_8f21c4", "blue_bottle", "420.00", "approved", "is-ok"],
    ["pay_2b9d11", "vercel", "2,400.00", "approved", "is-ok"],
    ["pay_77ac02", "casino_rewards", "900.00", "denied", "is-no"],
    ["pay_31de88", "hosting_co", "1,200.00", "unresolved", "is-unk"],
    ["pay_90bb13", "blue_bottle", "380.00", "approved", "is-ok"],
  ];

  const table = el("div", { className: "p-table" }, [
    el("div", { className: "p-table-head" }, [
      el("span", { text: "Payments" }),
      el("span", { text: "24h · demo data" }),
    ]),
    el("div", { className: "p-tr p-th" }, [
      el("span", { text: "payment" }),
      el("span", { text: "merchant" }),
      el("span", { text: "INR" }),
      el("span", { text: "status" }),
    ]),
    ...rows.map(([id, merchant, amount, status, tone]) =>
      el("div", { className: "p-tr" }, [
        el("span", { className: "p-mono-id", text: id }),
        el("span", { text: merchant }),
        el("span", { text: amount }),
        el("span", { className: `p-status ${tone}`, text: status }),
      ]),
    ),
  ]);

  const capabilities: Array<[string, string]> = [
    ["Payments", "Every request with its decision, reason code and provider state."],
    ["Mandates", "Live budgets, remaining caps and the exact signing key."],
    ["Ledger", "Balanced journals, plus re-post attempts that correctly no-op."],
    ["Audit", "The hash chain, verified event by event."],
  ];

  return el("section", { className: "p-section", id: "console", ariaLabel: "Operator console" }, [
    el("div", { className: "p-shell p-console" }, [
      el("div", { className: "p-console-copy" }, [
        el("h2", { className: "p-h2", text: "The operator's side." }),
        el("p", {
          text: "The console reads your gateway's admin API directly. No mock data in the real console, and no second source of truth.",
        }),
        el(
          "dl",
          { className: "p-caps" },
          capabilities.flatMap(([title, copy]) => [
            el("dt", { text: title }),
            el("dd", { text: copy }),
          ]),
        ),
      ]),
      table,
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

  const form = el("form", { className: "p-form" }, [
    el("label", { className: "p-label", text: "Gateway admin token" }),
    input,
    el("button", { className: "p-btn p-btn-primary", type: "submit" }, [
      document.createTextNode("Open operator console"),
      el("span", { className: "p-arrow", ariaHidden: true, text: "→" }),
    ]),
    el("p", {
      className: "p-hint",
      text: "Kept in this browser session. Local demo: demo-admin-token.",
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
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-access-card" }, [
        el("div", {}, [
          el("h2", { className: "p-h3", text: "Open the console." }),
          el("p", {
            className: "p-access-copy",
            text: "Paste the admin token for your gateway. It is sent only to the gateway you configured and stored nowhere else.",
          }),
        ]),
        form,
      ]),
    ]),
  ]);
}

function footer(): HTMLElement {
  const column = (title: string, links: Array<[string, string]>): HTMLElement =>
    el("div", { className: "p-footer-col" }, [
      el("span", { text: title }),
      ...links.map(([label, id]) => jump(label, id)),
    ]);

  return el("footer", { className: "p-footer", ariaLabel: "AgentPay footer" }, [
    el("div", { className: "p-shell" }, [
      el("div", { className: "p-footer-top" }, [
        el("div", { className: "p-footer-brand" }, [
          brand(),
          el("p", { text: "Payment infrastructure for autonomous agents. Authority, enforced before the money moves." }),
        ]),
        el("div", { className: "p-footer-nav" }, [
          column("Sections", [
            ["How it works", "how"],
            ["Policy", "policy"],
            ["Records", "records"],
            ["Console", "console"],
          ]),
          column("Get started", [
            ["Open console", "access"],
            ["Back to top", "top"],
          ]),
        ]),
      ]),
      el("div", { className: "p-footer-bottom" }, [
        el("span", { text: "© 2026 AgentPay" }),
        el("span", { text: "Simulation environment — no real money moves." }),
      ]),
    ]),
  ]);
}

/* ------------------------------------------------------------------ page */

let startPipeline: (() => void) | null = null;

export function landingScreen(options: LandingOptions): HTMLElement {
  const heroBlock = hero();
  startPipeline = heroBlock.pipeline.start;

  return el("div", { className: "landing p-site" }, [
    el("a", { className: "p-skip", href: "#how", text: "Skip to content" }),
    siteHeader(),
    el("main", {}, [
      heroBlock.node,
      howSection(),
      policySection(),
      recordsSection(),
      consoleSection(),
      accessSection(options),
    ]),
    footer(),
  ]);
}

export function startLanding(_root: HTMLElement): void {
  startPipeline?.();
}
