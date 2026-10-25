#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <new>
#include <string>
#include <vector>

#include "raros.hpp"
#include "dll.hpp"

// UnRAR source code may be used in any software to handle RAR archives without limitations free of charge, but cannot be used to develop a RAR (WinRAR) compatible archiver and to re-create the RAR compression algorithm, which is proprietary.

namespace {
constexpr unsigned int kMaxEntries = 20000;

struct ExtractState {
    JNIEnv* env = nullptr;
    jobject cancellationCheck = nullptr;
    jmethodID invoke = nullptr;
    std::vector<unsigned char> bytes;
    uint64_t maxPageBytes = 0;
    uint64_t maxDictionaryBytes = 0;
    uint64_t rejectedDictionaryBytes = 0;
    bool pageTooLarge = false;
    bool outputOutOfMemory = false;
};

void throwIOException(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message.c_str());
}

void throwDictionaryException(JNIEnv* env, uint64_t required, uint64_t allowed) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("dev/tabletcomics/archive/RarDictionaryLimitException");
    if (exceptionClass == nullptr) return;
    jmethodID constructor = env->GetMethodID(exceptionClass, "<init>", "(JJ)V");
    if (constructor == nullptr) return;
    jobject exception = env->NewObject(exceptionClass, constructor,
                                       static_cast<jlong>(required), static_cast<jlong>(allowed));
    if (exception != nullptr) env->Throw(static_cast<jthrowable>(exception));
}

