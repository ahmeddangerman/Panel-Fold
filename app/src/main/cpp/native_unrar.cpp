#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

#include "rar_session.hpp"

// UnRAR may handle RAR archives freely, but may not be used to develop a RAR
// compatible archiver or re-create its proprietary compression algorithm.
namespace {
using tabletcomics::RarError;
using tabletcomics::RarSession;
std::mutex sessionsMutex;
std::unordered_map<jlong, std::shared_ptr<RarSession>> sessions;
jlong nextSession = 1;

void throwIOException(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

void throwError(JNIEnv* env, const RarError& error) {
    if (env->ExceptionCheck()) return;
    if (error.requiredDictionaryBytes == 0) { throwIOException(env, error.what()); return; }
    jclass type = env->FindClass("dev/tabletcomics/archive/RarDictionaryLimitException");
    if (type == nullptr) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(JJ)V");
    if (constructor == nullptr) return;
    jobject value = env->NewObject(type, constructor, static_cast<jlong>(error.requiredDictionaryBytes),
                                   static_cast<jlong>(error.allowedDictionaryBytes));
    if (value != nullptr) env->Throw(static_cast<jthrowable>(value));
}

std::string pathString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw RarError("Cannot read this RAR archive's path.");
    std::string result;
    try { result = chars; } catch (...) { env->ReleaseStringUTFChars(value, chars); throw; }
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::wstring wideString(JNIEnv* env, jstring value) {
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw RarError("Cannot read this RAR page's name.");
    std::wstring result;
    try {
        result.reserve(static_cast<size_t>(length));
        for (jsize i = 0; i < length; ++i) {
            uint32_t codePoint = chars[i];
            if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i + 1 < length) {
                const uint32_t low = chars[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                    ++i;
                }
            }
            result.push_back(static_cast<wchar_t>(codePoint));
        }
    } catch (...) { env->ReleaseStringChars(value, chars); throw; }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring javaString(JNIEnv* env, const std::wstring& value) {
    std::vector<jchar> chars;
    for (wchar_t character : value) {
        uint32_t codePoint = static_cast<uint32_t>(character);
        if (codePoint <= 0xFFFF) chars.push_back(static_cast<jchar>(codePoint));
        else if (codePoint <= 0x10FFFF) {
            codePoint -= 0x10000;
            chars.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            chars.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

std::shared_ptr<RarSession> findSession(jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto found = sessions.find(id);
    if (found == sessions.end()) throw RarError("This RAR archive is closed.");
    return found->second;
}

jbyteArray extract(JNIEnv* env, RarSession& session, jlong ordinal, jlong maxPageBytes,
                   jlong maxDictionaryBytes, jobject cancellationCheck) {
    jclass functionClass = env->FindClass("kotlin/jvm/functions/Function0");
    if (functionClass == nullptr) return nullptr;
    jmethodID invoke = env->GetMethodID(functionClass, "invoke", "()Ljava/lang/Object;");
    if (invoke == nullptr) return nullptr;
    auto check = [env, cancellationCheck, invoke]() {
        if (env->ExceptionCheck()) return false;
        jobject ignored = env->CallObjectMethod(cancellationCheck, invoke);
        if (ignored != nullptr) env->DeleteLocalRef(ignored);
        return !env->ExceptionCheck();
    };
    if (ordinal < 0) throw RarError("This page is missing from the RAR archive.");
    auto bytes = session.extract(static_cast<size_t>(ordinal),
                                  static_cast<uint64_t>(std::max<jlong>(0, maxPageBytes)),
                                  static_cast<uint64_t>(std::max<jlong>(0, maxDictionaryBytes)), check);
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result != nullptr && !bytes.empty())
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

void extractToFile(JNIEnv* env, RarSession& session, jlong ordinal, jlong maxOutputBytes,
                   jlong maxDictionaryBytes, jstring targetPath, jobject cancellationCheck) {
    jclass functionClass = env->FindClass("kotlin/jvm/functions/Function0");
    if (functionClass == nullptr) return;
    jmethodID invoke = env->GetMethodID(functionClass, "invoke", "()Ljava/lang/Object;");
    if (invoke == nullptr) return;
    auto check = [env, cancellationCheck, invoke]() {
        if (env->ExceptionCheck()) return false;
        jobject ignored = env->CallObjectMethod(cancellationCheck, invoke);
        if (ignored != nullptr) env->DeleteLocalRef(ignored);
        return !env->ExceptionCheck();
    };
    if (ordinal < 0) throw RarError("This nested archive entry is missing.");
    session.extractToFile(static_cast<size_t>(ordinal),
                          static_cast<uint64_t>(std::max<jlong>(0, maxOutputBytes)),
                          static_cast<uint64_t>(std::max<jlong>(0, maxDictionaryBytes)),
                          pathString(env, targetPath), check);
}

// No C++ allocation/error may unwind across a JNI boundary.
#define JNI_ERRORS(env, value) \
    catch (const RarError& error) { throwError(env, error); return value; } \
    catch (const std::bad_alloc&) { throwIOException(env, "Not enough memory to open this RAR page."); return value; } \
    catch (const std::exception& error) { throwIOException(env, error.what()); return value; }
} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeList(JNIEnv* env, jobject, jstring path) {
    try {
        const auto entries = RarSession::list(pathString(env, path));
        jclass type = env->FindClass("dev/tabletcomics/archive/NativeRarEntry");
        if (type == nullptr) return nullptr;
        jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;JI)V");
        if (constructor == nullptr) return nullptr;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(entries.size()), type, nullptr);
        if (result == nullptr) return nullptr;
        for (jsize i = 0; i < static_cast<jsize>(entries.size()); ++i) {
            const auto& entry = entries[static_cast<size_t>(i)];
            jstring name = javaString(env, entry.name);
            if (name == nullptr) return nullptr;
            jobject value = env->NewObject(type, constructor, name, static_cast<jlong>(entry.size), static_cast<jint>(entry.flags));
            env->DeleteLocalRef(name);
            if (value == nullptr) return nullptr;
            env->SetObjectArrayElement(result, i, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    } JNI_ERRORS(env, nullptr)
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeOpenSession(JNIEnv* env, jobject, jstring path) {
    try {
        auto session = std::make_shared<RarSession>(pathString(env, path));
        std::lock_guard<std::mutex> lock(sessionsMutex);
        const jlong id = nextSession++;
        sessions.emplace(id, std::move(session));
        return id;
    } JNI_ERRORS(env, 0)
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeReadSession(JNIEnv* env, jobject, jlong id,
        jint ordinal, jlong maxPageBytes, jlong maxDictionaryBytes, jobject cancellationCheck) {
    try {
        auto session = findSession(id);
        return extract(env, *session, ordinal, maxPageBytes, maxDictionaryBytes, cancellationCheck);
    } JNI_ERRORS(env, nullptr)
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeWriteSessionToFile(JNIEnv* env, jobject, jlong id,
        jint ordinal, jlong maxOutputBytes, jlong maxDictionaryBytes, jstring targetPath,
        jobject cancellationCheck) {
    try {
        auto session = findSession(id);
        extractToFile(env, *session, ordinal, maxOutputBytes, maxDictionaryBytes, targetPath, cancellationCheck);
    } JNI_ERRORS(env, )
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeCancelSession(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto found = sessions.find(id);
    if (found != sessions.end()) found->second->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeCloseSession(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<RarSession> session;
    {
        std::lock_guard<std::mutex> lock(sessionsMutex);
        auto found = sessions.find(id);
        if (found == sessions.end()) return;
        session = found->second;
        sessions.erase(found);
    }
    session->close();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeExtract(JNIEnv* env, jobject, jstring path,
        jstring entryName, jlong maxPageBytes, jlong maxDictionaryBytes, jobject cancellationCheck) {
    try {
        const std::string archivePath = pathString(env, path);
        const auto wanted = wideString(env, entryName);
        const auto entries = RarSession::list(archivePath);
        jlong ordinal = -1;
        for (size_t i = 0; i < entries.size(); ++i) {
            if (entries[i].name != wanted) continue;
            if (ordinal >= 0) throw RarError("This RAR contains duplicate page names. Rename the duplicate files and recreate the archive.");
            ordinal = static_cast<jlong>(i);
        }
        RarSession session(archivePath);
        return extract(env, session, ordinal, maxPageBytes, maxDictionaryBytes, cancellationCheck);
    } JNI_ERRORS(env, nullptr)
}
