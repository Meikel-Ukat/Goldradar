package com.simo.goldradar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val complete: Boolean)

/** side = +1: level above price (buy stops / sellers' SL sit above). side = -1: below (sell stops / buyers' SL). */
data class Level(val name: String, val price: Double, val side: Int)

data class Cluster(val price: Double, val pct: Double)

data class BookView(
    val price: Double,
    val time: Long,
    val buyStops: List<Cluster>,   // above price, buy orders  -> SL of sellers + breakout buys
    val sellLimits: List<Cluster>, // above price, sell orders -> TP of buyers + sell limits
    val sellStops: List<Cluster>,  // below price, sell orders -> SL of buyers + breakout sells
    val buyLimits: List<Cluster>   // below price, buy orders  -> TP of sellers + buy limits
)

data class Sentiment(val longPct: Double, val shortPct: Double, val time: Long)

data class Cot(val date: String, val net: Long, val change: Long)

class Instr(
    val name: String,
    val oanda: String,
    val yahoo: String,
    val binance: String?,
    val roundStep: Double,
    val cotCode: String,
    val digits: Int
)

object Market {

    val GOLD = Instr("GOLD", "XAU_USD", "GC=F", "PAXGUSDT", 50.0, "088691", 2)
    val WTI = Instr("WTI", "WTICO_USD", "CL=F", null, 1.0, "067651", 2)
    val all = listOf(GOLD, WTI)

    private val hosts = listOf("https://api-fxpractice.oanda.com", "https://api-fxtrade.oanda.com")
    @Volatile private var hostIdx = 0

    fun fmt(i: Instr, v: Double) = String.format(Locale.US, "%.${i.digits}f", v)
    fun hm(t: Long): String = if (t <= 0) "--" else SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

