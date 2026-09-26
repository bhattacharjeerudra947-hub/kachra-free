// Kachra Free admin panel. Plain JavaScript, no framework.
// Every few seconds it fetches the whole state from /api/admin/state and
// redraws the tables and the map. Forms post directly to the admin API.
// The map is Leaflet with OpenStreetMap tiles: free, no key needed.

const REFRESH_MS = 5000;

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
    today = `reached stop ${truck.stopsVisitedToday} of ${truck.stops.length}`;
  }

  let road = "—";
  if (truck.routeAgeSeconds !== null) road = "updated " + ago(truck.routeAgeSeconds);

  return `
    <tr>
      <td><b>${esc(truck.id)}</b>${warning}</td>
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

  if (editId) openTruck(editId);

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
  if (stop.usualTime) facts.push("usually " + esc(stop.usualTime));
  if (stop.typicalDwellSeconds) facts.push("~" + Math.round(stop.typicalDwellSeconds / 60) + " min collecting");
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
    stops.push({ id: stop.id, name: stop.name, lat: stop.lat, lng: stop.lng });
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
    find("#map-hint").textContent = "Each truck has its own colour: its truck icon, numbered stops (in collection order) " +
      "and roads · Grey truck: offline · Dark dots: residents · Grey line: roads the open truck drove today";
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

// view: the part of the map on screen. Only what's inside it is drawn.
function drawStops(stops, color, selected, view) {
  let className = "pin stop";
  let weight = 3;
  if (selected) {
    className += " selected";
    weight = 5;
  }

  for (let i = 0; i < stops.length; i++) {
    const stop = stops[i];
    if (view.contains([stop.lat, stop.lng])) {
      addMarker(stop.lat, stop.lng, `<div class="${className}" style="background:${color}">${i + 1}</div>`, stop.name);
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
      addMarker(r.lat, r.lng, `<div class="pin resident"></div>`, r.username + ": " + r.message);
    }
  }
}

// Panning or zooming shows a different part of the map: draw what's there now.
map.on("moveend", () => {
  if (state) renderMap();
});

map.on("click", (event) => {
  // 6 decimal places is about 10 cm: plenty.
  const lat = Number(event.latlng.lat.toFixed(6));
  const lng = Number(event.latlng.lng.toFixed(6));

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
