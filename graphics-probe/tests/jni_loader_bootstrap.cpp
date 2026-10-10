// Host probe of actual JNI_CreateJavaVM options and the console's system-loader model.
#include <jni.h>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#include "runner_config.h"

int main(int argc, char **argv) {
    if (argc != 7) return 2;
    std::vector<std::string> strings = {"-Xmx2g",std::string("-Djava.class.path=")+argv[2],
        "-Djava.system.class.loader=runner.RunnerSystemClassLoader",std::string("-Drunner.jar=")+argv[3]};
    std::ifstream file(argv[1]);
    if (!file) return 2;
    std::string line;
    while (std::getline(file,line)) {
        if (line.empty()) continue;
        char normalized[RUNNER_VM_OPTION_CAPACITY];
        int loader = runner_loader_option(line.c_str(),normalized,sizeof(normalized),argv[5]);
        if (loader < 0 || (!loader && runner_option_reserved(line.c_str()))) return 3;
        strings.push_back(loader ? normalized : line);
        if (strings.size() > RUNNER_VM_OPTION_COUNT) return 3;
    }
    std::vector<JavaVMOption> options;
    for (std::string &value : strings) options.push_back({value.data(),nullptr});
    JavaVMInitArgs settings{};
    settings.version = JNI_VERSION_1_8; settings.nOptions = (jint)options.size(); settings.options = options.data();
    JavaVM *vm = nullptr; JNIEnv *env = nullptr;
    if (JNI_CreateJavaVM(&vm,(void**)&env,&settings) != JNI_OK) return 4;
    jclass main = env->FindClass("orbis/launcher/ForgeBootstrap");
    if (!main || env->ExceptionCheck()) { env->ExceptionDescribe(); return 5; }
    jmethodID method = env->GetStaticMethodID(main,"main","([Ljava/lang/String;)V");
    jclass string = env->FindClass("java/lang/String");
    jobjectArray args = env->NewObjectArray(3,string,nullptr);
    for (int i=0;i<3;i++) env->SetObjectArrayElement(args,i,env->NewStringUTF(argv[i+4]));
    env->CallStaticVoidMethod(main,method,args);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 6; }
    vm->DestroyJavaVM();
    return 0;
}
