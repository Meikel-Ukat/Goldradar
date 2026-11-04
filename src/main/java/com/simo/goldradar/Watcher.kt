package com.simo.goldradar

import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Builds the "market map" (order book, sentiment, COT, liquidity) and fires alerts.
 * alert(title, text, speak)
 */
class Watcher(
    private val prefs: SharedPreferences,
    private val alert: (String, String, String) -> Unit
) {
    private class State {
        @Volatile var book: BookView? = null
        @Volatile var sent: Sentiment? = null
        @Volatile var cot: Cot? = null
        @Volatile var levels: List<Level> = emptyList()
        @Volatile var src = ""
        @Volatile var px = 0.0
        @Volatile var lastSweepCandle = 0L
    }

    private val st = Market.all.associateWith { State() }
    private val cooldown = ConcurrentHashMap<String, Long>()

    private fun token(): String = prefs.getString("oanda", "")?.trim() ?: ""

    private fun ready(key: String, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = cooldown[key] ?: 0L
        if (now - last < ms) return false
        cooldown[key] = now
        return true
    }

    // every 5 min (OANDA books update every 20 min)
    fun pollBooks() {
        val t = token()
        if (t.isBlank()) return
        for (i in Market.all) {
            val s = st.getValue(i)
            Market.book(i, t)?.let { s.book = it }
            Market.sentiment(i, t)?.let { s.sent = it }
        }
    }

    // every 6 h
    fun pollCot() {
        for (i in Market.all) Market.cot(i)?.let { st.getValue(i).cot = it }
    }

    // every 60 s
    fun pollLiquidity() {
        val t = token()
        for (i in Market.all) {
            val s = st.getValue(i)
            val (src, cs) = Market.candles(i, t) ?: continue
            s.src = src
            s.px = cs.last().c
            s.levels = Market.levels(i, cs)
            checkApproach(i, s)
            checkSweeps(i, s, cs)
            checkBook(i, s)
        }
        RadarService.marketText = render()
    }

    private fun checkApproach(i: Instr, s: State) {
        val near = s.px * 0.0005   // ~$2 on gold
        for (l in s.levels) {
            if (l.name == "Round" || l.name.startsWith("Swing")) continue
            if (abs(s.px - l.price) <= near && ready("near${i.name}${l.name}${l.price}", 2 * 3_600_000L)) {
                val what = if (l.side > 0) "stops dyal sellers (buy stops)" else "stops dyal buyers (sell stops)"
                alert(
                    "💧 ${i.name} 9rreb l ${l.name}",
                    "Prix ${Market.fmt(i, s.px)} → ${l.name} ${Market.fmt(i, l.price)}\nHna kaykounou $what. Rdd lbal l stop hunt.",
                    "${i.name} near ${l.name}."
                )
            }
        }
    }

    private fun checkSweeps(i: Instr, s: State, cs: List<Candle>) {
        val sw = Market.sweeps(i, cs)
        if (sw.isEmpty()) return
        val c = sw.first().second
        if (c.t == s.lastSweepCandle) return
        s.lastSweepCandle = c.t
        for ((l, _) in sw) {
            val bear = l.side > 0
            alert(
                "🧲 ${i.name} SWEEP ${l.name} ${if (bear) "▼" else "▲"}",
                "Chm3a M15 khrjat ${if (bear) "lfo9" else "lta7t"} ${l.name} ${Market.fmt(i, l.price)} o sddat ${Market.fmt(i, c.c)}.\n" +
                    (if (bear) "Khdaw stops dyal sellers → ymken reversal SELL." else "Khdaw stops dyal buyers → ymken reversal BUY."),
                "${i.name} swept ${l.name}. Possible ${if (bear) "sell" else "buy"} reversal."
            )
        }
    }

    private fun checkBook(i: Instr, s: State) {
        val b = s.book ?: return
        val near = s.px * 0.0008
        b.buyStops.firstOrNull()?.let {
            if (abs(s.px - it.price) <= near && ready("bs${i.name}${it.price}", 3_600_000L))
                alert("🎯 ${i.name}: zone kbira dyal BUY STOPS", "Prix 9rreb l ${Market.fmt(i, it.price)} (${pct(it.pct)} dyal ordres OANDA).\nIla tkhdaw: spike ▲ o men ba3d ymken ytrje3.", "${i.name} near a big buy stop cluster.")
        }
        b.sellStops.firstOrNull()?.let {
            if (abs(s.px - it.price) <= near && ready("ss${i.name}${it.price}", 3_600_000L))
                alert("🎯 ${i.name}: zone kbira dyal SELL STOPS", "Prix 9rreb l ${Market.fmt(i, it.price)} (${pct(it.pct)} dyal ordres OANDA).\nIla tkhdaw: dump ▼ o men ba3d ymken ytrje3.", "${i.name} near a big sell stop cluster.")
        }
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)

    private fun render(): String {
        val sb = StringBuilder()
        for (i in Market.all) {
            val s = st.getValue(i)
            sb.append("━━ ${i.name} ${if (s.px > 0) Market.fmt(i, s.px) else "--"}  [${s.src.ifBlank { "-" }}]\n")
            s.sent?.let {
                val contra = when {
                    it.longPct >= 65 -> "→ retail BUY bezzaf: souq ymil ▼"
                    it.shortPct >= 65 -> "→ retail SELL bezzaf: souq ymil ▲"
                    else -> "→ mtwazen"
                }
                sb.append(String.format(Locale.US, "👥 Retail: %.0f%% BUY / %.0f%% SELL %s (%s)\n", it.longPct, it.shortPct, contra, Market.hm(it.time)))
            }
            s.book?.let { b ->
                fun list(l: List<Cluster>) = if (l.isEmpty()) "-" else l.joinToString("  ") { "${Market.fmt(i, it.price)} (${pct(it.pct)})" }
                sb.append("⬆ Lfo9 — SL dyal sellers / buy stops: ${list(b.buyStops)}\n")
                sb.append("⬆ Lfo9 — TP dyal buyers / sell limits: ${list(b.sellLimits)}\n")
                sb.append("⬇ Lta7t — SL dyal buyers / sell stops: ${list(b.sellStops)}\n")
                sb.append("⬇ Lta7t — TP dyal sellers / buy limits: ${list(b.buyLimits)}\n")
            }
            if (s.levels.isNotEmpty()) {
                val above = s.levels.filter { it.price > s.px }.sortedBy { it.price }.take(4)
                val below = s.levels.filter { it.price < s.px }.sortedByDescending { it.price }.take(4)
                sb.append("💧 Liquidity lfo9: " + above.joinToString("  ") { "${it.name} ${Market.fmt(i, it.price)}" }.ifBlank { "-" } + "\n")
                sb.append("💧 Liquidity lta7t: " + below.joinToString("  ") { "${it.name} ${Market.fmt(i, it.price)}" }.ifBlank { "-" } + "\n")
            }
            s.cot?.let {
                val side = if (it.net >= 0) "LONG" else "SHORT"
                sb.append(String.format(Locale.US, "🏦 COT %s: Funds net %s %,d (%+,d f simana)\n", it.date, side, abs(it.net), it.change))
            }
            sb.append("\n")
        }
        if (token().isBlank()) sb.append("ℹ Zid OANDA token bach tchouf stops/TP o % buy/sell.\n")
        else if (Market.lastOandaError.isNotBlank()) sb.append("⚠ ${Market.lastOandaError}\n")
        return sb.toString().trim()
    }
}
