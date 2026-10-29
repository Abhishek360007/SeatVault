/* ============================================================================
 * SeatVault SPA — vanilla JS. Talks to the same REST API Swagger documents.
 * JWT is kept in localStorage and attached as a Bearer header on protected calls.
 *
 * Pricing comes from the backend: events carry currency/basePriceMinor/
 * convenienceFeeMinor and each seat carries its resolved tierName + priceMinor.
 * The UI renders those values (it no longer hardcodes prices). Tier colours are the
 * only presentation detail kept client-side, keyed by distinct seat price.
 * The hold/pay/confirm calls are the production API.
 * ========================================================================== */

const API = "/api/v1";
const TOKEN_KEY = "seatvault.jwt";
const USER_KEY = "seatvault.user";

// Visual palette assigned to distinct seat prices (highest price = first colour).
const TIER_COLORS = ["#b45309", "#0e7490", "#2f5fe0", "#047857", "#b91c1c"];
const TIER_CLASSES = ["t0", "t1", "t2", "t0", "t1"];

// Label, emoji and poster gradient per category; keys match the API's EventCategory enum.
const CATEGORY_STYLE = {
    CONCERT:    { label: "Concert",    emoji: "🎤", grad: "linear-gradient(135deg,#1d4ed8,#0e7490)" },
    SPORTS:     { label: "Sports",     emoji: "🏟️", grad: "linear-gradient(135deg,#0e7490,#cbd0dc)" },
    CONFERENCE: { label: "Conference", emoji: "📊", grad: "linear-gradient(135deg,#2f5fe0,#cbd0dc)" },
    MOVIE:      { label: "Movie",      emoji: "🎬", grad: "linear-gradient(135deg,#7c3aed,#1d4ed8)" },
    THEATRE:    { label: "Theatre",    emoji: "🎭", grad: "linear-gradient(135deg,#b45309,#eef0f5)" },
    COMEDY:     { label: "Comedy",     emoji: "🎙️", grad: "linear-gradient(135deg,#047857,#eaecf2)" },
    OTHER:      { label: "Event",      emoji: "🎉", grad: "linear-gradient(135deg,#1d4ed8,#eef0f5)" },
};
const GRADS = Object.values(CATEGORY_STYLE).map(c => c.grad);

const state = {
    token: localStorage.getItem(TOKEN_KEY) || null,
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
    eventsPage: 0,
    searchTerm: "",
    // Discovery filters; empty string / null means "not filtering on this".
    filters: { category: "", city: "", from: "", to: "", min: "", max: "", availableOnly: false, sort: "DATE:ASC" },
    event: null,            // event being booked (with pricing fields)
    seats: [],              // seat list for current event (each with priceMinor/tierName)
    priceColors: new Map(), // distinct priceMinor -> colour/class (per event)
    selected: new Map(),    // seatId -> seat
    booking: null,          // active PENDING booking (carries amountMinor/currency from API)
    checkout: null,         // provider order for the booking being paid (from POST /checkout)
    checkoutEvent: null,    // event of the booking being paid (may differ when resumed from My tickets)
    paymentSettled: false,  // set once verify succeeds, so a closing widget doesn't report "cancelled"
    simMethod: "CARD",      // method picked in the simulated provider's panel
    countdown: null,
    stompClient: null,
    stompSub: null,
    previousFocus: null,
};

// money() formats minor units in the current event's currency.
function money(minor, currency) {
    const cur = currency || state.event?.currency || "INR";
    const major = (minor || 0) / 100;
    try {
        return new Intl.NumberFormat(undefined, { style: "currency", currency: cur, maximumFractionDigits: 2 }).format(major);
    } catch {
        return cur + " " + major.toFixed(2);
    }
}

// Stable per-event colour for a seat price (richer/front tiers get the warm colour).
function colorForPrice(priceMinor) {
    if (!state.priceColors.has(priceMinor)) {
        const idx = state.priceColors.size % TIER_COLORS.length;
        state.priceColors.set(priceMinor, { color: TIER_COLORS[idx], cls: TIER_CLASSES[idx] });
    }
    return state.priceColors.get(priceMinor);
}

/* ------------------------------- helpers -------------------------------- */
const $ = s => document.querySelector(s);
const $$ = s => Array.from(document.querySelectorAll(s));
const el = (t, c, h) => { const n = document.createElement(t); if (c) n.className = c; if (h !== undefined) n.innerHTML = h; return n; };
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;" }[c]));
const fmtDate = iso => iso ? new Date(iso).toLocaleString(undefined, { weekday:"short", day:"numeric", month:"short", year:"numeric", hour:"2-digit", minute:"2-digit" }) : "—";
const hashInt = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return h; };

function toast(msg, kind = "info", ms = 3800) {
    const ic = kind === "success" ? "✅" : kind === "error" ? "⚠️" : "ℹ️";
    const t = el("div", `toast ${kind}`, `<span class="ic">${ic}</span><span>${esc(msg)}</span>`);
    $("#toastWrap").appendChild(t);
    setTimeout(() => { t.style.opacity = "0"; t.style.transform = "translateX(40px)"; t.style.transition = ".25s"; setTimeout(() => t.remove(), 250); }, ms);
}

/* --------------------------------- API ---------------------------------- */
const MOCK_MODE = false; // Set to true to run static files directly in browser without a running backend

