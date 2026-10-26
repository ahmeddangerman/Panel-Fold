#include "rar_session.hpp"

#include <algorithm>
#include <limits>
#include <mutex>
#include <new>

#include "rar.hpp"

namespace tabletcomics {
namespace {
constexpr size_t kMaxEntries = 20000;
std::mutex engineMutex;

struct ReadState {
    ReadState(std::atomic<bool>* cancelFlag, const std::function<bool()>* check,
              uint64_t pageLimit, uint64_t dictionaryLimit)
        : cancelled(cancelFlag), keepGoing(check), maxPageBytes(pageLimit),
          maxDictionaryBytes(dictionaryLimit) {}
    std::atomic<bool>* cancelled;
    const std::function<bool()>* keepGoing;
    uint64_t maxPageBytes;
    uint64_t maxDictionaryBytes;
    uint64_t rejectedDictionaryBytes = 0;
    bool collect = false;
    bool tooLarge = false;
    bool outOfMemory = false;
    std::vector<unsigned char> bytes;

    bool check() const {
        if (cancelled->load()) return false;
        if (!(*keepGoing)()) {
            cancelled->store(true);
            return false;
        }
        return !cancelled->load();
    }
};

int callback(UINT message, LPARAM userData, LPARAM first, LPARAM second) {
    auto* state = reinterpret_cast<ReadState*>(userData);
    if (!state->check()) return -1;
    if (message == UCM_LARGEDICT) {
        const uint64_t required = static_cast<uint64_t>(first) * 1024;
        if (required > state->maxDictionaryBytes) {
            state->rejectedDictionaryBytes = required;
            return 0;
        }
        return 1;
    }
    // Progress is emitted during packed reads and solid skips, without copying
    // skipped output into the requested page's byte vector.
    if (message != UCM_PROCESSDATA || !state->collect) return 0;
    const auto count = static_cast<size_t>(second);
    if (count > state->maxPageBytes - std::min<uint64_t>(state->maxPageBytes, state->bytes.size())) {
        state->tooLarge = true;
        return -1;
    }
    const auto* bytes = reinterpret_cast<const unsigned char*>(first);
    try {
        state->bytes.insert(state->bytes.end(), bytes, bytes + count);
    } catch (const std::bad_alloc&) {
        state->outOfMemory = true;
        return -1;
    }
    return 0;
}

HANDLE open(const std::string& path, unsigned int mode) {
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path.c_str());
    data.OpenMode = mode;
    HANDLE archive = RAROpenArchiveEx(&data);
    if (archive == nullptr)
        throw RarError("Could not open this RAR archive (UnRAR error " + std::to_string(data.OpenResult) + ").");
    return archive;
}

void configureHeader(RARHeaderDataEx& header, std::vector<wchar_t>& name) {
    header = RARHeaderDataEx{};
    std::fill(name.begin(), name.end(), 0);
    header.FileNameEx = name.data();
    header.FileNameExSize = static_cast<unsigned int>(name.size());
}
} // namespace

RarSession::RarSession(std::string archivePath) : path(std::move(archivePath)) {}
RarSession::~RarSession() { close(); }
void RarSession::cancel() { cancelled.store(true); }

void RarSession::reset() {
    if (archive != nullptr) RARCloseArchive(archive);
    archive = nullptr;
    nextOrdinal = 0;
    retainedDictionaryBytes = 0;
}

void RarSession::close() {
    closed.store(true);
    cancel();
    std::lock_guard<std::mutex> lock(engineMutex);
    reset();
}

