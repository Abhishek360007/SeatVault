#!/usr/bin/env python3
"""
Adds a set of demo events to a SeatVault deployment through its admin API.

    python scripts/seed_demo_events.py                       # the live site
    python scripts/seed_demo_events.py http://localhost:8080 # a local run

It asks for the admin password without echoing it, and never stores it.
Re-running is safe: events whose title already exists are skipped.
Every artist, team and venue below is fictional.
Dates are relative to today, so the events are always upcoming.
Standard library only.
"""
import getpass
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

DEFAULT_URL = "https://seatvault.onrender.com"
IST = timezone(timedelta(hours=5, minutes=30))

# (days from today, IST time) set when each event starts.
EVENTS = [
    dict(title="Echo Avenue: Midnight City Tour", category="CONCERT", city="Bengaluru", venue="Lakeside Arena",
         when=(6, "19:30"), rows=8, seatsPerRow=12, base=1499, fee=99,
         tiers=[("Platinum", 4999, 2), ("Gold", 2999, 3)],
         description="Indie-pop five-piece Echo Avenue bring their sold-out Midnight City tour home, "
                     "with a two-hour set, a full light show and an acoustic encore."),
    dict(title="Sufi Nights with The Qawwali Collective", category="CONCERT", city="Delhi",
         venue="Courtyard Amphitheatre", when=(13, "20:00"), rows=6, seatsPerRow=10, base=1199, fee=59,
         tiers=[("Front Row", 2499, 1)],
         description="An open-air evening of qawwali and Sufi poetry under the stars, "
                     "performed by a twelve-member ensemble."),
    dict(title="Bengaluru Blazers vs Mumbai Mariners", category="SPORTS", city="Bengaluru",
         venue="Garden City Stadium", when=(9, "19:00"), rows=10, seatsPerRow=14, base=899, fee=49,
         tiers=[("Pavilion", 3499, 2)],
         description="T20 league night game. The top two teams on the table meet under lights; "
                     "gates open two hours before the first ball."),
    dict(title="Pune Panthers vs Hyderabad Hawks", category="SPORTS", city="Pune",
         venue="Riverside Football Ground", when=(17, "18:30"), rows=8, seatsPerRow=12, base=499, fee=29,
         tiers=[],
         description="Football derby day: a fast, physical rivalry with a title race on the line. "
                     "Family stands available."),
    dict(title="DevSummit India 2026: Cloud, AI & Beyond", category="CONFERENCE", city="Hyderabad",
         venue="Cyber Towers Convention Hall", when=(20, "09:30"), capacity=150, base=1999, fee=99,
         tiers=[],
         description="A full day of talks and workshops on cloud-native systems, applied AI and developer "
                     "productivity, with 20+ speakers and hands-on labs. Lunch included."),
    dict(title="Founders Forum: Building for Bharat", category="CONFERENCE", city="Mumbai",
         venue="Bandra Kurla Convention Hall", when=(24, "10:00"), capacity=80, base=0, fee=0,
         tiers=[],
         description="Free half-day meetup for early-stage founders: product teardown sessions, "
                     "a fundraising AMA and an evening networking mixer."),
    dict(title="Starfall: IMAX Premiere Night", category="MOVIE", city="Mumbai",
         venue="Marine Drive IMAX", when=(4, "21:00"), rows=7, seatsPerRow=14, base=450, fee=35,
         tiers=[("Recliner", 950, 2)],
         description="Be first to see the sci-fi epic Starfall on the giant IMAX screen, "
                     "with a live director Q&A before the show."),
    dict(title="Classic Cinema Club: Golden Era Double Bill", category="MOVIE", city="Chennai",
         venue="Marina Heritage Theatre", when=(11, "18:00"), rows=6, seatsPerRow=12, base=250, fee=20,
         tiers=[],
         description="Two restored black-and-white classics back to back, with an interval chai break "
                     "and a short talk by a film historian."),
    dict(title="The Last Monsoon: A Hindi Musical", category="THEATRE", city="Mumbai",
         venue="Juhu Playhouse", when=(8, "19:30"), rows=8, seatsPerRow=10, base=899, fee=49,
         tiers=[("Premium", 1799, 2)],
         description="A sweeping musical about one family and one unforgettable rainy season, "
                     "with live orchestra and original songs."),
    dict(title="Hamlet Reimagined", category="THEATRE", city="Bengaluru", venue="Indiranagar Stage",
         when=(15, "19:00"), rows=6, seatsPerRow=10, base=599, fee=30, tiers=[],
         description="Shakespeare's tragedy set in a modern corporate tower. Ninety minutes, no interval, "
                     "staged in the round."),
    dict(title="Laugh Riot: Office Hours", category="COMEDY", city="Delhi", venue="Hauz Khas Comedy Cellar",
         when=(5, "20:30"), rows=5, seatsPerRow=10, base=699, fee=39, tiers=[],
         description="Stand-up about deadlines, appraisals and the office group chat. Strictly 18+."),
    dict(title="Open Mic Mayhem", category="COMEDY", city="Pune", venue="Koregaon Laugh Lounge",
         when=(3, "21:00"), capacity=60, base=299, fee=19, tiers=[],
         description="Ten new comics, five minutes each, one very honest audience. "
                     "The best set of the night gets a headline spot next month."),
    dict(title="Coastal Food & Music Carnival", category="OTHER", city="Chennai",
         venue="Besant Nagar Beachfront", when=(12, "16:00"), capacity=200, base=399, fee=29, tiers=[],
         description="Street food from 30 stalls, live bands from sunset and a fireworks finale over the sea. "
                     "Entry includes two tasting tokens."),
]


