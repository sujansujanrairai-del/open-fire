/*
 * Lock-free single-producer/single-consumer command ring.
 *
 * The game thread pushes commands, the Oboe callback drains them. Nothing is
 * ever locked and nothing allocates: a command is a POD union large enough for
 * the biggest request (a full of_voice_params), and the ring is a fixed array
 * allocated when the engine is created.
 *
 * A full ring never blocks the caller and never drops silently: `try_push`
 * returns false, and the caller falls back to applying the command directly
 * with the engine's mutex (which can only happen under a pathological burst of
 * thousands of requests inside one callback period).
 */
#ifndef OPENFIRE_RING_BUFFER_H
#define OPENFIRE_RING_BUFFER_H

#include <atomic>
#include <cstdint>
#include <cstring>

namespace openfire {

template <typename T, size_t Capacity>
class CommandRing {
public:
    CommandRing() : writeIndex_(0), readIndex_(0) {}

    bool try_push(const T& item) {
        const size_t write = writeIndex_.load(std::memory_order_relaxed);
        const size_t next = (write + 1) % Capacity;
        if (next == readIndex_.load(std::memory_order_acquire)) {
            return false;  // full
        }
        slots_[write] = item;
        writeIndex_.store(next, std::memory_order_release);
        return true;
    }

    bool try_pop(T* out) {
        const size_t read = readIndex_.load(std::memory_order_relaxed);
        if (read == writeIndex_.load(std::memory_order_acquire)) {
            return false;  // empty
        }
        *out = slots_[read];
        readIndex_.store((read + 1) % Capacity, std::memory_order_release);
        return true;
    }

    bool empty() const {
        return readIndex_.load(std::memory_order_acquire) ==
               writeIndex_.load(std::memory_order_acquire);
    }

    static constexpr size_t capacity() { return Capacity; }

private:
    T slots_[Capacity];
    std::atomic<size_t> writeIndex_;
    std::atomic<size_t> readIndex_;
};

}  // namespace openfire

#endif  // OPENFIRE_RING_BUFFER_H