std::wstring jstringToWide(JNIEnv* env, jstring value) {
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::wstring result;
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        uint32_t codePoint = chars[i];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        result.push_back(static_cast<wchar_t>(codePoint));
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring wideToJstring(JNIEnv* env, const wchar_t* value) {
    std::vector<jchar> chars;
    for (const wchar_t* current = value; current != nullptr && *current != 0; ++current) {
        uint32_t codePoint = static_cast<uint32_t>(*current);
        if (codePoint <= 0xFFFF) {
            chars.push_back(static_cast<jchar>(codePoint));
        } else if (codePoint <= 0x10FFFF) {
            codePoint -= 0x10000;
            chars.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            chars.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

HANDLE openArchive(JNIEnv* env, jstring path, unsigned int mode, unsigned int* result) {
    const char* utfPath = env->GetStringUTFChars(path, nullptr);
    if (utfPath == nullptr) return nullptr;
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(utfPath);
    data.OpenMode = mode;
    HANDLE archive = RAROpenArchiveEx(&data);
    env->ReleaseStringUTFChars(path, utfPath);
    if (result != nullptr) *result = data.OpenResult;
    if (archive == nullptr && result != nullptr) *result = data.OpenResult;
    return archive;
}

int rarCallback(UINT message, LPARAM userData, LPARAM first, LPARAM second) {
    auto* state = reinterpret_cast<ExtractState*>(userData);
    if (state == nullptr) return -1;
    if (message == UCM_LARGEDICT) {
        const uint64_t required = static_cast<uint64_t>(first) * 1024ULL;
        if (required > state->maxDictionaryBytes) {
            state->rejectedDictionaryBytes = required;
            return 0;
        }
        return 1;
    }
    if (message != UCM_PROCESSDATA) return 0;

    if (state->cancellationCheck != nullptr && state->invoke != nullptr) {
        jobject ignored = state->env->CallObjectMethod(state->cancellationCheck, state->invoke);
        if (ignored != nullptr) state->env->DeleteLocalRef(ignored);
        if (state->env->ExceptionCheck()) return -1;
    }
    const auto count = static_cast<size_t>(second);
    if (count > state->maxPageBytes - std::min<uint64_t>(state->maxPageBytes, state->bytes.size())) {
        state->pageTooLarge = true;
        return -1;
    }
    const auto* data = reinterpret_cast<const unsigned char*>(first);
    try {
        state->bytes.insert(state->bytes.end(), data, data + count);
    } catch (const std::bad_alloc&) {
        state->outputOutOfMemory = true;
        return -1;
    }
    return 0;
}

void closeArchive(HANDLE archive) {
    if (archive != nullptr) RARCloseArchive(archive);
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeList(JNIEnv* env, jobject, jstring path) {
    unsigned int openResult = 0;
    HANDLE archive = openArchive(env, path, RAR_OM_LIST, &openResult);
    if (archive == nullptr) {
        throwIOException(env, "Could not open this RAR archive (UnRAR error " + std::to_string(openResult) + ").");
        return nullptr;
    }

    jclass entryClass = env->FindClass("dev/tabletcomics/archive/NativeRarEntry");
    if (entryClass == nullptr) { closeArchive(archive); return nullptr; }
    jmethodID constructor = env->GetMethodID(entryClass, "<init>", "(Ljava/lang/String;JI)V");
    if (constructor == nullptr) { closeArchive(archive); return nullptr; }

    struct EntryData { std::wstring name; jlong size; jint flags; };
    std::vector<EntryData> entries;
    entries.reserve(512);
    std::vector<wchar_t> nameBuffer(32768, 0);
    RARHeaderDataEx header{};
    header.FileNameEx = nameBuffer.data();
    header.FileNameExSize = static_cast<unsigned int>(nameBuffer.size());
    int code = ERAR_SUCCESS;
    while ((code = RARReadHeaderEx(archive, &header)) == ERAR_SUCCESS) {
        if (entries.size() >= kMaxEntries) {
            closeArchive(archive);
            throwIOException(env, "Comic has too many archive entries.");
            return nullptr;
        }
        const uint64_t size = (static_cast<uint64_t>(header.UnpSizeHigh) << 32) | header.UnpSize;
        try {
            entries.push_back({nameBuffer.data(), static_cast<jlong>(size), static_cast<jint>(header.Flags)});
        } catch (const std::bad_alloc&) {
            closeArchive(archive);
            throwIOException(env, "Not enough memory to read this RAR directory.");
            return nullptr;
        }
        code = RARProcessFile(archive, RAR_SKIP, nullptr, nullptr);
        if (code != ERAR_SUCCESS) break;
        header = RARHeaderDataEx{};
        std::fill(nameBuffer.begin(), nameBuffer.end(), 0);
        header.FileNameEx = nameBuffer.data();
        header.FileNameExSize = static_cast<unsigned int>(nameBuffer.size());
    }
    closeArchive(archive);
    if (code != ERAR_END_ARCHIVE) {
        throwIOException(env, "Could not read the RAR directory (UnRAR error " + std::to_string(code) + ").");
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(entries.size()), entryClass, nullptr);
    if (result == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(entries.size()); ++i) {
        const EntryData& entry = entries[static_cast<size_t>(i)];
        jstring name = wideToJstring(env, entry.name.c_str());
        jobject value = env->NewObject(entryClass, constructor, name, entry.size, entry.flags);
        if (name != nullptr) env->DeleteLocalRef(name);
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_tabletcomics_archive_NativeUnrar_nativeExtract(JNIEnv* env, jobject, jstring path,
                                                         jstring entryName, jlong maxPageBytes,
                                                         jlong maxDictionaryBytes,
                                                         jobject cancellationCheck) {
    unsigned int openResult = 0;
    HANDLE archive = openArchive(env, path, RAR_OM_EXTRACT, &openResult);
    if (archive == nullptr) {
        throwIOException(env, "Could not open this RAR archive (UnRAR error " + std::to_string(openResult) + ").");
        return nullptr;
    }

    ExtractState state;
    state.env = env;
    state.maxPageBytes = static_cast<uint64_t>(std::max<jlong>(0, maxPageBytes));
    state.maxDictionaryBytes = static_cast<uint64_t>(std::max<jlong>(0, maxDictionaryBytes));
    state.cancellationCheck = env->NewGlobalRef(cancellationCheck);
    jclass functionClass = env->FindClass("kotlin/jvm/functions/Function0");
    if (functionClass != nullptr) state.invoke = env->GetMethodID(functionClass, "invoke", "()Ljava/lang/Object;");
    RARSetCallback(archive, rarCallback, reinterpret_cast<LPARAM>(&state));

    const std::wstring wanted = jstringToWide(env, entryName);
    std::vector<wchar_t> nameBuffer(32768, 0);
    RARHeaderDataEx header{};
    header.FileNameEx = nameBuffer.data();
    header.FileNameExSize = static_cast<unsigned int>(nameBuffer.size());
    int code = ERAR_SUCCESS;
    bool found = false;
    while ((code = RARReadHeaderEx(archive, &header)) == ERAR_SUCCESS) {
        if (wanted == nameBuffer.data()) {
            found = true;
            code = RARProcessFile(archive, RAR_TEST, nullptr, nullptr);
            break;
        }
        code = RARProcessFile(archive, RAR_SKIP, nullptr, nullptr);
        if (code != ERAR_SUCCESS) break;
        header = RARHeaderDataEx{};
        header.FileNameEx = nameBuffer.data();
        header.FileNameExSize = static_cast<unsigned int>(nameBuffer.size());
    }

    closeArchive(archive);
    if (state.cancellationCheck != nullptr) env->DeleteGlobalRef(state.cancellationCheck);
    if (env->ExceptionCheck()) return nullptr;
    if (state.rejectedDictionaryBytes > 0) {
        throwDictionaryException(env, state.rejectedDictionaryBytes, state.maxDictionaryBytes);
        return nullptr;
    }
    if (state.pageTooLarge) {
        throwIOException(env, "This RAR page exceeds the app's image-size limit.");
        return nullptr;
    }
    if (state.outputOutOfMemory) {
        throwIOException(env, "Not enough memory to decode this RAR page.");
        return nullptr;
    }
    if (!found) {
        throwIOException(env, "This page is missing from the RAR archive.");
        return nullptr;
    }
    if (code != ERAR_SUCCESS) {
        throwIOException(env, "Could not extract this RAR page (UnRAR error " + std::to_string(code) + ").");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(state.bytes.size()));
    if (result != nullptr && !state.bytes.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(state.bytes.size()),
                                reinterpret_cast<const jbyte*>(state.bytes.data()));
    }
    return result;
}
