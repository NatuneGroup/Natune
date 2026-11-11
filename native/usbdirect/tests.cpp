#include "usbdirect/usb_direct_output.h"
#include "usbdirect/usb_direct_packets.h"
#include <cassert>
#include <cerrno>
#include <numeric>
#include <unistd.h>
using namespace natune::usbdirect;

int main() {
    usbdevfs_iso_packet_desc packets[] = {{16, 16, 0}, {16, 8, 0}, {16, 16, 0}};
    auto completed = accountIsoCompletion(0, packets, 3, 4, 40);
    assert(completed.frames == 8 && !completed.badPackets); // Partial OUT data and a silence tail.
    packets[1].status = static_cast<unsigned>(-EXDEV);
    completed = accountIsoCompletion(0, packets, 3, 4, 40);
    assert(completed.frames == 6 && completed.badPackets);
    IsoHealth health;
    assert(health.accept(0, completed.badPackets)); // An isolated missed frame is recoverable.
    assert(health.accept(0, false));
    for (unsigned i = 1; i < IsoHealth::kMaxConsecutiveBadUrbs; ++i) assert(health.accept(0, true));
    assert(!health.accept(0, true));
    health.reset();
    assert(health.accept(0, true));
    assert(!health.accept(-ENODEV, false)); // URB errors still fail immediately.
    assert(accountIsoCompletion(-EIO, packets, 3, 4, 40).frames == 0);
    packets[0].actual_length = 0;
    packets[1].status = 0;
    packets[1].actual_length = 0;
    packets[2].actual_length = 0;
    completed = accountIsoCompletion(0, packets, 3, 4, 40);
    assert(completed.frames == 0 && !completed.badPackets); // OUT controllers may omit lengths.
    assert(health.accept(0, completed.badPackets));
    packets[0].actual_length = 100;
    assert(accountIsoCompletion(0, packets, 3, 4, 4).frames == 1); // Never count padding or excess bytes.

    PacketClock full(44100, 1000, false);
    size_t frames = 0;
    for (int packet = 0; packet < 1000; ++packet) frames += full.next();
    assert(frames == 44100);
    PacketClock high(48000, 125, true);
    uint8_t feedback[] = {0, 0, 6, 0}; // 6 samples per microframe, 16.16.
    assert(high.feedback(feedback, sizeof(feedback)));
    for (int packet = 0; packet < 100; ++packet) assert(high.next() == 6);
    uint8_t implausible[] = {0, 0, 60, 0};
    assert(!high.feedback(implausible, sizeof(implausible)));
    assert(high.next() == 6);
    assert(packetIntervalUs(1, true) == 125);
    assert(packetIntervalUs(4, true) == 1000);
    assert(packetIntervalUs(0, true) == 0);

    ByteRing ring;
    ring.reset(8);
    const uint8_t data[] = {1, 2, 3, 4, 5, 6};
    uint8_t out[8]{};
    assert(ring.write(data, 6) == 6);
    assert(ring.read(out, 4) == 4);
    assert(ring.write(data, 6) == 6); // Wraps around both ends.
    assert(ring.read(out, 8) == 8);
    const uint8_t expected[] = {5, 6, 1, 2, 3, 4, 5, 6};
    assert(std::equal(out, out + 8, expected));
    assert(ring.size() == 0);

    int32_t encoded[] = {1, 0, 1, 1, 0, 0, 1, 9, 512, 1, 0, -1, 0, 48000, 2, 3, 24};
    std::vector<Candidate> candidates;
    assert(decodeCandidates(encoded, kCandidateInts, &candidates));
    assert(candidates.size() == 1 && candidates[0].subslot == 3);
    encoded[6] = 129; // An IN endpoint cannot carry playback data.
    assert(!decodeCandidates(encoded, kCandidateInts, &candidates));
    assert(!decodeCandidates(encoded, kCandidateInts - 1, &candidates));
    std::shared_ptr<Output> output;
    assert(Output::open(-1, {}, &output) == kErrorInvalidArgument);
    encoded[6] = 1;
    assert(decodeCandidates(encoded, kCandidateInts, &candidates));
    // An ordinary descriptor must fail cleanly, even when the worker started.
    assert(Output::open(STDIN_FILENO, candidates, &output) < 0);
}
