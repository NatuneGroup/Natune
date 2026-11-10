/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from Mornye (https://github.com/mornyeapp/Mornye), Copyright 2026 zarzet.
 * SPDX-License-Identifier of the adapted source: LGPL-3.0-only
 */

#include "usbdirect/usb_direct_output.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <poll.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <new>
#include <system_error>

namespace natune::usbdirect {
namespace {
using Clock = std::chrono::steady_clock;
constexpr int kSlots = 4;
constexpr int kMaxPackets = 32;
constexpr auto kControlWait = std::chrono::milliseconds(1500);
constexpr auto kIdleWait = std::chrono::milliseconds(2);
constexpr size_t kMaxControlQueue = 8;

int busIoctl(int fd, unsigned long request, void *data) {
    int result;
    do { result = ioctl(fd, request, data); } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : result;
}

struct FreeUrb { void operator()(usbdevfs_urb *p) const { std::free(p); } };
struct Slot {
    std::unique_ptr<usbdevfs_urb, FreeUrb> urb;
    std::vector<uint8_t> bytes;
    bool pending = false;
    bool submitted = false;
    uint64_t generation = 0;
    uint64_t musicFrames = 0;
    Clock::time_point sent;

    Slot(int packets, size_t capacity) : bytes(capacity) {
        urb.reset(static_cast<usbdevfs_urb *>(std::calloc(
            1, sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc))));
        if (!urb) throw std::bad_alloc();
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = bytes.data();
        urb->number_of_packets = packets;
        urb->usercontext = this;
    }

    bool succeeded() const {
        if (urb->status != 0) return false;
        for (int i = 0; i < urb->number_of_packets; ++i) {
            const auto &packet = urb->iso_frame_desc[i];
            if (packet.status != 0 || packet.actual_length != packet.length) return false;
        }
        return true;
    }
};

class Session {
public:
    ~Session() {
        if (fd_ < 0) return;
        cancelAll();
        const auto deadline = Clock::now() + std::chrono::milliseconds(500);
        while (anyPending() && Clock::now() < deadline) events();
        releaseInterfaces();
        // The Java connection closes after open; this is the last descriptor. USBFS close
        // synchronously kills any remaining URBs before their buffers can be freed.
        ::close(fd_);
    }

    int open(int fd, const std::vector<Candidate> &candidates) {
        fd_ = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (fd_ < 0) return -errno;
        const int speed = busIoctl(fd_, USBDEVFS_GET_SPEED, nullptr);
        // Full/high speed only: SuperSpeed needs companion endpoint descriptors.
        if (speed != 2 && speed != 3) return kErrorEndpoint;
        highSpeed_ = speed == 3;
        uint8_t active = 0;
        const int got = request(0x80, 8, 0, 0, &active, 1);
        if (got != 1) return got < 0 ? got : kErrorShortControl;
        int failure = kErrorNoCandidate;
        const auto deadline = Clock::now() + std::chrono::seconds(3);
        for (size_t i = 0; i < candidates.size(); ++i) {
            const auto &c = candidates[i];
            if (c.configuration != active) continue;
            int result = configure(c);
            if (result == 0) {
                try {
                    for (int slot = 0; slot < kSlots; ++slot) {
                        slots_.push_back(std::make_unique<Slot>(packets_, packets_ * c.data.maxPacket));
                    }
                    if (c.hasFeedback) feedback_ = std::make_unique<Slot>(1, c.feedback.maxPacket);
                } catch (const std::bad_alloc &) { result = kErrorNoMemory; }
            }
            if (result == 0) return static_cast<int>(i);
            failure = result;
            slots_.clear();
            feedback_.reset();
            releaseInterfaces();
            if (result == -ENODEV || result == -EBUSY || result == -EACCES || Clock::now() >= deadline) break;
        }
        return failure;
    }

    const Candidate &chosen() const { return chosen_; }

    void run(Output::Shared &shared) {
        const size_t frameBytes = chosen_.subslot * chosen_.channels;
        PacketClock clock(chosen_.sampleRate, intervalUs_, highSpeed_);
        uint64_t generation = 0;
        auto lastFeedback = Clock::now();
        bool wasPlaying = false;
        auto fail = [&](int error) {
            if (shared.error == 0) shared.error = error;
            shared.playing = false;
            cancelAll();
            shared.changed.notify_all();
        };
        for (;;) {
            const int eventResult = anyPending() ? events() : 0;
            std::unique_lock<std::mutex> lock(shared.mutex);
            if (shared.closed) break;
            if (eventResult < 0) fail(eventResult);
            if (shared.generation != generation) {
                generation = shared.generation;
                cancelAll();
                clock = PacketClock(chosen_.sampleRate, intervalUs_, highSpeed_);
                lastFeedback = Clock::now();
            }
            for (auto &slot : slots_) {
                if (slot->pending) {
                    if (Clock::now() - slot->sent > std::chrono::milliseconds(500)) fail(kErrorTimeout);
                } else if (slot->submitted) {
                    slot->submitted = false;
                    if (slot->generation == generation && shared.error == 0) {
                        if (!slot->succeeded()) fail(kErrorTransfer);
                        else shared.frames += slot->musicFrames;
                    }
                    slot->musicFrames = 0;
                }
            }
            if (!shared.controls.empty()) {
                auto control = shared.controls.front();
                shared.controls.pop_front();
                if (!control->abandoned) {
                    lock.unlock();
                    int result = request(control->input ? 0xa1 : 0x21, control->request,
                                         control->value, control->index, control->data.data(), control->data.size());
                    lock.lock();
                    control->result = result;
                    control->done = true;
                    shared.changed.notify_all();
                }
            }
            if (!shared.playing || shared.error != 0) {
                wasPlaying = false;
                // Keep the bus available for restoring hardware volume before close.
                shared.changed.wait_for(lock, std::chrono::milliseconds(20));
                continue;
            }
            if (!wasPlaying) { lastFeedback = Clock::now(); wasPlaying = true; }
            // Old transfers must finish cancellation before new-generation data is submitted.
            bool flushing = false;
            for (const auto &slot : slots_) flushing |= slot->pending && slot->generation != generation;
            if (feedback_) flushing |= feedback_->pending && feedback_->generation != generation;
            if (flushing) continue;
            if (feedback_ && !feedback_->pending) {
                if (feedback_->submitted && feedback_->generation == generation && feedback_->urb->status == 0 &&
                    feedback_->urb->iso_frame_desc[0].status == 0) {
                    const size_t got = feedback_->urb->iso_frame_desc[0].actual_length;
                    if (got <= feedback_->bytes.size() && clock.feedback(feedback_->bytes.data(), got)) lastFeedback = Clock::now();
                }
                feedback_->generation = generation;
                auto &packet = feedback_->urb->iso_frame_desc[0];
                packet.length = feedback_->bytes.size();
                const int result = submit(*feedback_, chosen_.feedback.address, packet.length);
                if (result < 0) { fail(result); continue; }
            }
            if (feedback_ && Clock::now() - lastFeedback > std::chrono::seconds(2)) {
                fail(kErrorTransfer);
                continue;
            }
            for (auto &slot : slots_) {
                if (slot->pending) continue;
                size_t total = 0;
                for (int packet = 0; packet < packets_; ++packet) {
                    const size_t length = clock.next() * frameBytes;
                    if (length > chosen_.data.maxPacket) { fail(kErrorBandwidth); break; }
                    slot->urb->iso_frame_desc[packet].length = length;
                    total += length;
                }
                if (shared.error != 0) break;
                const size_t real = std::min(total, shared.ring.size()) / frameBytes * frameBytes;
                shared.ring.read(slot->bytes.data(), real);
                std::memset(slot->bytes.data() + real, 0, total - real);
                slot->musicFrames = real / frameBytes;
                slot->generation = generation;
                const int result = submit(*slot, chosen_.data.address, total);
                if (result < 0) { fail(result); break; }
            }
        }
    }

private:
    int request(uint8_t type, uint8_t request, uint16_t value, uint16_t index, uint8_t *data, uint16_t size) {
        usbdevfs_ctrltransfer transfer{};
        transfer.bRequestType = type;
        transfer.bRequest = request;
        transfer.wValue = value;
        transfer.wIndex = index;
        transfer.wLength = size;
        transfer.timeout = 500;
        transfer.data = data;
        return busIoctl(fd_, USBDEVFS_CONTROL, &transfer);
    }

    int exact(bool input, uint8_t requestId, uint16_t index, uint8_t *data, uint16_t size, bool endpoint = false) {
        const uint8_t type = (input ? 0x80 : 0) | 0x20 | (endpoint ? 2 : 1);
        const int result = request(type, requestId, 0x100, index, data, size);
        return result == size ? 0 : result < 0 ? result : kErrorShortControl;
    }

    int claim(int interface) {
        for (const auto &entry : claimed_) if (entry.first == interface) return 0;
        unsigned id = interface;
        int result = busIoctl(fd_, USBDEVFS_CLAIMINTERFACE, &id);
        bool detached = false;
        if (result == -EBUSY) {
            usbdevfs_disconnect_claim claim{};
            claim.interface = interface;
            result = busIoctl(fd_, USBDEVFS_DISCONNECT_CLAIM, &claim);
            detached = result == 0;
        }
        if (result == 0) claimed_.push_back({interface, detached});
        return result;
    }

    int setAlternate(int interface, int alternate) {
        usbdevfs_setinterface setting{static_cast<unsigned>(interface), static_cast<unsigned>(alternate)};
        return busIoctl(fd_, USBDEVFS_SETINTERFACE, &setting);
    }

    int configure(const Candidate &c) {
        const auto &ep = c.data;
        const uint32_t period = packetIntervalUs(ep.interval, highSpeed_);
        if (period == 0 || period > 8000) return kErrorEndpoint;
        const size_t frames = c.hasFeedback ? maxFramesPerPacket(c.sampleRate, period)
            : static_cast<size_t>(std::ceil(static_cast<double>(c.sampleRate) * period / 1000000.0));
        const size_t needed = frames * c.subslot * c.channels;
        if (needed > ep.maxPacket) return kErrorBandwidth;
        int result = claim(c.control);
        if (result == 0) result = claim(c.interface);
        if (result < 0) return result;
        streamingInterface_ = c.interface;
        result = setAlternate(c.interface, 0);
        if (result < 0) return result;
        uint8_t wanted[4] = {static_cast<uint8_t>(c.sampleRate), static_cast<uint8_t>(c.sampleRate >> 8),
                            static_cast<uint8_t>(c.sampleRate >> 16), static_cast<uint8_t>(c.sampleRate >> 24)};
        uint8_t current[4] = {};
        if (c.uac2) {
            const uint16_t index = (c.clock << 8) | c.control;
            result = exact(true, 1, index, current, 4);
            if (result < 0) return result;
            if (std::memcmp(current, wanted, 4) != 0) {
                exact(false, 1, index, wanted, 4);
                result = exact(true, 1, index, current, 4);
                if (result < 0) return result;
                if (std::memcmp(current, wanted, 4) != 0) return kErrorClockRejected;
            }
        }
        result = setAlternate(c.interface, c.alternate);
        if (result < 0) return result;
        if (!c.uac2 && ep.rateControl) {
            result = exact(false, 1, ep.address, wanted, 3, true);
            if (result < 0) return result;
            result = exact(true, 0x81, ep.address, current, 3, true);
            if (result < 0) return result;
            if (std::memcmp(current, wanted, 3) != 0) return kErrorClockRejected;
        }
        chosen_ = c;
        intervalUs_ = period;
        packets_ = std::clamp<int>(4000 / period, 1, kMaxPackets);
        return 0;
    }

    int submit(Slot &slot, uint8_t endpoint, int length) {
        slot.urb->endpoint = endpoint;
        slot.urb->buffer_length = length;
        slot.urb->status = 0;
        for (int i = 0; i < slot.urb->number_of_packets; ++i) {
            slot.urb->iso_frame_desc[i].status = 0;
            slot.urb->iso_frame_desc[i].actual_length = 0;
        }
        const int result = busIoctl(fd_, USBDEVFS_SUBMITURB, slot.urb.get());
        slot.pending = result == 0;
        slot.submitted = result == 0;
        slot.sent = Clock::now();
        return result;
    }

    int events() {
        pollfd event{fd_, POLLOUT, 0};
        poll(&event, 1, 2);
        for (;;) {
            void *urb = nullptr;
            const int result = busIoctl(fd_, USBDEVFS_REAPURBNDELAY, &urb);
            if (result == -EAGAIN) return 0;
            if (result < 0) return result;
            auto *completed = static_cast<usbdevfs_urb *>(urb);
            static_cast<Slot *>(completed->usercontext)->pending = false;
        }
    }

    bool anyPending() const {
        for (const auto &slot : slots_) if (slot->pending) return true;
        return feedback_ && feedback_->pending;
    }

    void cancelAll() {
        for (auto &slot : slots_) if (slot->pending) busIoctl(fd_, USBDEVFS_DISCARDURB, slot->urb.get());
        if (feedback_ && feedback_->pending) busIoctl(fd_, USBDEVFS_DISCARDURB, feedback_->urb.get());
    }

    void releaseInterfaces() {
        if (streamingInterface_ >= 0) setAlternate(streamingInterface_, 0);
        for (auto entry = claimed_.rbegin(); entry != claimed_.rend(); ++entry) {
            unsigned id = entry->first;
            busIoctl(fd_, USBDEVFS_RELEASEINTERFACE, &id);
            if (entry->second) {
                usbdevfs_ioctl connect{entry->first, USBDEVFS_CONNECT, nullptr};
                busIoctl(fd_, USBDEVFS_IOCTL, &connect);
            }
        }
        claimed_.clear();
        streamingInterface_ = -1;
    }

    int fd_ = -1;
    bool highSpeed_ = false;
    int streamingInterface_ = -1;
    Candidate chosen_;
    uint32_t intervalUs_ = 0;
    int packets_ = 1;
    std::vector<std::pair<int, bool>> claimed_;
    std::vector<std::unique_ptr<Slot>> slots_;
    std::unique_ptr<Slot> feedback_;
};
}  // namespace
bool decodeCandidates(const int32_t *values, size_t count, std::vector<Candidate> *out) {
    if (values == nullptr || count == 0 || count % kCandidateInts != 0 || count / kCandidateInts > kMaxCandidates) {
        return false;
    }
    out->clear();
    for (size_t at = 0; at < count; at += kCandidateInts) {
        const int32_t *v = values + at;
        Candidate c;
        c.configuration = v[0];
        c.control = v[1];
        c.interface = v[2];
        c.alternate = v[3];
        c.uac2 = v[4] != 0;
        c.clock = v[5];
        c.data.address = static_cast<uint8_t>(v[6]);
        c.data.attributes = static_cast<uint8_t>(v[7]);
        c.data.maxPacket = v[8] > 0 ? static_cast<size_t>(v[8]) : 0;
        c.data.interval = v[9];
        c.data.rateControl = v[10] != 0;
        c.hasFeedback = v[11] >= 0;
        c.feedback.address = static_cast<uint8_t>(c.hasFeedback ? v[11] : 0);
        c.feedback.maxPacket = v[12] > 0 ? static_cast<size_t>(v[12]) : 0;
        c.sampleRate = v[13] > 0 ? static_cast<uint32_t>(v[13]) : 0;
        c.channels = v[14];
        c.subslot = v[15];
        c.bits = v[16];
        const bool valid = c.control >= 0 && c.control <= 255 && c.interface >= 0 && c.interface <= 255 &&
                           c.configuration > 0 && c.configuration <= 255 && c.alternate > 0 && c.alternate <= 255 && c.clock >= 0 && c.clock <= 255 &&
                           (v[6] > 0 && v[6] <= 15 && (c.data.attributes & 3) == 1 && c.data.maxPacket > 0 && c.data.maxPacket <= 3072) &&
                           c.sampleRate > 0 && c.sampleRate <= 768000 && c.channels >= 1 && c.channels <= 8 &&
                           c.subslot >= 2 && c.subslot <= 4 && c.bits >= 16 && c.bits <= c.subslot * 8 &&
                           (!c.hasFeedback || (v[11] > 128 && v[11] <= 143 && c.feedback.maxPacket >= 3 && c.feedback.maxPacket <= 64));
        if (!valid) return false;
        out->push_back(c);
    }
    return true;
}

int Output::open(int fd, std::vector<Candidate> candidates, std::shared_ptr<Output> *out) {
    if (fd < 0 || candidates.empty() || out == nullptr) return kErrorInvalidArgument;
    std::shared_ptr<Output> output;
    try {
        output.reset(new Output());
    } catch (const std::bad_alloc &) {
        return kErrorNoMemory;
    }
    Shared &shared = output->shared_;
    Output *self = output.get();
    try {
        self->worker_ = std::thread([self, fd, candidates = std::move(candidates)]() {
            Shared &state = self->shared_;
            pthread_setname_np(pthread_self(), "UsbDirect");
            // Same class as the platform's audio threads; refused without
            // harm where the process may not raise it.
            setpriority(PRIO_PROCESS, 0, -16);
            std::unique_ptr<Session> session;
            int result = kErrorNoMemory;
            try {
                session = std::make_unique<Session>();
                result = session->open(fd, candidates);
            } catch (const std::bad_alloc &) {
                result = kErrorNoMemory;
            }
            {
                std::lock_guard<std::mutex> lock(state.mutex);
                if (result >= 0) {
                    const Candidate &c = session->chosen();
                    self->frameBytes_ = static_cast<size_t>(c.subslot) * c.channels;
                    // A quarter second of audio, whole frames.
                    self->capacityBytes_ = std::max<size_t>(c.sampleRate / 4, 1024) * self->frameBytes_;
                    try {
                        state.ring.reset(self->capacityBytes_);
                    } catch (const std::bad_alloc &) {
                        result = kErrorNoMemory;
                    }
                }
                state.openResult = result;
                state.opened = true;
                state.changed.notify_all();
            }
            if (result >= 0) session->run(state);
            session.reset();
            std::lock_guard<std::mutex> lock(state.mutex);
            if (state.error == 0) state.error = kErrorClosed;
            for (auto &request : state.controls) {
                request->result = kErrorClosed;
                request->done = true;
            }
            state.controls.clear();
            state.changed.notify_all();
        });
    } catch (const std::system_error &) {
        return kErrorNoMemory;
    }
    int result;
    {
        std::unique_lock<std::mutex> lock(shared.mutex);
        shared.changed.wait(lock, [&] { return shared.opened; });
        result = shared.openResult;
    }
    if (result < 0) {
        output->close();
        return result;
    }
    *out = std::move(output);
    return result;
}

Output::~Output() { close(); }

int Output::write(const uint8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(shared_.mutex);
    if (shared_.closed) return kErrorClosed;
    if (shared_.error != 0) return shared_.error;
    const size_t n = std::min(size, shared_.ring.space()) / frameBytes_ * frameBytes_;
    shared_.ring.write(data, n);
    if (n > 0) shared_.changed.notify_all();
    return static_cast<int>(n);
}

int Output::setPlaying(bool playing) {
    std::lock_guard<std::mutex> lock(shared_.mutex);
    if (shared_.closed) return kErrorClosed;
    if (shared_.error != 0) return shared_.error;
    shared_.playing = playing;
    shared_.changed.notify_all();
    return 0;
}

int Output::flush() {
    std::lock_guard<std::mutex> lock(shared_.mutex);
    shared_.ring.clear();
    shared_.frames = 0;
    shared_.generation++;
    shared_.playing = false;
    shared_.changed.notify_all();
    return shared_.error;
}

int64_t Output::playedFrames() {
    std::lock_guard<std::mutex> lock(shared_.mutex);
    if (shared_.error != 0) return shared_.error;
    return static_cast<int64_t>(shared_.frames);
}

int Output::control(bool input, uint8_t request, uint16_t value, uint16_t index, uint8_t *data, uint16_t size) {
    auto pending = std::make_shared<ControlRequest>();
    pending->input = input;
    pending->request = request;
    pending->value = value;
    pending->index = index;
    pending->data.assign(data, data + size);
    std::unique_lock<std::mutex> lock(shared_.mutex);
    if (shared_.closed) return kErrorClosed;
    if (shared_.error == -ENODEV || shared_.error == kErrorClosed) return shared_.error;
    if (shared_.controls.size() >= kMaxControlQueue) return kErrorBusy;
    shared_.controls.push_back(pending);
    shared_.changed.notify_all();
    if (!shared_.changed.wait_for(lock, kControlWait, [&] { return pending->done; })) {
        pending->abandoned = true;
        return kErrorTimeout;
    }
    if (pending->result > 0 && input) {
        std::memcpy(data, pending->data.data(), std::min<size_t>(static_cast<size_t>(pending->result), size));
    }
    return pending->result;
}

void Output::close() {
    {
        std::lock_guard<std::mutex> lock(shared_.mutex);
        shared_.closed = true;
        shared_.changed.notify_all();
    }
    if (worker_.joinable() && worker_.get_id() != std::this_thread::get_id()) worker_.join();
}

}  // namespace natune::usbdirect
