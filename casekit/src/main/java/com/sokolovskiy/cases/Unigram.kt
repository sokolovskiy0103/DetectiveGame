package com.sokolovskiy.cases

import java.io.File
import java.text.Normalizer

/**
 * SentencePiece-Unigram tokenizer over the compact vocab files produced by eval/export_models.py.
 *
 * File format: first line `#unk=3 cls=1 sep=2 pad=0 pair=0`, then one `piece<TAB>score` per token,
 * line number = token id. Pure logic, unit-tested against the Python reference.
 */
class Unigram(lines: List<String>) {

    val unk: Int
    val cls: Int
    val sep: Int
    val pad: Int

    private val ids = HashMap<String, Int>()
    private val scores: DoubleArray
    private val maxLen: Int
    private val unkScore: Double

    init {
        val header = lines[0].removePrefix("#").trim().split(' ').associate {
            val (k, v) = it.split('=')
            k to v.toInt()
        }
        unk = header.getValue("unk"); cls = header.getValue("cls")
        sep = header.getValue("sep"); pad = header.getValue("pad")
        val body = lines.drop(1).filter { it.isNotEmpty() }
        scores = DoubleArray(body.size)
        var longest = 1
        body.forEachIndexed { i, line ->
            val tab = line.lastIndexOf('\t')
            val piece = line.substring(0, tab)
            ids[piece] = i
            scores[i] = line.substring(tab + 1).toDouble()
            longest = maxOf(longest, piece.codePointCount(0, piece.length))
        }
        maxLen = longest
        unkScore = (scores.minOrNull() ?: 0.0) - 10.0
    }

    private fun isSpecial(id: Int) = id == unk || id == cls || id == sep || id == pad

    /** Best segmentation of one word that already carries the leading ▁. */
    private fun word(w: String): List<Int> {
        val cps = w.codePoints().toArray()
        val n = cps.size
        val best = DoubleArray(n + 1) { -1e18 }
        val backStart = IntArray(n + 1)
        val backId = IntArray(n + 1)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] <= -1e17) continue
            for (j in i + 1..minOf(n, i + maxLen)) {
                val id = ids[String(cps, i, j - i)] ?: continue
                if (isSpecial(id)) continue
                val sc = best[i] + scores[id]
                if (sc > best[j]) { best[j] = sc; backStart[j] = i; backId[j] = id }
            }
            val sc = best[i] + unkScore
            if (sc > best[i + 1]) { best[i + 1] = sc; backStart[i + 1] = i; backId[i + 1] = unk }
        }
        val out = ArrayList<Int>()
        var p = n
        while (p > 0) { out.add(backId[p]); p = backStart[p] }
        out.reverse()
        val fused = ArrayList<Int>(out.size)
        for (t in out) if (!(t == unk && fused.isNotEmpty() && fused.last() == unk)) fused.add(t)
        return fused
    }

    /** Token ids for [text] without special tokens, at most [maxTokens]. */
    fun encode(text: String, maxTokens: Int = 32): List<Int> {
        val out = ArrayList<Int>()
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        for (w in norm.split(Regex("\\s+"))) {
            if (w.isEmpty()) continue
            out += word("▁$w")
        }
        return if (out.size > maxTokens) out.subList(0, maxTokens).toList() else out
    }

    companion object {
        fun load(file: File) = Unigram(file.readLines(Charsets.UTF_8))
    }
}
