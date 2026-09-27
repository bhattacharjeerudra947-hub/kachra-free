// Kachra Free admin panel. Plain JavaScript, no framework.
// Every few seconds it fetches the whole state from /api/admin/state and
// redraws the tables and the map. Forms post directly to the admin API.
// The map is Leaflet with OpenStreetMap tiles: free, no key needed.

const REFRESH_MS = 2000; // how often the page fetches fresh state

let state = null;            // last response from /api/admin/state
let selectedTruckId = null;  // truck whose stops are open in the editor
let stopsDraft = null;       // editable copy of that truck's stops
let draftDirty = false;      // unsaved edits: stop live-syncing the draft
let clickMode = null;        // what a map click does: "add-stop", "pick-house" or null
let trackPoints = [];        // today's GPS track of the selected truck
let settingsFilled = false;  // settings form filled in once (don't overwrite while typing)
let mapFitted = false;       // map zoomed to show everything once

// Short name for "find the element matching this CSS selector".
function find(selector) {
  return document.querySelector(selector);
}

const residentForm = find("#resident-form");
const settingsForm = find("#settings-form");

// ------------------------------------------------------------ helpers --

// Calls the server's JSON API. Throws an Error with the server's message
// if the request fails.
async function api(method, path, body) {
  const options = { method: method, headers: {} };
  if (body) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);

  let json = {};
  try {
    json = await response.json();
  } catch (error) {
    // Not JSON: leave it empty.
  }
  if (!response.ok) {
    throw new Error(json.error || response.statusText);
  }
  return json;
}

