/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from Mornye (https://github.com/mornyeapp/Mornye), Copyright 2026 zarzet.
 * SPDX-License-Identifier of the adapted source: LGPL-3.0-only
 */

#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace natune::usbdirect {

// Frames per isochronous packet. Without feedback (adaptive and synchronous
// endpoints) the nominal rate is spread over packets with a running remainder;
// an asynchronous endpoint's feedback replaces the nominal rate once it reads
// as plausible.
class PacketClock {
public:
    PacketClock(uint32_t sampleRate, uint32_t intervalUs, bool highSpeed)
        : nominal_(static_cast<double>(sampleRate) * intervalUs / 1000000.0),
          busFramesPerPacket_(static_cast<double>(intervalUs) / (highSpeed ? 125.0 : 1000.0)),
          highSpeed_(highSpeed) {}

    // Feedback is frames per bus (micro)frame: 10.14 in three bytes at full
    // speed, 16.16 in four at high speed. Some full-speed devices send 16.16
    // and some high-speed ones 10.14 in four bytes, so the other reading is
    // tried when the specified one is implausible.
    bool feedback(const uint8_t *bytes, size_t size) {
        if (size < 3) return false;
        uint32_t raw = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16);
        if (size >= 4) raw |= static_cast<uint32_t>(bytes[3]) << 24;
        const double tenFourteen = raw / 16384.0;
        const double sixteenSixteen = raw / 65536.0;
        const bool preferTenFourteen = size == 3 || !highSpeed_;
        const double first = (preferTenFourteen ? tenFourteen : sixteenSixteen) * busFramesPerPacket_;
        const double second = (preferTenFourteen ? sixteenSixteen : tenFourteen) * busFramesPerPacket_;
        if (plausible(first)) {
            frames_ = first;
        } else if (plausible(second)) {
            frames_ = second;
        } else {
            return false;
        }
        hasFeedback_ = true;
        return true;
    }

    size_t next() {
        remainder_ += hasFeedback_ ? frames_ : nominal_;
        const double whole = std::floor(remainder_);
        remainder_ -= whole;
        return whole > 0 ? static_cast<size_t>(whole) : 0;
    }

    double nominal() const { return nominal_; }

private:
    bool plausible(double frames) const {
        return std::isfinite(frames) && std::fabs(frames - nominal_) <= nominal_ * 0.02;
    }

    double nominal_;
    double busFramesPerPacket_;
    bool highSpeed_;
    double frames_ = 0;
    bool hasFeedback_ = false;
    double remainder_ = 0;
};

// Fixed-capacity byte FIFO. The caller holds the lock; reads and writes are at
// most two memcpy calls each, unlike the per-byte deque the transport started
// from.
class ByteRing {
public:
    void reset(size_t capacity) {
        buffer_.assign(capacity, 0);
        head_ = 0;
        size_ = 0;
    }

    size_t capacity() const { return buffer_.size(); }
    size_t size() const { return size_; }
    size_t space() const { return buffer_.size() - size_; }

    size_t write(const uint8_t *data, size_t count) {
        count = std::min(count, space());
        if (count == 0) return 0;
        const size_t tail = (head_ + size_) % std::max<size_t>(buffer_.size(), 1);
        const size_t first = std::min(count, buffer_.size() - tail);
        std::memcpy(buffer_.data() + tail, data, first);
        std::memcpy(buffer_.data(), data + first, count - first);
        size_ += count;
        return count;
    }

    size_t read(uint8_t *out, size_t count) {
        count = std::min(count, size_);
        if (count == 0) return 0;
        const size_t first = std::min(count, buffer_.size() - head_);
        std::memcpy(out, buffer_.data() + head_, first);
        std::memcpy(out + first, buffer_.data(), count - first);
        head_ = (head_ + count) % std::max<size_t>(buffer_.size(), 1);
        size_ -= count;
        return count;
    }

    void clear() {
        head_ = 0;
        size_ = 0;
    }

private:
    std::vector<uint8_t> buffer_;
    size_t head_ = 0;
    size_t size_ = 0;
};

// Bus period of one packet: bInterval counts 125 us microframes at high speed
// and 1 ms frames at full speed, as 2^(bInterval - 1).
inline uint32_t packetIntervalUs(int bInterval, bool highSpeed) {
    if (bInterval < 1 || bInterval > 16) return 0;
    return (highSpeed ? 125u : 1000u) << (bInterval - 1);
}

// Largest number of frames one packet may carry at this rate, allowing the 2%
// feedback deviation the clock accepts.
inline size_t maxFramesPerPacket(uint32_t sampleRate, uint32_t intervalUs) {
    const double nominal = static_cast<double>(sampleRate) * intervalUs / 1000000.0;
    return static_cast<size_t>(std::ceil(nominal * 1.02));
}

}  // namespace natune::usbdirect