async function api(path, { method = "GET", body, auth = true } = {}) {
    if (MOCK_MODE) {
        console.log("Mocking API call:", method, path, body);
        await new Promise(r => setTimeout(r, 400)); // simulate network delay

        // Auth login/register
        if (path === "/auth/login" || path === "/auth/register") {
            const email = body?.email || "user@example.com";
            const fullName = body?.fullName || "Jane Doe";
            const isAuthAdmin = email.includes("admin");
            return {
                token: "mock-jwt-token-12345",
                refreshToken: "mock-refresh-token-12345",
                tokenType: "Bearer",
                expiresInSeconds: 900,
                userId: 99,
                email: email,
                fullName: fullName,
                role: isAuthAdmin ? "ADMIN" : "USER"
            };
        }

        // Events search / list
        if (path.startsWith("/events?")) {
            return {
                content: [
                    { id: 1, title: "Coldplay Corporate Summit", description: "Music of the Spheres exclusive enterprise experience.", venue: "Wembley Enterprise Arena", eventDateTime: new Date(Date.now() + 86400000 * 5).toISOString(), totalCapacity: 60, availableSeats: 52, currency: "INR", basePriceMinor: 800000, convenienceFeeMinor: 40000 },
                    { id: 2, title: "Global Sports Cup Finals", description: "B2B VIP sporting championship.", venue: "Stadium Allianz", eventDateTime: new Date(Date.now() + 86400000 * 10).toISOString(), totalCapacity: 80, availableSeats: 70, currency: "INR", basePriceMinor: 1200000, convenienceFeeMinor: 60000 },
                    { id: 3, title: "Comedy B2B Boardroom Gala", description: "Unwind after the quarterly business review.", venue: "The Boardroom Theater", eventDateTime: new Date(Date.now() + 86400000 * 2).toISOString(), totalCapacity: 40, availableSeats: 0, currency: "INR", basePriceMinor: 500000, convenienceFeeMinor: 30000 }
                ],
                totalElements: 3,
                totalPages: 1,
                page: 0,
                first: true,
                last: true
            };
        }

        // Event details
        if (path.match(/\/events\/\d+$/)) {
            const id = parseInt(path.split("/").pop());
            if (id === 1) return { id: 1, title: "Coldplay Corporate Summit", description: "Music of the Spheres exclusive enterprise experience.", venue: "Wembley Enterprise Arena", eventDateTime: new Date(Date.now() + 86400000 * 5).toISOString(), totalCapacity: 60, availableSeats: 52, currency: "INR", basePriceMinor: 800000, convenienceFeeMinor: 40000 };
            if (id === 2) return { id: 2, title: "Global Sports Cup Finals", description: "B2B VIP sporting championship.", venue: "Stadium Allianz", eventDateTime: new Date(Date.now() + 86400000 * 10).toISOString(), totalCapacity: 80, availableSeats: 70, currency: "INR", basePriceMinor: 1200000, convenienceFeeMinor: 60000 };
            return { id: 3, title: "Comedy B2B Boardroom Gala", description: "Unwind after the quarterly business review.", venue: "The Boardroom Theater", eventDateTime: new Date(Date.now() + 86400000 * 2).toISOString(), totalCapacity: 40, availableSeats: 0, currency: "INR", basePriceMinor: 500000, convenienceFeeMinor: 30000 };
        }

        // Event seats
        if (path.match(/\/events\/\d+\/seats$/)) {
            const seats = [];
            const rows = ["A", "B", "C", "D", "E", "F"];
            const seatsPerRow = 10;
            let seatIdCounter = 100;
            rows.forEach((row, rIdx) => {
                for (let s = 1; s <= seatsPerRow; s++) {
                    const label = row + s;
                    let status = "AVAILABLE";
                    if (s % 7 === 0) status = "BOOKED";
                    else if (s % 8 === 0) status = "HELD";

                    let tierName = "General";
                    let priceMinor = 800000;
                    if (rIdx < 2) {
                        tierName = "Premium";
                        priceMinor = 1200000;
                    }

                    seats.push({
                        id: seatIdCounter++,
                        seatLabel: label,
                        status: status,
                        tierName: tierName,
                        priceMinor: priceMinor,
                        heldByUserId: status === "HELD" ? 99 : null
                    });
                }
            });
            return seats;
        }

        // Bookings Hold
        if (path === "/bookings/hold" && method === "POST") {
            return {
                id: 8888,
                eventId: body.eventId,
                eventTitle: "Coldplay Corporate Summit",
                status: "PENDING",
                seatCount: body.seatIds.length,
                seatLabels: body.seatIds.map((id, index) => "Seat-" + id),
                currency: "INR",
                amountMinor: body.seatIds.length * 840000,
                feeMinor: body.seatIds.length * 40000,
                expiresAt: new Date(Date.now() + 300000).toISOString()
            };
        }

        // Bookings Pay
        if (path.match(/\/bookings\/\d+\/pay$/) && method === "POST") {
            const bId = path.split("/")[2];
            if (body.paymentMethod === "FAIL_CARD") {
                throw new Error("Card declined");
            }
            if (body.paymentMethod === "TIMEOUT_CARD") {
                await new Promise(r => setTimeout(r, 1500));
                throw new Error("Payment provider timed out");
            }
            return {
                id: bId,
                eventId: 1,
                eventTitle: "Coldplay Corporate Summit",
                status: "CONFIRMED",
                seatCount: 2,
                seatLabels: ["A5", "A6"],
                currency: "INR",
                amountMinor: 1680000,
                feeMinor: 80000,
                confirmedAt: new Date().toISOString(),
                payment: {
                    status: "APPROVED",
                    providerReference: "PAY-STRIPE-MOCK-9999",
                    failureReason: null
                }
            };
        }

        // Cancel hold
        if (path.match(/\/bookings\/\d+\/cancel$/) && method === "POST") {
            return { id: 8888, status: "CANCELLED" };
        }

        // My tickets / Bookings list
        if (path.startsWith("/bookings")) {
            return {
                content: [
                    {
                        id: 9901,
                        eventTitle: "Coldplay Corporate Summit",
                        seatCount: 2,
                        seatLabels: ["A5", "A6"],
                        currency: "INR",
                        amountMinor: 1680000,
                        status: "CONFIRMED",
                        payment: {
                            status: "APPROVED",
                            providerReference: "PAY-STRIPE-MOCK-1111"
                        }
                    }
                ]
            };
        }
    }

    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (auth && state.token) headers["Authorization"] = `Bearer ${state.token}`;
    const res = await fetch(API + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
    if (res.status === 204) return null;
    const text = await res.text();
    let data = null; if (text) { try { data = JSON.parse(text); } catch { data = text; } }
    if (!res.ok) {
        if (res.status === 401 && state.token) logout();
        const fields = data?.fieldErrors ? " — " + data.fieldErrors.map(f => `${f.field}: ${f.message}`).join("; ") : "";
        const retry = res.headers.get("Retry-After");
        const err = new Error((data?.message || `Request failed (${res.status})`) + fields + (retry ? ` (retry in ${retry}s)` : ""));
        err.status = res.status; throw err;
    }
    return data;
}

/* --------------------------- categorisation ----------------------------- */
// Category is stored on the event; the gradient varies per event so cards in the
// same category still look distinct.
// Poster photo: the event's own imageUrl, else one of the two bundled photos for its
// category, picked by id. EventImages.posterFor applies the same rule for emails.
// The gradient sits underneath as a fallback while (or if) the photo loads.
function posterStyle(ev) {
    const key = CATEGORY_STYLE[ev.category] ? ev.category : "OTHER";
    const base = CATEGORY_STYLE[key];
    const grad = GRADS[hashInt(ev.id + ev.title) % GRADS.length];
    const n = (((ev.id || 0) % 2) + 2) % 2 + 1;
    const img = ev.imageUrl || `/img/events/${key.toLowerCase()}-${n}.jpg`;
    const imgSm = img.startsWith("/img/events/") ? img.replace(/\.jpg$/, "-sm.jpg") : img;
    return {
        cat: base.label, emoji: base.emoji, grad, img,
        bg: `url("${imgSm}") center / cover no-repeat, ${grad}`,
        bgLarge: `url("${img}") center / cover no-repeat, ${grad}`,
    };
}

/* --------------------------- seat tier model ---------------------------- */
function parseLabel(label) {
    const m = String(label).match(/^([A-Za-z]+)(\d+)$/);
    return m ? { row: m[1], num: parseInt(m[2], 10) } : { row: "·", num: 0 };
}

/* =============================== AUTH =================================== */
function renderUser() {
    const logged = !!state.token;
    $("#navLogin").classList.toggle("hidden", logged);
    $("#navUser").classList.toggle("hidden", !logged);
    $("#navTickets").classList.toggle("hidden", !logged);
    const admin = logged && state.user?.role === "ADMIN";
    $("#navCreate").classList.toggle("hidden", !admin);
    $("#navScan").classList.toggle("hidden", !(admin || (logged && state.user?.role === "SCANNER")));
    $("#ddCreate").classList.toggle("hidden", !admin);
    if (logged) {
        const name = state.user.fullName || state.user.email;
        $("#userName").textContent = name.split(" ")[0];
        $("#userAvatar").textContent = (name[0] || "U").toUpperCase();
        const rt = $("#userRole"); rt.textContent = state.user.role; rt.dataset.r = state.user.role;
    }
}
function setSession(auth) {
    state.token = auth.token;
    state.user = { userId: auth.userId, email: auth.email, fullName: auth.fullName, role: auth.role };
    localStorage.setItem(TOKEN_KEY, state.token);
    localStorage.setItem(USER_KEY, JSON.stringify(state.user));
    renderUser();
}
function logout() {
    state.token = null; state.user = null; state.booking = null;
    localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY);
    clearInterval(state.countdown); state.checkout = null;
    renderUser(); showHome();
    toast("Signed out.", "info");
}

/* =============================== MODALS ================================= */
function openModal(id) {
    state.previousFocus = document.activeElement;
    const modal = $("#" + id);
    modal.classList.remove("hidden");
    const firstInput = modal.querySelector("input, select, button.modal-close, [data-close]");
    if (firstInput) {
        setTimeout(() => firstInput.focus(), 50);
    }
}
function closeModal(id) {
    $("#" + id).classList.add("hidden");
    if (state.previousFocus) {
        state.previousFocus.focus();
        state.previousFocus = null;
    }
}
$$("[data-close]").forEach(b => b.addEventListener("click", () => closeModal(b.dataset.close)));
$$(".modal-backdrop, .drawer-backdrop").forEach(bd => bd.addEventListener("click", e => { if (e.target === bd) closeModal(bd.id); }));

document.addEventListener("keydown", e => {
    if (e.key === "Escape") {
        const active = $$(".modal-backdrop, .drawer-backdrop").find(m => !m.classList.contains("hidden"));
        if (active) {
            closeModal(active.id);
        }
    }
});

/* Inline Form Validation Helpers */
function showInlineError(input, message) {
    let errorEl = input.nextElementSibling;
    if (!errorEl || !errorEl.classList.contains("inline-error")) {
        errorEl = el("span", "inline-error", message);
        errorEl.style.color = "var(--red)";
        errorEl.style.fontSize = "0.78rem";
        errorEl.style.marginTop = "4px";
        errorEl.style.display = "block";
        input.after(errorEl);
    } else {
        errorEl.textContent = message;
    }
    input.style.borderColor = "var(--red)";
}

function clearInlineError(input) {
    const errorEl = input.nextElementSibling;
    if (errorEl && errorEl.classList.contains("inline-error")) {
        errorEl.remove();
    }
    input.style.borderColor = "";
}

function validateEmail(input) {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(input.value.trim())) {
        showInlineError(input, "Please enter a valid email address.");
        return false;
    }
    clearInlineError(input);
    return true;
}

function validatePassword(input) {
    if (input.value.length < 8) {
        showInlineError(input, "Password must be at least 8 characters.");
        return false;
    }
    clearInlineError(input);
    return true;
}

/* theme toggle: the initial theme is applied inline in <head>; this keeps it in sync */
const THEME_KEY = "sv_theme";
function applyTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme);
    const btn = $("#themeToggle");
    const next = theme === "dark" ? "light" : "dark";
    btn.textContent = theme === "dark" ? "☀️" : "🌙";
    btn.setAttribute("aria-label", `Switch to ${next} mode`);
    btn.title = `Switch to ${next} mode`;
}
applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light");
$("#themeToggle").addEventListener("click", () => {
    const theme = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
    applyTheme(theme);
    try { localStorage.setItem(THEME_KEY, theme); } catch {}
});
// Follow OS theme changes until the user picks one explicitly.
window.matchMedia?.("(prefers-color-scheme: dark)").addEventListener?.("change", e => {
    let saved = null;
    try { saved = localStorage.getItem(THEME_KEY); } catch {}
    if (!saved) applyTheme(e.matches ? "dark" : "light");
});

