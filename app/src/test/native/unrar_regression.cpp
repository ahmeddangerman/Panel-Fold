#include "rar_session.hpp"
#include "raros.hpp"
#include "dll.hpp"

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <future>
#include <stdexcept>
#include <thread>

using tabletcomics::RarError;
using tabletcomics::RarSession;
constexpr uint64_t pageLimit = 24ULL * 1024 * 1024;
constexpr uint64_t dictionaryLimit = 256ULL * 1024 * 1024;

struct SkipProgress { unsigned int polls = 0; unsigned int outputCalls = 0; };
int skipCallback(UINT message, LPARAM user, LPARAM, LPARAM) {
    auto* progress = reinterpret_cast<SkipProgress*>(user);
    if (message == UCM_PROCESSDATA) ++progress->outputCalls;
    if (message == UCM_PROGRESS && ++progress->polls == 2) return -1;
    return message == UCM_LARGEDICT ? 1 : 0;
}

void skipCancellation(const std::string& path) {
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char*>(path.c_str());
    data.OpenMode = RAR_OM_EXTRACT;
    HANDLE handle = RAROpenArchiveEx(&data);
    assert(handle != nullptr);
    SkipProgress progress;
    RARSetCallback(handle, skipCallback, reinterpret_cast<LPARAM>(&progress));
    RARHeaderDataEx header{};
    assert(RARReadHeaderEx(handle, &header) == ERAR_SUCCESS);
    // Cancellation must occur inside the first skipped entry, before the caller
    // can read the next header. Skipped content is not an output data callback.
    assert(RARProcessFile(handle, RAR_SKIP, nullptr, nullptr) != ERAR_SUCCESS);
    assert(progress.polls == 2);
    assert(progress.outputCalls == 0);
    RARCloseArchive(handle);
}

void compressed(const std::string& path) {
    const auto entries = RarSession::list(path);
    assert(!entries.empty());
    RarSession session(path);
    auto bytes = session.extract(0, pageLimit, dictionaryLimit, [] { return true; });
    assert(bytes.size() == entries[0].size);
    bool rejected = false;
    try { session.extract(0, pageLimit, 64 * 1024, [] { return true; }); }
    catch (const RarError& error) {
        rejected = error.requiredDictionaryBytes >= 256 * 1024 && error.allowedDictionaryBytes == 64 * 1024;
    }
    assert(rejected);
    assert(session.extract(0, pageLimit, dictionaryLimit, [] { return true; }) == bytes);
}

void solid(const std::string& path) {
    const auto entries = RarSession::list(path);
    assert(entries.size() > 1);
    const size_t last = entries.size() - 1;
    RarSession sequential(path);
    sequential.extract(last - 1, pageLimit, dictionaryLimit, [] { return true; });
    size_t forwardPolls = 0;
    auto lastBytes = sequential.extract(last, pageLimit, dictionaryLimit, [&] { ++forwardPolls; return true; });
    assert(lastBytes.size() == entries[last].size);
    RarSession fresh(path);
    size_t replayPolls = 0;
    assert(fresh.extract(last, pageLimit, dictionaryLimit, [&] { ++replayPolls; return true; }) == lastBytes);
    assert(forwardPolls < replayPolls);
    // Backwards reads reset the cursor and remain correct.
    auto firstBytes = sequential.extract(0, pageLimit, dictionaryLimit, [] { return true; });
    assert(firstBytes.size() == entries[0].size);
    RarSession interrupted(path);
    size_t checks = 0;
    bool cancelled = false;
    try { interrupted.extract(last, pageLimit, dictionaryLimit, [&] { return ++checks < 4; }); }
    catch (const RarError&) { cancelled = true; }
    assert(cancelled && checks == 4);
    assert(interrupted.extract(last, pageLimit, dictionaryLimit, [] { return true; }) == lastBytes);
    std::printf("solid %s: forward polls=%zu replay polls=%zu; cancelled skip recovered\n", path.c_str(), forwardPolls, replayPolls);
    skipCancellation(path);
}

void closeDuringRead(const std::string& path) {
    RarSession session(path);
    std::atomic<bool> inside{false}, release{false}, closeStarted{false};
    auto read = std::async(std::launch::async, [&] {
        try {
            session.extract(1, pageLimit, dictionaryLimit, [&] {
                inside.store(true);
                while (!release.load()) std::this_thread::yield();
                return true;
            });
            return false;
        } catch (const RarError&) { return true; }
    });
    while (!inside.load()) std::this_thread::yield();
    auto close = std::async(std::launch::async, [&] { closeStarted.store(true); session.close(); });
    while (!closeStarted.load()) std::this_thread::yield();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    release.store(true);
    close.get();
    assert(read.get());
    bool closed = false;
    try { session.extract(0, pageLimit, dictionaryLimit, [] { return true; }); }
    catch (const RarError&) { closed = true; }
    assert(closed);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const std::string fixtures = std::string(argv[1]) + "/";
    compressed(fixtures + "compressed-rar4.rar");
    compressed(fixtures + "compressed-rar5.rar");
    solid(fixtures + "solid-rar4.rar");
    solid(fixtures + "solid-rar5.rar");
    closeDuringRead(fixtures + "solid-rar5.rar");
    std::puts("Native archive regression probes passed.");
}
