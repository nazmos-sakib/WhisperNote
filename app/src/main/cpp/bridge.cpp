#include <jni.h>
#include <cstring>
#include <string>
#include <whisper.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <thread>
#include <memory>
#include <stdexcept>

struct Callback {
    JNIEnv* env;
    jobject listener;
    jmethodID progress, segment, cancelled, language;
    int64_t offsetMs, durationMs, emittedEndMs;
    bool languageSent = false;
};

static bool cancelled(void* data) {
    auto* c = static_cast<Callback*>(data);
    return c->env->ExceptionCheck() || c->env->CallBooleanMethod(c->listener, c->cancelled);
}

static void languageDetected(whisper_context* ctx, Callback* c) {
    if (c->languageSent || c->env->ExceptionCheck()) return;
    const int id = whisper_full_lang_id(ctx);
    if (id < 0) return;
    auto text = c->env->NewStringUTF(whisper_lang_str(id));
    c->env->CallVoidMethod(c->listener, c->language, text);
    c->env->DeleteLocalRef(text);
    c->languageSent = true;
}

// Called synchronously by whisper_full when a finalized segment is produced, before inference returns.
static void newSegments(whisper_context* ctx, whisper_state*, int count, void* data) {
    auto* c = static_cast<Callback*>(data);
    auto* env = c->env;
    languageDetected(ctx, c);
    const int total = whisper_full_n_segments(ctx);
    for (int i = total - count; i < total && !cancelled(c); ++i) {
        const int64_t start = std::max(c->emittedEndMs, c->offsetMs + whisper_full_get_segment_t0(ctx, i) * 10);
        const int64_t end = std::min(c->durationMs, c->offsetMs + whisper_full_get_segment_t1(ctx, i) * 10);
        const char* utf8 = whisper_full_get_segment_text(ctx, i);
        if (end <= start || !utf8[0]) continue;
        // JNI NewStringUTF expects modified UTF-8; use the standard UTF-8 String constructor instead.
        auto bytes = env->NewByteArray(strlen(utf8));
        env->SetByteArrayRegion(bytes, 0, strlen(utf8), reinterpret_cast<const jbyte*>(utf8));
        auto stringClass = env->FindClass("java/lang/String");
        auto charset = env->NewStringUTF("UTF-8");
        auto text = env->NewObject(stringClass, env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V"), bytes, charset);
        if (!env->ExceptionCheck()) env->CallVoidMethod(c->listener, c->segment, (jlong) start, (jlong) end, text);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(stringClass);
        if (env->ExceptionCheck()) return;
        c->emittedEndMs = end;
    }
}

extern "C" JNIEXPORT jstring JNICALL Java_app_naz_whispernote_core_WhisperEngine_transcribe(
    JNIEnv* env, jobject, jstring model, jstring pcm, jlong resumeMs, jstring language, jobject listener) {
    const char* m = env->GetStringUTFChars(model, nullptr);
    std::string modelPath(m); env->ReleaseStringUTFChars(model, m);
    const char* p = env->GetStringUTFChars(pcm, nullptr);
    std::string pcmPath(p); env->ReleaseStringUTFChars(pcm, p);
    const char* l = env->GetStringUTFChars(language, nullptr);
    std::string selectedLanguage(l); env->ReleaseStringUTFChars(language, l);
    int fd = -1;
    void* mapped = MAP_FAILED;
    size_t length = 0;
    try {
        fd = open(pcmPath.c_str(), O_RDONLY);
        struct stat st{};
        if (fd < 0 || fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size % 4 != 0 || st.st_size / 4 > INT32_MAX)
            throw std::runtime_error("Cannot read prepared audio.");
        length = st.st_size;
        const int64_t durationMs = (length / 4) * 1000 / WHISPER_SAMPLE_RATE;
        if (resumeMs < 0 || resumeMs >= durationMs) throw std::runtime_error("Invalid transcription checkpoint.");
        mapped = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped == MAP_FAILED) throw std::runtime_error("Not enough memory to map audio.");
        auto cls = env->GetObjectClass(listener);
        Callback c{env, listener, env->GetMethodID(cls,"onProgress","(I)V"),
            env->GetMethodID(cls,"onSegment","(JJLjava/lang/String;)V"), env->GetMethodID(cls,"isCancelled","()Z"),
            env->GetMethodID(cls,"onLanguage","(Ljava/lang/String;)V"), resumeMs, durationMs, resumeMs};
        if (cancelled(&c)) throw std::runtime_error("Transcription interrupted.");
        auto cp = whisper_context_default_params(); cp.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(
            whisper_init_from_file_with_params(modelPath.c_str(), cp), whisper_free);
        if (!ctx) throw std::runtime_error("Could not load model. Try the tiny model or download it again.");
        if (cancelled(&c)) throw std::runtime_error("Transcription interrupted.");
        // The UI can now leave Loading model; this does not advance the durable checkpoint.
        env->CallVoidMethod(listener, c.progress, (jint) (resumeMs * 100 / durationMs));
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
        params.language = selectedLanguage.empty() ? "auto" : selectedLanguage.c_str();
        params.translate = false;
        params.print_realtime = false; params.print_progress = false; params.print_timestamps = false;
        params.abort_callback = cancelled; params.abort_callback_user_data = &c;
        params.new_segment_callback = newSegments; params.new_segment_callback_user_data = &c;
        params.progress_callback = [](whisper_context* ctx, whisper_state*, int progress, void* data) {
            auto* c = static_cast<Callback*>(data);
            if (cancelled(c)) return;
            languageDetected(ctx, c);
            const int overall = (c->offsetMs * 100 + (c->durationMs - c->offsetMs) * progress) / c->durationMs;
            if (!c->env->ExceptionCheck()) c->env->CallVoidMethod(c->listener, c->progress, std::min(99, overall));
        };
        params.progress_callback_user_data = &c;
        // Infer only the unprocessed tail. Native timestamps are relative to that tail; callbacks restore
        // original-file offsets. Previously saved segments (including user edits) are never replaced.
        const int64_t offsetSamples = resumeMs * (WHISPER_SAMPLE_RATE / 1000);
        int result = whisper_full(ctx.get(), params, static_cast<float*>(mapped) + offsetSamples, length / 4 - offsetSamples);
        if (result != 0 || cancelled(&c)) throw std::runtime_error("Transcription interrupted or failed.");
        std::string detected = whisper_lang_str(whisper_full_lang_id(ctx.get()));
        munmap(mapped, length); close(fd);
        return env->NewStringUTF(detected.c_str());
    } catch (const std::exception& e) {
        if (mapped != MAP_FAILED) munmap(mapped, length);
        if (fd >= 0) close(fd);
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