/* nav + dropdown wiring */
$("#navLogin").addEventListener("click", () => openModal("authModal"));
$("#userChip").addEventListener("click", () => $("#userDropdown").classList.toggle("hidden"));
document.addEventListener("click", e => { if (!e.target.closest(".user-menu")) $("#userDropdown")?.classList.add("hidden"); });
$("#ddLogout").addEventListener("click", logout);
$("#ddTickets").addEventListener("click", openTickets);
$("#navTickets").addEventListener("click", openTickets);
$("#ddCreate").addEventListener("click", () => { $("#userDropdown").classList.add("hidden"); openModal("createModal"); });
$("#navCreate").addEventListener("click", () => openModal("createModal"));
$("#homeLink").addEventListener("click", e => { e.preventDefault(); showHome(); });
$("#backToHome").addEventListener("click", showHome);

/* auth tabs */
$$("[data-auth]").forEach(b => b.addEventListener("click", () => {
    $$("[data-auth]").forEach(x => x.classList.remove("active")); b.classList.add("active");
    $("#loginForm").classList.toggle("hidden", b.dataset.auth !== "login");
    $("#registerForm").classList.toggle("hidden", b.dataset.auth !== "register");
}));

$("#loginForm").addEventListener("submit", async e => {
    e.preventDefault(); const f = e.target;
    try {
        const a = await api("/auth/login", { auth: false, method: "POST", body: { email: f.email.value.trim(), password: f.password.value } });
        setSession(a); closeModal("authModal"); f.reset();
        toast(`Welcome back, ${a.fullName || a.email}!`, "success");
    } catch (err) { toast(err.message, "error"); }
});
$("#registerForm").addEventListener("submit", async e => {
    e.preventDefault(); const f = e.target;
    try {
        const a = await api("/auth/register", { auth: false, method: "POST", body: { fullName: f.fullName.value.trim(), email: f.email.value.trim(), password: f.password.value } });
        setSession(a); closeModal("authModal"); f.reset();
        toast("Account created — you're in!", "success");
    } catch (err) { toast(err.message, "error"); }
});

/* =============================== VIEWS ================================== */
function showHome({ push = true } = {}) {
    if (push && location.search) history.pushState({}, "", location.pathname);
    document.title = "SeatVault · Live Event Ticketing";
    $("#viewBooking").classList.add("hidden");
    $("#viewCheckout").classList.add("hidden");
    $("#viewScanner").classList.add("hidden");
    stopCamera();
    $("#viewHome").classList.remove("hidden");
    clearInterval(state.countdown);
    disconnectWebSocket();
    window.scrollTo({ top: 0, behavior: "smooth" });
    loadEvents();
}
function showBooking() {
    $("#viewHome").classList.add("hidden");
    $("#viewCheckout").classList.add("hidden");
    $("#viewScanner").classList.add("hidden");
    stopCamera();
    $("#viewBooking").classList.remove("hidden");
    window.scrollTo({ top: 0 });
}

function connectWebSocket(eventId) {
    if (typeof MOCK_MODE !== 'undefined' && MOCK_MODE) {
        console.log("WebSocket connection bypassed in MOCK_MODE.");
        return;
    }
    disconnectWebSocket();
    
    const socket = new SockJS("/ws-seatmap");
    state.stompClient = Stomp.over(socket);
    state.stompClient.debug = null; // Quiet client log spam
    
    state.stompClient.connect({}, () => {
        if (!state.event || state.event.id !== eventId) return;
        state.stompSub = state.stompClient.subscribe(`/topic/events/${eventId}/seats`, (message) => {
            const updates = JSON.parse(message.body);
            if (updates && Array.isArray(updates)) {
                updates.forEach(upd => {
                    const idx = state.seats.findIndex(s => s.id === upd.seatId);
                    if (idx !== -1) {
                        state.seats[idx].status = upd.status;
                        state.seats[idx].heldByUserId = upd.heldByUserId;
                    }
                });
                renderSeatmap();
                
                // Release selections if taken concurrently by another user
                let selectedChanged = false;
                for (const [selId, selSeat] of state.selected.entries()) {
                    const current = state.seats.find(s => s.id === selId);
                    if (current && current.status !== "AVAILABLE" && current.heldByUserId !== state.user?.userId) {
                        state.selected.delete(selId);
                        selectedChanged = true;
                    }
                }
                if (selectedChanged) {
                    toast("Some selected seats were held by another user.", "info");
                    renderSummary();
                }
            }
        });
    }, (err) => {
        // Retry connection after a short duration on connection drops
        setTimeout(() => {
            if (state.event && state.event.id === eventId && !$("#viewBooking").classList.contains("hidden")) {
                connectWebSocket(eventId);
                refreshAfterChange();
            }
        }, 5000);
    });
}

function disconnectWebSocket() {
    if (state.stompSub) {
        try { state.stompSub.unsubscribe(); } catch {}
        state.stompSub = null;
    }
    if (state.stompClient) {
        try { state.stompClient.disconnect(); } catch {}
        state.stompClient = null;
    }
}

/* =============================== EVENTS ================================= */
$("#heroSearchBtn").addEventListener("click", () => { state.searchTerm = $("#heroSearch").value.trim(); state.eventsPage = 0; loadEvents(); });
$("#heroSearch").addEventListener("keydown", e => { if (e.key === "Enter") $("#heroSearchBtn").click(); });

/* ----- discovery filters ----- */
function renderCategoryChips() {
    const wrap = $("#catChips");
    wrap.innerHTML = "";
    const options = [["", "All"], ...Object.entries(CATEGORY_STYLE).map(([k, v]) => [k, `${v.emoji} ${k === "OTHER" ? "Other" : v.label}`])];
    options.forEach(([value, label]) => {
        const chip = el("button", "chip", esc(label));
        chip.type = "button";
        chip.setAttribute("role", "radio");
        chip.setAttribute("aria-checked", String(state.filters.category === value));
        chip.addEventListener("click", () => {
            state.filters.category = value;
            $$("#catChips .chip").forEach(c => c.setAttribute("aria-checked", String(c === chip)));
            applyFilters();
        });
        wrap.appendChild(chip);
    });
}

async function loadCities() {
    try {
        const cities = await api("/events/cities", { auth: false });
        const sel = $("#fCity");
        const current = sel.value;
        sel.innerHTML = `<option value="">All cities</option>` +
            cities.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join("");
        sel.value = cities.includes(current) ? current : "";
    } catch { /* the filter still works without the list */ }
}

function applyFilters() { state.eventsPage = 0; loadEvents(); }

// Debounce typing in the price boxes so each keystroke doesn't fire a request.
let filterTimer = null;
function bindFilter(sel, key, read = e => e.target.value, debounce = false) {
    const input = $(sel);
    input.addEventListener(debounce ? "input" : "change", e => {
        state.filters[key] = read(e);
        clearTimeout(filterTimer);
        if (debounce) filterTimer = setTimeout(applyFilters, 350); else applyFilters();
    });
}
bindFilter("#fCity", "city");
bindFilter("#fFrom", "from");
bindFilter("#fTo", "to");
bindFilter("#fMin", "min", e => e.target.value, true);
bindFilter("#fMax", "max", e => e.target.value, true);
bindFilter("#fSort", "sort");
bindFilter("#fAvail", "availableOnly", e => e.target.checked);
$("#fClear").addEventListener("click", () => {
    state.filters = { category: "", city: "", from: "", to: "", min: "", max: "", availableOnly: false, sort: "DATE:ASC" };
    state.searchTerm = "";
    $("#heroSearch").value = "";
    ["#fCity", "#fFrom", "#fTo", "#fMin", "#fMax"].forEach(s => { $(s).value = ""; });
    $("#fSort").value = "DATE:ASC";
    $("#fAvail").checked = false;
    renderCategoryChips();
    applyFilters();
});
renderCategoryChips();

/** Query string for /events from the current search + filters. Prices are entered in major units. */
function eventQuery() {
    const f = state.filters;
    const [sortBy, direction] = f.sort.split(":");
    const params = new URLSearchParams({ page: state.eventsPage, size: 9, sortBy, direction });
    if (state.searchTerm) params.set("keyword", state.searchTerm);
    if (f.category) params.set("category", f.category);
    if (f.city) params.set("city", f.city);
    // "Upcoming" by default; a chosen From date replaces now. To covers the whole chosen day.
    params.set("fromDate", f.from ? new Date(f.from + "T00:00:00").toISOString() : new Date().toISOString());
    if (f.to) params.set("toDate", new Date(f.to + "T23:59:59.999").toISOString());
    if (f.min !== "" && +f.min >= 0) params.set("minPrice", Math.round(+f.min * 100));
    if (f.max !== "" && +f.max >= 0) params.set("maxPrice", Math.round(+f.max * 100));
    if (f.availableOnly) params.set("availableOnly", "true");
    return params;
}

