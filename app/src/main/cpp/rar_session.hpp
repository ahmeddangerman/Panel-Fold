#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace tabletcomics {

struct RarEntry {
    std::wstring name;
    uint64_t size;
    unsigned int flags;
};

class RarError : public std::runtime_error {
public:
    explicit RarError(const std::string& message) : std::runtime_error(message) {}
    uint64_t requiredDictionaryBytes = 0;
    uint64_t allowedDictionaryBytes = 0;
};

// All engine calls serialize around UnRAR's process-global error handler. Cancellation
// never takes that lock, so close/cancellation can interrupt a running solid skip.
class RarSession {
public:
    explicit RarSession(std::string path);
    ~RarSession();
    RarSession(const RarSession&) = delete;
    RarSession& operator=(const RarSession&) = delete;

    std::vector<unsigned char> extract(size_t ordinal, uint64_t maxPageBytes,
                                       uint64_t maxDictionaryBytes,
                                       const std::function<bool()>& keepGoing);
    void cancel();
    void close();
    static std::vector<RarEntry> list(const std::string& path);

private:
    void reset(); // Caller holds the engine lock.
    std::string path;
    void* archive = nullptr;
    size_t nextOrdinal = 0;
    uint64_t retainedDictionaryBytes = 0;
    std::atomic<bool> cancelled{false};
    std::atomic<bool> closed{false};
};

} // namespace tabletcomics
