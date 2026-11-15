package com.typ02.pomoshnik

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors

/** ИИ-модель, которая работает прямо на телефоне (без интернета). */
object Llm {
    const val MODEL_PAGE = "https://huggingface.co/litert-community/Gemma3-1B-IT"
    const val MODEL_FILE = "gemma3-1b-it-int4.task"

    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: LlmInference? = null

    @Volatile
    var lastError: String? = null

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, "model.task")

    fun installed(ctx: Context): Boolean = file(ctx).length() > 50_000_000L

    fun remove(ctx: Context) {
        exec.execute {
            try {
                engine?.close()
            } catch (e: Throwable) {
            }
            engine = null
            file(ctx).delete()
        }
    }

    /** Выполняет запрос в фоне; ответ (или null при ошибке) приходит в главный поток. */
    fun run(ctx: Context, prompt: String, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            var out: String? = null
            try {
                var e = engine
                if (e == null) {
                    val opts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(file(app).absolutePath)
                        .setMaxTokens(1024)
                        .build()
                    e = LlmInference.createFromOptions(app, opts)
                    engine = e
                }
                out = e!!.generateResponse(prompt)?.trim()
                lastError = null
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                try {
                    engine?.close()
                } catch (x: Throwable) {
                }
                engine = null
            }
            val res = out
            ui.post { cb(if (res.isNullOrEmpty()) null else res) }
        }
    }
}