function hasActiveFilters() {
    const f = state.filters;
    return !!(state.searchTerm || f.category || f.city || f.from || f.to || f.min !== "" || f.max !== "" || f.availableOnly);
}

// Filters can fire several requests in quick succession; only the latest may render,
// otherwise a slow older response would overwrite the newer result.
let eventsRequestSeq = 0;
async function loadEvents() {
    const seq = ++eventsRequestSeq;
    const grid = $("#eventsGrid");
    grid.innerHTML = Array.from({ length: 6 }).map(() => `<div class="skeleton sk-card"></div>`).join("");
    try {
        const page = await api(`/events?${eventQuery()}`, { auth: false });
        if (seq === eventsRequestSeq) renderEvents(page);
    } catch (err) {
        if (seq !== eventsRequestSeq) return;
        grid.innerHTML = ""; toast(err.message, "error");
    }
}

function renderEvents(page) {
    const grid = $("#eventsGrid");
    grid.innerHTML = "";
    $("#eventCount").textContent = page.totalElements ? `${page.totalElements} event${page.totalElements > 1 ? "s" : ""}` : "";
    if (!page.content.length) {
        grid.appendChild(el("div", "empty-state", hasActiveFilters()
            ? `<div class="ico">🔍</div><h3>No events match these filters</h3><p>Try a different category, city or date range, or clear the filters.</p>`
            : `<div class="ico">🎭</div><h3>No events yet</h3><p>${state.user?.role === "ADMIN" ? "Create the first one with “＋ Create event”." : "Check back soon — new shows drop regularly."}</p>`));
        $("#eventsPager").innerHTML = ""; return;
    }
    page.content.forEach(ev => grid.appendChild(eventCard(ev)));

    const pager = $("#eventsPager");
    pager.innerHTML = "";
    if (page.totalPages > 1) {
        const prev = el("button", "btn btn-soft btn-sm", "← Prev"); prev.disabled = page.first;
        prev.onclick = () => { state.eventsPage--; loadEvents(); scrollToEvents(); };
        const next = el("button", "btn btn-soft btn-sm", "Next →"); next.disabled = page.last;
        next.onclick = () => { state.eventsPage++; loadEvents(); scrollToEvents(); };
        pager.append(prev, el("span", "muted", `Page ${page.page + 1} / ${page.totalPages}`), next);
    }
}

function scrollToEvents() {
    $("#eventFilters").scrollIntoView({ behavior: "smooth", block: "start" });
}

function eventCard(ev) {
    const { cat, bg } = posterStyle(ev);
    const ratio = ev.totalCapacity ? ev.availableSeats / ev.totalCapacity : 0;
    const soldOut = ev.availableSeats === 0;
    const low = !soldOut && ratio < 0.2;
    const availCls = soldOut ? "none" : low ? "low" : "ok";
    const availTxt = soldOut ? "Sold out" : low ? `Only ${ev.availableSeats} left` : `${ev.availableSeats} seats left`;
    // "from" price = the event's base seat price (tiers can be higher).
    const priceTxt = ev.basePriceMinor > 0 ? `from ${money(ev.basePriceMinor, ev.currency)}` : "Free";
    const card = el("div", `event-card${soldOut ? " is-sold-out" : ""}`);
    card.tabIndex = 0;
    card.setAttribute("role", "button");
    card.setAttribute("aria-label", `${ev.title} at ${ev.venue}, ${availTxt}, ${priceTxt}`);
    card.innerHTML = `
        <div class="ec-poster" style='background:${bg}'>
            <span class="ec-cat">${esc(cat)}</span>
            ${soldOut ? `<span class="ec-soldout-badge">Sold out</span>` : ""}
            ${state.user?.role === "ADMIN" ? `<button class="ec-admin-del" title="Delete event" aria-label="Delete ${esc(ev.title)}">🗑</button>` : ""}
        </div>
        <div class="ec-body">
            <div class="ec-title">${esc(ev.title)}</div>
            <div class="ec-meta">
                <span>📍 ${esc(ev.venue)}${ev.city ? `, ${esc(ev.city)}` : ""}</span>
                <span>🗓️ ${esc(fmtDate(ev.eventDateTime))}</span>
            </div>
            <div class="ec-foot">
                <span class="ec-avail ${availCls}">${availTxt}</span>
                <span class="ec-price">${priceTxt}</span>
            </div>
        </div>`;
    const open = () => openBooking(ev);
    card.addEventListener("click", open);
    card.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); } });
    const del = card.querySelector(".ec-admin-del");
    if (del) del.addEventListener("click", e => { e.stopPropagation(); deleteEvent(ev); });
    return card;
}

async function deleteEvent(ev) {
    if (!confirm(`Delete “${ev.title}”? This cannot be undone.`)) return;
    try { await api(`/events/${ev.id}`, { method: "DELETE" }); toast("Event deleted.", "success"); loadEvents(); }
    catch (err) { toast(err.message, "error"); }
}

/* =============================== BOOKING =============================== */
async function openBooking(ev, { push = true } = {}) {
    if (push && new URLSearchParams(location.search).get("event") !== String(ev.id)) {
        history.pushState({ event: ev.id }, "", `?event=${ev.id}`);
    }
    document.title = `${ev.title} · SeatVault`;
    state.selected.clear();
    state.priceColors = new Map();
    const { cat, bgLarge } = posterStyle(ev);
    $("#bookingBanner").style.background = bgLarge;
    $("#bbCat").textContent = cat;
    $("#bbTitle").textContent = ev.title;
    $("#bbVenue").textContent = "📍 " + ev.venue + (ev.city ? `, ${ev.city}` : "");
    $("#eventAboutText").textContent = ev.description || "";
    $("#eventAbout").classList.toggle("hidden", !ev.description);
    $("#bbDate").textContent = "🗓️ " + fmtDate(ev.eventDateTime);
    
    // Set Host info dynamically (Luma style)
    const category = cat || "Event";
    $("#hostAvatar").textContent = category.substring(0, 2).toUpperCase();
    $("#eventHost").textContent = `${category} Premium Host`;

    // Wire Luma actions
    const addToCalBtn = $("#addToCalBtn");
    const shareEventBtn = $("#shareEventBtn");
    
    const newAddToCalBtn = addToCalBtn.cloneNode(true);
    const newShareEventBtn = shareEventBtn.cloneNode(true);
    addToCalBtn.parentNode.replaceChild(newAddToCalBtn, addToCalBtn);
    shareEventBtn.parentNode.replaceChild(newShareEventBtn, shareEventBtn);

    newAddToCalBtn.addEventListener("click", () => downloadIcs(ev));
    newShareEventBtn.addEventListener("click", () => shareEvent(ev));

    showBooking();
    $("#seatmap").innerHTML = "";
    $("#seatmapLoading").classList.remove("hidden");
    try {
        // Re-fetch the event so we have authoritative pricing + availability fields.
        const [full, seats] = await Promise.all([
            api(`/events/${ev.id}`, { auth: false }),
            api(`/events/${ev.id}/seats`, { auth: false }),
        ]);
        state.event = full;
        state.seats = seats;
        $("#bbAvail").textContent = `${full.availableSeats}/${full.totalCapacity} available`;
        renderSummary();
        renderSeatmap();
        connectWebSocket(ev.id);
    } catch (err) { toast(err.message, "error"); }
}

