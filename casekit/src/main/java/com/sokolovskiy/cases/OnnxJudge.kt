package com.sokolovskiy.cases

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Paraphrase judge on a small multilingual sentence encoder (paraphrase-multilingual-mpnet-base-v2, int8 ONNX, ~190 MB):
 * a phrase counts for a rule when it is clearly closer to the rule's examples than to its counter-examples.
 * Runs in-process on the phone; the model files come from [MODEL_FILES] in a folder ([load]).
 * Thresholds were tuned on `cases/stairs/semantic_cases.json` (see `SemanticTuning` in dev tests).
 */
class OnnxJudge private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val vocab: Unigram,
) : SemanticJudge {

    private val cache = ConcurrentHashMap<String, FloatArray>()

    private fun embed(texts: List<String>): List<FloatArray> {
        val missing = texts.distinct().filter { !cache.containsKey(it) }
        for (chunk in missing.chunked(BATCH)) {
            val seqs = chunk.map { listOf(vocab.cls) + vocab.encode(it, MAX_TOKENS) + listOf(vocab.sep) }
            val len = seqs.maxOf { it.size }
            val ids = Array(seqs.size) { r -> LongArray(len) { c -> (seqs[r].getOrNull(c) ?: vocab.pad).toLong() } }
            val mask = Array(seqs.size) { r -> LongArray(len) { c -> if (c < seqs[r].size) 1L else 0L } }
            OnnxTensor.createTensor(env, ids).use { t1 ->
                OnnxTensor.createTensor(env, mask).use { t2 ->
                    session.run(mapOf("input_ids" to t1, "attention_mask" to t2)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val out = r[0].value as Array<FloatArray>
                        chunk.forEachIndexed { i, t -> cache[t] = out[i] }
                    }
                }
            }
        }
        return texts.map { cache.getValue(it) }
    }

    /** Highest cosine similarity of [text] to any of [phrases] (the model outputs unit vectors). */
    fun similarity(text: String, phrases: List<String>): Double {
        val v = embed(listOf(text) + phrases)
        val q = v[0]
        return (1 until v.size).maxOf { i -> q.indices.sumOf { (q[it] * v[i][it]).toDouble() } }
    }

    override fun decide(text: String, examples: List<String>, counters: List<String>): Boolean? {
        if (text.isBlank() || examples.isEmpty()) return null
        val rule = similarity(text, examples)
        val counter = if (counters.isEmpty()) 0.0 else similarity(text, counters)
        return rule >= FLOOR && rule - counter >= MARGIN
    }

    fun close() { session.close() }

    companion object {
        val MODEL_FILES = setOf("embed.onnx", "embed.vocab")
        fun present(dir: File) = MODEL_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }

        // tuned on cases/stairs/semantic_cases.json
        const val FLOOR = 0.55
        const val MARGIN = 0.075
        private const val BATCH = 32
        private const val MAX_TOKENS = 64

        fun load(dir: File, threads: Int = 4): OnnxJudge {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(
                File(dir, "embed.onnx").absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) },
            )
            return OnnxJudge(env, session, Unigram.load(File(dir, "embed.vocab")))
        }
    }
}

/** What a [CaseModule] needs from whoever provides the paraphrase model: the judge once ready, and host controls. */
interface SemanticHost {
    val judge: SemanticJudge?

    /** For the host's screen: `{ enabled, state: OFF|DOWNLOADING|LOADING|READY|ERROR, progress, message, sizeMb }`. */
    fun status(): org.json.JSONObject

    fun setEnabled(on: Boolean)
}
