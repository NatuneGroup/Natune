#import "ATAudioException.h"
#include <stdio.h>
#include <stdlib.h>

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "CHECK failed at %s:%d: %s\n", __FILE__, __LINE__, #value); exit(1); } } while (0)

int main(void) {
    @autoreleasepool {
        CHECK(audio_try(^BOOL { return YES; }));
        CHECK(!audio_try(^BOOL { return NO; }));
        __block int calls = 0;
        CHECK(!audio_try(^BOOL {
            ++calls;
            [NSException raise:NSInternalInconsistencyException format:@"injected setFormat rejection -10868"];
            return YES;
        }));
        CHECK(calls == 1);
        CHECK(audio_try(^BOOL { ++calls; return YES; }));
        CHECK(calls == 2); // A caught Apple exception does not prevent the plain-graph retry.
        CHECK(!audio_try(^BOOL {
            @try {
                [NSException raise:NSInvalidArgumentException format:@"injected graph teardown rejection"];
            } @finally {
                ++calls;
            }
            return YES;
        }));
        CHECK(calls == 3);
        puts("PASS Objective-C success/refusal/NSException containment and post-exception retry");
    }
    return 0;
}