function downloadIcs(event) {
    const date = new Date(event.eventDateTime);
    const end = new Date(date.getTime() + 2 * 60 * 60 * 1000); // assume 2 hours
    const fmt = (d) => d.toISOString().replace(/[-:]/g, "").split(".")[0] + "Z";
    
    const icsContent = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//SeatVault//Event//EN",
        "BEGIN:VEVENT",
        `UID:sv-event-${event.id}@seatvault.com`,
        `DTSTAMP:${fmt(new Date())}`,
        `DTSTART:${fmt(date)}`,
        `DTEND:${fmt(end)}`,
        `SUMMARY:${event.title}`,
        `DESCRIPTION:${event.description || ""}`,
        `LOCATION:${event.venue}`,
        "END:VEVENT",
        "END:VCALENDAR"
    ].join("\r\n");
    
    const blob = new Blob([icsContent], { type: "text/calendar;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `${event.title.replace(/\s+/g, "_")}.ics`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    toast("iCalendar event downloaded!", "success");
}

function shareEvent(event) {
    const url = `${window.location.origin}${window.location.pathname}?event=${event.id}`;
    navigator.clipboard.writeText(url).then(() => {
        toast("Event link copied to clipboard!", "success");
    }).catch(() => {
        toast("Failed to copy link.", "error");
    });
}

function renderSeatmap() {
    $("#seatmapLoading").classList.add("hidden");
    const map = $("#seatmap");
    map.innerHTML = "";

    // group by row
    const byRow = {};
    state.seats.forEach(s => { const { row } = parseLabel(s.seatLabel); (byRow[row] ??= []).push(s); });
    const rowKeys = Object.keys(byRow).sort();

    // Price legend, built from the distinct prices the API returned (highest first).
    const distinct = [...new Set(state.seats.map(s => s.priceMinor))].sort((a, b) => b - a);
    $("#seatTiers").innerHTML = distinct.map(p => {
        const c = colorForPrice(p);
        const name = state.seats.find(s => s.priceMinor === p)?.tierName;
        return `<span class="tier-chip"><i style="background:${c.color}"></i>${name ? esc(name) + " · " : ""}${money(p)}</span>`;
    }).join("");

    rowKeys.forEach(rowKey => {
        const seats = byRow[rowKey].sort((a, b) => parseLabel(a.seatLabel).num - parseLabel(b.seatLabel).num);
        const rowEl = el("div", "seat-row");
        rowEl.appendChild(el("span", "row-label", esc(rowKey)));
        const mid = Math.ceil(seats.length / 2);
        seats.forEach((s, i) => {
            if (seats.length > 6 && i === mid) rowEl.appendChild(el("span", "aisle"));
            const c = colorForPrice(s.priceMinor);
            const status = s.status.toLowerCase(); // available / held / booked
            const sel = state.selected.has(s.id);
            const b = el("button", `seat ${c.cls} ${status}${sel ? " selected" : ""}`, esc(s.seatLabel));
            b.dataset.id = s.id;
            b.title = `${s.seatLabel}${s.tierName ? " · " + s.tierName : ""} · ${money(s.priceMinor)} · ${s.status}`;
            if (s.status === "AVAILABLE") b.addEventListener("click", () => toggleSeat(s));
            rowEl.appendChild(b);
        });
        map.appendChild(rowEl);
    });
}

function toggleSeat(seat) {
    if (state.selected.has(seat.id)) state.selected.delete(seat.id);
    else {
        if (state.selected.size >= 10) { toast("You can book up to 10 seats at once.", "info"); return; }
        state.selected.set(seat.id, seat);
    }
    const node = $(`#seatmap .seat[data-id="${seat.id}"]`);
    if (node) node.classList.toggle("selected", state.selected.has(seat.id));
    renderSummary();
}

function renderSummary() {
    const has = state.selected.size > 0;
    $("#summaryEmpty").classList.toggle("hidden", has);
    $("#summaryList").classList.toggle("hidden", !has);
    $("#summaryTotals").classList.toggle("hidden", !has);
    const btn = $("#checkoutBtn");
    const list = $("#summaryList");
    list.innerHTML = "";
    const feePerSeat = state.event?.convenienceFeeMinor || 0;
    let subtotal = 0;
    [...state.selected.values()].forEach(s => {
        subtotal += s.priceMinor;
        const c = colorForPrice(s.priceMinor);
        const row = el("div", "sum-seat");
        row.innerHTML = `<span class="lbl"><i class="tier-dot" style="background:${c.color}"></i>${esc(s.seatLabel)}${s.tierName ? ` <span class="muted">· ${esc(s.tierName)}</span>` : ""}</span>
                         <span>${money(s.priceMinor)} <button class="x" title="Remove">✕</button></span>`;
        row.querySelector(".x").addEventListener("click", () => {
            state.selected.delete(s.id);
            const node = $(`#seatmap .seat[data-id="${s.id}"]`); if (node) node.classList.remove("selected");
            renderSummary();
        });
        list.appendChild(row);
    });
    const fee = state.selected.size * feePerSeat;
    $("#sumSubtotal").textContent = money(subtotal);
    $("#sumFee").textContent = money(fee);
    $("#sumTotal").textContent = money(subtotal + fee);
    btn.disabled = !has;
    btn.textContent = has ? `Checkout · ${state.selected.size} seat${state.selected.size > 1 ? "s" : ""}` : "Select seats";
}

/* ----- checkout = create the hold, then open the payment modal ----- */
$("#checkoutBtn").addEventListener("click", checkout);

async function checkout() {
    if (!state.selected.size) return;
    if (!state.token) { toast("Please sign in to book these seats.", "info"); openModal("authModal"); return; }
    const seatIds = [...state.selected.keys()];
    $("#checkoutBtn").disabled = true;
    try {
        const booking = await api("/bookings/hold", { method: "POST", body: { eventId: state.event.id, seatIds } });
        await openCheckout(booking);
    } catch (err) {
        toast(err.message, "error");
        // seats may have been taken concurrently — refresh the map
        const seats = await api(`/events/${state.event.id}/seats`, { auth: false });
        state.seats = seats; renderSeatmap();
        // drop selections no longer available
        const avail = new Set(seats.filter(s => s.status === "AVAILABLE").map(s => s.id));
        [...state.selected.keys()].forEach(id => { if (!avail.has(id)) state.selected.delete(id); });
        renderSummary();
    } finally { $("#checkoutBtn").disabled = false; }
}

/* =============================== CHECKOUT ============================== */
// Hold seats → open (or reuse) the booking's provider order → the customer pays in
// the provider's widget → the server verifies the signed result and confirms.
// The browser is never trusted with the amount or the outcome: both are checked
// server-side, and a webhook confirms the same payment if this tab is closed.

let razorpayScript = null;
function loadRazorpay() {
    if (window.Razorpay) return Promise.resolve();
    razorpayScript ??= new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "https://checkout.razorpay.com/v1/checkout.js";
        s.onload = resolve;
        s.onerror = () => { razorpayScript = null; reject(new Error("Couldn't load Razorpay checkout. Check your connection and try again.")); };
        document.head.appendChild(s);
    });
    return razorpayScript;
}

function showCheckout() {
    $("#viewHome").classList.add("hidden");
    $("#viewBooking").classList.add("hidden");
    $("#viewScanner").classList.add("hidden");
    stopCamera();
    $("#viewCheckout").classList.remove("hidden");
    window.scrollTo({ top: 0 });
}

async function openCheckout(booking) {
    state.booking = booking;
    state.checkout = null;
    state.paymentSettled = false;
    try {
        state.checkoutEvent = state.event?.id === booking.eventId
            ? state.event : await api(`/events/${booking.eventId}`, { auth: false });
    } catch { state.checkoutEvent = { id: booking.eventId, title: booking.eventTitle, venue: "", eventDateTime: null }; }
    renderCheckout(booking, state.checkoutEvent);
    showCheckout();
    disconnectWebSocket();
    startCountdown(booking.expiresAt);
    setPayStatus(null);
    setPayBusy(true, "Preparing secure checkout…");
    try {
        const co = await api(`/bookings/${booking.id}/checkout`, { method: "POST" });
        state.checkout = co;
        const razorpay = co.provider === "razorpay";
        $("#coProviderRazorpay").classList.toggle("hidden", !razorpay);
        $("#coProviderSimulated").classList.toggle("hidden", razorpay);
        $("#coRef").textContent = co.bookingReference;
        $("#coPassRef").textContent = co.bookingReference;
        if (razorpay) loadRazorpay().catch(() => {}); // warm up; errors surface on Pay
        setPayBusy(false);
    } catch (err) {
        setPayBusy(false);
        $("#coPayBtn").disabled = true;
        setPayStatus("error", err.message);
    }
}

function renderCheckout(b, ev) {
    const { cat, bgLarge } = posterStyle(ev);
    const when = ev.eventDateTime ? new Date(ev.eventDateTime) : null;
    const place = [ev.venue, ev.city].filter(Boolean).join(", ");
    $("#coEventTitle").textContent = b.eventTitle;
    $("#coEventMeta").textContent = [place, when ? fmtDate(ev.eventDateTime) : ""].filter(Boolean).join(" · ");
    // Per-seat lines and totals come from the booking the API froze at hold time.
    $("#coLines").innerHTML = (b.seats || []).map(s =>
        `<div class="co-line"><span>Seat ${esc(s.seatLabel)}${s.tierName ? `<span class="tier">${esc(s.tierName)}</span>` : ""}</span><span>${money(s.priceMinor, b.currency)}</span></div>`).join("");
    $("#coQty").textContent = b.seatCount;
    $("#coSubtotal").textContent = money(b.amountMinor - b.feeMinor, b.currency);
    $("#coFee").textContent = b.feeMinor ? money(b.feeMinor, b.currency) : "Free";
    $("#coTotal").textContent = money(b.amountMinor, b.currency);
    $("#coRef").textContent = b.reference || "—";

    $("#coPassHead").style.background = bgLarge;
    $("#coPassCat").textContent = cat;
    $("#coPassEmoji").textContent = "";
    $("#coPassTitle").textContent = b.eventTitle;
    $("#coPassDate").textContent = when ? when.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : "—";
    $("#coPassTime").textContent = when ? when.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) : "—";
    $("#coPassVenue").textContent = place || "—";
    $("#coPassSeats").textContent = b.seatLabels.join(", ");
    $("#coPassRef").textContent = b.reference || "—";
    const st = $("#coPassState"); st.textContent = "Awaiting payment"; st.classList.remove("ok");
    $("#coTerms").checked = false;
}

function setPayStatus(kind, message) {
    const box = $("#coStatus");
    box.className = "co-status" + (kind ? " " + kind : " hidden");
    box.textContent = message || "";
}

function setPayBusy(busy, label) {
    const btn = $("#coPayBtn");
    btn.disabled = busy || !state.checkout;
    btn.textContent = busy ? label : `Pay ${state.checkout ? money(state.checkout.amountMinor, state.checkout.currency) : ""}`;
}