// Everything shown comes from users (names, addresses...), so escape it
// before putting it into HTML.
function esc(text) {
  if (text === null || text === undefined) text = "";
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function ago(seconds) {
  if (seconds < 60) return Math.round(seconds) + "s ago";
  if (seconds < 3600) return Math.round(seconds / 60) + " min ago";
  if (seconds < 86400) return Math.round(seconds / 3600) + " h ago";
  return Math.round(seconds / 86400) + " days ago";
}

function truckById(id) {
  for (const truck of state.trucks) {
    if (truck.id === id) return truck;
  }
  return null;
}

// A copy of a list of stops, so editing the copy doesn't change the original.
function copyStops(stops) {
  return JSON.parse(JSON.stringify(stops));
}

function showError(error) {
  alert(error.message);
}

// ------------------------------------------------------------ refresh --

async function refresh() {
  try {
    state = await api("GET", "/api/admin/state");
    find("#status").textContent = "Updated " + new Date().toLocaleTimeString();
  } catch (error) {
    find("#status").textContent = "Can't reach the server: " + error.message;
    return;
  }

  if (selectedTruckId) {
    const selected = truckById(selectedTruckId);
    if (!selected) {
      closeTruck(); // it was deleted
    } else {
      // Live two-way sync: stops the driver adds show up here, unless the
      // admin is in the middle of editing.
      if (!draftDirty) stopsDraft = copyStops(selected.stops);
      if (find("#show-track").checked) await loadTrack();
    }
  }

  renderSystemAlerts();
  // Keep the open history table up to date (today's round fills in live).
  if (historyTruckId) {
    if (truckById(historyTruckId)) {
      await loadHistory();
    } else {
      closeHistory(); // the truck was deleted
    }
  }

  renderTrucks();
  renderTruckEditor();
  renderResidents();
  renderTruckSelect();
  if (!settingsFilled) fillSettings();
  renderMap();
}

// ------------------------------------------------------------- alerts --

function renderSystemAlerts() {
  let html = "";
  for (const text of state.alerts) {
    html += `<li class="bad">${esc(text)}</li>`;
  }
  if (html === "") {
    html = `<li class="ok">All good: road routes and location search are working.</li>`;
  }
  find("#system-alerts").innerHTML = html;
}

// ------------------------------------------------------------- trucks --

function truckRow(truck) {
  let warning = "";
  if (truck.routingError) {
    warning = ` <span class="bad" title="${esc(truck.routingError)}">⚠</span>`;
  }

  let today = "";
  if (truck.stops.length > 0) {
    today = `collected ${truck.collectedToday} of ${truck.stops.length}`;
  }

  let road = "—";
  if (truck.routeAgeSeconds !== null) road = "updated " + ago(truck.routeAgeSeconds);

  return `
    <tr>
      <td><button class="link" data-history-truck="${esc(truck.id)}" title="Show collection history">${esc(truck.id)}</button>${warning}</td>
      <td>${onlinePill(truck)} <span class="hint">${lastLocationText(truck)}</span></td>
      <td>${truck.stops.length}</td>
      <td>${today}</td>
      <td>${road}</td>
      <td>${truck.pastRuns} real, ${truck.demoRuns} demo days</td>
      <td>
        <button data-edit-truck="${esc(truck.id)}">Edit stops</button>
        <button class="danger" data-delete-truck="${esc(truck.id)}">Delete</button>
      </td>
    </tr>`;
}

// "Online" = sent its location within the last 2 minutes (the server's
// ACTIVE_SECONDS); otherwise "Offline".
function onlinePill(truck) {
  if (truck.active) return `<span class="online-pill on">● Online</span>`;
  return `<span class="online-pill off">● Offline</span>`;
}

function lastLocationText(truck) {
  if (truck.secondsSinceUpdate === null) return "never sent a location";
  return "last location " + ago(truck.secondsSinceUpdate);
}
// Offline means the driver pressed Stop, or no location has arrived for 10 s
// (no internet, no GPS, phone off...).

function renderTrucks() {
  let html = "";
  let online = 0;
  for (const truck of state.trucks) {
    html += truckRow(truck);
    if (truck.active) online += 1;
  }
  if (html === "") {
    html = `<tr><td colspan="7" class="hint">No trucks yet.</td></tr>`;
  }
  find("#trucks tbody").innerHTML = html;

  const offline = state.trucks.length - online;
  find("#trucks-summary").innerHTML =
    `<span class="online-pill on">${online} online</span> <span class="online-pill off">${offline} offline</span>`;
}

// One click listener for the whole table. The buttons carry the truck ID
// in data-edit-truck / data-delete-truck attributes.
find("#trucks").addEventListener("click", async (event) => {
  const editId = event.target.dataset.editTruck;
  const deleteId = event.target.dataset.deleteTruck;
  const historyId = event.target.dataset.historyTruck;

  if (editId) openTruck(editId);
  if (historyId) openHistory(historyId);

  if (deleteId && confirm(`Delete truck ${deleteId} and its stops?`)) {
    try {
      await api("DELETE", "/api/admin/trucks?id=" + encodeURIComponent(deleteId));
      refresh();
    } catch (error) {
      showError(error);
    }
  }
});

find("#truck-form").addEventListener("submit", async (event) => {
  event.preventDefault(); // stay on the page instead of submitting the form
  const form = event.target;
  const id = form.truckId.value.trim();
  try {
    await api("POST", "/api/admin/trucks", { id: id });
    form.reset();
    await refresh();
    openTruck(id);
  } catch (error) {
    showError(error);
  }
});

// ---------------------------------------------- collection history --
//
// Clicking a truck's ID shows its past collection rounds: one row per day,
// one column per stop, with when the truck arrived there and how long it
// spent collecting.

let historyTruckId = null; // truck whose history is open

async function openHistory(id) {
  historyTruckId = id;
  await loadHistory();
  find("#history-panel").hidden = false;
  find("#history-panel").scrollIntoView({ behavior: "smooth" });
}

function closeHistory() {
  historyTruckId = null;
  find("#history-panel").hidden = true;
}

find("#close-history").addEventListener("click", closeHistory);

async function loadHistory() {
  try {
    const history = await api("GET", "/api/admin/history?truckId=" + encodeURIComponent(historyTruckId));
    renderHistory(history);
  } catch (error) {
    find("#history-summary").textContent = "Couldn't load the history: " + error.message;
  }
}

// Seconds since 1970 -> "07:05" (the laptop's local time).
function clock(seconds) {
  const date = new Date(seconds * 1000);
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

// A length of time in seconds -> "3 min" (or "<1 min").
function duration(seconds) {
  const minutes = Math.round(seconds / 60);
  if (minutes < 1) return "<1 min";
  return minutes + " min";
}

// "2026-09-27" -> "Sat 27 Sep 2026"
function niceDate(isoDate) {
  const date = new Date(isoDate + "T00:00:00");
  return date.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short", year: "numeric" });
}

function historyHeader(stops) {
  let html = "<tr><th>Day</th>";
  for (let i = 0; i < stops.length; i++) {
    html += `<th>${i + 1}. ${esc(stops[i].name)}</th>`;
  }
  html += "<th>Stops collected</th><th>Round</th></tr>";
  return html;
}

// The first row: what's typical for each stop, from all past days.
function usualRow(stops) {
  let html = `<tr class="usual"><td><b>Usually</b></td>`;
  for (const stop of stops) {
    let cell = "—";
    if (stop.usualTime) cell = esc(stop.usualTime);
    html += `<td>arrives ${cell}<br><span class="hint">stops ~${duration(stop.typicalDwellSeconds)}</span></td>`;
  }
  html += "<td></td><td></td></tr>";
  return html;
}

// A stop not collected that day: "skipped" if a later stop was collected,
// otherwise just not reached (yet).
function missedText(visits, index) {
  for (let later = index + 1; later < visits.length; later++) {
    if (visits[later] !== null) return "⚠ skipped";
  }
  return "—";
}

function dayRow(day, stopCount) {
  let tag = `<span class="tag real">real</span>`;
  if (day.synthetic) tag = `<span class="tag demo">demo</span>`;
  if (day.today) tag = `<span class="tag today">today</span>`;

  let html = `<tr><td>${niceDate(day.date)}<br>${tag}</td>`;
  let visited = 0;
  let firstArrival = null;
  let lastDeparture = null;
  for (let i = 0; i < day.visits.length; i++) {
    const visit = day.visits[i];
    if (visit === null) {
      html += `<td class="hint">${missedText(day.visits, i)}</td>`;
      continue;
    }
    visited += 1;

    // Demo rounds have arrival and departure; real rounds only have the
    // time the driver pressed "Garbage collected".
    let from = visit.collectedAt;
    let to = visit.collectedAt;
    if (visit.arrived !== undefined) {
      from = visit.arrived;
      to = visit.left;
      html += `<td>${clock(visit.arrived)} → ${clock(visit.left)}<br>` +
        `<span class="hint">stopped ${duration(visit.left - visit.arrived)}</span></td>`;
    } else {
      html += `<td>✓ ${clock(visit.collectedAt)}<br><span class="hint">collected</span></td>`;
    }
    if (firstArrival === null || from < firstArrival) firstArrival = from;
    if (lastDeparture === null || to > lastDeparture) lastDeparture = to;
  }

  let round = "";
  if (firstArrival !== null) {
    round = `${clock(firstArrival)} – ${clock(lastDeparture)}<br><span class="hint">${duration(lastDeparture - firstArrival)} in total</span>`;
  }
  html += `<td>${visited} of ${stopCount}</td><td>${round}</td></tr>`;
  return html;
}

function renderHistory(history) {
  find("#history-title").textContent = "Collection history: " + history.truckId;

  let real = 0;
  let demo = 0;
  for (const day of history.days) {
    if (day.synthetic) demo += 1;
    else real += 1;
  }

  if (history.stops.length === 0) {
    find("#history-summary").textContent = "This truck has no stops yet, so there's nothing to show.";
    find("#history thead").innerHTML = "";
    find("#history tbody").innerHTML = "";
    return;
  }
  if (history.days.length === 0) {
    find("#history-summary").textContent =
      "No collection rounds yet. A round is recorded as the truck visits its stops " +
      "(or use Generate demo history in Edit stops).";
  } else {
    find("#history-summary").textContent =
      `${history.days.length} days: ${real} real, ${demo} demo. Each cell shows when the truck ` +
      `arrived at that stop and how long it spent collecting there.`;
  }

  find("#history thead").innerHTML = historyHeader(history.stops);
  let rows = usualRow(history.stops);
  for (const day of history.days) {
    rows += dayRow(day, history.stops.length);
  }
  find("#history tbody").innerHTML = rows;
}

// ---------------------------------------------------- stops editor --

function openTruck(id) {
  selectedTruckId = id;
  stopsDraft = copyStops(truckById(id).stops);
  draftDirty = false;
  trackPoints = [];
  find("#show-track").checked = false;
  setClickMode(null);
  renderTruckEditor();
  renderMap(true);
  find("#truck-editor").scrollIntoView({ behavior: "smooth" });
}

function closeTruck() {
  selectedTruckId = null;
  stopsDraft = null;
  draftDirty = false;
  setClickMode(null);
  renderTruckEditor();
  renderMap();
}

function markDirty() {
  draftDirty = true;
  renderTruckEditor();
  renderMap();
}

function stopFacts(stop) {
  const facts = [];
  if (stop.status) facts.push("today: " + stop.status);
  if (stop.usualTime) facts.push("usually " + esc(stop.usualTime));
  if (stop.typicalDwellSeconds) facts.push("stops ~" + Math.round(stop.typicalDwellSeconds / 60) + " min");
  if (stop.addedBy === "driver") facts.push("added by driver");
  if (stop.legSource === "driven") facts.push("road to next: as driven");
  if (stop.legSource === "osrm") facts.push("road to next: shortest route");
  return facts.join(" · ");
}

function renderTruckEditor() {
  const truck = selectedTruckId ? truckById(selectedTruckId) : null;
  find("#truck-editor").hidden = !truck;
  if (!truck) return;

  find("#truck-editor-title").textContent = "Stops of truck " + truck.id;
  find("#routing-error").textContent = truck.routingError || "";
  if (draftDirty) {
    find("#unsaved").textContent = "Unsaved changes (saving replaces the whole stop list)";
  } else {
    find("#unsaved").textContent = "";
  }

  // Don't redraw while the admin is typing a stop name.
  if (find("#stops").contains(document.activeElement)) return;

  let html = "";
  for (let i = 0; i < stopsDraft.length; i++) {
    const stop = stopsDraft[i];
    html += `
      <li>
        <input data-stop-name="${i}" value="${esc(stop.name)}">
        <button data-move="${i}" data-by="-1" title="Earlier">↑</button>
        <button data-move="${i}" data-by="1" title="Later">↓</button>
        <button data-remove="${i}" title="Remove">✕</button>
        <span class="hint">${stopFacts(stop)}</span>
      </li>`;
  }
  if (html === "") {
    html = `<li class="hint">No stops yet. Click "Add stops", then click the map, or press "Add stop" in the driver app.</li>`;
  }
  find("#stops").innerHTML = html;
}

// Typing in a stop's name box.
find("#stops").addEventListener("input", (event) => {
  const index = event.target.dataset.stopName;
  if (index === undefined) return;
  stopsDraft[Number(index)].name = event.target.value;
  draftDirty = true;
  find("#unsaved").textContent = "Unsaved changes (saving replaces the whole stop list)";
});

// The ↑ ↓ ✕ buttons next to each stop.
find("#stops").addEventListener("click", (event) => {
  const data = event.target.dataset;

  if (data.move !== undefined) {
    const from = Number(data.move);
    const to = from + Number(data.by);
    if (to < 0 || to >= stopsDraft.length) return;
    // Swap the two stops.
    const moving = stopsDraft[from];
    stopsDraft[from] = stopsDraft[to];
    stopsDraft[to] = moving;
  } else if (data.remove !== undefined) {
    stopsDraft.splice(Number(data.remove), 1);
  } else {
    return; // clicked somewhere else in the list
  }
  markDirty();
});

find("#add-stops").addEventListener("click", () => {
  if (clickMode === "add-stop") {
    setClickMode(null);
  } else {
    setClickMode("add-stop");
  }
});

find("#save-stops").addEventListener("click", async () => {
  // Only send what the admin can edit; the server keeps the rest.
  const stops = [];
  for (const stop of stopsDraft) {
    // wrapLongitude also fixes stops that were saved from a copy of the map.
    stops.push({ id: stop.id, name: stop.name, lat: stop.lat, lng: wrapLongitude(stop.lng) });
  }
  try {
    await api("POST", "/api/admin/trucks/stops", { truckId: selectedTruckId, stops: stops });
    draftDirty = false;
    setClickMode(null);
    refresh();
  } catch (error) {
    showError(error);
  }
});

find("#discard-stops").addEventListener("click", () => {
  stopsDraft = copyStops(truckById(selectedTruckId).stops);
  draftDirty = false;
  renderTruckEditor();
  renderMap();
});

find("#close-truck").addEventListener("click", closeTruck);

async function loadTrack() {
  try {
    const result = await api("GET", "/api/admin/track?truckId=" + encodeURIComponent(selectedTruckId));
    trackPoints = result.points;
  } catch (error) {
    trackPoints = [];
  }
}

find("#show-track").addEventListener("change", async (event) => {
  const show = event.target.checked;
  if (show) {
    await loadTrack();
  } else {
    trackPoints = [];
  }
  renderMap();
  if (show && trackPoints.length === 0) alert("No GPS points from this truck today yet.");
});

find("#demo-generate").addEventListener("click", async () => {
  if (draftDirty) {
    alert("Save or discard your stop changes first.");
    return;
  }
  try {
    const result = await api("POST", "/api/admin/trucks/demo-history",
      { truckId: selectedTruckId, action: "generate" });
    alert(`Added ${result.runs} days of made-up history (collection time at each stop, drive time between stops).`);
    refresh();
  } catch (error) {
    showError(error);
  }
});

find("#demo-clear").addEventListener("click", async () => {
  try {
    const result = await api("POST", "/api/admin/trucks/demo-history",
      { truckId: selectedTruckId, action: "clear" });
    alert(`Removed ${result.runs} days of demo history. Real history is kept.`);
    refresh();
  } catch (error) {
    showError(error);
  }
});

// ---------------------------------------------------------- residents --

function residentRow(r) {
  let stop = "";
  if (r.stopName) {
    stop = `${esc(r.stopName)} <span class="hint">${r.stopDistanceMeters} m</span>`;
  }
  return `
    <tr>
      <td><b>${esc(r.username)}</b></td>
      <td>${esc(r.truckId)}</td>
      <td>${stop}</td>
      <td>${r.alertMinutes} min</td>
      <td>${esc(r.message)}</td>
      <td>
        <button data-edit-resident="${esc(r.username)}">Edit</button>
        <button class="danger" data-delete-resident="${esc(r.username)}">Delete</button>
      </td>
    </tr>`;
}

function renderResidents() {
  let html = "";
  for (const r of state.residents) {
    html += residentRow(r);
  }
  if (html === "") {
    html = `<tr><td colspan="6" class="hint">No residents yet.</td></tr>`;
  }
  find("#residents tbody").innerHTML = html;
}

// The Truck ID dropdown in the resident form.
function renderTruckSelect() {
  const select = residentForm.truckId;
  if (select === document.activeElement) return; // don't close it while open
  const current = select.value;
  let html = `<option value="">Choose…</option>`;
  for (const truck of state.trucks) {
    html += `<option value="${esc(truck.id)}">${esc(truck.id)}</option>`;
  }
  select.innerHTML = html;
  select.value = current;
}

function residentByUsername(username) {
  for (const r of state.residents) {
    if (r.username === username) return r;
  }
  return null;
}

find("#residents").addEventListener("click", async (event) => {
  const editUsername = event.target.dataset.editResident;
  const deleteUsername = event.target.dataset.deleteResident;

  if (editUsername) {
    // Fill the form below with this resident, ready to edit.
    const r = residentByUsername(editUsername);
    residentForm.username.value = r.username;
    residentForm.truckId.value = r.truckId;
    residentForm.address.value = r.address || "";
    residentForm.latitude.value = r.lat;
    residentForm.longitude.value = r.lng;
    residentForm.alertMinutes.value = r.alertMinutes;
    find("#resident-form-title").textContent = "Edit resident " + r.username;
    find("#resident-editor").hidden = false;
    residentForm.scrollIntoView({ behavior: "smooth" });
  }

  if (deleteUsername && confirm(`Delete resident ${deleteUsername}?`)) {
    try {
      await api("DELETE", "/api/admin/residents?username=" + encodeURIComponent(deleteUsername));
      refresh();
    } catch (error) {
      showError(error);
    }
  }
});

residentForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const body = {
    username: residentForm.username.value,
    truckId: residentForm.truckId.value,
    address: residentForm.address.value,
    latitude: parseFloat(residentForm.latitude.value),
    longitude: parseFloat(residentForm.longitude.value),
    alertMinutes: parseInt(residentForm.alertMinutes.value, 10),
  };
  if (isNaN(body.latitude) || isNaN(body.longitude)) {
    alert("Latitude and longitude must be numbers.");
    return;
  }
  try {
    await api("POST", "/api/admin/residents", body);
    closeResidentEditor();
    refresh();
  } catch (error) {
    showError(error);
  }
});

