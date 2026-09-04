(function () {
  "use strict";

  const BRANDS = [
    "Abarth", "Alfa Romeo", "Audi", "Bentley", "BMW", "BYD", "Chevrolet", "Chrysler",
    "Citroën", "Cupra", "Dacia", "Dodge", "DS Automobiles", "Ferrari", "Fiat", "Ford",
    "Genesis", "Honda", "Hyundai", "Infiniti", "Isuzu", "Iveco", "Jaguar", "Jeep", "Kia",
    "Lamborghini", "Land Rover", "Lexus", "MAN", "Maserati", "Mazda", "Mercedes-Benz", "MG",
    "MINI", "Mitsubishi", "Nissan", "Opel", "Peugeot", "Polestar", "Porsche", "Ram", "Renault",
    "Rolls-Royce", "Saab", "SEAT", "Škoda", "Smart", "Subaru", "Suzuki", "Tesla", "Toyota",
    "Volkswagen", "Volvo"
  ];

  const ALIASES = {
    "mercedes-benz": ["mercedes", "mercedes-benz"],
    "volkswagen": ["volkswagen", "vw"],
    "alfa romeo": ["alfa romeo", "alfa"],
    "land rover": ["land rover", "range rover"],
    "ds automobiles": ["ds automobiles", "ds store", "citroën ds"],
    "škoda": ["škoda", "skoda"],
    "citroën": ["citroën", "citroen"],
    "bmw": ["bmw"],
    "mini": ["mini"]
  };

  const els = {
    form: document.getElementById("searchForm"), brand: document.getElementById("brandSelect"),
    dealers: document.getElementById("dealersCheck"), repair: document.getElementById("repairCheck"),
    radius: document.getElementById("radiusRange"), radiusOutput: document.getElementById("radiusOutput"),
    locationCard: document.getElementById("locationCard"), locationTitle: document.getElementById("locationTitle"),
    locationDetail: document.getElementById("locationDetail"), locate: document.getElementById("locateButton"),
    manual: document.getElementById("manualLocation"), placeInput: document.getElementById("placeInput"),
    findPlace: document.getElementById("findPlaceButton"), search: document.getElementById("searchButton"),
    list: document.getElementById("resultsList"), title: document.getElementById("resultsTitle"),
    meta: document.getElementById("resultsMeta"), sort: document.getElementById("sortButton"),
    mapStatus: document.getElementById("mapStatus"), recenter: document.getElementById("recenterButton"),
    toast: document.getElementById("toast"), section: document.querySelector(".results-section")
  };

  BRANDS.forEach(name => {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name;
    els.brand.appendChild(option);
  });

  const map = L.map("map", { zoomControl: false, attributionControl: true }).setView([51.9, 19.1], 6);
  L.control.zoom({ position: "topright" }).addTo(map);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
  }).addTo(map);

  let userPosition = null;
  let userMarker = null;
  let radiusCircle = null;
  let resultLayer = L.layerGroup().addTo(map);
  let markerById = new Map();
  let currentResults = [];
  let toastTimer = null;

  function setMapStatus(text, state) {
    els.mapStatus.className = "map-status" + (state ? " " + state : "");
    els.mapStatus.querySelector("span:last-child").textContent = text;
  }

  function showToast(message, error) {
    clearTimeout(toastTimer);
    els.toast.textContent = message;
    els.toast.className = "toast show" + (error ? " error" : "");
    toastTimer = setTimeout(() => { els.toast.className = "toast"; }, 4200);
  }

  function updateSearchReady() {
    els.search.disabled = !(userPosition && els.brand.value && (els.dealers.checked || els.repair.checked));
  }

  function setUserPosition(lat, lon, label, accuracy) {
    userPosition = { lat: Number(lat), lon: Number(lon), label: label || "Current location" };
    els.locationCard.className = "location-card ready";
    els.locationTitle.textContent = label || "Location ready";
    els.locationDetail.textContent = accuracy ? `Accurate to about ${Math.round(accuracy)} m` : "Search will start from here";
    els.manual.hidden = true;
    updateSearchReady();
    if (userMarker) map.removeLayer(userMarker);
    if (radiusCircle) map.removeLayer(radiusCircle);
    userMarker = L.marker([lat, lon], {
      icon: L.divIcon({ className: "", html: '<div class="user-location-dot"></div>', iconSize: [20,20], iconAnchor: [10,10] }),
      zIndexOffset: 1000
    }).addTo(map).bindTooltip("Your location", { direction: "top", offset: [0,-12] });
    drawRadius();
    map.setView([lat, lon], 12);
    setMapStatus(label || "Location ready", "ready");
    reverseGeocode(lat, lon);
  }

  function drawRadius() {
    if (!userPosition) return;
    if (radiusCircle) map.removeLayer(radiusCircle);
    radiusCircle = L.circle([userPosition.lat, userPosition.lon], {
      radius: Number(els.radius.value) * 1000,
      color: "#0b58d0", weight: 1.3, opacity: .4, fillColor: "#0b58d0", fillOpacity: .045
    }).addTo(map);
  }

  async function reverseGeocode(lat, lon) {
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}&zoom=13&addressdetails=1`;
      const response = await fetch(url, { headers: { "Accept-Language": navigator.language || "en" } });
      if (!response.ok) return;
      const data = await response.json();
      const a = data.address || {};
      const place = a.city || a.town || a.village || a.municipality || a.county;
      if (place && userPosition) {
        userPosition.label = place;
        els.locationTitle.textContent = place;
        setMapStatus(`Near ${place}`, "ready");
      }
    } catch (_) { /* Coordinates still work without a place name. */ }
  }

  function requestLocation() {
    els.locationCard.className = "location-card";
    els.locationTitle.textContent = "Finding your location…";
    els.locationDetail.textContent = "You may be asked for permission";
    setMapStatus("Finding your location", "searching");
    if (!navigator.geolocation) return locationFailed("Location is not supported by this browser.");
    navigator.geolocation.getCurrentPosition(
      p => setUserPosition(p.coords.latitude, p.coords.longitude, "Location ready", p.coords.accuracy),
      e => locationFailed(e.code === 1 ? "Location permission was not allowed." : "Your location could not be found."),
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 180000 }
    );
  }

  function locationFailed(message) {
    els.locationCard.className = "location-card error";
    els.locationTitle.textContent = "Enter your area instead";
    els.locationDetail.textContent = message;
    els.manual.hidden = false;
    setMapStatus("Location needed", "");
    updateSearchReady();
  }

  async function findManualPlace() {
    const query = els.placeInput.value.trim();
    if (!query) return showToast("Enter a city, town, or postcode first.", true);
    els.findPlace.disabled = true;
    els.findPlace.textContent = "…";
    try {
      const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&addressdetails=1&q=${encodeURIComponent(query)}`;
      const response = await fetch(url, { headers: { "Accept-Language": navigator.language || "en" } });
      if (!response.ok) throw new Error("Place search failed");
      const data = await response.json();
      if (!data.length) throw new Error("No matching place found");
      const item = data[0];
      const a = item.address || {};
      const label = a.city || a.town || a.village || a.municipality || item.display_name.split(",")[0];
      setUserPosition(item.lat, item.lon, label, null);
    } catch (error) {
      showToast(error.message === "No matching place found" ? "That place was not found. Try a nearby city or postcode." : "Place search is temporarily unavailable.", true);
    } finally {
      els.findPlace.disabled = false;
      els.findPlace.textContent = "Set";
    }
  }

  function normalize(value) {
    return String(value || "").toLocaleLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, " ").trim();
  }

  function brandTerms(brand) {
    const direct = ALIASES[brand.toLocaleLowerCase()] || [brand];
    return [...new Set(direct.map(normalize).filter(Boolean))];
  }

  function escapeRegex(value) { return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  function buildOverpassQuery(brand, radius, lat, lon) {
    const rawTerms = ALIASES[brand.toLocaleLowerCase()] || [brand];
    const terms = [...new Set(rawTerms.flatMap(value => [String(value), normalize(value)]))].map(escapeRegex).join("|");
    const around = `(around:${radius * 1000},${lat},${lon})`;
    const tags = ["brand", "name", "operator", "service:vehicle:brand", "car:brand", "vehicle:brand"];
    const lines = [];
    const types = ["node", "way", "relation"];
    const shops = [];
    if (els.dealers.checked) shops.push("car", "car_dealer");
    if (els.repair.checked) shops.push("car_repair", "tyres");
    types.forEach(type => shops.forEach(shop => tags.forEach(tag => {
      lines.push(`${type}[\"shop\"=\"${shop}\"][\"${tag}\"~\"${terms}\",i]${around};`);
    })));
    if (els.repair.checked) {
      types.forEach(type => ["amenity", "craft"].forEach(key => tags.forEach(tag => {
        lines.push(`${type}[\"${key}\"=\"car_repair\"][\"${tag}\"~\"${terms}\",i]${around};`);
      })));
    }
    return `[out:json][timeout:28];(${lines.join("")});out center tags;`;
  }

  async function fetchOverpass(query) {
    const endpoints = [
      "https://overpass-api.de/api/interpreter",
      "https://overpass.kumi.systems/api/interpreter"
    ];
    let lastError;
    for (const endpoint of endpoints) {
      try {
        const response = await fetch(endpoint, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
          body: "data=" + encodeURIComponent(query)
        });
        if (!response.ok) throw new Error(`Directory returned ${response.status}`);
        return await response.json();
      } catch (error) { lastError = error; }
    }
    throw lastError || new Error("Directory unavailable");
  }

  async function fetchNominatimBrand(brand, radius, lat, lon) {
    const latDelta = radius / 111;
    const lonDelta = radius / (111 * Math.max(.3, Math.cos(lat * Math.PI / 180)));
    const viewbox = [lon - lonDelta, lat + latDelta, lon + lonDelta, lat - latDelta].join(",");
    const query = `${brand} car dealer repair`;
    const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=30&addressdetails=1&namedetails=1&bounded=1&viewbox=${encodeURIComponent(viewbox)}&q=${encodeURIComponent(query)}`;
    try {
      const response = await fetch(url, { headers: { "Accept-Language": navigator.language || "en" } });
      if (!response.ok) return [];
      const data = await response.json();
      return data.filter(item => {
        const kind = `${item.category || ""} ${item.type || ""}`;
        const text = normalize(`${item.display_name || ""} ${item.namedetails?.name || ""}`);
        return /car|vehicle|shop/.test(kind) && brandTerms(brand).some(term => text.includes(term));
      }).map(item => ({
        id: `nominatim-${item.osm_type}-${item.osm_id}`,
        lat: Number(item.lat), lon: Number(item.lon),
        tags: {
          name: item.namedetails?.name || item.display_name.split(",")[0],
          "addr:street": item.address?.road,
          "addr:housenumber": item.address?.house_number,
          "addr:city": item.address?.city || item.address?.town || item.address?.village,
          shop: /repair|tyre/.test(item.type || "") ? "car_repair" : "car"
        }, source: "Nominatim"
      }));
    } catch (_) { return []; }
  }

  function haversine(lat1, lon1, lat2, lon2) {
    const rad = n => n * Math.PI / 180;
    const dLat = rad(lat2 - lat1), dLon = rad(lon2 - lon1);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function parseOverpass(element) {
    const lat = element.lat ?? element.center?.lat;
    const lon = element.lon ?? element.center?.lon;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    return { id: `overpass-${element.type}-${element.id}`, lat, lon, tags: element.tags || {}, source: "Overpass" };
  }

  function classify(tags) {
    const values = `${tags.shop || ""} ${tags.amenity || ""} ${tags.craft || ""}`;
    return /repair|tyres/.test(values) ? "repair" : "dealer";
  }

  function formatAddress(tags) {
    const street = [tags["addr:street"], tags["addr:housenumber"]].filter(Boolean).join(" ");
    const locality = [tags["addr:postcode"], tags["addr:city"] || tags["addr:town"] || tags["addr:village"]].filter(Boolean).join(" ");
    return [street, locality].filter(Boolean).join(", ") || "Address available in Google Maps";
  }

  function formatResult(raw, brand) {
    const tags = raw.tags || {};
    const type = classify(tags);
    const name = tags.name || tags.operator || `${brand} ${type === "dealer" ? "dealership" : "workshop"}`;
    const distance = haversine(userPosition.lat, userPosition.lon, raw.lat, raw.lon);
    const services = [];
    if (type === "dealer") services.push("Sales"); else services.push("Service");
    if (tags["service:vehicle:repair"] === "yes" || tags.workshop) services.push("Repairs");
    if (tags["service:vehicle:tyres"] === "yes" || tags.shop === "tyres") services.push("Tyres");
    const web = tags.website || tags["contact:website"] || null;
    return { ...raw, name, type, distance, address: formatAddress(tags), services: [...new Set(services)], phone: tags.phone || tags["contact:phone"] || null, web };
  }

  function deduplicate(items) {
    const unique = [];
    for (const item of items.sort((a,b) => a.distance - b.distance)) {
      const duplicate = unique.some(u => normalize(u.name) === normalize(item.name) && haversine(u.lat,u.lon,item.lat,item.lon) < .15);
      if (!duplicate) unique.push(item);
    }
    return unique;
  }

  function googleMapsUrl(item) {
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(item.lat + "," + item.lon)}`;
  }

  function iconSvg(type) {
    return type === "repair"
      ? '<svg viewBox="0 0 24 24"><path d="m14.7 6.3 3-3a4 4 0 0 1-5.1 5.1L6 15l-3 6 6-3 6.6-6.6a4 4 0 0 1 5.1-5.1l-3 3-3-3Z"/></svg>'
      : '<svg viewBox="0 0 24 24"><path d="M3 10.5 5.3 5h13.4l2.3 5.5M5 14h.01M19 14h.01M4 19v1M20 19v1M3 10.5v7h18v-7H3Z"/></svg>';
  }

  function placeIcon(type) {
    return L.divIcon({
      className: "", iconSize: [36,42], iconAnchor: [17,39], popupAnchor: [0,-39],
      html: `<div class="place-marker ${type}"><div>${iconSvg(type)}</div></div>`
    });
  }

  function renderResults(results, brand, radius) {
    els.list.innerHTML = "";
    markerById.clear();
    resultLayer.clearLayers();
    els.title.textContent = `${brand} nearby`;
    els.meta.textContent = results.length ? `${results.length} verified ${results.length === 1 ? "place" : "places"} within ${radius} km` : `No verified places within ${radius} km`;
    els.sort.hidden = !results.length;

    if (!results.length) {
      els.list.innerHTML = `<div class="empty-state"><div class="empty-map" aria-hidden="true"><svg viewBox="0 0 64 64"><path d="m5 14 17-8 20 8 17-8v44l-17 8-20-8-17 8V14Z"/><path d="M22 6v44M42 14v44"/><circle cx="33" cy="26" r="7"/><path d="M33 33v8"/></svg></div><strong>No verified match found</strong><span>Try a larger radius. Listings without a clear link to ${escapeHtml(brand)} are intentionally left out.</span></div>`;
      setMapStatus("No verified matches", "");
      return;
    }

    results.forEach(item => {
      const marker = L.marker([item.lat, item.lon], { icon: placeIcon(item.type), title: item.name }).addTo(resultLayer);
      marker.bindPopup(`<div class="popup-card"><strong>${escapeHtml(item.name)}</strong><p>${escapeHtml(item.address)} · ${item.distance.toFixed(1)} km</p><a href="${googleMapsUrl(item)}" target="_blank" rel="noopener">Open in Google Maps</a></div>`);
      marker.on("click", () => {
        activateCard(item.id);
        window.open(googleMapsUrl(item), "_blank", "noopener");
      });
      markerById.set(item.id, marker);

      const card = document.createElement("button");
      card.type = "button";
      card.className = "result-card";
      card.dataset.id = item.id;
      card.setAttribute("aria-label", `${item.name}, ${item.distance.toFixed(1)} kilometres away. Show on map.`);
      card.innerHTML = `<span class="result-icon ${item.type}">${iconSvg(item.type)}</span><span class="result-copy"><strong>${escapeHtml(item.name)}</strong><span>${escapeHtml(item.address)}</span><span class="result-tags">${item.services.map(s => `<em>${escapeHtml(s)}</em>`).join("")}<em>${item.source}</em></span></span><span class="result-distance">${item.distance.toFixed(1)} km</span>`;
      card.addEventListener("click", () => {
        activateCard(item.id);
        map.flyTo([item.lat, item.lon], Math.max(map.getZoom(), 15), { duration: .6 });
        marker.openPopup();
      });
      card.addEventListener("dblclick", () => window.open(googleMapsUrl(item), "_blank", "noopener"));
      els.list.appendChild(card);
    });

    const bounds = L.latLngBounds([[userPosition.lat,userPosition.lon], ...results.map(r => [r.lat,r.lon])]);
    map.fitBounds(bounds, { padding: [55,55], maxZoom: 14 });
    setMapStatus(`${results.length} verified ${results.length === 1 ? "place" : "places"}`, "ready");
  }

  function activateCard(id) {
    document.querySelectorAll(".result-card.active").forEach(card => card.classList.remove("active"));
    const card = document.querySelector(`.result-card[data-id="${CSS.escape(id)}"]`);
    if (card) { card.classList.add("active"); card.scrollIntoView({ block: "nearest", behavior: "smooth" }); }
  }

  function escapeHtml(value) {
    const div = document.createElement("div");
    div.textContent = String(value || "");
    return div.innerHTML;
  }

  function setLoading(loading) {
    els.section.setAttribute("aria-busy", String(loading));
    els.search.disabled = loading;
    els.search.classList.toggle("loading", loading);
    els.search.querySelector("span").textContent = loading ? "Checking live listings…" : "Find nearby places";
    if (loading) {
      els.list.innerHTML = '<div class="skeleton-card"></div><div class="skeleton-card"></div><div class="skeleton-card"></div>';
      els.meta.textContent = "Checking multiple live listing indexes";
      setMapStatus("Searching live listings", "searching");
    } else updateSearchReady();
  }

  async function search(event) {
    event?.preventDefault();
    const brand = els.brand.value;
    const radius = Number(els.radius.value);
    if (!userPosition) return locationFailed("Set a location before searching.");
    if (!brand) return showToast("Select a vehicle brand first.", true);
    if (!els.dealers.checked && !els.repair.checked) return showToast("Choose dealerships, workshops, or both.", true);
    setLoading(true);
    els.title.textContent = `Searching for ${brand}`;
    try {
      const query = buildOverpassQuery(brand, radius, userPosition.lat, userPosition.lon);
      const [overpass, nominatim] = await Promise.all([
        fetchOverpass(query),
        fetchNominatimBrand(brand, radius, userPosition.lat, userPosition.lon)
      ]);
      const fromOverpass = (overpass.elements || []).map(parseOverpass).filter(Boolean);
      currentResults = deduplicate([...fromOverpass, ...nominatim].map(item => formatResult(item, brand)).filter(item => item.distance <= radius + .2));
      renderResults(currentResults, brand, radius);
    } catch (error) {
      currentResults = [];
      resultLayer.clearLayers();
      els.title.textContent = "Listings unavailable";
      els.meta.textContent = "The live directories did not respond";
      els.list.innerHTML = '<div class="empty-state"><div class="empty-map" aria-hidden="true"><svg viewBox="0 0 64 64"><path d="m5 14 17-8 20 8 17-8v44l-17 8-20-8-17 8V14Z"/><path d="M22 6v44M42 14v44"/><path d="m27 21 12 12M39 21 27 33"/></svg></div><strong>Live listings are taking a break</strong><span>Nothing is stored or guessed. Wait a moment, then try the search again.</span></div>';
      setMapStatus("Directory unavailable", "");
      showToast("The live business directories are busy. Please try again shortly.", true);
    } finally { setLoading(false); }
  }

  els.form.addEventListener("submit", search);
  els.brand.addEventListener("change", updateSearchReady);
  [els.dealers, els.repair].forEach(box => box.addEventListener("change", updateSearchReady));
  els.radius.addEventListener("input", () => {
    const value = Number(els.radius.value);
    els.radiusOutput.value = `${value} km`;
    els.radius.style.background = `linear-gradient(to right,var(--blue) 0 ${(value - 5) / 95 * 100}%,#dbe3ee ${(value - 5) / 95 * 100}% 100%)`;
    drawRadius();
  });
  els.locate.addEventListener("click", requestLocation);
  els.findPlace.addEventListener("click", findManualPlace);
  els.placeInput.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); findManualPlace(); } });
  els.recenter.addEventListener("click", () => {
    if (userPosition) map.flyTo([userPosition.lat,userPosition.lon], 13, { duration: .6 });
    else requestLocation();
  });
  els.sort.addEventListener("click", () => {
    currentResults.sort((a,b) => a.distance - b.distance);
    renderResults(currentResults, els.brand.value, Number(els.radius.value));
  });
  document.addEventListener("visibilitychange", () => { if (!document.hidden) setTimeout(() => map.invalidateSize(), 100); });
  window.addEventListener("resize", () => map.invalidateSize());

  requestLocation();
})();