$$("#coSimMethods .chip").forEach(chip => chip.addEventListener("click", () => {
    state.simMethod = chip.dataset.method;
    $$("#coSimMethods .chip").forEach(c => c.setAttribute("aria-checked", String(c === chip)));
}));
$$('input[name="simOutcome"]').forEach(r => r.addEventListener("change", () => {
    $$('input[name="simOutcome"]').forEach(o => o.closest(".pay-method").classList.toggle("selected", o.checked));
}));

$("#coPayBtn").addEventListener("click", () => {
    const co = state.checkout; if (!co) return;
    if (!$("#coTerms").checked) { setPayStatus("error", "Please accept the Terms of Service to continue."); return; }
    setPayStatus(null);
    return co.provider === "razorpay" ? payWithRazorpay(co) : payWithSimulator(co);
});

async function payWithSimulator(co) {
    setPayBusy(true, "Processing payment…");
    try {
        const outcome = $('input[name="simOutcome"]:checked').value;
        const r = await api("/payments/simulated/checkout", { method: "POST", body: { orderId: co.orderId, outcome, method: state.simMethod } });
        if (!r.signature) { await reportFailure(co, r.errorDescription); return; }
        setPayBusy(true, "Confirming payment…");
        await verifyPayment(co, r.orderId, r.paymentId, r.signature);
    } catch (err) {
        setPayStatus("error", err.message);
    } finally {
        if (!state.paymentSettled) setPayBusy(false);
    }
}

async function payWithRazorpay(co) {
    setPayBusy(true, "Opening Razorpay…");
    try { await loadRazorpay(); } catch (err) { setPayBusy(false); setPayStatus("error", err.message); return; }
    const primary = getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
    const rzp = new window.Razorpay({
        key: co.keyId,
        order_id: co.orderId,
        amount: co.amountMinor,
        currency: co.currency,
        name: co.merchantName,
        description: co.description,
        prefill: { name: co.customerName, email: co.customerEmail },
        notes: { bookingReference: co.bookingReference },
        theme: { color: primary || "#1d4ed8" },
        handler: async resp => {
            setPayBusy(true, "Confirming payment…");
            try { await verifyPayment(co, resp.razorpay_order_id, resp.razorpay_payment_id, resp.razorpay_signature); }
            finally { if (!state.paymentSettled) setPayBusy(false); }
        },
        modal: {
            ondismiss: () => {
                if (state.paymentSettled) return;
                setPayBusy(false);
                if ($("#coStatus").classList.contains("hidden")) {
                    setPayStatus("info", "Payment cancelled. Your seats are still held: pay before the timer runs out, or release them.");
                }
            },
        },
    });
    rzp.on("payment.failed", r => reportFailure(co, r.error?.description));
    setPayBusy(false); // the widget shows its own progress from here
    rzp.open();
}

async function verifyPayment(co, orderId, paymentId, signature) {
    try {
        const confirmed = await api(`/bookings/${co.bookingId}/payments/verify`, { method: "POST", body: { orderId, paymentId, signature } });
        onPaymentConfirmed(confirmed);
    } catch (err) {
        if (err.status === 409) {
            // The hold ended before the money arrived: the server refunded it.
            clearInterval(state.countdown);
            state.checkout = null;
            setPayBusy(false);
            setPayStatus("error", err.message);
            toast(err.message, "error", 8000);
        } else {
            setPayStatus("error", `${err.message} You can try again.`);
        }
    }
}

async function reportFailure(co, reason) {
    const why = reason || "Payment failed";
    setPayStatus("error", `${why}. Your seats are still held, so you can try again.`);
    try { await api(`/bookings/${co.bookingId}/payments/failure`, { method: "POST", body: { orderId: co.orderId, reason: why } }); } catch {}
}

function onPaymentConfirmed(booking) {
    state.paymentSettled = true;
    clearInterval(state.countdown);
    state.booking = null;
    state.checkout = null;
    const st = $("#coPassState"); st.textContent = "Confirmed"; st.classList.add("ok");
    $("#coPayBtn").disabled = true;
    $("#coPayBtn").textContent = "Paid ✓";
    setPayStatus(null);
    showTicket(booking);
}

/** Leaves checkout for the event's seat map (which re-fetches live availability). */
function leaveCheckout() {
    clearInterval(state.countdown);
    const ev = state.checkoutEvent;
    state.checkout = null;
    if (ev?.id) openBooking(ev); else showHome();
}

$("#confirmDone").addEventListener("click", () => {
    if (!$("#viewCheckout").classList.contains("hidden")) leaveCheckout();
});

$("#checkoutBack").addEventListener("click", () => {
    if (state.booking && !state.paymentSettled) {
        toast("Your seats stay held until the timer ends. Resume from “My tickets”.", "info", 5000);
    }
    leaveCheckout();
});

$("#coReleaseBtn").addEventListener("click", async () => {
    const b = state.booking; if (!b) { leaveCheckout(); return; }
    if (!confirm("Cancel this booking and release your seats?")) return;
    try {
        await api(`/bookings/${b.id}/cancel`, { method: "POST" });
        toast("Booking cancelled and seats released.", "info");
        state.booking = null;
        leaveCheckout();
    } catch (err) { setPayStatus("error", err.message); }
});

function startCountdown(expiresAt) {
    clearInterval(state.countdown);
    const cd = $("#coCountdown");
    cd.classList.remove("danger");
    if (!expiresAt) { cd.textContent = "—"; return; }
    const tick = () => {
        const left = Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000);
        if (left <= 0) {
            cd.textContent = "expired"; cd.classList.add("danger");
            clearInterval(state.countdown);
            state.checkout = null;
            setPayBusy(false);
            setPayStatus("error", "Your seat hold expired and the seats were released. Go back to pick seats again.");
            return;
        }
        cd.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
        cd.classList.toggle("danger", left <= 30);
    };
    tick(); state.countdown = setInterval(tick, 1000);
}

/** Fetches the PDF with the user's token (a plain link can't send it) and saves it. */
async function downloadTicket(booking) {
    try {
        const res = await fetch(`${API}/bookings/${booking.id}/ticket`, { headers: { Authorization: `Bearer ${state.token}` } });
        if (!res.ok) throw new Error(res.status === 409 ? "The e-ticket is available once the booking is confirmed." : `Download failed (${res.status})`);
        const url = URL.createObjectURL(await res.blob());
        const a = document.createElement("a");
        a.href = url; a.download = `SeatVault-${booking.reference || booking.id}.pdf`;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) { toast(err.message, "error"); }
}

async function refreshAfterChange() {
    state.selected.clear();
    if (state.event) {
        try {
            const [seats, ev] = await Promise.all([
                api(`/events/${state.event.id}/seats`, { auth: false }),
                api(`/events/${state.event.id}`, { auth: false }),
            ]);
            state.seats = seats; state.event = ev;
            $("#bbAvail").textContent = `${ev.availableSeats}/${ev.totalCapacity} available`;
            if (!$("#viewBooking").classList.contains("hidden")) renderSeatmap();
        } catch {}
    }
    renderSummary();
}

/* =============================== TICKET ================================= */
function showTicket(booking) {
    $("#tkEvent").textContent = booking.eventTitle;
    $("#tkSeats").textContent = booking.seatLabels.join(", ");
    $("#tkRef").textContent = booking.reference || "#" + booking.id;
    // Amount + payment status come straight from the confirmed booking response.
    $("#tkAmount").textContent = money(booking.amountMinor, booking.currency);
    const ref = booking.payment?.providerReference;
    const statusEl = $("#tkPayStatus");
    if (statusEl) statusEl.textContent = (booking.payment?.status || "APPROVED") + (ref ? " · " + ref : "");
    $("#tkDownload").onclick = () => downloadTicket(booking);
    openModal("confirmModal");
    toast("Payment successful — booking confirmed!", "success");
}

/* =============================== TICKETS DRAWER ======================== */
async function openTickets() {
    $("#userDropdown").classList.add("hidden");
    openModal("ticketsDrawer");
    const list = $("#ticketsList");
    list.innerHTML = `<div class="empty-state"><div class="ico">⏳</div>Loading…</div>`;
    try {
        const page = await api(`/bookings?page=0&size=30&sort=createdAt,desc`);
        if (!page.content.length) { list.innerHTML = `<div class="empty-state"><div class="ico">🎫</div><h3>No tickets yet</h3><p>Book an event to see it here.</p></div>`; return; }
        list.innerHTML = "";
        page.content.forEach(b => list.appendChild(ticketCard(b)));
    } catch (err) { list.innerHTML = ""; toast(err.message, "error"); }
}