function closeResidentEditor() {
  find("#resident-editor").hidden = true;
  if (clickMode === "pick-house") setClickMode(null);
}

find("#cancel-resident").addEventListener("click", closeResidentEditor);

find("#pick-house").addEventListener("click", () => setClickMode("pick-house"));

// ----------------------------------------------------------- settings --

function fillSettings() {
  for (const key in state.settings) {
    // Each input's name matches a setting's name.
    if (settingsForm[key]) settingsForm[key].value = state.settings[key];
  }
  settingsFilled = true;
}

settingsForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const body = {};
  for (const input of settingsForm.elements) {
    if (!input.name) continue; // e.g. the Save button
    if (input.type === "number") {
      body[input.name] = parseFloat(input.value);
    } else {
      body[input.name] = input.value;
    }
  }
  try {
    await api("POST", "/api/admin/settings", body);
    settingsFilled = false; // show the saved values
    await refresh();
    alert("Settings saved.");
  } catch (error) {
    showError(error);
  }
});

// ---------------------------------------------------------------- map --

const map = L.map("map").setView([20.59, 78.96], 5); // all of India
L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 19,
  attribution: "&copy; OpenStreetMap contributors",
}).addTo(map);
// Everything we draw goes in this layer, so it can be cleared in one go.
const layer = L.layerGroup().addTo(map);

