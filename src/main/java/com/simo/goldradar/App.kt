package com.simo.goldradar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Html
import android.util.Xml
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

data class Feed(val name: String, val url: String, val everySec: Long)

data class NewsItem(
    val source: String,
    val title: String,
    val summary: String,
    val link: String,
    val time: Long
)

data class CalEvent(
    val title: String,
    val country: String,
    val time: Long,
    val impact: String,
    val forecast: String,
    val previous: String
)

/**
 * All sources are free and need NO API key.
 * Add / remove feeds here.
 */
object Sources {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

    private fun gnews(q: String): String =
        "https://news.google.com/rss/search?q=" +
            URLEncoder.encode("$q when:1h", "UTF-8") +
            "&hl=en-US&gl=US&ceid=US:en"

    val feeds = listOf(
        // FAST: Trump Truth Social posts (independent archive, checks every few minutes)
        Feed("TRUMP", "https://www.trumpstruth.org/feed", 20),
        // FAST: squawk-style market headlines
        Feed("FinancialJuice", "https://www.financialjuice.com/feed.ashx?xy=rss", 20),
        Feed("FXStreet", "https://www.fxstreet.com/rss/news", 45),
        Feed("Investing", "https://www.investing.com/rss/news_11.rss", 60),
        Feed("OilPrice", "https://oilprice.com/rss/main", 90),
        // Google News searches (last hour only)
        Feed("GN-Trump", gnews("Trump tariffs OR Trump Iran OR Trump oil OR Trump Fed OR Trump sanctions"), 40),
        Feed("GN-Gold", gnews("gold price OR bullion OR XAUUSD"), 60),
        Feed("GN-Oil", gnews("crude oil OR OPEC OR WTI OR Brent"), 60),
        Feed("GN-Fed", gnews("Federal Reserve OR CPI OR payrolls OR inflation data"), 60),
        Feed("GN-Geo", gnews("missile strike OR invasion OR ceasefire OR Strait of Hormuz"), 60)
    )

    // ---------------- HTTP ----------------
    fun http(url: String, timeout: Int = 8000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.useCaches = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    // ---------------- RSS / Atom ----------------
    fun fetchFeed(f: Feed): List<NewsItem>? {
        val xml = http(f.url) ?: return null
        return parseFeed(f.name, xml)
    }

    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim()

    fun parseFeed(src: String, xml: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml))
            var inItem = false
            var tag = ""
            val title = StringBuilder()
            val desc = StringBuilder()
            val link = StringBuilder()
            val date = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name.lowercase()
                        if (tag == "item" || tag == "entry") {
                            inItem = true
                            title.clear(); desc.clear(); link.clear(); date.clear()
                        } else if (inItem && tag == "link" && link.isEmpty()) {
                            p.getAttributeValue(null, "href")?.let { link.append(it) }
                        }
                    }
                    XmlPullParser.TEXT -> if (inItem) {
                        val t = p.text ?: ""
                        when (tag) {
                            "title" -> title.append(t)
                            "description", "summary", "content", "content:encoded" ->
                                if (desc.length < 2000) desc.append(t)
                            "link" -> link.append(t.trim())
                            "pubdate", "published", "updated", "dc:date" ->
                                if (date.isEmpty()) date.append(t.trim())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val n = p.name.lowercase()
                        if ((n == "item" || n == "entry") && inItem) {
                            inItem = false
                            val d = clean(desc.toString())
                            var t = clean(title.toString())
                            if (t.isBlank() || t.startsWith("[No Title]")) t = d.take(280)
                            if (t.isNotBlank()) {
                                out.add(NewsItem(src, t, d.take(600), link.toString().trim(), parseDate(date.toString())))
                            }
                        }
                        tag = ""
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {
            // malformed feed: return what we parsed so far
        }
        return out
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )

    fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        for (f in dateFormats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                if (f.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(t)!!.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    /** Normalized key so the same headline from 3 sources alerts only once. */
    fun key(title: String): String =
        title.lowercase().substringBefore(" - ").replace(Regex("[^a-z0-9]"), "").take(90)

    // ---------------- Prices (no key) ----------------
    private fun binance(sym: String): Double? =
        http("https://api.binance.com/api/v3/ticker/price?symbol=$sym", 5000)?.let {
            runCatching { JSONObject(it).getString("price").toDouble() }.getOrNull()
        }

    private fun yahoo(sym: String): Double? =
        http(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(sym, "UTF-8") + "?interval=1m&range=1d", 6000
        )?.let {
            runCatching {
                JSONObject(it).getJSONObject("chart").getJSONArray("result")
                    .getJSONObject(0).getJSONObject("meta").getDouble("regularMarketPrice")
            }.getOrNull()
        }

    /** PAXG on Binance tracks spot gold in real time 24/7; Yahoo futures as fallback. */
    fun gold(): Double? = binance("PAXGUSDT") ?: yahoo("GC=F")

    fun wti(): Double? = yahoo("CL=F")

    // ---------------- Economic calendar (ForexFactory weekly JSON) ----------------
    fun calendar(): List<CalEvent>? {
        val s = http("https://nfs.faireconomy.media/ff_calendar_thisweek.json") ?: return null
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                CalEvent(
                    o.optString("title"),
                    o.optString("country"),
                    parseDate(o.optString("date")),
                    o.optString("impact"),
                    o.optString("forecast"),
                    o.optString("previous")
                )
            }
        }.getOrNull()
    }
}

