// Per-source extraction. Each extractor is an async function(ctx) returning a
// list of entries, newest first:
//
//   { key, title, kicker?, sub?, date?, body?, audio?, links?: [{label, href}] }
//
// `body` is HTML that has already been through WF.sanitize.
"use strict";

window.WF = (function () {
  // ---- helpers ------------------------------------------------------------

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
    for (const c of children) {
      if (c == null) continue;
      el.append(c instanceof Node ? c : String(c));
    }
    return el;
  }

  function parseHtml(text, baseUrl) {
    const doc = new DOMParser().parseFromString(text, "text/html");
    if (baseUrl && !doc.querySelector("base")) {
      const base = doc.createElement("base");
      base.href = baseUrl;
      doc.head.prepend(base);
    }
    return doc;
  }

  function text(el) {
    return el ? el.textContent.replace(/\s+/g, " ").trim() : "";
  }

  function abs(url, base) {
    if (!url) return "";
    try {
      const u = new URL(url.trim(), base);
      return u.protocol === "http:" || u.protocol === "https:" ? u.href : "";
    } catch (e) {
      return "";
    }
  }

  function meta(doc, name) {
    const el = doc.querySelector(`meta[property="${name}"], meta[name="${name}"]`);
    return el ? (el.getAttribute("content") || "").trim() : "";
  }

  const MONTHS = "jan feb mar apr may jun jul aug sep oct nov dec".split(" ");

  /**
   * The calendar date a source says, as local noon. A word of the day dated
   * "Mon, 12 Oct 2026 00:01:03 EDT" is the word for October 12, even though
   * that instant is still October 11 in Nashville.
   */
  function calendarDate(raw) {
    if (!raw) return null;
    let m = /(\d{1,2})\s+([A-Za-z]{3})[a-z]*\.?,?\s+(\d{4})/.exec(raw);
    if (m && MONTHS.includes(m[2].toLowerCase())) {
      return new Date(+m[3], MONTHS.indexOf(m[2].toLowerCase()), +m[1], 12);
    }
    m = /([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})/.exec(raw);
    if (m && MONTHS.includes(m[1].toLowerCase())) {
      return new Date(+m[3], MONTHS.indexOf(m[1].toLowerCase()), +m[2], 12);
    }
    m = /(\d{4})-(\d{2})-(\d{2})/.exec(raw);
    if (m) return new Date(+m[1], +m[2] - 1, +m[3], 12);
    const d = new Date(raw);
    return isNaN(d) ? null : d;
  }

  function formatDate(d, short) {
    const date = d instanceof Date ? d : new Date(d);
    if (isNaN(date)) return String(d);
    return date.toLocaleDateString(undefined, short
      ? { month: "short", day: "numeric" }
      : { weekday: "long", month: "long", day: "numeric", year: "numeric" });
  }

  // ---- sanitizing ---------------------------------------------------------

  const KEEP = new Set(("P BR B STRONG I EM U A UL OL LI BLOCKQUOTE H1 H2 H3 H4 H5 H6 " +
    "SUP SUB SMALL IMG FIGURE FIGCAPTION HR DL DT DD CITE Q ABBR TABLE TBODY THEAD " +
    "TR TD TH PRE CODE").split(" "));
  const DROP = new Set(("SCRIPT STYLE NOSCRIPT IFRAME FORM INPUT BUTTON SELECT TEXTAREA " +
    "NAV SVG CANVAS VIDEO OBJECT EMBED LINK META TEMPLATE DIALOG").split(" "));
  // Block-ish wrappers we unwrap but whose boundaries should stay paragraph breaks.
  const BLOCKY = new Set("DIV SECTION ARTICLE HEADER FOOTER MAIN ASIDE CENTER".split(" "));

  function cleanNode(el, base) {
    for (const child of [...el.childNodes]) {
      if (child.nodeType === Node.COMMENT_NODE) {
        child.remove();
        continue;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) continue;
      const tag = child.tagName.toUpperCase();
      if (DROP.has(tag) || child.hidden || child.getAttribute("aria-hidden") === "true") {
        child.remove();
        continue;
      }
      cleanNode(child, base);
      if (!KEEP.has(tag)) {
        if (BLOCKY.has(tag) && child.childNodes.length) {
          const p = document.createElement("p");
          p.append(...child.childNodes);
          // Don't wrap block content in a <p>; just unwrap it.
          if (p.querySelector("p, ul, ol, blockquote, h1, h2, h3, h4, table, figure, dl, hr")) {
            child.replaceWith(...p.childNodes);
          } else {
            child.replaceWith(p);
          }
        } else {
          child.replaceWith(...child.childNodes);
        }
        continue;
      }
      if (tag === "IMG") {
        const src = child.getAttribute("data-src") || child.getAttribute("src");
        const w = child.getAttribute("width");
        if (!abs(src, base) || w === "1" || w === "0") {
          child.remove();
          continue;
        }
        child.setAttribute("src", abs(src, base));
      }
      for (const attr of [...child.attributes]) {
        const n = attr.name.toLowerCase();
        if (tag === "A" && n === "href") {
          const href = abs(attr.value, base);
          if (href) child.setAttribute("href", href);
          else child.removeAttribute("href");
        } else if (tag === "IMG" && (n === "src" || n === "alt")) {
          // keep
        } else if ((tag === "TD" || tag === "TH") && (n === "colspan" || n === "rowspan")) {
          // keep
        } else {
          child.removeAttribute(attr.name);
        }
      }
    }
  }

  /** Sanitizes nodes (or an HTML string) into an HTML string safe to show. */
  function sanitize(input, base) {
    const container = document.createElement("div");
    if (typeof input === "string") {
      const doc = new DOMParser().parseFromString(input, "text/html");
      container.append(...[...doc.body.childNodes].map((n) => document.importNode(n, true)));
    } else {
      const nodes = input instanceof NodeList || Array.isArray(input) ? [...input] : [input];
      for (const n of nodes) if (n) container.append(document.importNode(n, true));
    }
    cleanNode(container, base);
    // Drop paragraphs left empty by the cleanup.
    for (const p of container.querySelectorAll("p")) {
      if (!p.textContent.trim() && !p.querySelector("img")) p.remove();
    }
    return container.innerHTML.trim();
  }

  // ---- feeds --------------------------------------------------------------

  function firstTag(el, names) {
    for (const name of names) {
      const found = el.getElementsByTagName(name)[0];
      if (found) return found;
    }
    return null;
  }

  function contentHtml(el) {
    if (!el) return "";
    // Atom type="xhtml" puts markup inline; everything else is escaped HTML.
    if (el.getAttribute("type") === "xhtml") {
      return [...el.childNodes].map((n) => new XMLSerializer().serializeToString(n)).join("");
    }
    return el.textContent;
  }

  /** Parses RSS 2.0 / RSS 1.0 / Atom into raw items, newest first. */
  function parseFeed(xml, base) {
    let nodes = [...xml.getElementsByTagName("item")];
    if (!nodes.length) nodes = [...xml.getElementsByTagName("entry")];
    const items = nodes.map((n) => {
      let link = "";
      for (const l of n.getElementsByTagName("link")) {
        const href = l.getAttribute("href");
        const rel = l.getAttribute("rel");
        if (href && (!rel || rel === "alternate")) { link = href; break; }
        if (!href && l.textContent.trim()) { link = l.textContent.trim(); break; }
      }
      let audio = "";
      for (const enc of n.getElementsByTagName("enclosure")) {
        const url = enc.getAttribute("url");
        if (url && /^audio|mpeg/.test(enc.getAttribute("type") || "")) audio = url;
      }
      const date = calendarDate(text(firstTag(n, ["pubDate", "published", "updated", "dc:date"])));
      const html = contentHtml(firstTag(n, ["content:encoded", "content"])) ||
        contentHtml(firstTag(n, ["description", "summary"]));
      const title = text(parseHtml(text(firstTag(n, ["title"]))).body) ;
      const guid = text(firstTag(n, ["guid", "id"])) || link || title;
      return {
        key: guid,
        title: title || "(untitled)",
        date: date && !isNaN(date) ? date : null,
        html,
        link: abs(link, base),
        audio: abs(audio, base),
      };
    });
    if (items.every((i) => i.date)) items.sort((a, b) => b.date - a.date);
    return items;
  }

  // ---- extractors ---------------------------------------------------------

  const extractors = {};

  /** Any RSS or Atom feed. */
  extractors.rss = async function (ctx) {
    const xml = await ctx.fetchXml(ctx.source.url);
    return parseFeed(xml, ctx.source.url).map((i) => ({
      key: i.key,
      title: i.title,
      date: i.date,
      body: sanitize(i.html, i.link || ctx.source.url),
      audio: i.audio,
      links: [{ label: "Open", href: i.link }],
    }));
  };

  /** Fallback for a web page: title plus the page's main content. */
  function genericPage(doc, url) {
    const main = doc.querySelector("main, article, [role=main]") || doc.body;
    for (const junk of main.querySelectorAll("header, footer, nav, aside")) junk.remove();
    const title = meta(doc, "og:title") || text(doc.querySelector("h1")) || text(doc.querySelector("title"));
    return {
      key: url + "#" + title,
      title,
      body: sanitize(main.childNodes, url),
      links: [{ label: "Open", href: url }],
    };
  }

  extractors.page = async function (ctx) {
    const doc = await ctx.fetchHtml(ctx.source.url);
    return [genericPage(doc, ctx.source.url)];
  };

  extractors.awad = extractors.page;
  extractors.wordnik = extractors.page;
  extractors.dictcom = extractors.page;
  extractors.mw = extractors.rss;

  extractors.wiktionary = async function (ctx) {
    const items = await extractors.rss(ctx);
    return items.slice(0, 1);
  };

  extractors.oed = async function (ctx) {
    const doc = await ctx.fetchHtml(ctx.source.url);
    // Find the "Word of the day" block and the entry it links to.
    const heading = [...doc.querySelectorAll("h1, h2, h3, h4, p, span, div")]
      .find((el) => /^word of the day$/i.test(text(el)));
    console.log("oed heading:", heading && heading.outerHTML.slice(0, 300));
    let block = heading;
    while (block && !block.querySelector("a[href*='/dictionary/']")) block = block.parentElement;
    const link = block && block.querySelector("a[href*='/dictionary/']");
    console.log("oed link:", link && link.href);
    if (!link) throw new Error("Couldn't find the OED word of the day.");
    const entry = await ctx.fetchHtml(link.href);
    return [genericPage(entry, link.href)];
  };

  return { h, parseHtml, text, abs, meta, sanitize, parseFeed, calendarDate, formatDate, extractors };
})();