// Each truck gets its own colour, used for its truck icon, its stops and
// its roads. Trucks are listed sorted by ID, so a truck keeps its colour.
const TRUCK_COLORS = ["#1c7ed6", "#e8590c", "#2f9e44", "#ae3ec9", "#e03131", "#0c8599", "#f59f00", "#5f3dc4"];

function truckColor(truckId) {
  for (let i = 0; i < state.trucks.length; i++) {
    if (state.trucks[i].id === truckId) return TRUCK_COLORS[i % TRUCK_COLORS.length];
  }
  return TRUCK_COLORS[0];
}

// Material "local shipping" truck icon, as an inline SVG (no image files).
const TRUCK_SVG = '<svg viewBox="0 0 24 24"><path d="M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm13.5-9 1.96 2.5H17V9.5h2.5zm-1.5 9c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/></svg>';

function setClickMode(mode) {
  clickMode = mode;
  find("#map").classList.toggle("picking", mode !== null);

  if (mode === "add-stop") {
    find("#add-stops").textContent = "Done adding stops";
    find("#map-hint").textContent = "Click the map to add stops to the end of the collection order.";
  } else if (mode === "pick-house") {
    find("#add-stops").textContent = "Add stops by clicking the map";
    find("#map-hint").textContent = "Click the map where the resident's house is.";
  } else {
    find("#add-stops").textContent = "Add stops by clicking the map";
    find("#map-hint").textContent = "Each truck has its own colour: its truck icon, bin stops (numbered in collection " +
      "order) and roads · ✓ collected today · ! skipped today · Grey truck: offline · Person: resident · " +
      "Grey line: roads the open truck drove today";
  }
}

