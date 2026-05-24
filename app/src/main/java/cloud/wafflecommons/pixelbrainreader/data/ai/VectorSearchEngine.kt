package cloud.wafflecommons.pixelbrainreader.data.ai

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Base64
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Resolved top-K result. [content] is ALWAYS plaintext at this point —
 * the engine just-in-time decrypts private chunks using the cached vault
 * password from [SecretManager]. If the vault is locked at query time
 * (no password cached), private hits are silently dropped from the result
 * so the rest of the pipeline never sees encrypted bytes by accident.
 */
data class SearchHit(
    val fileId: String,
    val content: String,
    val isPrivate: Boolean
)

/**
 * On-device multilingual sentence encoder for the local RAG pipeline.
 *
 * Model: `paraphrase-multilingual-MiniLM-L12-v2` exported to TFLite by
 * `scripts/convert_minilm.py`, with mean-pooling + L2-normalize baked
 * into the graph. Fixed input shape (1, 256) int32 for both
 * `input_ids` and `attention_mask`; output is a single (1, 384) FloatArray
 * of unit-length values (cosine similarity == dot product).
 *
 * Tokenizer: HuggingFace's unified `tokenizer.json` format, consumed via
 * DJL's [HuggingFaceTokenizer] (Rust-backed via the `tokenizer-native`
 * Android AAR). Configured to pad+truncate to 256 tokens at the source,
 * so the TFLite buffers are always full-shape.
 *
 * Replaced the prior MediaPipe TextEmbedder + USE-Lite (100-dim,
 * English-only word-averaging) path which gave near-uniform cosine
 * scores for any input. See `convert_minilm.py` for the conversion
 * pipeline and the multilingual probe that validates this swap.
 */
