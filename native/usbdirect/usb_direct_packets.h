#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <linux/usbdevice_fs.h>

namespace natune::usbdirect {

struct IsoCompletion {
    uint64_t frames = 0;
    bool badPackets = false;
};

inline IsoCompletion accountIsoCompletion(int urbStatus, const usbdevfs_iso_packet_desc *packets,
                                         size_t count, size_t frameBytes, size_t musicBytes) {
    IsoCompletion completed;
    if (urbStatus != 0 || frameBytes == 0) return completed;
    for (size_t i = 0; i < count; ++i) {
        const auto &packet = packets[i];
        const size_t music = std::min(musicBytes, static_cast<size_t>(packet.length));
        musicBytes -= music;
        if (packet.status != 0) completed.badPackets = true;
        else completed.frames += std::min(music, static_cast<size_t>(packet.actual_length)) / frameBytes;
    }
    return completed;
}

class IsoHealth {
public:
    static constexpr unsigned kMaxConsecutiveBadUrbs = 8;

    bool accept(int urbStatus, bool badPackets) {
        if (urbStatus != 0) return false;
        consecutiveBad_ = badPackets ? consecutiveBad_ + 1 : 0;
        return consecutiveBad_ < kMaxConsecutiveBadUrbs;
    }

    void reset() { consecutiveBad_ = 0; }

private:
    unsigned consecutiveBad_ = 0;
};

}  // namespace natune::usbdirect