// Leaflet's divIcon takes HTML, so all labels are escaped.
// onTop: draw above the other markers. Leaflet otherwise stacks markers by
// latitude, so a stop further south would cover a truck.
function addMarker(lat, lng, html, title, onTop = false) {
  const icon = L.divIcon({ className: "", html: html, iconSize: null });
  const zIndexOffset = onTop ? 1000 : 0;
  L.marker([lat, lng], { icon: icon, title: title, zIndexOffset: zIndexOffset }).addTo(layer);
}

function line(points, color, weight) {
  // interactive: false so clicks pass through to the map when adding stops.
  L.polyline(points, { color: color, weight: weight, opacity: 0.85, interactive: false }).addTo(layer);
}

function drawTruck(truck, color) {
  let className = "truck-marker";
  let title = truck.id + ": online, " + lastLocationText(truck);
  if (!truck.active) {
    className += " offline";
    title = truck.id + ": offline, " + lastLocationText(truck);
  }
  // Label: "TRUCK-1 · 12s ago" (a truck on the map has sent a location, so
  // secondsSinceUpdate is set).
  const html = `<div class="${className}">
      <div class="icon" style="background:${color}">${TRUCK_SVG}</div>
      <div class="label">${esc(truck.id)} · ${ago(truck.secondsSinceUpdate)}</div>
    </div>`;
  addMarker(truck.lat, truck.lng, html, title, true);
}

