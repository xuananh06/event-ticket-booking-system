"use strict";

// Optional keyboard guidance; server validation and ordinary form submission remain authoritative.
(() => {
    const summary = document.getElementById("validation-summary");
    if (!summary) {
        return;
    }
    const controls = [...document.querySelectorAll("input, select, textarea")];
    for (const entry of summary.querySelectorAll("[data-field]")) {
        const candidates = controls.filter(control => control.name === entry.dataset.field
            && control.type !== "hidden" && !control.disabled && control.id);
        const control = candidates.find(candidate => candidate.getAttribute("aria-invalid") === "true") || candidates[0];
        if (control) {
            const link = document.createElement("a");
            link.href = `#${control.id}`;
            link.textContent = entry.textContent;
            entry.replaceChildren(link);
        }
    }
    summary.focus();
})();
