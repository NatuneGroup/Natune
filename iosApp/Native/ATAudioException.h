#ifndef AT_AUDIO_EXCEPTION_H
#define AT_AUDIO_EXCEPTION_H
#import <Foundation/Foundation.h>
#include <stdbool.h>

/* NSException must never escape into Kotlin. Do not log exception reasons, which can include private locators. */
static inline bool audio_try(BOOL (^operation)(void)) {
    @try {
        return operation() != NO;
    } @catch (NSException *exception) {
        (void)exception;
        return false;
    }
}

#endif