    // ---------------- OANDA (needs free demo token) ----------------
    private fun oandaGet(host: String, path: String, token: String): Pair<Int, String?> = try {
        val c = URL("$host/v3/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept-Datetime-Format", "UNIX")
        try {
            val code = c.responseCode
            code to (if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        -1 to null
    }

    /** Tries demo host, then live host if the token is a live one. */
    private fun oanda(path: String, token: String): JSONObject? {
        val (code, body) = oandaGet(hosts[hostIdx], path, token)
        if (body != null) return runCatching { JSONObject(body) }.getOrNull()
        if (code == 401) {
            val other = 1 - hostIdx
            val (c2, b2) = oandaGet(hosts[other], path, token)
            if (b2 != null) {
                hostIdx = other
                return runCatching { JSONObject(b2) }.getOrNull()
            }
            if (c2 == 401) lastOandaError = "Token ghalat (401)"
        } else if (code > 0) {
            lastOandaError = "OANDA error $code"
        }
        return null
    }

    @Volatile var lastOandaError = ""

    private fun unix(s: String?): Long = s?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L

    fun book(i: Instr, token: String): BookView? {
        val o = oanda("instruments/${i.oanda}/orderBook", token)?.optJSONObject("orderBook") ?: return null
        val p = o.optString("price").toDoubleOrNull() ?: return null
        val b = o.optJSONArray("buckets") ?: return null
        val bs = ArrayList<Cluster>()
        val sl = ArrayList<Cluster>()
        val ss = ArrayList<Cluster>()
        val bl = ArrayList<Cluster>()
        for (k in 0 until b.length()) {
            val x = b.getJSONObject(k)
            val pr = x.optString("price").toDoubleOrNull() ?: continue
            if (abs(pr - p) / p > 0.03) continue
            val lp = x.optString("longCountPercent").toDoubleOrNull() ?: 0.0
            val sp = x.optString("shortCountPercent").toDoubleOrNull() ?: 0.0
            if (pr > p) {
                bs.add(Cluster(pr, lp)); sl.add(Cluster(pr, sp))
            } else if (pr < p) {
                ss.add(Cluster(pr, sp)); bl.add(Cluster(pr, lp))
            }
        }
        fun top(l: List<Cluster>) = l.filter { it.pct > 0 }.sortedByDescending { it.pct }.take(3)
        return BookView(p, unix(o.optString("time")), top(bs), top(sl), top(ss), top(bl))
    }

    fun sentiment(i: Instr, token: String): Sentiment? {
        val o = oanda("instruments/${i.oanda}/positionBook", token)?.optJSONObject("positionBook") ?: return null
        val b = o.optJSONArray("buckets") ?: return null
        var l = 0.0
        var s = 0.0
        for (k in 0 until b.length()) {
            val x = b.getJSONObject(k)
            l += x.optString("longCountPercent").toDoubleOrNull() ?: 0.0
            s += x.optString("shortCountPercent").toDoubleOrNull() ?: 0.0
        }
        val t = l + s
        if (t <= 0) return null
        return Sentiment(l / t * 100, s / t * 100, unix(o.optString("time")))
    }

    // ---------------- CFTC COT (free, weekly) ----------------
    fun cot(i: Instr): Cot? {
        val url = "https://publicreporting.cftc.gov/resource/72hh-3qpy.json?cftc_contract_market_code=${i.cotCode}" +
            "&\$order=report_date_as_yyyy_mm_dd%20DESC&\$limit=2"
        val s = Sources.http(url) ?: return null
        return runCatching {
            val a = JSONArray(s)
            fun net(o: JSONObject): Long =
                o.getString("m_money_positions_long_all").toDouble().toLong() -
                    o.getString("m_money_positions_short_all").toDouble().toLong()
            val x = a.getJSONObject(0)
            val n = net(x)
            val prev = if (a.length() > 1) net(a.getJSONObject(1)) else n
            Cot(x.optString("report_date_as_yyyy_mm_dd").take(10), n, n - prev)
        }.getOrNull()
    }

    // ---------------- Candles (OANDA -> Binance -> Yahoo) ----------------
    fun candles(i: Instr, token: String?): Pair<String, List<Candle>>? {
        if (!token.isNullOrBlank()) {
            val a = oanda("instruments/${i.oanda}/candles?granularity=M15&count=500&price=M", token)?.optJSONArray("candles")
            if (a != null && a.length() > 30) {
                val out = ArrayList<Candle>()
                for (k in 0 until a.length()) {
                    val x = a.getJSONObject(k)
                    val m = x.optJSONObject("mid") ?: continue
                    out.add(
                        Candle(
                            unix(x.optString("time")),
                            m.getString("o").toDouble(), m.getString("h").toDouble(),
                            m.getString("l").toDouble(), m.getString("c").toDouble(),
                            x.optBoolean("complete", true)
                        )
                    )
                }
                if (out.size > 30) return "OANDA" to out
            }
        }
        if (i.binance != null) {
            val s = Sources.http("https://api.binance.com/api/v3/klines?symbol=${i.binance}&interval=15m&limit=500")
            val out = s?.let {
                runCatching {
                    val a = JSONArray(it)
                    val now = System.currentTimeMillis()
                    (0 until a.length()).map { k ->
                        val r = a.getJSONArray(k)
                        Candle(
                            r.getLong(0), r.getString(1).toDouble(), r.getString(2).toDouble(),
                            r.getString(3).toDouble(), r.getString(4).toDouble(), r.getLong(6) < now
                        )
                    }
                }.getOrNull()
            }
            if (out != null && out.size > 30) return "PAXG≈" to out
        }
        val s = Sources.http(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(i.yahoo, "UTF-8") + "?interval=15m&range=5d"
        ) ?: return null
        val out = runCatching {
            val r = JSONObject(s).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = r.getJSONArray("timestamp")
            val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val o = q.getJSONArray("open"); val h = q.getJSONArray("high")
            val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
            val list = ArrayList<Candle>()
            for (k in 0 until ts.length()) {
                if (o.isNull(k) || h.isNull(k) || l.isNull(k) || c.isNull(k)) continue
                list.add(Candle(ts.getLong(k) * 1000, o.getDouble(k), h.getDouble(k), l.getDouble(k), c.getDouble(k), k < ts.length() - 1))
            }
            list
        }.getOrNull()
        return if (out != null && out.size > 30) "Yahoo≈" to out else null
    }

    // ---------------- Liquidity levels (SMC) ----------------
    fun levels(i: Instr, cs: List<Candle>): List<Level> {
        if (cs.size < 30) return emptyList()
        val out = ArrayList<Level>()
        val day = 86_400_000L
        val hour = 3_600_000L
        val last = cs.last()
        val px = last.c
        val today = last.t / day * day
        // previous trading day (skip empty weekend days)
        var prevDay = today - day
        var prev = cs.filter { it.t >= prevDay && it.t < prevDay + day }
        var guard = 0
        while (prev.size < 8 && guard < 4) {
            prevDay -= day; guard++
            prev = cs.filter { it.t >= prevDay && it.t < prevDay + day }
        }
        if (prev.isNotEmpty()) {
            out.add(Level("PDH", prev.maxOf { it.h }, 1))
            out.add(Level("PDL", prev.minOf { it.l }, -1))
        }
        val asia = cs.filter { it.t >= today && it.t < today + 7 * hour }
        if (asia.size >= 8 && last.t >= today + 7 * hour) {
            out.add(Level("Asia High", asia.maxOf { it.h }, 1))
            out.add(Level("Asia Low", asia.minOf { it.l }, -1))
        }
        // untouched swing highs / lows (fractal 3) in last ~2 days
        val n = cs.size
        val start = maxOf(3, n - 200)
        val sh = ArrayList<Double>()
        val sl = ArrayList<Double>()
        for (k in start until n - 3) {
            val c = cs[k]
            if ((1..3).all { cs[k - it].h < c.h && cs[k + it].h <= c.h } && (k + 1 until n).none { cs[it].h > c.h }) sh.add(c.h)
            if ((1..3).all { cs[k - it].l > c.l && cs[k + it].l >= c.l } && (k + 1 until n).none { cs[it].l < c.l }) sl.add(c.l)
        }
        val tol = px * 0.0006
        fun equals(list: List<Double>, side: Int): Set<Double> {
            val res = HashSet<Double>()
            val s = list.sorted()
            for (a in s.indices) for (b in a + 1 until s.size) {
                if (s[b] - s[a] <= tol) {
                    val v = if (side > 0) s[b] else s[a]
                    if (res.add(v)) out.add(Level(if (side > 0) "Equal Highs" else "Equal Lows", v, side))
                    res.add(s[a]); res.add(s[b])
                }
            }
            return res
        }
        val eqH = equals(sh, 1)
        val eqL = equals(sl, -1)
        sh.filter { it > px && it !in eqH }.sorted().take(2).forEach { out.add(Level("Swing High", it, 1)) }
        sl.filter { it < px && it !in eqL }.sortedDescending().take(2).forEach { out.add(Level("Swing Low", it, -1)) }
        // round numbers
        val up = ceil(px / i.roundStep) * i.roundStep
        val dn = floor(px / i.roundStep) * i.roundStep
        if (up != px) out.add(Level("Round", up, 1))
        if (dn != px) out.add(Level("Round", dn, -1))
        // merge levels that are almost the same price
        val merged = ArrayList<Level>()
        for (l in out.sortedBy { it.price }) {
            val m = merged.lastOrNull()
            if (m != null && abs(m.price - l.price) <= tol && m.side == l.side) {
                if (!m.name.contains(l.name)) merged[merged.size - 1] = m.copy(name = m.name + "+" + l.name)
            } else merged.add(l)
        }
        return merged
    }

    /** Last completed candle wicked through a level and closed back = liquidity sweep. */
    fun sweeps(i: Instr, cs: List<Candle>): List<Pair<Level, Candle>> {
        val idx = cs.indexOfLast { it.complete }
        if (idx < 40) return emptyList()
        val base = cs.subList(0, idx)
        val c = cs[idx]
        val res = ArrayList<Pair<Level, Candle>>()
        for (l in levels(i, base)) {
            if (l.name == "Round") continue
            if (l.side > 0 && c.h > l.price && c.c < l.price) res.add(l to c)
            if (l.side < 0 && c.l < l.price && c.c > l.price) res.add(l to c)
        }
        return res
    }
}
