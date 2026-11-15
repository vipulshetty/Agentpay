export interface ElOptions {
  className?: string;
  text?: string;
  title?: string;
  href?: string;
  type?: string;
  value?: string;
  placeholder?: string;
  checked?: boolean;
  onClick?: (event: MouseEvent) => void;
  onInput?: (event: Event) => void;
}

/** Small element builder, so views read as structure instead of string concatenation. */
export function el(tag: string, options: ElOptions = {}, children: Array<Node | string> = []): HTMLElement {
  const node = document.createElement(tag);
  if (options.className) node.className = options.className;
  if (options.text !== undefined) node.textContent = options.text;
  if (options.title) node.title = options.title;
  if (options.placeholder) node.setAttribute("placeholder", options.placeholder);
  if (tag === "a" && options.href) node.setAttribute("href", options.href);
  if (tag === "input") {
    const input = node as HTMLInputElement;
    if (options.type) input.type = options.type;
    if (options.value !== undefined) input.value = options.value;
    if (options.checked !== undefined) input.checked = options.checked;
  }
  if (tag === "button" && options.type) {
    (node as HTMLButtonElement).type = options.type as "button" | "submit" | "reset";
  }
  if (options.onClick) node.addEventListener("click", options.onClick as EventListener);
  if (options.onInput) node.addEventListener("input", options.onInput);
  for (const child of children) {
    node.append(child);
  }
  return node;
}

export function pill(text: string): HTMLElement {
  return el("span", { className: `pill ${text}`, text });
}

export function table(headers: string[], rows: Array<Array<Node | string>>): HTMLElement {
  const head = el("thead", {}, [el("tr", {}, headers.map((header) => el("th", { text: header })))]);
  const body = el(
    "tbody",
    {},
    rows.map((cells) => el("tr", {}, cells.map((cell) => el("td", {}, [cell])))),
  );
  return el("table", {}, [head, body]);
}

export function empty(message: string): HTMLElement {
  return el("div", { className: "empty", text: message });
}

export function moneyCell(amountMinor: number, currency: string): HTMLElement {
  return el("span", { className: "mono", text: `${currency} ${(amountMinor / 100).toFixed(2)}` });
}
