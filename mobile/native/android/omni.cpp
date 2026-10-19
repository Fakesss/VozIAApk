#include "control.h"
#include "omnivoice.h"
#include <memory>
#include <algorithm>
#include <vector>
// From patches/omnivoice-step-hook.patch: called before each MaskGIT step on this thread.
extern "C" void ov_vozia_set_step_hook(bool (*hook)(void*,int,int),void* ud);
// From patches/omnivoice-threads.patch: CPU threads for the next ov_init.
extern "C" void ov_vozia_set_threads(int n_threads);
namespace {
// Reports synthesis progress per decoding step (monotonic across chunks) and stops on cancel.
struct StepHook {
    Control* ctl; float best=.2f;
    explicit StepHook(Control* c):ctl(c) {ov_vozia_set_step_hook(&StepHook::step,this);}
    ~StepHook() {ov_vozia_set_step_hook(nullptr,nullptr);}
    static bool step(void* data,int step,int steps) {
        auto self=static_cast<StepHook*>(data);
        const float value=.2f+.78f*static_cast<float>(step)/static_cast<float>(steps>0?steps:1);
        if(value>self->best){self->best=value;self->ctl->progress(value);}
        return Control::cancelled(self->ctl);
    }
};
}
extern "C" JNIEXPORT jint JNICALL Java_com_voicestudio_mobile_local_NativeBridge_omniAbi(JNIEnv*,jobject) {return OV_ABI_VERSION;}
// seconds>0 fixes the output length (a dub line in its slot); reserve = cores left to the other lane.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_voicestudio_mobile_local_NativeBridge_cloneAudio(JNIEnv* e,jobject,jstring model,jstring codec,jstring text,jstring refText,jfloatArray reference,jfloat seconds,jint reserve,jobject control) {
    Utf8 m(e,model),c(e,codec),t(e,text),r(e,refText); Control ctl(e,control);
    ov_log_set([](ov_log_level,const char*,void*){},nullptr);
    ov_init_params init{}; ov_init_default_params(&init); init.model_path=m.chars;init.codec_path=c.chars;init.use_fa=false;
    if(Control::cancelled(&ctl))return nullptr;
    ov_vozia_set_threads(heavy_threads(reserve));
    std::unique_ptr<ov_context,decltype(&ov_free)> context(ov_init(&init),ov_free);
    if(!context){fail(e);return nullptr;} ctl.progress(0.2f);
    std::vector<float> ref(e->GetArrayLength(reference));e->GetFloatArrayRegion(reference,0,ref.size(),ref.data());
    ov_tts_params params{};ov_tts_default_params(&params);params.text=t.chars;params.ref_text=r.chars;
    params.ref_audio_24k=ref.data();params.ref_n_samples=ref.size();params.cancel=Control::cancelled;params.cancel_user_data=&ctl;
    if(seconds>0.f)params.T_override=ov_duration_sec_to_tokens(context.get(),std::min(static_cast<float>(seconds),30.f));
    ov_audio audio{};ov_status status;
    {StepHook hook(&ctl);status=ov_synthesize(context.get(),&params,&audio);}
    jfloatArray result=nullptr;
    if(status==OV_STATUS_OK&&!Control::cancelled(&ctl)){result=e->NewFloatArray(audio.n_samples);e->SetFloatArrayRegion(result,0,audio.n_samples,audio.samples);ctl.progress(1.f);}
    else if(status!=OV_STATUS_CANCELLED&&!Control::cancelled(&ctl))fail(e);
    ov_audio_free(&audio);if(ctl.failed)fail(e);return result;
}
