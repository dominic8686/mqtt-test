#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unistd.h>

#include "common.h"
#include "llama.h"
#include "sampling.h"

#define TAG "LlmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model   *g_model   = nullptr;
static llama_context *g_context = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mqttai_calc_tools_LlamaInference_loadModel(JNIEnv *env, jobject, jstring jpath) {
    if (g_model) return JNI_TRUE; // already loaded

    llama_backend_init();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Loading model: %s", path);

    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Create context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 2048;
    cparams.n_batch = 512;
    const int n_threads = std::max(2, std::min(4, (int)sysconf(_SC_NPROCESSORS_ONLN) - 1));
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    g_context = llama_init_from_model(g_model, cparams);
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded, ctx=%d threads=%d", 2048, n_threads);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_mqttai_calc_tools_LlamaInference_complete(JNIEnv *env, jobject, jstring jprompt, jint maxTokens) {
    if (!g_model || !g_context) {
        return env->NewStringUTF("");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_context), false);

    // Tokenize
    auto tokens = common_tokenize(g_context, prompt_str, true, true);
    LOGI("Prompt tokens: %d", (int)tokens.size());

    // Decode prompt in batches
    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < (int)tokens.size(); i += 512) {
        common_batch_clear(batch);
        int end = std::min(i + 512, (int)tokens.size());
        for (int j = i; j < end; j++) {
            bool last = (j == (int)tokens.size() - 1);
            common_batch_add(batch, tokens[j], j, {0}, last);
        }
        if (llama_decode(g_context, batch)) {
            LOGE("decode failed");
            llama_batch_free(batch);
            return env->NewStringUTF("");
        }
    }

    // Sample tokens
    common_params_sampling sparams;
    sparams.temp = 0.1f; // low temp for deterministic routing
    auto *sampler = common_sampler_init(g_model, sparams);

    std::string result;
    int pos = (int)tokens.size();
    const auto *vocab = llama_model_get_vocab(g_model);

    for (int i = 0; i < maxTokens; i++) {
        auto id = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, id, true);

        if (llama_vocab_is_eog(vocab, id)) break;

        result += common_token_to_piece(g_context, id);

        common_batch_clear(batch);
        common_batch_add(batch, id, pos++, {0}, true);
        if (llama_decode(g_context, batch)) break;
    }

    common_sampler_free(sampler);
    llama_batch_free(batch);

    LOGI("Generated: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mqttai_calc_tools_LlamaInference_unloadModel(JNIEnv *, jobject) {
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mqttai_calc_tools_LlamaInference_isLoaded(JNIEnv *, jobject) {
    return g_model != nullptr && g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}
