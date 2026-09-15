package org.audoiboo.tracker.tts

/** User-facing local synthesis quality. FAST remains the compatibility default. */
enum class TtsQuality {
    FAST,
    HIGH_QUALITY,
}

/** Runtime/model family. Different families require different sherpa-onnx configurations. */
enum class TtsEngineFamily {
    PIPER_VITS,
    SUPERTONIC,
}