std::vector<RarEntry> RarSession::list(const std::string& path) {
    std::lock_guard<std::mutex> lock(engineMutex);
    ErrHandler.Clean();
    HANDLE handle = open(path, RAR_OM_LIST);
    std::vector<RarEntry> result;
    try {
        std::vector<wchar_t> name(32768);
        RARHeaderDataEx header{};
        int code;
        while (true) {
            configureHeader(header, name);
            code = RARReadHeaderEx(handle, &header);
            if (code != ERAR_SUCCESS) break;
            if (result.size() >= kMaxEntries) throw RarError("Comic has too many archive entries.");
            result.push_back({name.data(), (static_cast<uint64_t>(header.UnpSizeHigh) << 32) | header.UnpSize, header.Flags});
            code = RARProcessFile(handle, RAR_SKIP, nullptr, nullptr);
            if (code != ERAR_SUCCESS) break;
        }
        if (code != ERAR_END_ARCHIVE)
            throw RarError("Could not read the RAR directory (UnRAR error " + std::to_string(code) + ").");
        RARCloseArchive(handle);
        return result;
    } catch (...) {
        RARCloseArchive(handle);
        throw;
    }
}

std::vector<unsigned char> RarSession::extract(size_t ordinal, uint64_t maxPageBytes,
                                              uint64_t maxDictionaryBytes,
                                              const std::function<bool()>& keepGoing) {
    std::lock_guard<std::mutex> lock(engineMutex);
    cancelled.store(false);
    if (closed.load()) throw RarError("This RAR archive is closed.");
    if (!keepGoing()) throw RarError("RAR extraction cancelled.");
    ErrHandler.Clean();
    // Lowering the budget also releases a larger dictionary retained by a prior
    // page. Backward requests must reconstruct a solid stream from its beginning.
    if (ordinal < nextOrdinal || retainedDictionaryBytes > maxDictionaryBytes) reset();
    ReadState state{&cancelled, &keepGoing, std::min<uint64_t>(maxPageBytes, std::numeric_limits<int32_t>::max()), maxDictionaryBytes};
    try {
        if (ordinal >= kMaxEntries) throw RarError("This page is missing from the RAR archive.");
        if (archive == nullptr) archive = open(path, RAR_OM_EXTRACT);
        RARSetCallback(archive, callback, reinterpret_cast<LPARAM>(&state));
        RARSetDictionaryLimit(archive, maxDictionaryBytes);
        std::vector<wchar_t> name(32768);
        RARHeaderDataEx header{};
        int code = ERAR_SUCCESS;
        bool found = false;
        while (nextOrdinal <= ordinal) {
            if (!state.check()) throw RarError("RAR extraction cancelled.");
            configureHeader(header, name);
            code = RARReadHeaderEx(archive, &header);
            if (code != ERAR_SUCCESS) break;
            state.collect = nextOrdinal == ordinal;
            const uint64_t size = (static_cast<uint64_t>(header.UnpSizeHigh) << 32) | header.UnpSize;
            if (state.collect && size > state.maxPageBytes)
                throw RarError("This RAR page exceeds the app's image-size limit.");
            code = RARProcessFile(archive, state.collect ? RAR_TEST : RAR_SKIP, nullptr, nullptr);
            if (code != ERAR_SUCCESS) break;
            if (header.Method != 0x30)
                retainedDictionaryBytes = std::max<uint64_t>(retainedDictionaryBytes, std::max<uint64_t>(header.DictSize * 1024ULL, 0x40000));
            ++nextOrdinal;
            if (state.collect) { found = true; break; }
        }
        RARSetCallback(archive, nullptr, 0);
        if (state.rejectedDictionaryBytes != 0) {
            RarError error("The RAR dictionary exceeds the current safe memory budget.");
            error.requiredDictionaryBytes = state.rejectedDictionaryBytes;
            error.allowedDictionaryBytes = maxDictionaryBytes;
            throw error;
        }
        if (state.tooLarge) throw RarError("This RAR page exceeds the app's image-size limit.");
        if (state.outOfMemory) throw RarError("Not enough memory to decode this RAR page.");
        if (!state.check()) throw RarError("RAR extraction cancelled.");
        if (code != ERAR_SUCCESS)
            throw RarError("Could not extract this RAR page (UnRAR error " + std::to_string(code) + ").");
        if (!found) throw RarError("This page is missing from the RAR archive.");
        return std::move(state.bytes);
    } catch (...) {
        // Interrupted/corrupt decompression must never be resumed with partial
        // dictionary state; the next request opens a clean extraction cursor.
        reset();
        throw;
    }
}
} // namespace tabletcomics
