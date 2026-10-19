#pragma once
#include <jni.h>
#include <string>
#include <cstdint>
#include <atomic>
#include <algorithm>
#include <fstream>
#include <thread>
#include <vector>
// JNI modified UTF-8 encodes supplementary characters differently. Convert
// UTF-16 explicitly so model tokenizers receive standard UTF-8, including emoji.
struct Utf8 {
    std::string storage; const char* chars;
    Utf8(JNIEnv* e,jstring text) {
        const auto input=e->GetStringChars(text,nullptr);const auto size=e->GetStringLength(text);
        if(input) for(jsize i=0;i<size;++i) {
            uint32_t cp=input[i];
            if(cp>=0xd800&&cp<=0xdbff&&i+1<size&&input[i+1]>=0xdc00&&input[i+1]<=0xdfff) {cp=0x10000+((cp-0xd800)<<10)+(input[++i]-0xdc00);}
            else if(cp>=0xd800&&cp<=0xdfff)cp=0xfffd;
            if(cp<0x80)storage+=static_cast<char>(cp);
            else if(cp<0x800){storage+=static_cast<char>(0xc0|(cp>>6));storage+=static_cast<char>(0x80|(cp&63));}
            else if(cp<0x10000){storage+=static_cast<char>(0xe0|(cp>>12));storage+=static_cast<char>(0x80|((cp>>6)&63));storage+=static_cast<char>(0x80|(cp&63));}
            else {storage+=static_cast<char>(0xf0|(cp>>18));storage+=static_cast<char>(0x80|((cp>>12)&63));storage+=static_cast<char>(0x80|((cp>>6)&63));storage+=static_cast<char>(0x80|(cp&63));}
        }
        if(input)e->ReleaseStringChars(text,input);chars=storage.c_str();
    }
};
struct Control {
    JNIEnv* owner; JavaVM* vm=nullptr; jobject value; jmethodID check; jmethodID report;
    std::atomic<bool> failed{false};
    struct Attached {
        JavaVM* vm; JNIEnv* env=nullptr; bool detached=false;
        explicit Attached(JavaVM* v):vm(v) {
            if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED) {
                detached=vm->AttachCurrentThread(&env,nullptr)==JNI_OK;
                if(!detached)env=nullptr;
            }
        }
        ~Attached(){if(detached)vm->DetachCurrentThread();}
    };
    Control(JNIEnv* e,jobject v):owner(e),value(e->NewGlobalRef(v)) {
        e->GetJavaVM(&vm);auto c=e->GetObjectClass(v);
        check=e->GetMethodID(c,"cancelled","()Z");report=e->GetMethodID(c,"progress","(F)V");e->DeleteLocalRef(c);
    }
    ~Control(){owner->DeleteGlobalRef(value);}
    bool exception(JNIEnv* e) {
        if(!e||e->ExceptionCheck()) {
            failed=true;
            // Exceptions on an attached native worker cannot unwind the caller's
            // Java stack. Preserve the failure and surface it after native cleanup.
            if(e&&e!=owner)e->ExceptionClear();
            return true;
        }
        return false;
    }
    static bool cancelled(void* data) {
        auto c=static_cast<Control*>(data);
        if(c->failed)return true;
        Attached thread(c->vm);if(c->exception(thread.env))return true;
        const auto result=thread.env->CallBooleanMethod(c->value,c->check);
        return c->exception(thread.env)||result;
    }
    void progress(float f) {
        if(failed)return;
        Attached thread(vm);if(exception(thread.env))return;
        thread.env->CallVoidMethod(value,report,f);exception(thread.env);
    }
};
inline void fail(JNIEnv* e) {
    if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"Native inference failed");
}
// Cores at least half as fast as the fastest one, from the kernel's per-core
// capacity (big.LITTLE phones); half of all cores when the kernel does not say.
inline int fast_cores() {
    const int count=std::max(1,static_cast<int>(std::thread::hardware_concurrency()));
    std::vector<long> capacity;
    for(int i=0;i<count;++i) {
        std::ifstream file("/sys/devices/system/cpu/cpu"+std::to_string(i)+"/cpu_capacity");
        long value=0;
        if(file>>value&&value>0)capacity.push_back(value);
    }
    if(capacity.size()!=static_cast<size_t>(count))return std::max(1,count/2);
    const long top=*std::max_element(capacity.begin(),capacity.end());
    return static_cast<int>(std::count_if(capacity.begin(),capacity.end(),[top](long value){return value*2>=top;}));
}
// Threads for the large models: the fast cores, leaving room for the small-model lane.
inline int heavy_threads() {return std::clamp(fast_cores(),2,6);}