function ticketCard(b) {
    const card = el("div", "t-card");
    const amount = b.amountMinor != null ? money(b.amountMinor, b.currency) : "";
    const pay = b.payment; // { status, providerReference, failureReason } when available
    const payLine = pay
        ? `<div class="t-card-pay">Payment: <span class="status-pill status-${pay.status}">${pay.status}</span>${pay.providerReference ? ` · <span class="muted">${esc(pay.providerReference)}</span>` : ""}</div>`
        : "";
    card.innerHTML = `
        <div class="t-card-top">
            <div><div class="t-title">${esc(b.eventTitle)}</div>
            <div class="t-card-seats">${b.seatCount} seat(s): ${b.seatLabels.map(esc).join(", ")}</div>
            ${payLine}</div>
            <span class="status-pill status-${b.status}">${b.status}</span>
        </div>
        <div class="t-card-foot">
            <span class="muted" style="font-size:.8rem">${esc(b.reference || "#" + b.id)}${amount ? " · " + amount : ""}</span>
        </div>`;
    if (b.status === "PENDING") {
        const resume = el("button", "btn btn-primary btn-sm", "Resume payment →");
        resume.addEventListener("click", () => {
            closeModal("ticketsDrawer");
            openCheckout(b); // same open order as before, so nothing is charged twice
        });
        card.querySelector(".t-card-foot").appendChild(resume);
    }
    if (b.status === "CONFIRMED") {
        const show = el("button", "btn btn-primary btn-sm", "🎫 Show tickets");
        show.addEventListener("click", () => openPasses(b));
        card.querySelector(".t-card-foot").appendChild(show);
        const dl = el("button", "btn btn-soft btn-sm", "⬇ PDF");
        dl.addEventListener("click", () => downloadTicket(b));
        card.querySelector(".t-card-foot").appendChild(dl);
    }
    return card;
}

/* =============================== TICKET PASSES ========================= */
// The owner's on-screen tickets: one wallet-style pass per seat, each with the
// same signed QR as the PDF.
const passState = { passes: [], index: 0, booking: null, event: null };

async function openPasses(booking) {
    try {
        const [passes, event] = await Promise.all([
            api(`/bookings/${booking.id}/passes`),
            api(`/events/${booking.eventId}`, { auth: false }).catch(() => null),
        ]);
        Object.assign(passState, { passes, index: 0, booking, event });
        renderPass();
        $("#passDownload").onclick = () => downloadTicket(booking);
        closeModal("ticketsDrawer");
        openModal("passModal");
    } catch (err) { toast(err.message, "error"); }
}

function renderPass() {
    const { passes, index, booking, event } = passState;
    const p = passes[index];
    const when = event?.eventDateTime ? new Date(event.eventDateTime) : null;
    const { bgLarge } = posterStyle(event || { id: booking.eventId, title: booking.eventTitle });
    const place = event ? [event.venue, event.city].filter(Boolean).join(", ") : "";
    $("#passCard").innerHTML = `
        <div class="wallet-top"><span class="logo">🎟️</span> SeatVault</div>
        <div class="wallet-body">
            <div class="wallet-venue">${esc(place)}</div>
            <div class="wallet-title">${esc(booking.eventTitle)}</div>
            <div class="wallet-row">
                <div><span>Date</span><strong>${when ? esc(when.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" })) : "—"}</strong></div>
                <div><span>Time</span><strong>${when ? esc(when.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })) : "—"}</strong></div>
            </div>
            <div class="wallet-row">
                <div><span>Seat</span><strong>${esc(p.seatLabel)}</strong></div>
                <div><span>Tier</span><strong>${esc(p.tierName || "Standard")}</strong></div>
                <div><span>Ref</span><strong>${esc(booking.reference || "#" + booking.id)}</strong></div>
            </div>
            <div class="wallet-qr"><img src="${p.qrPng}" alt="QR code for seat ${esc(p.seatLabel)}"/></div>
        </div>
        <div class="wallet-art" style='background:${bgLarge}'></div>
        ${p.checkedInAt ? `<div class="wallet-used"><span>✓ Checked in ${esc(new Date(p.checkedInAt).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }))}</span></div>` : ""}`;
    $("#passCounter").textContent = `Ticket ${index + 1} of ${passes.length}`;
    $("#passPrev").disabled = index === 0;
    $("#passNext").disabled = index === passes.length - 1;
}
$("#passPrev").addEventListener("click", () => { passState.index--; renderPass(); });
$("#passNext").addEventListener("click", () => { passState.index++; renderPass(); });

/* =============================== GATE SCANNER ========================== */
// Staff (ADMIN / SCANNER) admit people at an event. Every code is checked with the
// server, which verifies the signature, the event, the booking, and that the
// seat hasn't already been admitted.
const scan = { stream: null, timer: null, detector: null, busy: false, last: { token: null, at: 0 }, eventId: null };
const RESCAN_COOLDOWN_MS = 4000; // the same QR held in front of the camera isn't re-sent

async function showScanner() {
    stopCamera();
    $("#viewHome").classList.add("hidden");
    $("#viewBooking").classList.add("hidden");
    $("#viewCheckout").classList.add("hidden");
    $("#viewScanner").classList.remove("hidden");
    window.scrollTo({ top: 0 });
    disconnectWebSocket();
    try {
        // Events from 12h ago onwards: today's doors may already be open.
        const since = new Date(Date.now() - 12 * 3600 * 1000).toISOString();
        const page = await api(`/events?size=100&sortBy=DATE&direction=ASC&fromDate=${encodeURIComponent(since)}`, { auth: false });
        const sel = $("#scanEvent");
        sel.innerHTML = page.content.map(e =>
            `<option value="${e.id}">${esc(e.title)} · ${esc(fmtDate(e.eventDateTime))}</option>`).join("")
            || `<option value="">No upcoming events</option>`;
        if (scan.eventId && page.content.some(e => e.id === scan.eventId)) sel.value = scan.eventId;
        scan.eventId = sel.value ? +sel.value : null;
        refreshScanStats();
    } catch (err) { toast(err.message, "error"); }
}
$("#navScan").addEventListener("click", showScanner);
$("#scanEvent").addEventListener("change", e => {
    scan.eventId = e.target.value ? +e.target.value : null;
    $("#scanLog").innerHTML = `<p class="co-hint">No scans yet.</p>`;
    setScanResult("idle", "Ready to scan", "Results appear here.");
    refreshScanStats();
});

async function refreshScanStats() {
    if (!scan.eventId) { $("#scanStats").textContent = "—"; return; }
    try {
        const s = await api(`/tickets/summary?eventId=${scan.eventId}`);
        $("#scanStats").textContent = `${s.checkedIn} / ${s.ticketsSold} admitted`;
    } catch { $("#scanStats").textContent = "—"; }
}

$("#scanToggle").addEventListener("click", () => scan.stream ? stopCamera() : startCamera());

async function startCamera() {
    if (!navigator.mediaDevices?.getUserMedia) {
        toast("This browser can't use the camera here. Use HTTPS (or localhost), or paste ticket codes below.", "error", 6000);
        return;
    }
    try {
        scan.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
    } catch (err) {
        toast(err.name === "NotAllowedError" ? "Camera permission was denied." : "Couldn't start the camera: " + err.message, "error", 6000);
        return;
    }
    const video = $("#scanVideo");
    video.srcObject = scan.stream;
    await video.play().catch(() => {});
    $("#scanIdle").classList.add("hidden");
    $("#scanToggle").textContent = "Stop camera";
    // Native decoder where available (Android, macOS); otherwise the bundled jsQR.
    if ("BarcodeDetector" in window) {
        try {
            if ((await BarcodeDetector.getSupportedFormats()).includes("qr_code")) scan.detector = new BarcodeDetector({ formats: ["qr_code"] });
        } catch {}
    }
    if (!scan.detector) await loadJsQr();
    scan.timer = setInterval(scanFrame, 200);
}

function stopCamera() {
    clearInterval(scan.timer); scan.timer = null;
    scan.stream?.getTracks().forEach(t => t.stop());
    scan.stream = null;
    const video = $("#scanVideo"); if (video) video.srcObject = null;
    $("#scanIdle")?.classList.remove("hidden");
    const btn = $("#scanToggle"); if (btn) btn.textContent = "Start camera";
}

let jsQrScript = null;
function loadJsQr() {
    if (window.jsQR) return Promise.resolve();
    jsQrScript ??= new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "vendor/jsqr-1.4.0.js";
        s.onload = resolve; s.onerror = () => { jsQrScript = null; reject(new Error("QR decoder failed to load")); };
        document.head.appendChild(s);
    });
    return jsQrScript;
}

async function scanFrame() {
    const video = $("#scanVideo");
    if (scan.busy || !scan.stream || video.readyState < 2) return;
    let token = null;
    try {
        if (scan.detector) {
            const codes = await scan.detector.detect(video);
            token = codes[0]?.rawValue || null;
        } else if (window.jsQR) {
            const canvas = $("#scanCanvas");
            const w = video.videoWidth, h = video.videoHeight;
            if (!w || !h) return;
            canvas.width = w; canvas.height = h;
            const ctx = canvas.getContext("2d", { willReadFrequently: true });
            ctx.drawImage(video, 0, 0, w, h);
            token = jsQR(ctx.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: "dontInvert" })?.data || null;
        }
    } catch { return; }
    if (!token) return;
    const now = Date.now();
    if (token === scan.last.token && now - scan.last.at < RESCAN_COOLDOWN_MS) return;
    scan.last = { token, at: now };
    await checkTicket(token);
}

