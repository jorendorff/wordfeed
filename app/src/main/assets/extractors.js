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
    for (const el of container.querySelectorAll("p, li")) {
      if (!el.textContent.trim() && !el.querySelector("img")) el.remove();
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

  function esc(s) {
    return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  }

  function section(label, html) {
    return html && html.trim() ? `<h3>${esc(label)}</h3>${html}` : "";
  }

  /** Trims leading/trailing <br>s and whitespace from an HTML string. */
  function trimBreaks(html) {
    return html.replace(/^(\s|<br\s*\/?>)+|(\s|<br\s*\/?>)+$/gi, "");
  }

  /** Wraps inline-only HTML in a <p>. */
  function para(html) {
    html = trimBreaks(html);
    if (!html) return "";
    return /<(p|ul|ol|blockquote|table|figure|h\d|dl)\b/i.test(html) ? html : `<p>${html}</p>`;
  }

  function required(el, what) {
    if (!el) throw new Error(`Couldn't find ${what} on the page. The site may have changed.`);
    return el;
  }

  // OED: the full entry is for subscribers, so we show the homepage's
  // word-of-the-day box and link to the entry.
  extractors.oed = async function (ctx) {
    const doc = await ctx.fetchHtml(ctx.source.url);
    const box = required(doc.querySelector(".wotd"), "the word of the day");
    const a = required(box.querySelector("h3 a") || box.querySelector("h3"), "the word");
    const href = a.getAttribute("href") ? abs(a.getAttribute("href"), ctx.source.url) : "";
    return [{
      key: text(a),
      title: text(a),
      sub: text(box.querySelector(".wotdPos")),
      body: para(sanitize(box.querySelector(".wotdDef") || [], ctx.source.url)),
      links: [{ label: "Open in the OED", href }],
    }];
  };

  // A.Word.A.Day: the "today" page, laid out as labeled sections.
  extractors.awad = async function (ctx) {
    const url = ctx.source.url;
    const doc = await ctx.fetchHtml(url);
    const word = text(required(doc.querySelector("h3"), "the word"));

    const isLabel = (n) => n.nodeType === Node.ELEMENT_NODE && n.tagName === "DIV" &&
      !n.children.length && /^[A-Z][A-Z .']+:$/.test(text(n));
    const isStop = (n) => n.nodeType === Node.ELEMENT_NODE &&
      (n.matches("a[name=comments], #comments-root, script") ||
        !!n.querySelector("a[name=comments], #comments-root"));
    const sections = {};
    for (const label of [...doc.querySelectorAll("div")].filter(isLabel)) {
      const nodes = [];
      for (let n = label.nextSibling; n && !isLabel(n) && !isStop(n); n = n.nextSibling) nodes.push(n);
      sections[text(label).replace(/:$/, "")] = nodes;
    }

    let sub = "";
    let audio = "";
    for (const n of sections["PRONUNCIATION"] || []) {
      if (n.nodeType !== Node.ELEMENT_NODE) continue;
      const mp3 = n.matches("a[href$='.mp3']") ? n : n.querySelector("a[href$='.mp3']");
      if (mp3) audio = abs(mp3.getAttribute("href"), url);
      if (!sub) sub = text(n);
    }

    // The sidebar box: date, theme, and today's picture.
    const box = [...doc.querySelectorAll("div")].find((d) => /This week.s theme/.test(text(d)) &&
      ![...d.children].some((c) => c.tagName === "DIV" && /This week.s theme/.test(text(c))));
    const boxText = text(box);
    const theme = (/This week.s theme\s*(.*?)\s*This week.s words/.exec(boxText) || [])[1] || "";
    const date = calendarDate((/^(\w{3} \d{1,2}, \d{4})/.exec(boxText) || [])[1]);
    let figure = "";
    const img = box && [...box.querySelectorAll("img")].find((i) => !/magnify|sound-icon/.test(i.getAttribute("src")));
    if (img) {
      const link = img.closest("a");
      const big = link && /\.(jpe?g|png|gif|webp)$/i.test(link.getAttribute("href")) ? link.getAttribute("href") : "";
      const src = abs(big || img.getAttribute("src"), url);
      const captions = [];
      for (let n = (link || img).nextElementSibling; n && n.tagName === "DIV"; n = n.nextElementSibling) {
        captions.push(trimBreaks(sanitize(n.childNodes, url)));
      }
      figure = `<figure><img src="${esc(src)}" alt="${esc(img.getAttribute("alt") || word)}">` +
        (captions.length ? `<figcaption>${captions.join("<br>")}</figcaption>` : "") + `</figure>`;
    }

    const part = (name) => para(sanitize(sections[name] || [], url));
    const donate = [...doc.querySelectorAll("a")].find((a) => /^donate$/i.test(text(a)));
    return [{
      key: word,
      kicker: "A.Word.A.Day with Anu Garg",
      title: word,
      sub,
      date,
      audio,
      body: [
        theme ? section("This week’s theme", `<p>${esc(theme)}</p>`) : "",
        section("Meaning", part("MEANING")),
        section("Etymology", part("ETYMOLOGY")),
        section("Usage", part("USAGE")),
        figure,
        section("A thought for today", part("A THOUGHT FOR TODAY")),
      ].join(""),
      links: [
        { label: "Open", href: meta(doc, "og:url") || url },
        donate && { label: "Donate", href: abs(donate.getAttribute("href"), url) },
      ],
    }];
  };

  // Wordnik: the word-of-the-day page, minus the site chrome.
  extractors.wordnik = async function (ctx) {
    const url = ctx.source.url;
    const doc = await ctx.fetchHtml(url);
    const col = required(doc.querySelector("#wotd .content_column, #wotd"), "the word of the day");
    const h1 = required(col.querySelector("h1"), "the word");
    const word = text(h1);

    let defs = "";
    const guts = col.querySelector(".module-definitions .guts") || col.querySelector(".module-definitions");
    if (guts) {
      for (const el of guts.children) {
        if (el.matches("h3, .source")) defs += `<p class="src">${esc(text(el))}</p>`;
        else if (el.matches("ul, ol")) defs += `<ol>${sanitize(el.childNodes, url)}</ol>`;
      }
    }

    let examples = "";
    for (const li of col.querySelectorAll(".module-examples li, .examples li")) {
      const quote = text(li.querySelector(".text")) || text(li);
      const srcLink = li.querySelector(".source a");
      const cite = srcLink
        ? `<cite><a href="${esc(abs(srcLink.getAttribute("href"), url))}">${esc(text(srcLink))}</a></cite>`
        : (li.querySelector(".source") ? `<cite>${esc(text(li.querySelector(".source")))}</cite>` : "");
      examples += `<blockquote><p>${esc(quote)}</p>${cite}</blockquote>`;
    }

    const notes = [...col.querySelectorAll("p.note")].map((p) => text(p)).filter(Boolean);
    const adopt = doc.querySelector("a[href*='/adoptaword/']");
    const wordLink = h1.querySelector("a");
    return [{
      key: word,
      title: word,
      date: calendarDate(text(doc.querySelector(".wotd_banner .date"))),
      body: [
        section("Definitions", defs),
        section("Examples", examples),
        section("Note", notes.map((n) => `<p>${esc(n)}</p>`).join("")),
      ].join(""),
      links: [
        { label: "Open", href: wordLink ? abs(wordLink.getAttribute("href"), url) : url },
        adopt && { label: "Adopt this word", href: abs(adopt.getAttribute("href"), url) },
      ],
    }];
  };

  // Merriam-Webster: the RSS feed, restructured. The description's <p>s are
  // nested, which the HTML parser flattens into a simple sequence.
  extractors.mw = async function (ctx) {
    const xml = await ctx.fetchXml(ctx.source.url);
    return parseFeed(xml, ctx.source.url).map((item) => {
      const doc = parseHtml(item.html, item.link);
      let sub = "";
      let entryLink = "";
      const parts = [];
      for (const p of doc.querySelectorAll("p")) {
        const t = text(p);
        if (!t) continue;
        if (/Word of the Day for/i.test(t) && t.length < 120) continue;
        const see = p.querySelector("a");
        if (see && /^see the entry/i.test(text(see))) {
          entryLink = abs(see.getAttribute("href"), item.link);
          continue;
        }
        const strongs = p.querySelectorAll("strong");
        if (strongs.length && text(strongs[0]) === item.title && /\\/.test(t)) {
          // "abscond • \ab-SKAHND\ • verb"
          const pron = (/\\([^\\]+)\\/.exec(t) || [])[1] || "";
          const pos = text(p.querySelector("em"));
          sub = [pos, pron].filter(Boolean).join("  ·  ");
          continue;
        }
        if (strongs.length && text(strongs[0]).length >= t.length - 1 && /[:?]$/.test(t)) {
          parts.push(`<h3>${esc(t.replace(/:$/, ""))}</h3>`);
          continue;
        }
        const html = sanitize(p.childNodes, item.link);
        parts.push(/^\/\//.test(t)
          ? `<p class="ex">${trimBreaks(html.replace(/^\s*\/\/\s*/, ""))}</p>`
          : `<p>${trimBreaks(html)}</p>`);
      }
      return {
        key: item.key,
        title: item.title,
        sub,
        date: item.date,
        audio: item.audio,
        body: parts.join(""),
        links: [
          { label: "Open", href: item.link },
          entryLink && { label: "Dictionary entry", href: entryLink },
        ],
      };
    });
  };

  // Wiktionary: today's item from the featured feed.
  extractors.wiktionary = async function (ctx) {
    const xml = await ctx.fetchXml(ctx.source.url);
    const item = parseFeed(xml, ctx.source.url)[0];
    if (!item) return [];
    const base = "https://en.wiktionary.org/";
    const doc = parseHtml(item.html, base);
    const titleEl = doc.querySelector("#WOTD-rss-title");
    const word = text(titleEl) || item.title;
    let sub = "";
    const cell = titleEl && titleEl.closest("td");
    if (cell) {
      const copy = cell.cloneNode(true);
      for (const b of copy.querySelectorAll("b")) b.remove();
      const POS = { n: "noun", v: "verb", adj: "adjective", adv: "adverb", prep: "preposition",
        conj: "conjunction", interj: "interjection", pron: "pronoun", prop: "proper noun" };
      sub = text(copy).replace(/^(\w+)\b\.?/, (m, abbr) => POS[abbr] || m);
    }
    const mp3 = doc.querySelector("audio source[type^='audio/mpeg']");
    const wordLink = titleEl && titleEl.closest("a");
    return [{
      key: item.key,
      title: word,
      sub,
      date: item.date,
      audio: mp3 ? abs(mp3.getAttribute("src"), base) : "",
      body: sanitize(doc.querySelector("#WOTD-rss-description") || [], base),
      links: [
        { label: "Open", href: wordLink ? abs(wordLink.getAttribute("href"), base) : item.link },
      ],
    }];
  };

  // Dictionary.com: the word-of-the-day page lists several recent words.
  extractors.dictcom = async function (ctx) {
    const url = ctx.source.url;
    const doc = await ctx.fetchHtml(url);
    const entries = [...doc.querySelectorAll(".wotd-entry-wrapper")];
    if (!entries.length) required(null, "any words");
    return entries.map((e) => {
      const head = e.querySelector(".wotd-entry-headword");
      const word = text(head);
      const btn = e.querySelector("[data-audiosrc]");
      const audio = btn
        ? abs(btn.getAttribute("data-audiosrc"), (btn.getAttribute("data-audioorigin") || "https://audio.dictionary.com") + "/")
        : "";
      const pron = e.querySelector(".wotd-entry-phonetics");
      const pos = text(e.querySelector(".wotd-entry-pos"));
      const explanation = e.querySelector(".wotd-entry-explanation-section p");
      const example = e.querySelector(".wotd-entry-example");
      return {
        key: word,
        title: word,
        subHtml: [pos ? `<i>${esc(pos)}</i>` : "", pron ? sanitize(pron.childNodes, url) : ""]
          .filter(Boolean).join("  ·  "),
        date: calendarDate(text(e.querySelector(".wotd-entry-date"))),
        audio,
        body: [
          para(sanitize(e.querySelector(".wotd-entry-definition") || [], url)),
          section("Explanation", explanation ? para(sanitize(explanation.childNodes, url)) : ""),
          section("Example", example ? `<p class="ex">${sanitize(example.childNodes, url)}</p>` : ""),
        ].join(""),
        links: [{ label: "Open", href: head ? abs(head.getAttribute("href"), url) : url }],
      };
    });
  };

  return { h, parseHtml, text, abs, meta, sanitize, parseFeed, calendarDate, formatDate, extractors };
})();
