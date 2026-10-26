/*
 * Live listener map for the Natune presence service. Plain browser script, no build step.
 * It reads only the aggregate from /api/listeners/active. Text is written with textContent, never as HTML.
 */
(function () {
  "use strict";

  var ENDPOINT = "/api/listeners/active";
  var STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
  var POLL_MS = 30000;
  var RETRY_MS = 60000;
  var TOP_COUNTRIES = 10;

  var headline = document.getElementById("headline");
  var statusLine = document.getElementById("status");
  var topCityLine = document.getElementById("top-city");
  var countryList = document.getElementById("countries");

  var regionNames = null;
  try {
    regionNames = new Intl.DisplayNames(["en"], { type: "region" });
  } catch (error) {
    regionNames = null;
  }

  function countryName(code) {
    if (typeof code !== "string" || !/^[A-Z]{2}$/.test(code)) {
      return "Unknown";
    }
    var name = null;
    try {
      name = regionNames ? regionNames.of(code) : null;
    } catch (error) {
      name = null;
    }
    return name || code;
  }

  function headlineFor(count) {
    return count === 1 ? "1 person listening right now" : count + " people listening right now";
  }

  function listenersLabel(count) {
    return count === 1 ? "1 listener" : count + " listeners";
  }

  function timeLabel(iso) {
    var date = new Date(iso);
    return isNaN(date.getTime()) ? "" : date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function isCity(entry) {
    return Boolean(
      entry &&
        typeof entry.city === "string" &&
        typeof entry.country === "string" &&
        typeof entry.count === "number" &&
        typeof entry.latitude === "number" &&
        typeof entry.longitude === "number" &&
        Math.abs(entry.latitude) <= 90 &&
        Math.abs(entry.longitude) <= 180
    );
  }

  var mapReady = false;
  var pending = null;
  var map = null;

  function emptyCollection() {
    return { type: "FeatureCollection", features: [] };
  }

  function onMapLoad() {
    map.addSource("cities", { type: "geojson", data: emptyCollection() });
    map.addLayer({
      id: "city-halo",
      type: "circle",
      source: "cities",
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["get", "count"], 3, 12, 50, 28],
        "circle-color": "#58b77a",
        "circle-opacity": 0.25,
        "circle-blur": 0.6
      }
    });
    map.addLayer({
      id: "city-dot",
      type: "circle",
      source: "cities",
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["get", "count"], 3, 5, 50, 11],
        "circle-color": "#7fd29a",
        "circle-stroke-color": "#0d1410",
        "circle-stroke-width": 1.5
      }
    });
    map.on("mouseenter", "city-dot", function () {
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", "city-dot", function () {
      map.getCanvas().style.cursor = "";
    });
    map.on("click", "city-dot", function (event) {
      var feature = event.features && event.features[0];
      if (!feature) {
        return;
      }
      var box = document.createElement("div");
      box.className = "popup";
      var title = document.createElement("strong");
      title.textContent = String(feature.properties.city);
      var detail = document.createElement("span");
      var count = Number(feature.properties.count);
      detail.textContent = countryName(String(feature.properties.country)) + ", " + listenersLabel(count);
      box.appendChild(title);
      box.appendChild(detail);
      new window.maplibregl.Popup({ offset: 12 })
        .setLngLat(feature.geometry.coordinates.slice())
        .setDOMContent(box)
        .addTo(map);
    });
    mapReady = true;
    if (pending !== null) {
      applyCities(pending);
      pending = null;
    }
  }

  function applyCities(data) {
    if (map === null) {
      return;
    }
    if (!mapReady) {
      pending = data;
      return;
    }
    var cities = Array.isArray(data.cities) ? data.cities : [];
    var features = cities.filter(isCity).map(function (city) {
      return {
        type: "Feature",
        geometry: { type: "Point", coordinates: [city.longitude, city.latitude] },
        properties: { city: city.city, country: city.country, count: city.count }
      };
    });
    map.getSource("cities").setData({ type: "FeatureCollection", features: features });
  }

  function render(data) {
    var count =
      typeof data.activeListeners === "number" && data.activeListeners >= 0 ? Math.floor(data.activeListeners) : 0;
    headline.textContent = headlineFor(count);

    var top = data.topCity;
    if (top && typeof top.city === "string") {
      topCityLine.textContent = top.city + ", " + countryName(top.country) + " (" + listenersLabel(top.count) + ")";
    } else {
      topCityLine.textContent = "No city has 3 or more listeners yet.";
    }

    countryList.textContent = "";
    var countries = Array.isArray(data.countries) ? data.countries.slice(0, TOP_COUNTRIES) : [];
    countries.forEach(function (entry) {
      var item = document.createElement("li");
      item.textContent = countryName(entry.country) + ": " + entry.count;
      countryList.appendChild(item);
    });

    applyCities(data);
  }

  var timer = null;

  function schedule(delay) {
    if (timer !== null) {
      window.clearTimeout(timer);
    }
    timer = window.setTimeout(function () {
      timer = null;
      refresh();
    }, delay);
  }

  function refresh() {
    // A hidden tab stops polling; becoming visible again restarts the cycle.
    if (document.visibilityState === "hidden") {
      return;
    }
    fetch(ENDPOINT, { headers: { accept: "application/json" }, cache: "no-store" })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("HTTP " + response.status);
        }
        return response.json();
      })
      .then(function (data) {
        render(data);
        var when = timeLabel(data.updatedAt);
        statusLine.textContent = (when ? "Updated " + when + ". " : "") + "Refreshes about every 30 seconds.";
        schedule(POLL_MS);
      })
      .catch(function () {
        statusLine.textContent = "Live counts are unavailable right now. Retrying shortly.";
        schedule(RETRY_MS);
      });
  }

  if (typeof window.maplibregl !== "undefined") {
    map = new window.maplibregl.Map({
      container: "map",
      style: STYLE_URL,
      center: [12, 28],
      zoom: 1.6,
      attributionControl: { compact: true }
    });
    map.addControl(new window.maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.on("load", onMapLoad);
  } else {
    document.getElementById("map").textContent = "The map could not load. The live count still updates.";
  }

  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") {
      schedule(0);
    }
  });

  refresh();
})();