$("#scanManual").addEventListener("submit", async e => {
    e.preventDefault();
    const token = $("#scanToken").value.trim();
    if (!token) return;
    await checkTicket(token);
    $("#scanToken").value = "";
});

async function checkTicket(token) {
    if (!scan.eventId) { toast("Choose the event for this gate first.", "error"); return; }
    scan.busy = true;
    try {
        const r = await api("/tickets/verify", { method: "POST", body: { token, eventId: scan.eventId } });
        const who = `Seat <strong>${esc(r.seatLabel)}</strong>${r.tierName ? ` · ${esc(r.tierName)}` : ""} · ${esc(r.holderName || "")}<br/>${esc(r.bookingReference || "")}`;
        if (r.alreadyCheckedIn) {
            const at = new Date(r.checkedInAt).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
            setScanResult("used", "⚠ ALREADY USED", `${who}<br/>Admitted at ${esc(at)}${r.checkedInBy ? ` by ${esc(r.checkedInBy)}` : ""}. Do not admit again.`);
            logScan("used", "USED", r.seatLabel, r.holderName);
            feedback(false);
        } else {
            setScanResult("ok", "✓ ADMIT", who);
            logScan("ok", "ADMIT", r.seatLabel, r.holderName);
            feedback(true);
            refreshScanStats();
        }
    } catch (err) {
        const title = err.status === 400 ? "✕ INVALID TICKET" : "✕ DO NOT ADMIT";
        const why = err.status === 400 ? "This QR code is not a genuine SeatVault ticket." : err.message;
        setScanResult("bad", title, esc(why));
        logScan("bad", "DENY", "—", why);
        feedback(false);
    } finally {
        scan.busy = false;
    }
}

function setScanResult(kind, verdict, detailHtml) {
    $("#scanResult").className = "scan-result " + kind;
    $("#scanVerdict").textContent = verdict;
    $("#scanDetail").innerHTML = detailHtml;
}

function logScan(kind, tag, seat, who) {
    const log = $("#scanLog");
    if (log.querySelector(".co-hint")) log.innerHTML = "";
    const row = el("div", `scan-log-row ${kind}`,
        `<span><span class="tag">${tag}</span> · ${esc(seat)} · ${esc(who || "")}</span><span class="muted">${esc(new Date().toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" }))}</span>`);
    log.prepend(row);
    while (log.children.length > 30) log.lastChild.remove();
}

/** A short beep (and buzz on phones): high for admit, low for refuse. */
function feedback(ok) {
    try { navigator.vibrate?.(ok ? 80 : [60, 60, 60]); } catch {}
    try {
        const ctx = feedback.ctx ??= new (window.AudioContext || window.webkitAudioContext)();
        const osc = ctx.createOscillator(), gain = ctx.createGain();
        osc.frequency.value = ok ? 880 : 220;
        gain.gain.setValueAtTime(0.15, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + (ok ? 0.15 : 0.35));
        osc.connect(gain).connect(ctx.destination);
        osc.start(); osc.stop(ctx.currentTime + (ok ? 0.15 : 0.35));
    } catch {}
}

/* =============================== ADMIN CREATE ========================== */
$("#createMode").addEventListener("change", e => {
    const grid = e.target.value === "grid";
    $("#rowsF").classList.toggle("hidden", !grid);
    $("#perRowF").classList.toggle("hidden", !grid);
    $("#capF").classList.toggle("hidden", grid);
    // Tiers only make sense for grid seating.
    $("#premRowsF").classList.toggle("hidden", !grid);
    $("#premPriceF").classList.toggle("hidden", !grid);
});
$("#createForm").addEventListener("submit", async e => {
    e.preventDefault(); const f = e.target;
    if (!f.eventDateTime.value) { toast("Pick a date & time.", "error"); return; }
    const body = {
        title: f.title.value.trim(), venue: f.venue.value.trim(),
        description: f.description.value.trim() || null,
        category: f.category.value,
        city: f.city.value.trim() || null,
        imageUrl: f.imageUrl.value.trim() || null,
        eventDateTime: new Date(f.eventDateTime.value).toISOString(),
        currency: (f.currency?.value || "INR").trim().toUpperCase(),
        // Rupees in the form → minor units (paise) for the API.
        basePriceMinor: Math.round((parseFloat(f.basePrice?.value) || 0) * 100),
        convenienceFeeMinor: Math.round((parseFloat(f.convenienceFee?.value) || 0) * 100),
    };
    if (f.mode.value === "grid") {
        body.rows = +f.rows.value; body.seatsPerRow = +f.seatsPerRow.value;
        // Optional simple "Premium front rows" tier.
        const prRows = parseInt(f.premiumRows?.value) || 0;
        const prPrice = parseFloat(f.premiumPrice?.value);
        if (prRows > 0 && prPrice > 0) {
            if (prRows > body.rows) { toast("Premium rows can't exceed total rows.", "error"); return; }
            body.tiers = [{ name: "Premium", priceMinor: Math.round(prPrice * 100), rows: prRows }];
        }
    } else { body.totalCapacity = +f.totalCapacity.value; }
    try {
        const ev = await api("/events", { method: "POST", body });
        toast(`“${ev.title}” created with ${ev.totalCapacity} seats.`, "success");
        closeModal("createModal"); f.reset(); $("#createMode").dispatchEvent(new Event("change"));
        loadCities();
        showHome();
    } catch (err) { toast(err.message, "error"); }
});

/* =============================== BOOTSTRAP ============================= */
const loginEmail = $("#loginForm [name='email']");
const loginPassword = $("#loginForm [name='password']");
if (loginEmail) loginEmail.addEventListener("input", () => validateEmail(loginEmail));
if (loginPassword) loginPassword.addEventListener("input", () => validatePassword(loginPassword));

const regEmail = $("#registerForm [name='email']");
const regPassword = $("#registerForm [name='password']");
if (regEmail) regEmail.addEventListener("input", () => validateEmail(regEmail));
if (regPassword) regPassword.addEventListener("input", () => validatePassword(regPassword));

/* Hero headline typewriter: types the whole headline in 2 s on each page load. */
function typeHeroHeadline(totalMs = 2000) {
    const h1 = document.querySelector(".hero h1");
    const root = document.documentElement;
    if (!h1 || h1.dataset.typed) { root.classList.remove("hero-typing"); return; }
    h1.dataset.typed = "1";
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) { root.classList.remove("hero-typing"); return; }
    // Screen readers get the whole headline at once; <br> counts as a space.
    const label = [...h1.childNodes].map(n => n.nodeName === "BR" ? " " : n.textContent).join("");
    h1.setAttribute("aria-label", label.replace(/\s+/g, " ").trim());

    // Wrap every character in a hidden span, keeping <br> and the gradient span intact.
    const chars = [];
    const wrap = node => {
        [...node.childNodes].forEach(child => {
            if (child.nodeType === Node.TEXT_NODE) {
                const frag = document.createDocumentFragment();
                [...child.textContent].forEach(ch => {
                    const s = document.createElement("span");
                    s.className = "tw-char";
                    s.setAttribute("aria-hidden", "true");
                    s.textContent = ch;
                    chars.push(s);
                    frag.appendChild(s);
                });
                child.replaceWith(frag);
            } else if (child.nodeType === Node.ELEMENT_NODE && child.tagName !== "BR") {
                wrap(child);
            }
        });
    };
    wrap(h1);
    const caret = document.createElement("span");
    caret.className = "tw-caret";
    caret.setAttribute("aria-hidden", "true");
    root.classList.remove("hero-typing");

    const start = performance.now();
    let shown = 0;
    const step = now => {
        const target = Math.min(chars.length, Math.ceil(((now - start) / totalMs) * chars.length));
        while (shown < target) chars[shown++].classList.add("on");
        // The caret follows the last typed character; it paints its own background, so it
        // stays visible inside the gradient line too.
        const last = chars[Math.max(shown - 1, 0)];
        last.after(caret);
        if (shown < chars.length) requestAnimationFrame(step);
        else setTimeout(() => { caret.classList.add("done"); setTimeout(() => caret.remove(), 500); }, 900);
    };
    requestAnimationFrame(step);
}
typeHeroHeadline();

renderUser();
loadCities();

// ?event=<id> is the event detail page; it's what "Share Event" copies and what
// opening an event pushes, so back/forward move between the list and detail pages.
function route() {
    const query = new URLSearchParams(location.search);
    if (query.has("tickets")) {
        history.replaceState({}, "", location.pathname);
        showHome({ push: false });
        if (state.token) openTickets();
        else { toast("Sign in to see your tickets.", "info"); openModal("authModal"); }
        return;
    }
    const evId = query.get("event");
    if (!evId) { showHome({ push: false }); return; }
    api(`/events/${evId}`, { auth: false })
        .then(ev => openBooking(ev, { push: false }))
        .catch(() => { toast("That event could not be found.", "error"); showHome(); });
}
window.addEventListener("popstate", route);
route();