@Singleton
class VectorSearchEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingDao: EmbeddingDao,
    private val cryptoManager: CryptoManager,
    private val secretManager: SecretManager
) {
    private data class Engine(
        val interpreter: Interpreter,
        val tokenizer: HuggingFaceTokenizer,
        // Resolved at init by reading the TFLite graph's input tensor names.
        // The Python signature declares (input_ids, attention_mask), but the
        // TF→TFLite converter has been observed to re-emit inputs alphabetically
        // — silently swapping the two and producing near-uniform embeddings.
        // Resolving by NAME makes the engine robust to converter reorderings.
        val inputIdsIndex: Int,
        val attentionMaskIndex: Int,
    )

    @Volatile private var engine: Engine? = null
    @Volatile private var multilingualProbeDone = false
    @Volatile private var schemaCheckDone = false

    /**
     * Stage an asset (model or tokenizer) into `cacheDir` and return the
     * cached file. Re-copies when the cached size disagrees with the
     * bundled asset — without this, swapping the asset between installs
     * silently keeps the old model alive forever.
     */
    private fun setupAsset(name: String): java.io.File {
        val file = java.io.File(context.cacheDir, name)

        val assetSize: Long = try {
            context.assets.open(name).use { it.available().toLong() }
        } catch (e: Exception) {
            Log.w("Cortex", "Asset stat failed for $name: ${e.message}")
            -1L
        }

        if (file.exists()) {
            val cachedSize = file.length()
            val stale = cachedSize < 1024 || (assetSize > 0 && cachedSize != assetSize)
            if (stale) {
                Log.w(
                    "Cortex",
                    "Stale cached $name (cached=$cachedSize asset=$assetSize). Deleting and re-copying."
                )
                file.delete()
            }
        }

        if (!file.exists()) {
            Log.d("Cortex", "Copying $name to cache (size=$assetSize)…")
            context.assets.open(name).use { inputStream ->
                if (inputStream.available() < 1024) {
                    throw java.io.IOException("Asset $name is corrupted/empty (<1KB). Cannot copy.")
                }
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }

        if (file.length() < 1024) {
            file.delete()
            throw java.io.IOException("Asset copy failed for $name: resulting file too small.")
        }

        return file
    }

    private fun getEngineSafe(): Engine? {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }

            val modelFile = try {
                setupAsset(MODEL_FILE)
            } catch (e: Exception) {
                Log.e("Cortex", "Model asset setup failed: ${e.message}")
                return null
            }
            val tokFile = try {
                setupAsset(TOKENIZER_FILE)
            } catch (e: Exception) {
                Log.e("Cortex", "Tokenizer asset setup failed: ${e.message}")
                return null
            }

            val interpreter = try {
                val options = Interpreter.Options().apply { setNumThreads(2) }
                Interpreter(modelFile, options)
            } catch (e: Exception) {
                Log.e("Cortex", "TFLite Interpreter init failed", e)
                return null
            }

            // Diagnostic: shapes that came out of the TFLite metadata. If these
            // disagree with our constants, something is wrong with the converted
            // model — fail loudly here rather than at the first embed() call.
            val inCount = interpreter.inputTensorCount
            val outCount = interpreter.outputTensorCount
            val outShape = interpreter.getOutputTensor(0).shape()
            val inputNames = (0 until inCount).map { interpreter.getInputTensor(it).name() }
            Log.i(
                "RAG_DEBUG",
                "TFLite init: inputs=$inCount outputs=$outCount  inputNames=$inputNames " +
                    "outShape=${outShape.toList()}"
            )
            if (inCount != 2) {
                Log.e(
                    "RAG_DEBUG",
                    "Model expects $inCount inputs; this engine assumes 2 (input_ids, attention_mask). " +
                        "Re-run scripts/convert_minilm.py."
                )
                interpreter.close()
                return null
            }

            // Resolve input slots by NAME. TFLite tensor names typically arrive
            // suffixed (e.g. "serving_default_input_ids:0") so we match by
            // substring rather than exact equality.
            val idsIdx = inputNames.indexOfFirst { it.contains("input_ids") }
            val maskIdx = inputNames.indexOfFirst { it.contains("attention_mask") }
            if (idsIdx < 0 || maskIdx < 0 || idsIdx == maskIdx) {
                Log.e(
                    "RAG_DEBUG",
                    "Cannot locate input_ids/attention_mask in TFLite inputs=$inputNames. " +
                        "Re-run scripts/convert_minilm.py with the documented signature."
                )
                interpreter.close()
                return null
            }
            if (idsIdx != 0 || maskIdx != 1) {
                Log.w(
                    "RAG_DEBUG",
                    "TFLite input order is NOT (input_ids, attention_mask). " +
                        "Resolved indices: input_ids=$idsIdx attention_mask=$maskIdx. " +
                        "Feeding by name — this would have silently corrupted embeddings under positional feed."
                )
            }

            val tokenizer = try {
                HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokFile.toPath())
                    .optMaxLength(MAX_SEQ_LEN)
                    .optPadToMaxLength()
                    .optTruncation(true)
                    .build()
            } catch (t: Throwable) {
                Log.e(
                    "Cortex",
                    "HuggingFaceTokenizer init failed — likely missing libdjl_tokenizer.so. " +
                        "Confirm the `ai.djl.android:tokenizer-native` AAR is in dependencies.",
                    t
                )
                interpreter.close()
                return null
            }

            engine = Engine(interpreter, tokenizer, inputIdsIndex = idsIdx, attentionMaskIndex = maskIdx)
            Log.d(
                "Cortex",
                "Sentence encoder ready (model=${modelFile.length() / 1_000_000}MB, " +
                    "tokenizer=${tokFile.length() / 1000}KB, seqLen=$MAX_SEQ_LEN, dim=$EMBED_DIM)"
            )
        }
        engine?.let { runMultilingualProbeOnce(it) }
        return engine
    }

    /**
     * Phase-1 diagnostic. Runs ONCE per process on the first successful
     * encoder init. Embeds three sentences and compares cosines so we
     * can see at a glance whether the swap actually fixed the
     * "every chunk is equally similar" failure mode.
     */
    private fun runMultilingualProbeOnce(eng: Engine) {
        if (multilingualProbeDone) return
        multilingualProbeDone = true
        try {
            val en = embedInternal(eng, "What did I think about Dune 2?")
            val fr = embedInternal(eng, "Qu'est-ce que j'ai pensé de Dune 2 ?")
            val unrelated = embedInternal(eng, "The mitochondrion is the powerhouse of the cell")

            val crossLang = cosineSimilarity(en, fr)
            val unrelatedSim = cosineSimilarity(en, unrelated)
            val spread = crossLang - unrelatedSim

            Log.i(
                "RAG_DEBUG",
                "MULTILINGUAL PROBE: dim=${en.size} enMag=${"%.4f".format(magnitude(en))} " +
                    "frMag=${"%.4f".format(magnitude(fr))} unrelatedMag=${"%.4f".format(magnitude(unrelated))}"
            )
            Log.i(
                "RAG_DEBUG",
                "MULTILINGUAL PROBE: cosine(EN, FR-same-meaning)=${"%.3f".format(crossLang)}  " +
                    "cosine(EN, unrelated-EN)=${"%.3f".format(unrelatedSim)}  spread=${"%.3f".format(spread)}"
            )
            Log.i(
                "RAG_DEBUG",
                "MULTILINGUAL PROBE: VERDICT — healthy multilingual: EN-FR > 0.80, unrelated < 0.30, spread > 0.50"
            )
            if (en.size != EMBED_DIM) {
                Log.w(
                    "RAG_DEBUG",
                    "MULTILINGUAL PROBE: dim=${en.size} (expected $EMBED_DIM) — model/schema mismatch"
                )
            }
        } catch (e: Exception) {
            Log.w("RAG_DEBUG", "MULTILINGUAL PROBE failed: ${e.message}")
        }
    }

    /**
     * One-shot per process: if the persisted embedder-schema version
     * doesn't match the current model contract, wipe the embeddings
     * table and re-stamp. The IndexingWorker backfill (via
     * `FileDao.getFilesWithoutEmbeddings`) then picks up every `.md`
     * row on the next index pass, re-embedding under the new model.
     *
     * Bump [EMBEDDER_SCHEMA_VERSION] whenever the model or its output
     * shape changes — that's the migration signal.
     */
    private suspend fun ensureSchemaVersion() {
        if (schemaCheckDone) return
        schemaCheckDone = true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREFS_KEY_VERSION, null)
        if (stored == EMBEDDER_SCHEMA_VERSION) return

        val staleCount = runCatching { embeddingDao.count() }.getOrDefault(-1)
        Log.i(
            "RAG_DEBUG",
            "Embedder schema migration: '$stored' -> '$EMBEDDER_SCHEMA_VERSION'. " +
                "Wiping $staleCount embedding(s); next IndexingWorker run will re-embed."
        )
        runCatching { embeddingDao.deleteAll() }
            .onFailure { Log.w("RAG_DEBUG", "deleteAll failed: ${it.message}") }
        prefs.edit().putString(PREFS_KEY_VERSION, EMBEDDER_SCHEMA_VERSION).apply()
    }

    private fun magnitude(v: FloatArray): Float {
        var s = 0.0f
        for (x in v) s += x * x
        return sqrt(s)
    }

    /**
     * Synchronous embed. Caller must already be off the main thread.
     * `tokenizer` is configured with [optPadToMaxLength] + truncation so
     * the ids/mask arrays from DJL are always exactly [MAX_SEQ_LEN] long;
     * we still guard with `minOf` in case a tokenizer revision drops that
     * invariant. Output is a unit-length vector (the TFLite graph ends in
     * `tf.math.l2_normalize`, so cosine == dot product on these).
     */
    private fun embedInternal(eng: Engine, text: String): FloatArray {
        val encoding = eng.tokenizer.encode(text)
        val srcIds = encoding.ids
        val srcMask = encoding.attentionMask

        val inputIds = Array(1) { IntArray(MAX_SEQ_LEN) { 1 } }
        val attentionMask = Array(1) { IntArray(MAX_SEQ_LEN) }
        val len = minOf(srcIds.size, MAX_SEQ_LEN)
        for (i in 0 until len) {
            inputIds[0][i] = srcIds[i].toInt()
            attentionMask[0][i] = srcMask[i].toInt()
        }

        // Build the inputs array indexed by the model's ACTUAL tensor order
        // (resolved by name at init). Under positional feed this code silently
        // swapped input_ids ↔ attention_mask whenever the TFLite converter
        // emitted inputs alphabetically, collapsing every embedding to nearly
        // the same vector and turning RAG retrieval into DB-insertion order.
        val inputs = arrayOfNulls<Any>(2)
        inputs[eng.inputIdsIndex] = inputIds
        inputs[eng.attentionMaskIndex] = attentionMask

        val output = Array(1) { FloatArray(EMBED_DIM) }
        eng.interpreter.runForMultipleInputsOutputs(
            inputs,
            mapOf<Int, Any>(0 to output)
        )
        return output[0]
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        ensureSchemaVersion()
        val eng = getEngineSafe()
            ?: throw IllegalStateException("Vector Engine not ready (asset missing or init failed)")
        val vector = embedInternal(eng, text)
        val mag = magnitude(vector)
        Log.d(
            "RAG_DEBUG",
            "embed: textLen=${text.length} -> dim=${vector.size} mag=${"%.4f".format(mag)} " +
                "head=${vector.take(3).map { "%.3f".format(it) }}"
        )
        vector
    }

    /**
     * Search for relevant notes using cosine similarity.
     * Returns up to [limit] top-scoring chunks with plaintext content.
     * Private chunks are decrypted just-in-time using the cached vault password;
     * if the vault is locked, those hits are dropped from the result.
     */
    suspend fun search(query: String, limit: Int = 3): List<SearchHit> = withContext(Dispatchers.IO) {
        Log.d("RAG_DEBUG", "search: query='${query.take(80).replace("\n", " ")}' (len=${query.length})")
        ensureSchemaVersion()

        val eng = getEngineSafe()
        if (eng == null) {
            Log.w("RAG_DEBUG", "search skipped: Vector Engine not ready")
            return@withContext emptyList()
        }

        val queryVector = try {
            embedInternal(eng, query)
        } catch (e: Exception) {
            Log.w("RAG_DEBUG", "Query embedding failed: ${e.message}")
            return@withContext emptyList()
        }
        val queryMag = magnitude(queryVector)
        Log.d(
            "RAG_DEBUG",
            "search: query embedded -> dim=${queryVector.size} mag=${"%.4f".format(queryMag)} " +
                "head=${queryVector.take(3).map { "%.3f".format(it) }}"
        )
        // Hard-bail on degenerate query vectors. Below this floor the model
        // either silently no-op'd (output buffer left zero) or collapsed; in
        // either case cosineSimilarity returns 0 for every chunk and
        // sortedByDescending is stable, so top-K becomes DAO-insertion order
        // — the exact "random files" symptom we keep getting paged on.
        // Returning empty makes the failure VISIBLE instead of silently wrong.
        if (queryMag < QUERY_MAG_FLOOR) {
            Log.w(
                "RAG_DEBUG",
                "search: ABORT — query vector is degenerate (mag=${"%.4f".format(queryMag)} < $QUERY_MAG_FLOOR). " +
                    "Returning empty rather than top-K of DAO order. Check the TFLite model / tokenizer."
            )
            return@withContext emptyList()
        }
        if (queryMag < 0.99f || queryMag > 1.01f) {
            Log.w(
                "RAG_DEBUG",
                "search: query vector NOT unit-length (mag=${"%.4f".format(queryMag)}). " +
                    "Model should L2-normalize internally — check the conversion script."
            )
        }

        val allEmbeddings = embeddingDao.getAllEmbeddings()
        Log.d("RAG_DEBUG", "search: ${allEmbeddings.size} embedding(s) in DB to compare against")
        if (allEmbeddings.isEmpty()) {
            Log.w("RAG_DEBUG", "search: embeddings table is EMPTY — IndexingWorker has not produced any embeddings yet")
            return@withContext emptyList()
        }

        // Reify so we can dump the top-5 AND iterate for decryption. Also
        // skip stale-dim embeddings (e.g. left over from the prior 100-dim
        // USE-Lite model) instead of letting cosineSimilarity return 0 for
        // every comparison — those rows should be wiped by the schema-
        // version migration, but defend in depth.
        val scored = allEmbeddings.mapNotNull { entity ->
            val chunkVec = entity.vector.toFloatArray()
            if (chunkVec.size != queryVector.size) return@mapNotNull null
            val similarity = cosineSimilarity(queryVector, chunkVec)
            Triple(entity, similarity, magnitude(chunkVec))
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) {
            Log.w(
                "RAG_DEBUG",
                "search: all ${allEmbeddings.size} embeddings have wrong dim (expected $EMBED_DIM); " +
                    "they need to be wiped + re-embedded. Returning empty."
            )
            return@withContext emptyList()
        }

        val sMin = scored.last().second
        val sMax = scored.first().second
        val sMean = scored.sumOf { it.second.toDouble() } / scored.size
        val spread = sMax - sMin
        Log.d(
            "RAG_DEBUG",
            "search: score dist over ${scored.size} chunks  min=${"%.3f".format(sMin)} " +
                "mean=${"%.3f".format(sMean)} max=${"%.3f".format(sMax)} spread=${"%.3f".format(spread)}"
        )
        // Second guard: even with a unit-length query, if every chunk scores
        // nearly identically, the top-K is meaningless ordering noise. Bail
        // rather than handing arbitrary chunks to the LLM as "context".
        if (scored.size > 1 && spread < MIN_SCORE_SPREAD) {
            Log.w(
                "RAG_DEBUG",
                "search: ABORT — score spread ${"%.4f".format(spread)} < $MIN_SCORE_SPREAD across " +
                    "${scored.size} chunks. Embedding space is collapsed (model/tokenizer mismatch?)."
            )
            return@withContext emptyList()
        }
        Log.d("RAG_DEBUG", "search: TOP-5 candidates (pre-decrypt, pre-limit):")
        scored.take(5).forEachIndexed { idx, (entity, sim, mag) ->
            val preview = if (entity.isPrivate) {
                "[ciphertext ${entity.content.length} chars — JIT-decrypt at resolution]"
            } else {
                entity.content.take(120).replace("\n", " ")
            }
            Log.d(
                "RAG_DEBUG",
                "  #${idx + 1} sim=${"%.3f".format(sim)} chunkMag=${"%.4f".format(mag)} " +
                    "private=${entity.isPrivate} id=${entity.id.take(8)} file=${entity.fileId}\n      '$preview'"
            )
        }

        val vaultPassword = secretManager.getVaultPassword()
        var privateDecrypted = 0
        var privateDropped = 0
        val hits = mutableListOf<SearchHit>()
        for ((entity, _, _) in scored) {
            if (hits.size >= limit) break

            if (entity.isPrivate) {
                if (vaultPassword.isNullOrBlank()) {
                    privateDropped++
                    continue
                }
                val pwd = vaultPassword.toCharArray()
                val plaintext = try {
                    val ciphertext = Base64.decode(entity.content, Base64.NO_WRAP)
                    cryptoManager.decrypt(ciphertext, pwd)
                } catch (e: Exception) {
                    Log.w(
                        "RAG_DEBUG",
                        "Failed to decrypt private chunk for ${entity.fileId}: ${e.message}"
                    )
                    privateDropped++
                    continue
                } finally {
                    java.util.Arrays.fill(pwd, ' ')
                }
                privateDecrypted++
                hits.add(SearchHit(entity.fileId, plaintext, isPrivate = true))
            } else {
                hits.add(SearchHit(entity.fileId, entity.content, isPrivate = false))
            }
        }

        Log.d(
            "RAG_DEBUG",
            "search: returning ${hits.size} hit(s); privateDecrypted=$privateDecrypted privateDropped=$privateDropped " +
                "files=${hits.map { it.fileId }.distinct()}"
        )

        return@withContext hits
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        if (normA == 0.0f || normB == 0.0f) return 0.0f

        return dotProduct / (sqrt(normA) * sqrt(normB))
    }

    private companion object {
        /** Output of `scripts/convert_minilm.py`. Drop this in via the same path. */
        const val MODEL_FILE = "sentences_encoder.tflite"
        const val TOKENIZER_FILE = "tokenizer.json"

        /** Must match the `MAX_SEQ_LEN` constant in `scripts/convert_minilm.py`. */
        const val MAX_SEQ_LEN = 256

        /** MiniLM-L12 hidden size; output of the L2-normalized mean-pool. */
        const val EMBED_DIM = 384

        /**
         * Bump when the model OR output dimension changes. Mismatch triggers
         * a one-shot `embeddings` table wipe; IndexingWorker then re-embeds
         * every `.md` via the existing `getFilesWithoutEmbeddings` backfill.
         */
        const val EMBEDDER_SCHEMA_VERSION = "minilm-multilingual-v1"
        private const val PREFS_NAME = "embedder_state"
        private const val PREFS_KEY_VERSION = "schema_version"

        /**
         * Floor for a *usable* query magnitude. The TFLite graph L2-normalizes
         * internally, so a healthy query is ~1.0. A magnitude this low only
         * happens when the output buffer was left zero-initialized (silent
         * inference failure) — search bails so the LLM doesn't get fed
         * arbitrary "first-K-in-DB" chunks.
         */
        private const val QUERY_MAG_FLOOR = 0.5f

        /**
         * Floor for the score spread across all stored embeddings. If the
         * embedding space has collapsed (e.g. swapped input tensors causing
         * every text to embed to nearly the same vector), all chunks score
         * nearly equally and top-K becomes ordering noise. Bail in that case.
         */
        private const val MIN_SCORE_SPREAD = 0.01f
    }
}
