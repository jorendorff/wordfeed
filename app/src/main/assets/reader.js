// The reader page. Runs inside an Android WebView (talking to Kotlin through
// the WordfeedNative bridge) or inside headless Chromium (tools/snap.mjs,
// which provides a stand-in bridge).
//
// Bridge contract (all arguments and results are strings):
//   WordfeedNative.config()        -> JSON {source, dark}
//   WordfeedNative.fetch(id, url)  -> starts a fetch; later the host calls
//                                     window.__wf.ready(id, ok)
//   WordfeedNative.take(id)        -> the response body (or error message)
//   WordfeedNative.rendered(key)   -> reports the newest entry's key ("" on failure)
"use strict";

(function () {
  const pending = new Map();
  // Ids are unique per page load, so a reply meant for a previous load of
  // this WebView can't be mistaken for one of ours.
  const idPrefix = Math.random().toString(36).slice(2) + ".";
  let nextId = 1;

  window.__wf = {
    ready(id, ok) {
      const p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      const body = WordfeedNative.take(id);
      if (ok) p.resolve(body);
      else p.reject(new Error(body || "fetch failed"));
    },
  };

  function fetchText(url) {
    return new Promise((resolve, reject) => {
      const id = idPrefix + nextId++;
      pending.set(id, { resolve, reject });
      WordfeedNative.fetch(id, url);
    });
  }

  const config = JSON.parse(WordfeedNative.config());
  const source = config.source;
  if (config.dark) document.documentElement.classList.add("dark");
  if (source.style) document.documentElement.classList.add("style-" + source.style);

  const ctx = {
    source,
    fetchText,
    async fetchHtml(url) {
      const text = await fetchText(url);
      return WF.parseHtml(text, url);
    },
    async fetchXml(url) {
      const text = await fetchText(url);
      const doc = new DOMParser().parseFromString(text, "application/xml");
      if (doc.querySelector("parsererror")) throw new Error("Not valid XML: " + url);
      return doc;
    },
  };

  const h = WF.h;

  function renderEntry(item) {
    const article = h("article", { class: "entry" });
    if (item.kicker) article.append(h("p", { class: "kicker" }, item.kicker));
    article.append(h("h1", { class: "headword" }, item.title));
    if (item.subHtml) {
      const sub = h("p", { class: "sub" });
      sub.innerHTML = item.subHtml;
      article.append(sub);
    } else if (item.sub) {
      article.append(h("p", { class: "sub" }, item.sub));
    }
    if (item.date) article.append(h("p", { class: "date" }, WF.formatDate(item.date)));
    if (item.audio) {
      article.append(h("audio", { controls: "", preload: "none", src: item.audio }));
    }
    if (item.body) article.append(withBody(item.body));
    const links = (item.links || []).filter((l) => l && l.href);
    if (links.length) {
      article.append(h("nav", { class: "actions" },
        ...links.map((l) => h("a", { href: l.href }, l.label))));
    }
    return article;
  }

  function withBody(html) {
    const div = h("div", { class: "body" });
    div.innerHTML = html;
    return div;
  }

  /** Earlier entries: one row each, linking out to the entry on its site. */
  function renderEarlier(items) {
    const section = h("section", { class: "earlier" }, h("h2", {}, "Earlier"));
    for (const item of items) {
      const href = item.href || ((item.links || []).find((l) => l && l.href) || {}).href;
      const row = h(href ? "a" : "div", href ? { class: "prev", href } : { class: "prev" },
        h("span", { class: "t" }, item.title),
        h("span", { class: "d" }, item.date ? WF.formatDate(item.date, true) : ""));
      section.append(row);
    }
    return section;
  }

  function renderError(err) {
    const home = source.home || source.url;
    return h("div", { class: "error" },
      h("p", {}, "Couldn't load " + source.name + "."),
      h("p", { class: "why" }, String(err && err.message || err)),
      h("nav", { class: "actions" }, h("a", { href: home }, "Open the site")));
  }

  async function main() {
    const root = document.getElementById("root");
    try {
      const extract = WF.extractors[source.extractor] || WF.extractors.rss;
      const items = await extract(ctx);
      if (!items || !items.length) throw new Error("Found nothing to show.");
      root.replaceChildren(renderEntry(items[0]));
      if (items.length > 1) root.append(renderEarlier(items.slice(1, 8)));
      WordfeedNative.rendered(items[0].key || items[0].title);
    } catch (err) {
      console.error(err && err.stack || err);
      root.replaceChildren(renderError(err));
      WordfeedNative.rendered("");
    }
  }

  main();
})();
