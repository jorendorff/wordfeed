package com.jorendorff.wordfeed

/**
 * A place to get words from.
 *
 * If [feedUrl] is set, we fetch it as RSS/Atom. If that fails (or there is no
 * feed), we fall back to showing [pageUrl] in a web view.
 */
data class Source(
    val id: String,
    val name: String,
    val shortName: String,
    val blurb: String,
    val feedUrl: String? = null,
    val pageUrl: String? = null,
    val defaultOn: Boolean = true,
    val custom: Boolean = false,
)

val BUILTIN_SOURCES = listOf(
    Source(
        id = "oed",
        name = "OED Word of the Day",
        shortName = "OED",
        blurb = "Oxford English Dictionary. Often obscure, obsolete or regional words.",
        feedUrl = "https://www.oed.com/rss/wordoftheday",
        pageUrl = "https://www.oed.com/",
    ),
    Source(
        id = "awad",
        name = "A.Word.A.Day",
        shortName = "AWAD",
        blurb = "Anu Garg, wordsmith.org, since 1994. Weekly themes.",
        feedUrl = "https://wordsmith.org/awad/rss1.xml",
        pageUrl = "https://wordsmith.org/words/today.html",
    ),
    Source(
        id = "awww",
        name = "A Way with Words",
        shortName = "Way with Words",
        blurb = "Martha Barnette and Grant Barrett. Radio show and blog; independent nonprofit.",
        feedUrl = "https://waywordradio.org/feed/",
        pageUrl = "https://waywordradio.org/",
    ),
    Source(
        id = "haggardhawks",
        name = "Haggard Hawks",
        shortName = "Haggard Hawks",
        blurb = "Paul Anthony Jones. Obscure words and etymology.",
        feedUrl = "https://www.haggardhawks.com/blog-feed.xml",
        pageUrl = "https://www.haggardhawks.com/words",
    ),
    Source(
        id = "wordnik",
        name = "Wordnik Word of the Day",
        shortName = "Wordnik",
        blurb = "Nonprofit dictionary of every English word.",
        pageUrl = "https://www.wordnik.com/word-of-the-day",
    ),
    Source(
        id = "mw",
        name = "Merriam-Webster Word of the Day",
        shortName = "M-W",
        blurb = "Merriam-Webster editors. Includes Peter Sokolowski's podcast audio.",
        feedUrl = "https://www.merriam-webster.com/wotd/feed/rss2",
        pageUrl = "https://www.merriam-webster.com/word-of-the-day",
    ),
    Source(
        id = "wiktionary",
        name = "Wiktionary Word of the Day",
        shortName = "Wiktionary",
        blurb = "Picked and written by Wiktionary volunteers.",
        feedUrl = "https://en.wiktionary.org/w/api.php?action=featuredfeed&feed=wotd&feedformat=rss",
        pageUrl = "https://en.wiktionary.org/wiki/Wiktionary:Word_of_the_day",
    ),
    Source(
        id = "dictcom",
        name = "Dictionary.com Word of the Day",
        shortName = "Dictionary.com",
        blurb = "Commercial, ad-supported. Common-ish words.",
        pageUrl = "https://www.dictionary.com/e/word-of-the-day/",
        defaultOn = false,
    ),
)
