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

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "usbdirect/usb_direct_stream.h"

namespace natune::usbdirect {

struct Endpoint {
    uint8_t address = 0;
    uint8_t attributes = 0;
    size_t maxPacket = 0;
    int interval = 0;
    bool rateControl = false;
};

// One streaming alternate setting, already matched to the track's format on
// the Kotlin side.
struct Candidate {
    int configuration = 0;
    int control = 0;
    int interface = 0;
    int alternate = 0;
    bool uac2 = false;
    int clock = 0;
    Endpoint data;
    bool hasFeedback = false;
    Endpoint feedback;
    uint32_t sampleRate = 0;
    int channels = 0;
    int subslot = 0;
    int bits = 0;
};

// Ints per candidate in the array Kotlin passes:
// configuration, control, interface, alternate, uac2, clock, data address,
// data attributes, data max packet, data interval, rate control, feedback
// address (-1 for none), feedback max packet, rate, channels, subslot, bits.
constexpr size_t kCandidateInts = 17;
constexpr size_t kMaxCandidates = 32;

// USBFS failures use negative errno; transport failures use the range below.
constexpr int kErrorClockRejected = -1000;
constexpr int kErrorBandwidth = -1001;
constexpr int kErrorNoCandidate = -1002;
constexpr int kErrorInvalidArgument = -1003;
constexpr int kErrorClosed = -1004;
constexpr int kErrorTransfer = -1005;
constexpr int kErrorTimeout = -1006;
constexpr int kErrorBusy = -1007;
constexpr int kErrorNoMemory = -1008;
constexpr int kErrorEndpoint = -1009;
constexpr int kErrorShortControl = -1010;

bool decodeCandidates(const int32_t *values, size_t count, std::vector<Candidate> *out);

// A USB audio streaming interface driven through USBFS on a worker thread
// that owns the descriptor and URBs. Calls from other threads only touch the
// shared state below and never wait on the bus, except control().
class Output {
public:
    ~Output();

    // Returns the index of the candidate that was configured, or a negative
    // error; *out is set only on success.
    static int open(int fd, std::vector<Candidate> candidates, std::shared_ptr<Output> *out);

    size_t capacityFrames() const { return capacityBytes_ / frameBytes_; }

    // Copies whole frames into the ring. Returns the bytes taken (possibly 0)
    // or a negative error once the stream has failed.
    int write(const uint8_t *data, size_t size);

    int setPlaying(bool playing);

    // Drops everything queued and restarts the frame count. Returns at once:
    // transfers still on the bus are cancelled by the worker and their frames
    // are not counted.
    int flush();

    // Frames that left in completed transfers since the last flush, or a
    // negative error.
    int64_t playedFrames();

    // Class request to an interface of the device, served between transfers.
    // Returns the bytes transferred or a negative error.
    int control(bool input, uint8_t request, uint16_t value, uint16_t index, uint8_t *data, uint16_t size);

    // Stops streaming, gives the interfaces back to the kernel driver and
    // joins the worker.
    void close();

    struct ControlRequest {
        bool input = false;
        uint8_t request = 0;
        uint16_t value = 0;
        uint16_t index = 0;
        std::vector<uint8_t> data;
        int result = 0;
        bool done = false;
        bool abandoned = false;
    };

    struct Shared {
        std::mutex mutex;
        std::condition_variable changed;
        ByteRing ring;
        bool playing = false;
        bool closed = false;
        uint64_t frames = 0;
        uint64_t generation = 0;
        int error = 0;
        bool opened = false;
        int openResult = 0;
        std::deque<std::shared_ptr<ControlRequest>> controls;
    };

private:
    Output() = default;

    Shared shared_;
    std::thread worker_;
    size_t frameBytes_ = 1;
    size_t capacityBytes_ = 0;
};

}  // namespace natune::usbdirect