// Material "delete" icon (a dustbin) and "person" icon, as inline SVGs.
const BIN_SVG = '<svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>';
const PERSON_SVG = '<svg viewBox="0 0 24 24"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>';

// A stop: a white bin on a circle in its truck's colour, the stop's number
// in a bubble at the top right, and today's status in a bubble at the top
// left: a green tick when collected, a yellow hazard sign when skipped.
function stopMarkerHtml(stop, number, color, selected) {
  let status = "";
  if (stop.status === "collected") status = `<div class="bubble status done">✓</div>`;
  if (stop.status === "skipped") status = `<div class="bubble status skipped">!</div>`;
  let className = "stop-marker";
  if (selected) className += " selected";
  return `<div class="${className}">
      <div class="icon" style="background:${color}">${BIN_SVG}</div>
      <div class="bubble number" style="color:${color}; border-color:${color}">${number}</div>
      ${status}
    </div>`;
}

// view: the part of the map on screen. Only what's inside it is drawn.
function drawStops(stops, color, selected, view) {
  let weight = 3;
  if (selected) weight = 5;

  for (let i = 0; i < stops.length; i++) {
    const stop = stops[i];
    if (view.contains([stop.lat, stop.lng])) {
      let title = stop.name;
      if (stop.status) title += " (" + stop.status + " today)";
      addMarker(stop.lat, stop.lng, stopMarkerHtml(stop, i + 1, color, selected), title);
    }

    // The road to the next stop: as driven, or the shortest road route (from
    // the server). A leg with neither isn't drawn. Unsaved edits may have
    // changed which stop comes next, so legs reappear once saved.
    const isLast = i === stops.length - 1;
    const hasUnsavedEdits = selected && draftDirty;
    if (!isLast && stop.legPoints && !hasUnsavedEdits) {
      if (L.latLngBounds(stop.legPoints).intersects(view)) {
        line(stop.legPoints, color, weight);
      }
    }
  }
}