/**
 * Offline keyword engine. No AI API.
 * gold / oil: expected direction (+ bullish, - bearish)
 * w: how much the news matters (impact)
 */
class Rule(val tag: String, val gold: Double, val oil: Double, val w: Double, vararg words: String) {
    val rx = Regex(
        "\\b(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?:s|es|ed|d|ing)?\\b",
        RegexOption.IGNORE_CASE
    )
}

data class Signal(val gold: Double, val oil: Double, val strength: Double, val tags: List<String>) {
    val level: String
        get() = when {
            strength >= 3.0 -> "HIGH"
            strength >= 1.5 -> "MEDIUM"
            strength > 0 -> "LOW"
            else -> "NONE"
        }

    private fun arrow(v: Double) = when { v >= 0.5 -> "▲"; v <= -0.5 -> "▼"; else -> "•" }
    private fun word(v: Double) = when { v >= 0.5 -> "up"; v <= -0.5 -> "down"; else -> "neutral" }

    fun headline() = "GOLD ${arrow(gold)}  |  WTI ${arrow(oil)}  —  $level"
    fun spoken() = "Gold ${word(gold)}, oil ${word(oil)}."
}

object Analyzer {

    private val rules = listOf(
        // --- Geopolitics ---
        Rule("War", 1.5, 1.0, 2.0, "war", "invasion", "invade", "missile", "airstrike", "air strike",
            "drone strike", "military strike", "bombing", "attacked", "troops", "escalation", "retaliation"),
        Rule("Nuclear", 1.5, 0.5, 2.0, "nuclear"),
        Rule("Iran", 0.8, 1.2, 1.5, "iran", "tehran"),
        Rule("MiddleEast", 0.8, 0.8, 1.0, "israel", "gaza", "hezbollah", "houthi", "red sea", "saudi"),
        Rule("Hormuz", 1.0, 2.5, 3.0, "strait of hormuz", "hormuz"),
        Rule("Russia", 0.5, 0.8, 1.0, "russia", "ukraine", "putin", "kremlin"),
        Rule("Venezuela", 0.3, 0.8, 1.0, "venezuela", "maduro"),
        Rule("Peace", -1.2, -1.2, 2.0, "ceasefire", "cease-fire", "peace deal", "peace agreement", "truce", "peace talk"),

        // --- Fed / rates ---
        Rule("Fed dovish", 1.5, 0.3, 2.0, "rate cut", "cut rates", "cuts rates", "cutting rates", "dovish",
            "lower interest rates", "lower rates", "quantitative easing"),
        Rule("Fed hawkish", -1.5, -0.3, 2.0, "rate hike", "raise rates", "raises rates", "hawkish",
            "higher for longer", "hike rates"),
        Rule("Fed", 0.0, 0.0, 1.0, "fomc", "federal reserve", "fed chair", "fed governor", "powell", "fed minutes"),

        // --- US data ---
        Rule("Hot data", -1.0, 0.0, 1.5, "hotter than expected", "above expectations", "beats expectations",
            "stronger than expected", "above forecast"),
        Rule("Cold data", 1.0, 0.0, 1.5, "cooler than expected", "below expectations", "misses expectations",
            "weaker than expected", "below forecast"),
        Rule("Key data", 0.0, 0.0, 1.5, "cpi", "nonfarm", "non-farm", "payroll", "jobless claim", "pce",
            "gdp", "ism", "unemployment rate", "ppi", "retail sales"),

        // --- Dollar ---
        Rule("USD strong", -1.0, -0.3, 1.0, "dollar rallies", "dollar surges", "dollar jumps", "strong dollar", "dollar gains"),
        Rule("USD weak", 1.0, 0.3, 1.0, "dollar falls", "dollar slides", "weaker dollar", "dollar slumps", "dollar drops"),

        // --- Trade / Trump policy ---
        Rule("Tariffs", 1.0, -0.8, 2.0, "tariff", "trade war", "import duty", "duties on"),
        Rule("Trade deal", -0.8, 0.6, 1.5, "trade deal", "trade agreement"),
        Rule("Sanctions", 0.6, 1.0, 1.5, "sanction", "embargo", "oil ban"),
        Rule("Drill", 0.0, -1.0, 1.0, "drill baby", "lower oil prices", "oil prices down", "more oil"),
        Rule("Shutdown", 0.8, -0.2, 1.0, "government shutdown", "debt ceiling", "default"),

        // --- Gold specific ---
        Rule("CB buying", 1.0, 0.0, 1.0, "central bank gold", "gold reserves", "gold purchase", "pboc gold"),
        Rule("Safe haven", 1.0, 0.0, 1.0, "safe haven", "safe-haven", "risk-off", "flight to safety"),
        Rule("Recession", 0.8, -1.2, 1.5, "recession", "slowdown", "demand concern", "weak demand"),

        // --- Oil specific ---
        Rule("OPEC cut", 0.0, 2.0, 2.5, "output cut", "production cut", "cut output", "cut production",
            "supply cut", "opec+ cut"),
        Rule("OPEC raise", 0.0, -2.0, 2.5, "output increase", "raise output", "boost output",
            "increase production", "ramp up output", "output hike", "unwind cut"),
        Rule("OPEC", 0.0, 0.0, 1.5, "opec", "aramco"),
        Rule("Inv draw", 0.0, 1.2, 2.0, "inventory draw", "crude draw", "stocks fell", "inventories fell",
            "inventories decline", "drawdown"),
        Rule("Inv build", 0.0, -1.2, 2.0, "inventory build", "crude build", "stocks rose", "inventories rose",
            "inventories increase"),
        Rule("EIA/API", 0.0, 0.0, 1.5, "eia", "crude inventories", "api crude", "crude stocks"),
        Rule("Supply hit", 0.0, 1.5, 2.0, "pipeline", "refinery fire", "refinery outage", "outage", "disruption",
            "hurricane", "force majeure", "shut in", "shut-in"),
        Rule("SPR", 0.0, -1.0, 1.5, "strategic petroleum reserve", "spr release"),
        Rule("China", 0.3, 0.8, 1.0, "china stimulus", "pboc cut", "china demand"),

        // --- Relevance only ---
        Rule("Gold", 0.0, 0.0, 0.5, "gold", "xau", "bullion"),
        Rule("Oil", 0.0, 0.0, 0.5, "oil", "crude", "wti", "brent")
    )

