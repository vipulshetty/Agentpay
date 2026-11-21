/**
 * The AgentPayB mark, traced from the supplied brand sheet.
 *
 * It is inline SVG rather than a bitmap so it stays sharp at any size. The slope is measured from
 * the sheet: a 45° blade with flat top and bottom cuts, plus the folded facet tucked beneath it.
 * Both halves carry a gradient, matching the glow in the reference artwork.
 */

const SVG_NS = "http://www.w3.org/2000/svg";

export const BRAND_GREEN = "#2bf0a0";

let uid = 0;

export function logoMark(className = "brand-mark"): SVGSVGElement {
  // Two brands on one page must not share gradient ids.
  const suffix = `-${++uid}`;

  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 226 122");
  svg.setAttribute("class", className);
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");

  const defs = document.createElementNS(SVG_NS, "defs");
  defs.innerHTML = `
    <linearGradient id="blade${suffix}" x1="0" y1="1" x2="1" y2="0">
      <stop offset="0" stop-color="#17ef99"/>
      <stop offset="1" stop-color="#6dfab8"/>
    </linearGradient>
    <linearGradient id="facet${suffix}" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#06412c"/>
      <stop offset="1" stop-color="#03996a"/>
    </linearGradient>`;

  const blade = document.createElementNS(SVG_NS, "path");
  blade.setAttribute("d", "M117 0 H184 L67 122 H0 Z");
  blade.setAttribute("fill", `url(#blade${suffix})`);

  const facet = document.createElementNS(SVG_NS, "path");
  facet.setAttribute("d", "M191 40 L226 90 L170 118 L142 90 Z");
  facet.setAttribute("fill", `url(#facet${suffix})`);

  svg.append(defs, blade, facet);
  return svg;
}
