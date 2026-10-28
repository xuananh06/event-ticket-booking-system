"use strict";

// Supplementary preview only; the server owns prices, allocation and deadlines.
(() => {
    const form = document.getElementById("seat-selection");
    if (!form || typeof BigInt !== "function") {
        return;
    }
    const count = document.getElementById("selected-count");
    const total = document.getElementById("selected-total");
    const update = () => {
        const selected = form.querySelectorAll('input[name="eventSeatId"]:checked:not(:disabled)');
        let cents = 0n;
        for (const seat of selected) {
            const [whole, fraction = ""] = seat.dataset.price.split(".");
            cents += BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
        }
        count.textContent = String(selected.length);
        total.textContent = `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
    };
    form.addEventListener("change", update);
    update();
})();