    fun analyze(text: String): Signal {
        var g = 0.0
        var o = 0.0
        var w = 0.0
        val tags = ArrayList<String>()
        for (r in rules) {
            if (r.rx.containsMatchIn(text)) {
                g += r.gold
                o += r.oil
                w += r.w
                tags.add(r.tag)
            }
        }
        return Signal(g, o, w, tags)
    }
}

class RadarService : Service() {

    companion object {
        const val CH_FG = "radar_fg"
        const val CH_ALERT = "radar_alert"
        const val CH_QUIET = "radar_quiet"
        const val FG_ID = 1

        val log = CopyOnWriteArrayList<String>()
        @Volatile var running = false
        @Volatile var lastCheck = 0L
        @Volatile var goldPx = 0.0
        @Volatile var wtiPx = 0.0

        fun addLog(s: String) {
            log.add(0, s)
            while (log.size > 150) log.removeAt(log.size - 1)
        }

        fun hms(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))
    }

    private class PriceTrack(val name: String, val thresholdPct: Double) {
        val hist = ArrayDeque<Pair<Long, Double>>()
        var lastAlert = 0L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var nm: NotificationManager
    private var exec: ScheduledExecutorService? = null
    private var wake: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())
    private val seeded = Collections.synchronizedSet(HashSet<String>())
    private val calFired = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var calendar: List<CalEvent> = emptyList()
    private val goldTrack = PriceTrack("GOLD", 0.30)   // % move in ~3 min
    private val wtiTrack = PriceTrack("WTI", 0.60)
    private val ids = AtomicInteger(100)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("radar", Context.MODE_PRIVATE)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FG, "Radar status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Market alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
        )
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Trump posts (low impact)", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = fgNotification("Kaybda...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FG_ID, n)
        }
        if (running) return START_STICKY
        running = true

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goldradar:poll").apply { acquire() }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        val ex = Executors.newScheduledThreadPool(6)
        exec = ex
        for (f in Sources.feeds) {
            ex.scheduleWithFixedDelay({ safe { pollFeed(f) } }, 0, f.everySec, TimeUnit.SECONDS)
        }
        ex.scheduleWithFixedDelay({ safe { pollPrices() } }, 2, 15, TimeUnit.SECONDS)
        ex.scheduleWithFixedDelay({ safe { Sources.calendar()?.let { calendar = it } } }, 0, 30 * 60, TimeUnit.SECONDS)
        ex.scheduleWithFixedDelay({ safe { checkCalendar() } }, 10, 15, TimeUnit.SECONDS)
        ex.scheduleWithFixedDelay({ safe { updateFg() } }, 5, 20, TimeUnit.SECONDS)

        addLog("[${hms(System.currentTimeMillis())}] ▶ Radar bda — ${Sources.feeds.size} sources")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        exec?.shutdownNow()
        tts?.shutdown()
        try { wake?.release() } catch (_: Exception) {}
        addLog("[${hms(System.currentTimeMillis())}] ■ Radar wqef")
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    // ---------------- News ----------------
    private fun pollFeed(f: Feed) {
        val items = Sources.fetchFeed(f) ?: return
        lastCheck = System.currentTimeMillis()
        val firstRun = seeded.add(f.name)   // first fetch: remember everything, don't alert old news
        for (n in items) {
            val k = if (n.source == "TRUMP" && n.link.isNotBlank()) n.link else Sources.key(n.title)
            if (k.length < 8 || !seen.add(k)) continue
            trimSeen()
            if (firstRun) continue
            if (n.time > 0 && System.currentTimeMillis() - n.time > 30 * 60_000) continue
            handle(n)
        }
    }

    private fun trimSeen() {
        synchronized(seen) {
            val it = seen.iterator()
            while (seen.size > 4000 && it.hasNext()) { it.next(); it.remove() }
        }
    }

    private fun handle(n: NewsItem) {
        val s = Analyzer.analyze(n.title + " " + n.summary)
        val isTrump = n.source == "TRUMP"
        val min = if (prefs.getBoolean("onlyHigh", false)) 3.0 else 1.5
        val important = s.strength >= min
        if (!isTrump && !important) return
        val title = (if (isTrump) "🇺🇸 TRUMP  " else "") + s.headline()
        val body = n.title + "\n\n[" + n.source + "] " + s.tags.joinToString(", ")
        val spoken = (if (isTrump) "Trump post. " else "") + s.spoken() + " " + n.title.take(220)
        alert(title, body, n.link, loud = important, speak = spoken)
    }

    // ---------------- Prices ----------------
    private fun pollPrices() {
        Sources.gold()?.let { goldPx = it; checkMove(goldTrack, it) }
        Sources.wti()?.let { wtiPx = it; checkMove(wtiTrack, it) }
    }

    private fun checkMove(p: PriceTrack, px: Double) {
        val now = System.currentTimeMillis()
        p.hist.addLast(now to px)
        while (p.hist.isNotEmpty() && now - p.hist.first().first > 3 * 60_000) p.hist.removeFirst()
        val ref = p.hist.first().second
        if (ref <= 0) return
        val pct = (px - ref) / ref * 100.0
        if (abs(pct) >= p.thresholdPct && now - p.lastAlert > 10 * 60_000) {
            p.lastAlert = now
            val dir = if (pct > 0) "▲ PUMP" else "▼ DUMP"
            val txt = String.format(Locale.US, "%s %s %.2f%% f 3 d9ay9 → %.2f", p.name, dir, pct, px)
            alert(
                "⚡ ${p.name} MOVE", "$txt\nChi khabar khraj daba — chof l'news.", null, true,
                "${p.name} ${if (pct > 0) "spiking up" else "dropping fast"}, ${String.format(Locale.US, "%.1f", abs(pct))} percent."
            )
        }
    }

    // ---------------- Calendar ----------------
    private fun checkCalendar() {
        val now = System.currentTimeMillis()
        for (e in calendar) {
            if (e.time <= 0) continue
            val relevant = e.country == "USD" &&
                (e.impact == "High" || e.title.contains("Crude Oil Inventories", ignoreCase = true))
            if (!relevant) continue
            val d = e.time - now
            val info = "Forecast: ${e.forecast.ifBlank { "-" }}  |  Previous: ${e.previous.ifBlank { "-" }}"
            if (d in 0L..5 * 60_000L && calFired.add("pre" + e.title + e.time)) {
                alert("⏰ 5 MIN: ${e.title}", "$info\nWjjed rask — volatility ghadi tji.", null, true,
                    "Five minutes to ${e.title}.")
            }
            if (d in -60_000L..0L && calFired.add("now" + e.title + e.time)) {
                alert("🔴 DABA: ${e.title}", "$info\nL'khabar khraj — gold w WTI ghadi ithrrko.", null, true,
                    "${e.title} is out now.")
            }
        }
    }

    // ---------------- Notifications ----------------
    private fun alert(title: String, text: String, link: String?, loud: Boolean, speak: String?) {
        val id = ids.incrementAndGet()
        val b = Notification.Builder(this, if (loud) CH_ALERT else CH_QUIET)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text.substringBefore("\n"))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShowWhen(true)
        val target = if (!link.isNullOrBlank()) Intent(Intent.ACTION_VIEW, Uri.parse(link))
        else Intent(this, MainActivity::class.java)
        b.setContentIntent(
            PendingIntent.getActivity(this, id, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        nm.notify(id, b.build())
        addLog("[${hms(System.currentTimeMillis())}] $title\n$text")
        if (loud && speak != null && ttsReady && prefs.getBoolean("tts", true)) {
            tts?.speak(speak, TextToSpeech.QUEUE_ADD, null, "a$id")
        }
    }

    private fun fgNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_FG)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("Gold Radar khdam")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateFg() {
        val t = String.format(
            Locale.US, "Check: %s  •  GOLD %.2f  •  WTI %.2f",
            if (lastCheck > 0) hms(lastCheck) else "--", goldPx, wtiPx
        )
        nm.notify(FG_ID, fgNotification(t))
    }
}

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("radar", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        root.addView(TextView(this).apply {
            text = "GOLD / WTI RADAR"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F5C542"))
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        }
        root.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(Button(this).apply {
            text = "START"
            setOnClickListener {
                prefs.edit().putBoolean("on", true).apply()
                startForegroundService(Intent(this@MainActivity, RadarService::class.java))
            }
        }, lp)
        row.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                prefs.edit().putBoolean("on", false).apply()
                stopService(Intent(this@MainActivity, RadarService::class.java))
            }
        }, lp)
        root.addView(row)

        root.addView(CheckBox(this).apply {
            text = "🔊 Qra l'alerts b sout (squawk)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("tts", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("tts", c).apply() }
        })
        root.addView(CheckBox(this).apply {
            text = "Ghir HIGH impact (Trump posts dima kaywslo)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("onlyHigh", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("onlyHigh", c).apply() }
        })
        root.addView(Button(this).apply {
            text = "🔋 Khalli l'app tkhdem dima (batterie)"
            setOnClickListener { askBattery() }
        })

        logView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            status.text = "✅ Batterie: deja m-autorisé"
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        status.text = if (RadarService.running) {
            String.format(
                Locale.US, "🟢 KHDAM  •  check: %s\nGOLD %.2f   WTI %.2f",
                if (RadarService.lastCheck > 0) RadarService.hms(RadarService.lastCheck) else "--",
                RadarService.goldPx, RadarService.wtiPx
            )
        } else "🔴 Wa9ef — dghot START"
        logView.text = if (RadarService.log.isEmpty()) "Mazal ta alert..." else RadarService.log.joinToString("\n\n")
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }
}

/** Restart the radar after phone reboot if it was ON. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED &&
            c.getSharedPreferences("radar", Context.MODE_PRIVATE).getBoolean("on", false)
        ) {
            c.startForegroundService(Intent(c, RadarService::class.java))
        }
    }
}
