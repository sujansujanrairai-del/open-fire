/*
 * The decoded sound library.
 *
 * The store is written from the game thread (a clip is loaded once, before it
 * is ever heard) and read from the audio thread. A loaded clip is immutable,
 * so a plain shared_ptr hand-off is enough: the audio thread takes a copy of
 * the pointer when a voice starts, and the clip stays alive until the voice
 * ends even if the store has already dropped it.
 */
#ifndef OPENFIRE_CLIP_STORE_H
#define OPENFIRE_CLIP_STORE_H

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "Clip.h"

namespace openfire {

class ClipStore {
public:
    /** Stores a decoded clip and returns its id (> 0), or 0 on failure. */
    int32_t add(int32_t frames, int32_t channels, std::vector<int16_t> samples) {
        if (frames <= 0 || channels <= 0 || channels > 8) return 0;
        const size_t expected = static_cast<size_t>(frames) * static_cast<size_t>(channels);
        if (samples.size() < expected) return 0;
        auto clip = std::make_shared<Clip>(frames, channels, std::move(samples));
        std::lock_guard<std::mutex> lock(mutex_);
        const int32_t id = nextId_++;
        clips_[id] = clip;
        return id;
    }

    /** Returns the clip, or nullptr. Safe to call from the audio thread. */
    std::shared_ptr<const Clip> get(int32_t id) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = clips_.find(id);
        return found == clips_.end() ? nullptr : found->second;
    }

    void remove(int32_t id) {
        std::lock_guard<std::mutex> lock(mutex_);
        clips_.erase(id);
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        clips_.clear();
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return clips_.size();
    }

private:
    mutable std::mutex mutex_;
    std::unordered_map<int32_t, std::shared_ptr<const Clip>> clips_;
    int32_t nextId_ = 1;
};

}  // namespace openfire

#endif  // OPENFIRE_CLIP_STORE_H
