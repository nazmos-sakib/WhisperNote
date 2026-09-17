package app.naz.whispernote.core

import androidx.annotation.Keep

@Keep
class WhisperEngine {
    @Keep interface Listener {
        fun onProgress(progress: Int)
        fun onSegment(start: Long, end: Long, text: String)
        fun onLanguage(language: String) {}
        fun isCancelled(): Boolean
    }
    external fun transcribe(model: String, pcm: String, resumeMs: Long, language: String, listener: Listener): String
    companion object { init { System.loadLibrary("whispernote") } }
}
