/*
 * The decoded sound library.
 *
 * Clips are published by decoder threads and read by the real-time audio
 * callback. The callback must never wait on a mutex, so a fixed atomic pointer
 * table is the read path. Clip objects are retained until the engine shuts
 * down; removing a clip only withdraws its id from future plays. This keeps a
 * queued play command and any live voice safe without shared_ptr refcount work
 * or reclamation on the callback thread.
 */
#ifndef OPENFIRE_CLIP_STORE_H
#define OPENFIRE_CLIP_STORE_H

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include "Clip.h"

namespace openfire {

class ClipStore {
public:
    static constexpr size_t kMaxClips = 4096;

    ClipStore() {
        for (auto& slot : slots_) slot.store(nullptr, std::memory_order_relaxed);
    }

    /** Stores a decoded clip and returns its id (> 0), or 0 on failure. */
    int32_t add(int32_t frames, int32_t channels, std::vector<int16_t> samples) {
        if (frames <= 0 || channels <= 0 || channels > 8) return 0;
        const size_t expected = static_cast<size_t>(frames) * static_cast<size_t>(channels);
        if (samples.size() < expected) return 0;

        auto clip = std::make_unique<Clip>(frames, channels, std::move(samples));
        std::lock_guard<std::mutex> lock(mutex_);
        if (nextId_ >= static_cast<int32_t>(kMaxClips)) return 0;
        const int32_t id = nextId_++;
        const Clip* pointer = clip.get();
        owned_.push_back(std::move(clip));
        slots_[static_cast<size_t>(id)].store(pointer, std::memory_order_release);
        size_.fetch_add(1, std::memory_order_relaxed);
        return id;
    }

    /** Lock-free lookup for the real-time callback. The pointer remains valid until clear(). */
    const Clip* get(int32_t id) const {
        if (id <= 0 || id >= static_cast<int32_t>(kMaxClips)) return nullptr;
        return slots_[static_cast<size_t>(id)].load(std::memory_order_acquire);
    }

    /** Withdraws the id; the immutable clip is reclaimed later by clear(). */
    void remove(int32_t id) {
        if (id <= 0 || id >= static_cast<int32_t>(kMaxClips)) return;
        const Clip* previous = slots_[static_cast<size_t>(id)].exchange(nullptr, std::memory_order_acq_rel);
        if (previous != nullptr) size_.fetch_sub(1, std::memory_order_relaxed);
    }

    /** Call only after the stream has stopped and all voices have been cleared. */
    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& slot : slots_) slot.store(nullptr, std::memory_order_relaxed);
        owned_.clear();
        size_.store(0, std::memory_order_relaxed);
    }

    size_t size() const { return size_.load(std::memory_order_relaxed); }

private:
    std::array<std::atomic<const Clip*>, kMaxClips> slots_{};
    mutable std::mutex mutex_;
    std::vector<std::unique_ptr<Clip>> owned_;
    std::atomic<size_t> size_{0};
    int32_t nextId_ = 1;
};

}  // namespace openfire

#endif  // OPENFIRE_CLIP_STORE_H