def request(base, method, path, body=None, token=None, timeout=240):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"message": raw.decode(errors="replace")[:200]}


def wake(base):
    """Free hosts sleep when idle; the first request can take a few minutes."""
    print(f"Connecting to {base} (a sleeping free instance can take ~3 minutes to wake)...")
    for attempt in range(12):
        try:
            status, body = request(base, "GET", "/actuator/health", timeout=60)
            if status == 200 and body and body.get("status") == "UP":
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(15)
    sys.exit("The site didn't come up. Check the Render dashboard, then try again.")


def starts_at(days, hhmm):
    hour, minute = map(int, hhmm.split(":"))
    local = datetime.now(IST).replace(hour=hour, minute=minute, second=0, microsecond=0) + timedelta(days=days)
    return local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def payload(e):
    body = {
        "title": e["title"], "description": e["description"], "venue": e["venue"], "city": e["city"],
        "category": e["category"], "eventDateTime": starts_at(*e["when"]), "currency": "INR",
        "basePriceMinor": e["base"] * 100, "convenienceFeeMinor": e["fee"] * 100,
    }
    if "capacity" in e:
        body["totalCapacity"] = e["capacity"]
    else:
        body["rows"], body["seatsPerRow"] = e["rows"], e["seatsPerRow"]
        if e["tiers"]:
            body["tiers"] = [{"name": n, "priceMinor": p * 100, "rows": r} for n, p, r in e["tiers"]]
    return body


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL).rstrip("/")
    wake(base)
    email = input("Admin email [admin@seatvault.local]: ").strip() or "admin@seatvault.local"
    password = getpass.getpass("Admin password (hidden): ")
    status, auth = request(base, "POST", "/api/v1/auth/login", {"email": email, "password": password})
    if status != 200:
        sys.exit(f"Login failed ({status}): {auth.get('message') if auth else ''}")
    if auth.get("role") != "ADMIN":
        sys.exit("That account isn't an admin.")
    token = auth["token"]

    created = skipped = 0
    for e in EVENTS:
        q = urllib.parse.urlencode({"keyword": e["title"], "size": 50})
        _, found = request(base, "GET", f"/api/v1/events?{q}")
        if found and any(ev["title"] == e["title"] for ev in found.get("content", [])):
            print(f"  skip    {e['title']} (already exists)")
            skipped += 1
            continue
        status, ev = request(base, "POST", "/api/v1/events", payload(e), token)
        if status == 201:
            print(f"  created {ev['title']}: {ev['city']}, {ev['totalCapacity']} seats")
            created += 1
        else:
            print(f"  FAILED  {e['title']} ({status}): {ev.get('message') if ev else ''}")
    print(f"\nDone: {created} created, {skipped} already there. Open {base}")


if __name__ == "__main__":
    main()
