#include <jni.h>
#include <android/log.h>
#include <stable-diffusion.h>
#include <mutex>
#include <memory>
#include <string>
#include <atomic>
#include <stdexcept>
#include <cmath>

namespace {
std::mutex context_mutex;
sd_ctx_t* active = nullptr; // accessed only under context_mutex; generation owns lifetime
std::atomic<bool> cancelled{false};
JavaVM* vm = nullptr;
struct Sink { jobject object; jmethodID log; jmethodID progress; };
struct Env {
    JNIEnv* env = nullptr; bool attached = false;
    Env() { if(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        vm->AttachCurrentThread(&env, nullptr); attached = true;
    }}
    ~Env() { if(attached) vm->DetachCurrentThread(); }
};
void log_callback(sd_log_level_t, const char* text, void* data) {
    __android_log_write(ANDROID_LOG_INFO, "MOSS", text);
    auto* sink = static_cast<Sink*>(data); Env e;
    // Engine logs are UTF-8. Convert via byte[] in Java, avoiding NewStringUTF's modified UTF-8.
    auto n = static_cast<jsize>(strlen(text));
    jbyteArray bytes=e.env->NewByteArray(n);
    if(bytes) { e.env->SetByteArrayRegion(bytes,0,n,reinterpret_cast<const jbyte*>(text));
        e.env->CallVoidMethod(sink->object,sink->log,bytes); e.env->DeleteLocalRef(bytes); }
    if(e.env->ExceptionCheck()) e.env->ExceptionClear();
}
void progress_callback(int step,int steps,float seconds,void* data) {
    auto* sink=static_cast<Sink*>(data); Env e;
    e.env->CallVoidMethod(sink->object,sink->progress,step,steps,seconds);
    if(e.env->ExceptionCheck()) e.env->ExceptionClear();
}
std::string utf8(JNIEnv* env,jbyteArray bytes) {
    std::string s(env->GetArrayLength(bytes),'\0');
    env->GetByteArrayRegion(bytes,0,s.size(),reinterpret_cast<jbyte*>(s.data())); return s;
}
struct Callbacks {
    JNIEnv* env; Sink sink;
    Callbacks(JNIEnv* e,jobject obj):env(e) {
        env->GetJavaVM(&vm); sink.object=env->NewGlobalRef(obj);
        jclass cls=env->GetObjectClass(obj);
        sink.log=env->GetMethodID(cls,"onNativeLog","([B)V");
        sink.progress=env->GetMethodID(cls,"onNativeProgress","(IIF)V");
        env->DeleteLocalRef(cls);
        sd_set_backend_eval_callback([](ggml_tensor*, bool ask, void*) -> bool {
            // Observe only after cancellation; returning false after execution aborts the scheduler.
            // This independent flag also survives generate_image's internal cancellation reset.
            return ask && cancelled.load();
        },nullptr);
        sd_set_log_callback(log_callback,&sink); sd_set_progress_callback(progress_callback,&sink);
    }
    ~Callbacks(){sd_set_backend_eval_callback(nullptr,nullptr);sd_set_log_callback(nullptr,nullptr); sd_set_progress_callback(nullptr,nullptr);env->DeleteGlobalRef(sink.object);}
};
struct Context {
    sd_ctx_t* p;
    ~Context(){ {std::lock_guard<std::mutex> lock(context_mutex);active=nullptr;} if(p)free_sd_ctx(p); }
};
}
extern "C" JNIEXPORT void JNICALL
Java_org_mossimage_mobile_NativeEngine_prepare(JNIEnv*,jobject) { cancelled=false; }
extern "C" JNIEXPORT void JNICALL
Java_org_mossimage_mobile_NativeEngine_cancel(JNIEnv*,jobject) {
    cancelled=true; std::lock_guard<std::mutex> lock(context_mutex);
    if(active) sd_cancel_generation(active,SD_CANCEL_ALL);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_mossimage_mobile_NativeEngine_generate(JNIEnv* env,jobject,jbyteArray directory,
 jbyteArray prompt,jint size,jint steps,jlong seed,jfloat cfg,jint threads,jobject listener) {
    try {
        if(size<256||size>1024||size%16||steps<1||steps>100||threads<1||threads>8||!std::isfinite(cfg)||cfg<0||cfg>20)
            throw std::runtime_error("Invalid generation parameters");
        Callbacks callbacks(env,listener);
        std::string dir=utf8(env,directory), text=utf8(env,prompt);
        std::string dit=dir+"/diffusion.gguf",llm=dir+"/encoder.gguf",vae=dir+"/vae.safetensors",tokenizer=dir+"/tokenizer.json";
        sd_ctx_params_t cp; sd_ctx_params_init(&cp);
        cp.diffusion_model_path=dit.c_str(); cp.llm_path=llm.c_str(); cp.vae_path=vae.c_str(); cp.tokenizer=tokenizer.c_str();
        cp.n_threads=threads; cp.backend="cpu"; cp.params_backend="cpu";
        cp.enable_mmap=true; cp.eager_load=false; cp.conditioning_cache_size=0;
        cp.vae_format=SD_VAE_FORMAT_FLUX2;
        // No unverified attention or driver-specific patch enabled.
        Context ctx{new_sd_ctx(&cp)};
        if(!ctx.p) throw std::runtime_error("Model loading failed; inspect engine log and available physical RAM");
        { std::lock_guard<std::mutex> lock(context_mutex); active=ctx.p;
          if(cancelled) sd_cancel_generation(active,SD_CANCEL_ALL); }
        if(cancelled) throw std::runtime_error("Cancelled after loading");
        if(std::string(sd_get_model_version_name(ctx.p))!="MOSS-Image")
            throw std::runtime_error("Imported diffusion model is not MOSS-Image");
        sd_img_gen_params_t p; sd_img_gen_params_init(&p);
        p.prompt=text.c_str(); p.negative_prompt=""; p.width=size;p.height=size;p.seed=seed;p.batch_count=1;
        p.sample_params.sample_method=EULER_SAMPLE_METHOD;
        p.sample_params.scheduler=DISCRETE_SCHEDULER;
        p.sample_params.sample_steps=steps;p.sample_params.guidance.txt_cfg=cfg;p.sample_params.flow_shift=5.f;
        p.vae_tiling_params.enabled=true;p.vae_tiling_params.tile_size_w=256;p.vae_tiling_params.tile_size_h=256;
        sd_image_generation_result_t* raw=nullptr;
        bool ok=generate_image(ctx.p,&p,&raw);
        std::unique_ptr<sd_image_generation_result_t,decltype(&free_sd_image_generation_result)> result(raw,free_sd_image_generation_result);
        if(cancelled) throw std::runtime_error("Generation cancelled");
        if(!ok||!raw||raw->count!=1)throw std::runtime_error("Inference returned no image");
        auto& image=raw->items[0].image;
        if(!image.data||image.width!=static_cast<unsigned>(size)||image.height!=static_cast<unsigned>(size)||image.channel!=3)
            throw std::runtime_error("Unexpected native image format");
        const auto count=static_cast<jsize>(image.width*image.height*3);
        auto out=env->NewByteArray(count);
        if(out)env->SetByteArrayRegion(out,0,count,reinterpret_cast<const jbyte*>(image.data));
        return out;
    } catch(const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());return nullptr;
    }
}
