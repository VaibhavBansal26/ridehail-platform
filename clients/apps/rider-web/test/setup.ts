import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

afterEach(() => cleanup());

// jsdom has no <dialog> modal support; model just enough of it for the confirm dialogs
if (typeof HTMLDialogElement !== "undefined") {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.removeAttribute("open"); };
}

// The real map pulls in the whole of Leaflet, which jsdom cannot draw and which makes every screen's test slow. Screens are tested for
// what they hand to the map (see ride-map.test.tsx, which replaces this stub with one that records it); the map itself is checked in a browser.
vi.mock("@/components/ride-map", () => ({ RideMap: () => null }));