// On first load, frame all the trucks (or, if none has reported yet, all
// their stops).
function fitAllTrucks() {
  const points = [];
  for (const truck of state.trucks) {
    if (truck.lat !== null) points.push([truck.lat, truck.lng]);
  }
  if (points.length === 0) {
    for (const truck of state.trucks) {
      for (const stop of truck.stops) points.push([stop.lat, stop.lng]);
    }
  }
  if (points.length === 0) return false;
  map.fitBounds(points, { padding: [60, 60], maxZoom: 16 });
  return true;
}

function renderMap(zoomToSelected = false) {
  if (!mapFitted) mapFitted = fitAllTrucks();
  if (zoomToSelected && stopsDraft && stopsDraft.length > 0) {
    const focus = [];
    for (const stop of stopsDraft) focus.push([stop.lat, stop.lng]);
    map.fitBounds(focus, { padding: [40, 40], maxZoom: 16 });
  }

  layer.clearLayers();
  // A bit more than the screen, so things just off the edge are ready
  // when panning.
  const view = map.getBounds().pad(0.2);

  for (const truck of state.trucks) {
    const color = truckColor(truck.id);
    const selected = truck.id === selectedTruckId;
    // The selected truck shows the draft, including unsaved edits.
    const stops = selected ? stopsDraft : truck.stops;
    drawStops(stops, color, selected, view);

    if (truck.lat !== null && view.contains([truck.lat, truck.lng])) {
      drawTruck(truck, color);
    }
  }

  if (trackPoints.length > 1) line(trackPoints, "#495057", 2);

  for (const r of state.residents) {
    if (view.contains([r.lat, r.lng])) {
      addMarker(r.lat, r.lng, `<div class="resident-marker">${PERSON_SVG}</div>`, r.username + ": " + r.message);
    }
  }
}

// Panning or zooming shows a different part of the map: draw what's there now.
map.on("moveend", () => {
  if (state) renderMap();
});

// Leaflet shows copies of the world side by side, so a click on a copy can
// give a longitude like -271.6 instead of 88.4. Bring it back to -180..180.
function wrapLongitude(lng) {
  return ((lng + 180) % 360 + 360) % 360 - 180;
}

map.on("click", (event) => {
  // 6 decimal places is about 10 cm: plenty.
  const lat = Number(event.latlng.lat.toFixed(6));
  const lng = Number(wrapLongitude(event.latlng.lng).toFixed(6));

  if (clickMode === "add-stop" && stopsDraft) {
    stopsDraft.push({ id: null, name: "Stop " + (stopsDraft.length + 1), lat: lat, lng: lng });
    markDirty();
  } else if (clickMode === "pick-house") {
    residentForm.latitude.value = lat;
    residentForm.longitude.value = lng;
    setClickMode(null);
  }
});

// ---------------------------------------------------------------- go --

setClickMode(null);
refresh();
setInterval(refresh, REFRESH_MS);
