/**
 * llama.cpp JNI bridge for MeetingTranscriber Android.
 *
 * Implements all native methods declared in LlmNative.kt.
 *
 * Build dependencies:
 * 1. git clone https://github.com/ggerganov/llama.cpp.git into cpp/
 * 2. CMakeLists.txt links llama + common
 *
 * Thread safety: all JNI entry points are protected by g_mutex.
 * generate() holds the lock for its entire duration — this is intentional:
 * it prevents concurrent generation and protects against unloadModel() races.
 *
 * JNI naming convention:
 * Java_{package_with_underscores}_{ClassName}_{methodName}
 * Package: com_example_meetingtranscriber_engine_llm
 * Class:   LlmNative
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <mutex>
#include <atomic>
#include <android/log.h>

#define TAG "LlmNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// === llama.cpp headers ===
#include "llama.h"
#include "common.h"

// === Global state (protected by g_mutex) ===
static std::mutex        g_mutex;
static llama_model*      g_model   = nullptr;
static llama_context*    g_ctx     = nullptr;
static llama_sampler*    g_sampler = nullptr;
static const llama_vocab* g_vocab  = nullptr;
static bool              g_loaded  = false;
static std::atomic<bool> g_cancel{false};

// JNI helper — get C string from jstring
static const char* jstringToC(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    return env->GetStringUTFChars(jstr, nullptr);
}

// === loadModel ===
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_meetingtranscriber_engine_llm_LlmNative_loadModel(
    JNIEnv* env, jclass /*clazz*/,
    jstring jmodelPath, jint nCtx, jint nThreads,
    jboolean useMmap, jboolean useMlock) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_loaded) {
        LOGI("Model already loaded, skipping");
        return JNI_TRUE;
    }

    const char* modelPath = jstringToC(env, jmodelPath);
    LOGI("Loading model: %s (ctx=%d, threads=%d)", modelPath, nCtx, nThreads);

    // 1. Initialize llama backend
    llama_backend_init();

    // 2. Load model
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = useMmap;
    model_params.use_mlock = useMlock;

    g_model = llama_model_load_from_file(modelPath, model_params);
    if (!g_model) {
        LOGE("Model load failed: %s", modelPath);
        env->ReleaseStringUTFChars(jmodelPath, modelPath);
        return JNI_FALSE;
    }

    // 3. Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = nCtx;
    ctx_params.n_threads       = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Context creation failed");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(jmodelPath, modelPath);
        return JNI_FALSE;
    }

    // 4. Get vocabulary (pointer into g_model, valid as long as g_model lives)
    g_vocab = llama_model_get_vocab(g_model);

    env->ReleaseStringUTFChars(jmodelPath, modelPath);
    g_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// === generate ===
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_meetingtranscriber_engine_llm_LlmNative_generate(
    JNIEnv* env, jclass /*clazz*/,
    jstring jprompt, jint maxTokens, jfloat temperature,
    jfloat topP, jint topK, jfloat repeatPenalty,
    jobjectArray jstopStrings, jobject jcallback) {

    // Hold lock for entire generation — prevents concurrent generate() and
    // protects against unloadModel() freeing the model mid-inference.
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_loaded || !g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char* prompt = jstringToC(env, jprompt);
    int promptLen = strlen(prompt);
    LOGI("Inference start: prompt=%d chars, max=%d tokens", promptLen, maxTokens);

    // 1. Tokenize prompt
    int nPromptTokens = -llama_tokenize(g_vocab, prompt, promptLen, nullptr, 0, true, true);
    if (nPromptTokens <= 0) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        if (nPromptTokens == 0) {
            LOGI("Empty prompt, nothing to generate");
        } else {
            LOGE("Tokenize failed (pass 1)");
        }
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens(nPromptTokens + maxTokens);
    int actualTokens = llama_tokenize(g_vocab, prompt, promptLen, tokens.data(), nPromptTokens, true, true);
    if (actualTokens <= 0) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        LOGE("Tokenize failed (pass 2)");
        return env->NewStringUTF("");
    }
    tokens.resize(actualTokens);

    // 2. Set up sampler chain with repeat penalty
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    g_sampler = llama_sampler_chain_init(sparams);
    if (!g_sampler) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        LOGE("Sampler chain init failed");
        return env->NewStringUTF("");
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
        -1,              // penalty_last_n: use full context
        repeatPenalty,   // penalty_repeat: from caller
        0.0f,            // penalty_freq: disabled
        0.0f));          // penalty_present: disabled
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(1234));  // deterministic seed

    // 3. Extract stop strings
    jsize stopCount = env->GetArrayLength(jstopStrings);
    std::vector<std::string> stopWords(stopCount);
    for (jsize i = 0; i < stopCount; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(jstopStrings, i);
        if (js) {
            const char* s = jstringToC(env, js);
            stopWords[i] = s;
            env->ReleaseStringUTFChars(js, s);
            env->DeleteLocalRef(js);
        }
    }

    // 4. Cache callback method IDs (only when callback is provided)
    jclass cbClass = nullptr;
    jmethodID cbMethod = nullptr;
    jclass integerClass = nullptr;
    jmethodID integerValueOf = nullptr;
    if (jcallback) {
        cbClass = env->GetObjectClass(jcallback);
        cbMethod = env->GetMethodID(cbClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        integerClass = env->FindClass("java/lang/Integer");
        integerValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    }

    // 5. Clear KV cache
    llama_kv_self_clear(g_ctx);

    // 6. Decode prompt tokens
    {
        llama_batch batch = llama_batch_init(actualTokens, 0, 1);
        batch.n_tokens = actualTokens;
        for (int i = 0; i < actualTokens; i++) {
            batch.token[i]     = tokens[i];
            batch.pos[i]       = i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = (i == actualTokens - 1) ? 1 : 0;
        }

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Prompt decode failed");
            llama_batch_free(batch);
            llama_sampler_free(g_sampler);
            g_sampler = nullptr;
            if (cbClass) env->DeleteLocalRef(cbClass);
            if (integerClass) env->DeleteLocalRef(integerClass);
            env->ReleaseStringUTFChars(jprompt, prompt);
            return env->NewStringUTF("");
        }
        llama_batch_free(batch);
    }

    // 7. Autoregressive generation
    g_cancel = false;
    std::string output;
    int nGenerated = 0;
    int nPast = actualTokens;

    while (nGenerated < maxTokens && !g_cancel) {
        // Sample next token
        llama_token newToken = llama_sampler_sample(g_sampler, g_ctx, -1);

        // Check EOS
        if (llama_vocab_is_eog(g_vocab, newToken)) {
            LOGI("EOS token, stopping");
            break;
        }

        tokens.push_back(newToken);

        // Token → text (use larger buffer to avoid silent truncation)
        char buf[1024];
        int len = llama_token_to_piece(g_vocab, newToken, buf, sizeof(buf), 0, true);
        if (len < 0) {
            // Buffer too small — rare but handle gracefully
            std::vector<char> largeBuf(-len + 1);
            len = llama_token_to_piece(g_vocab, newToken, largeBuf.data(), largeBuf.size(), 0, true);
            if (len > 0) output.append(largeBuf.data(), len);
        } else if (len > 0) {
            output.append(buf, len);
        }

        // Check custom stop words
        bool stopped = false;
        for (const auto& sw : stopWords) {
            if (!sw.empty() && output.find(sw) != std::string::npos) {
                stopped = true;
                break;
            }
        }
        if (stopped) break;

        nGenerated++;

        // Callback to Java layer (CallObjectMethod — correct for Object-returning invoke)
        if (jcallback && cbMethod) {
            jobject intObj = env->CallStaticObjectMethod(integerClass, integerValueOf, (jint)nGenerated);
            jobject result = env->CallObjectMethod(jcallback, cbMethod, intObj);
            if (result) env->DeleteLocalRef(result);
            if (intObj) env->DeleteLocalRef(intObj);
        }

        // Decode the new token
        llama_batch nextBatch = llama_batch_init(1, 0, 1);
        nextBatch.n_tokens     = 1;
        nextBatch.token[0]     = newToken;
        nextBatch.pos[0]       = nPast;
        nextBatch.n_seq_id[0]  = 1;
        nextBatch.seq_id[0][0] = 0;
        nextBatch.logits[0]    = 1;

        int decodeRet = llama_decode(g_ctx, nextBatch);
        llama_batch_free(nextBatch);

        if (decodeRet != 0) {
            LOGE("Decode failed at token %d", nGenerated);
            break;
        }
        nPast++;
    }

    // 8. Cleanup
    llama_sampler_free(g_sampler);
    g_sampler = nullptr;
    llama_kv_self_clear(g_ctx);

    if (cbClass) env->DeleteLocalRef(cbClass);
    if (integerClass) env->DeleteLocalRef(integerClass);
    env->ReleaseStringUTFChars(jprompt, prompt);
    LOGI("Inference done: %d tokens, %d chars", nGenerated, (int)output.size());
    return env->NewStringUTF(output.c_str());
}

// === cancelGenerate ===
extern "C" JNIEXPORT void JNICALL
Java_com_example_meetingtranscriber_engine_llm_LlmNative_cancelGenerate(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    // Atomic flag — safe to set from any thread without holding g_mutex
    g_cancel = true;
    LOGI("Generation cancelled");
}

// === unloadModel ===
extern "C" JNIEXPORT void JNICALL
Java_com_example_meetingtranscriber_engine_llm_LlmNative_unloadModel(
    JNIEnv* /*env*/, jclass /*clazz*/) {

    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("Freeing model...");

    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    // Null g_vocab BEFORE freeing g_model — it's a pointer into g_model
    g_vocab = nullptr;
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    g_loaded = false;
    LOGI("Model freed");
}

// === isSupported ===
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_meetingtranscriber_engine_llm_LlmNative_isSupported(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    return JNI_TRUE;
}
