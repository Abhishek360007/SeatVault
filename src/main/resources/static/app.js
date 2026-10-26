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

// Deterministic poster gradient + emoji per event, by category/id.
const CATEGORY_STYLE = {
    Concert:    { emoji: "🎤", grad: "linear-gradient(135deg,#1d4ed8,#0e7490)" },
    Sports:     { emoji: "🏟️", grad: "linear-gradient(135deg,#0e7490,#cbd0dc)" },
    Theatre:    { emoji: "🎭", grad: "linear-gradient(135deg,#b45309,#eef0f5)" },
    Comedy:     { emoji: "🎙️", grad: "linear-gradient(135deg,#047857,#eaecf2)" },
    Conference: { emoji: "📊", grad: "linear-gradient(135deg,#2f5fe0,#cbd0dc)" },
    Event:      { emoji: "🎉", grad: "linear-gradient(135deg,#1d4ed8,#eef0f5)" },
};
const GRADS = Object.values(CATEGORY_STYLE).map(c => c.grad);

const state = {
    token: localStorage.getItem(TOKEN_KEY) || null,
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
    eventsPage: 0,
    searchTerm: "",
    event: null,            // event being booked (with pricing fields)
    seats: [],              // seat list for current event (each with priceMinor/tierName)
    priceColors: new Map(), // distinct priceMinor -> colour/class (per event)
    selected: new Map(),    // seatId -> seat
    booking: null,          // active PENDING booking (carries amountMinor/currency from API)
    payIdempotencyKey: null,// generated once per checkout, reused across pay retries
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

// RFC4122-ish idempotency key for a payment attempt.
function newIdempotencyKey() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return "key-" + Date.now() + "-" + Math.random().toString(16).slice(2);
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
// The API has no category/poster fields, so derive them deterministically from
// the title so each event has a stable, distinct look.
function categoryOf(ev) {
    const t = (ev.title + " " + (ev.description || "")).toLowerCase();
    if (/concert|live|music|tour|fest|coldplay|band/.test(t)) return "Concert";
    if (/match|cup|league|sport|final|game|cricket|football/.test(t)) return "Sports";
    if (/play|theatre|theater|drama|musical|opera|ballet/.test(t)) return "Theatre";
    if (/comedy|stand|laugh|comic/.test(t)) return "Comedy";
    if (/conf|summit|expo|keynote|tech|meetup/.test(t)) return "Conference";
    return "Event";
}
function posterStyle(ev) {
    const cat = categoryOf(ev);
    const base = CATEGORY_STYLE[cat] || CATEGORY_STYLE.Event;
    const grad = GRADS[hashInt(ev.id + ev.title) % GRADS.length];
    return { cat, emoji: base.emoji, grad };
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
    renderUser(); closeModal("payModal"); showHome();
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
function showHome() {
    $("#viewBooking").classList.add("hidden");
    $("#viewHome").classList.remove("hidden");
    clearInterval(state.countdown);
    disconnectWebSocket();
    window.scrollTo({ top: 0, behavior: "smooth" });
    loadEvents();
}
function showBooking() {
    $("#viewHome").classList.add("hidden");
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

async function loadEvents() {
    const grid = $("#eventsGrid");
    grid.innerHTML = Array.from({ length: 6 }).map(() => `<div class="skeleton sk-card"></div>`).join("");
    const params = new URLSearchParams({ page: state.eventsPage, size: 9, sort: "eventDateTime" });
    if (state.searchTerm) params.set("keyword", state.searchTerm);
    try {
        const page = await api(`/events?${params}`, { auth: false });
        renderEvents(page);
    } catch (err) { grid.innerHTML = ""; toast(err.message, "error"); }
}

function renderEvents(page) {
    const grid = $("#eventsGrid");
    grid.innerHTML = "";
    $("#eventCount").textContent = page.totalElements ? `${page.totalElements} event${page.totalElements > 1 ? "s" : ""}` : "";
    if (!page.content.length) {
        grid.appendChild(el("div", "empty-state",
            `<div class="ico">🎭</div><h3>No events yet</h3><p>${state.user?.role === "ADMIN" ? "Create the first one with “＋ Create event”." : "Check back soon — new shows drop regularly."}</p>`));
        $("#eventsPager").innerHTML = ""; return;
    }
    page.content.forEach(ev => grid.appendChild(eventCard(ev)));

    const pager = $("#eventsPager");
    pager.innerHTML = "";
    if (page.totalPages > 1) {
        const prev = el("button", "btn btn-soft btn-sm", "← Prev"); prev.disabled = page.first;
        prev.onclick = () => { state.eventsPage--; loadEvents(); };
        const next = el("button", "btn btn-soft btn-sm", "Next →"); next.disabled = page.last;
        next.onclick = () => { state.eventsPage++; loadEvents(); };
        pager.append(prev, el("span", "muted", `Page ${page.page + 1} / ${page.totalPages}`), next);
    }
}

function eventCard(ev) {
    const { cat, emoji, grad } = posterStyle(ev);
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
        <div class="ec-poster" style="background:${grad}">
            <span class="ec-cat">${esc(cat)}</span>
            <span class="ec-emoji" aria-hidden="true">${emoji}</span>
            ${soldOut ? `<span class="ec-soldout-badge">Sold out</span>` : ""}
            ${state.user?.role === "ADMIN" ? `<button class="ec-admin-del" title="Delete event" aria-label="Delete ${esc(ev.title)}">🗑</button>` : ""}
        </div>
        <div class="ec-body">
            <div class="ec-title">${esc(ev.title)}</div>
            <div class="ec-meta">
                <span>📍 ${esc(ev.venue)}</span>
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
async function openBooking(ev) {
    if (!state.token) { toast("Please sign in to book.", "info"); openModal("authModal"); return; }
    state.selected.clear();
    state.priceColors = new Map();
    const { cat, emoji, grad } = posterStyle(ev);
    $("#bookingBanner").style.background = grad;
    $("#bbCat").textContent = cat;
    $("#bbTitle").textContent = ev.title;
    $("#bbVenue").textContent = "📍 " + ev.venue;
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
    const seatIds = [...state.selected.keys()];
    $("#checkoutBtn").disabled = true;
    try {
        const booking = await api("/bookings/hold", { method: "POST", body: { eventId: state.event.id, seatIds } });
        state.booking = booking;
        // One idempotency key per checkout; reused across "Pay" retries so a retry
        // after a network blip never double-charges.
        state.payIdempotencyKey = newIdempotencyKey();
        openPayModal();
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

/* =============================== PAYMENT =============================== */
function openPayModal() {
    const b = state.booking;
    // Amount comes from the booking the API froze at hold time (authoritative).
    const total = money(b.amountMinor, b.currency);
    $("#payEventLine").textContent = b.eventTitle;
    $("#paySeats").textContent = `Seats: ${b.seatLabels.join(", ")}`
        + (b.feeMinor ? `  ·  incl. fees ${money(b.feeMinor, b.currency)}` : "");
    $("#payTotal").textContent = total;
    $("#payNowAmount").textContent = total;
    // reset method
    $$('input[name="pm"]').forEach((r, i) => { r.checked = i === 0; r.closest(".pay-method").classList.toggle("selected", i === 0); });
    openModal("payModal");
    startCountdown(b.expiresAt);
}

$$('input[name="pm"]').forEach(r => r.addEventListener("change", () => {
    $$(".pay-method").forEach(m => m.classList.remove("selected"));
    r.closest(".pay-method").classList.add("selected");
}));

function startCountdown(expiresAt) {
    clearInterval(state.countdown);
    const cd = $("#payCountdown");
    const tick = () => {
        const left = Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000);
        if (left <= 0) {
            cd.textContent = "expired"; cd.classList.add("danger");
            clearInterval(state.countdown);
            toast("Your hold expired — seats released.", "info");
            closeModal("payModal"); state.booking = null;
            refreshAfterChange();
            return;
        }
        cd.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
        cd.classList.toggle("danger", left <= 30);
    };
    tick(); state.countdown = setInterval(tick, 1000);
}

$("#payNowBtn").addEventListener("click", async () => {
    const b = state.booking; if (!b) return;
    const btn = $("#payNowBtn");
    if (btn.dataset.busy === "1") return; // guard against rapid double-clicks
    btn.dataset.busy = "1";
    const method = $('input[name="pm"]:checked').value;
    btn.disabled = true;
    const old = btn.innerHTML; btn.innerHTML = method === "TIMEOUT_CARD" ? "Contacting bank…" : "Processing…";
    // Reuse the same idempotency key across retries of THIS booking's payment.
    if (!state.payIdempotencyKey) state.payIdempotencyKey = newIdempotencyKey();
    try {
        const confirmed = await api(`/bookings/${b.id}/pay`, {
            method: "POST",
            body: { paymentMethod: method, idempotencyKey: state.payIdempotencyKey },
        });
        clearInterval(state.countdown);
        closeModal("payModal");
        showTicket(confirmed);
        state.booking = null;
        state.payIdempotencyKey = null;
        refreshAfterChange();
    } catch (err) {
        // Declined/failed: surface the result; the same key lets the user safely retry.
        toast(err.message, "error", 5000);
        btn.disabled = false; btn.innerHTML = old;
    } finally {
        btn.dataset.busy = "";
    }
});

$("#cancelHoldBtn").addEventListener("click", async () => {
    const b = state.booking; if (!b) return;
    try { await api(`/bookings/${b.id}/cancel`, { method: "POST" }); toast("Hold released.", "info"); }
    catch (err) { toast(err.message, "error"); }
    clearInterval(state.countdown); closeModal("payModal"); state.booking = null;
    refreshAfterChange();
});
$("#payClose").addEventListener("click", () => {
    // closing the modal leaves the hold active; it will expire or can be resumed from My Tickets
    clearInterval(state.countdown);
    toast("Hold kept — resume from “My tickets” before it expires.", "info");
});

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
function pseudoQr(seed) {
    const n = 21, cell = 5, h = hashInt(seed);
    let rects = "";
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
        const corner = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
        const on = corner ? ((x === 0 || x === 6 || y === 0 || y === 6 || (x > 1 && x < 5 && y > 1 && y < 5)) ? 1 :
                   (x >= n - 7 ? ((x === n - 7 || x === n - 1 || y === 0 || y === 6 || (x > n - 6 && x < n - 2 && y > 1 && y < 5)) ? 1 : 0)
                   : ((x === 0 || x === 6 || y === n - 7 || y === n - 1 || (x > 1 && x < 5 && y > n - 6 && y < n - 2)) ? 1 : 0)))
                   : ((h >> ((x * 7 + y * 13) % 31)) & 1);
        if (on) rects += `<rect x="${x * cell}" y="${y * cell}" width="${cell}" height="${cell}"/>`;
    }
    return `<svg width="${n*cell}" height="${n*cell}" viewBox="0 0 ${n*cell} ${n*cell}" xmlns="http://www.w3.org/2000/svg" style="background:#fff;padding:6px;border-radius:8px"><g fill="#0a0b12">${rects}</g></svg>`;
}

function showTicket(booking) {
    $("#tkEvent").textContent = booking.eventTitle;
    $("#tkSeats").textContent = booking.seatLabels.join(", ");
    $("#tkId").textContent = "#" + booking.id;
    // Amount + payment status come straight from the confirmed booking response.
    $("#tkAmount").textContent = money(booking.amountMinor, booking.currency);
    const ref = booking.payment?.providerReference;
    const statusEl = $("#tkPayStatus");
    if (statusEl) statusEl.textContent = (booking.payment?.status || "APPROVED") + (ref ? " · " + ref : "");
    $("#tkQr").innerHTML = pseudoQr("SV-" + booking.id + "-" + booking.seatLabels.join(""));
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
            <span class="muted" style="font-size:.8rem">Booking #${b.id}${amount ? " · " + amount : ""}</span>
        </div>`;
    if (b.status === "PENDING") {
        const resume = el("button", "btn btn-primary btn-sm", "Resume payment →");
        resume.addEventListener("click", () => {
            state.booking = b;
            // Fresh idempotency key for this resumed attempt.
            state.payIdempotencyKey = newIdempotencyKey();
            closeModal("ticketsDrawer");
            openPayModal(); // uses the booking's own amountMinor/currency from the API
        });
        card.querySelector(".t-card-foot").appendChild(resume);
    }
    return card;
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
        description: (f.description.value.trim() || f.category.value),
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

renderUser();
// Check for shared event link parameters (Luma/District style)
const params = new URLSearchParams(window.location.search);
const evId = params.get("event");
if (evId) {
    api(`/events/${evId}`, { auth: false }).then(ev => {
        // Direct route to the details page if a shared event ID is in the URL
        openBooking(ev);
    }).catch(() => {
        loadEvents();
    });
} else {
    loadEvents();
}
