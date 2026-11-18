/*
 * Bounded lock-free MPMC command queue for game/control threads -> Oboe.
 *
 * Android gameplay commands can originate on the UI thread and several
 * coroutine/decoder threads, so an SPSC queue silently corrupted concurrent
 * play/stop requests. Per-cell sequence numbers give every producer its own
 * reservation while keeping the audio callback lock-free and allocation-free.
 */
#ifndef OPENFIRE_RING_BUFFER_H
#define OPENFIRE_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace openfire {

template <typename T, size_t Capacity>
class CommandRing {
    static_assert(Capacity >= 2, "Queue capacity must be at least two");
    static_assert((Capacity & (Capacity - 1)) == 0, "Queue capacity must be a power of two");
    static_assert(std::is_trivially_copyable<T>::value, "Audio commands must be POD-like");

    struct Cell {
        std::atomic<size_t> sequence;
        T item{};
    };

public:
    CommandRing() : enqueuePosition_(0), dequeuePosition_(0) {
        for (size_t i = 0; i < Capacity; ++i) {
            cells_[i].sequence.store(i, std::memory_order_relaxed);
        }
    }

    bool try_push(const T& item) {
        size_t position = enqueuePosition_.load(std::memory_order_relaxed);
        Cell* cell = nullptr;
        for (;;) {
            cell = &cells_[position & kMask];
            const size_t sequence = cell->sequence.load(std::memory_order_acquire);
            const intptr_t difference = static_cast<intptr_t>(sequence) -
                                        static_cast<intptr_t>(position);
            if (difference == 0) {
                if (enqueuePosition_.compare_exchange_weak(
                        position, position + 1, std::memory_order_relaxed)) break;
            } else if (difference < 0) {
                return false; // full
            } else {
                position = enqueuePosition_.load(std::memory_order_relaxed);
            }
        }
        cell->item = item;
        cell->sequence.store(position + 1, std::memory_order_release);
        return true;
    }

    bool try_pop(T* out) {
        if (out == nullptr) return false;
        size_t position = dequeuePosition_.load(std::memory_order_relaxed);
        Cell* cell = nullptr;
        for (;;) {
            cell = &cells_[position & kMask];
            const size_t sequence = cell->sequence.load(std::memory_order_acquire);
            const intptr_t difference = static_cast<intptr_t>(sequence) -
                                        static_cast<intptr_t>(position + 1);
            if (difference == 0) {
                if (dequeuePosition_.compare_exchange_weak(
                        position, position + 1, std::memory_order_relaxed)) break;
            } else if (difference < 0) {
                return false; // empty
            } else {
                position = dequeuePosition_.load(std::memory_order_relaxed);
            }
        }
        *out = cell->item;
        cell->sequence.store(position + Capacity, std::memory_order_release);
        return true;
    }

    bool empty() const {
        const size_t position = dequeuePosition_.load(std::memory_order_relaxed);
        const Cell& cell = cells_[position & kMask];
        return static_cast<intptr_t>(cell.sequence.load(std::memory_order_acquire)) -
                   static_cast<intptr_t>(position + 1) < 0;
    }

    static constexpr size_t capacity() { return Capacity; }

private:
    static constexpr size_t kMask = Capacity - 1;
    Cell cells_[Capacity];
    std::atomic<size_t> enqueuePosition_;
    std::atomic<size_t> dequeuePosition_;
};

}  // namespace openfire

#endif  // OPENFIRE_RING_BUFFER_H
