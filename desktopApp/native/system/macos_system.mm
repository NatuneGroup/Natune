/*
 * ArchiveTune (2026), GPL-3.0
 * © Rukamori — github.com/rukamori; Portions © vossgraves — github.com/vossgraves
 * Contributors: see git history. Do not remove or alter this notice.
 */
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>
#include "jni_callbacks.hpp"
#include <memory>
#include <vector>

static void mainSync(dispatch_block_t block) {
    if ([NSThread isMainThread]) block(); else dispatch_sync(dispatch_get_main_queue(), block);
}
static NSString* string(JNIEnv* env, jstring value) {
    if (!value) return @"";
    const jchar* chars = env->GetStringChars(value, nullptr);
    NSString* result = [[NSString alloc] initWithCharacters:reinterpret_cast<const unichar*>(chars)
        length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, chars);
    return result;
}
@interface ATSystem : NSObject {
@public
    std::shared_ptr<JavaCallbacks> callbacks;
}
@property(nonatomic, strong) NSStatusItem* status;
@property(nonatomic, strong) NSWindow* window;
@property(nonatomic, strong) NSMutableArray* targets;
@property(nonatomic, strong) NSMutableArray* notifications;
@property(nonatomic, copy) NSString* track;
@property(nonatomic, copy) NSString* artworkTrack;
@property(nonatomic, strong) MPMediaItemArtwork* artwork;
@property BOOL canSeek;
@property BOOL closed;
@property BOOL sleeping;
- (void)menu:(NSMenuItem*)sender;
- (void)visibility;
- (void)dispose;
@end

@implementation ATSystem
- (void)menu:(NSMenuItem*)sender {
    if (self.closed) return;
    const char* actions[] = {"raise", "toggle", "next", "previous", "quit"};
    if (sender.tag >= 0 && sender.tag < 5) callbacks->emit(actions[sender.tag]);
}
- (void)visibility {
    if (!self.closed) callbacks->visibility(
        !(self.window.occlusionState & NSWindowOcclusionStateVisible), !self.sleeping);
}
- (void)dispose {
    self.closed = YES;
    MPRemoteCommandCenter* center = MPRemoteCommandCenter.sharedCommandCenter;
    NSArray* commands = @[center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
        center.stopCommand, center.nextTrackCommand, center.previousTrackCommand,
        center.changePlaybackPositionCommand, center.changePlaybackRateCommand];
    for (NSUInteger i = 0; i < self.targets.count; ++i) {
        [commands[i] removeTarget:self.targets[i]];
        [commands[i] setEnabled:NO];
    }
    for (id observer in self.notifications) {
        [NSNotificationCenter.defaultCenter removeObserver:observer];
        [NSWorkspace.sharedWorkspace.notificationCenter removeObserver:observer];
    }
    [NSStatusBar.systemStatusBar removeStatusItem:self.status];
    MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = nil;
    MPNowPlayingInfoCenter.defaultCenter.playbackState = MPNowPlayingPlaybackStateStopped;
    callbacks.reset();
}
@end

extern "C" JNIEXPORT jlong JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_create(
    JNIEnv* env, jobject, jlong, jobject target, jobjectArray labels) {
    auto callback = std::make_shared<JavaCallbacks>(env, target);
    NSMutableArray<NSString*>* titles = [NSMutableArray array];
    for (int i = 0; i < env->GetArrayLength(labels); ++i) {
        auto label = static_cast<jstring>(env->GetObjectArrayElement(labels, i));
        [titles addObject:string(env, label)]; env->DeleteLocalRef(label);
    }
    __block ATSystem* session = nil;
    mainSync(^{
        session = [ATSystem new];
        session->callbacks = callback;
        session.window = NSApp.keyWindow ?: NSApp.mainWindow;
        if (!session.window) { session = nil; return; }
        session.track = @"";
        session.targets = [NSMutableArray array];
        session.notifications = [NSMutableArray array];
        session.status = [NSStatusBar.systemStatusBar statusItemWithLength:NSVariableStatusItemLength];
        session.status.button.title = @"♪";
        session.status.button.toolTip = NSProcessInfo.processInfo.processName;
        NSMenu* menu = [NSMenu new];
        for (NSUInteger i = 0; i < titles.count; ++i) {
            NSMenuItem* item = [[NSMenuItem alloc] initWithTitle:titles[i] action:@selector(menu:) keyEquivalent:@""];
            item.target = session; item.tag = i; [menu addItem:item];
        }
        session.status.menu = menu;
        MPRemoteCommandCenter* center = MPRemoteCommandCenter.sharedCommandCenter;
        NSArray<MPRemoteCommand*>* commands = @[center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
            center.stopCommand, center.nextTrackCommand, center.previousTrackCommand,
            center.changePlaybackPositionCommand, center.changePlaybackRateCommand];
        const char* names[] = {"play", "pause", "toggle", "stop", "next", "previous", "seek", "speed"};
        __weak ATSystem* weak = session;
        for (NSUInteger i = 0; i < commands.count; ++i) {
            commands[i].enabled = YES;
            const char* action = names[i];
            id token = [commands[i] addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
                __block MPRemoteCommandHandlerStatus result = MPRemoteCommandHandlerStatusCommandFailed;
                mainSync(^{
                    ATSystem* strong = weak;
                    if (!strong || strong.closed) return;
                    if ([event isKindOfClass:MPChangePlaybackPositionCommandEvent.class]) {
                        if (!strong.canSeek) { result = MPRemoteCommandHandlerStatusNoSuchContent; return; }
                        auto position = static_cast<jlong>(((MPChangePlaybackPositionCommandEvent*)event).positionTime * 1000000);
                        strong->callbacks->emit(action, strong.track.UTF8String, position);
                    } else if ([event isKindOfClass:MPChangePlaybackRateCommandEvent.class]) {
                        strong->callbacks->emit(action, "", 0, ((MPChangePlaybackRateCommandEvent*)event).playbackRate);
                    } else strong->callbacks->emit(action);
                    result = MPRemoteCommandHandlerStatusSuccess;
                });
                return result;
            }];
            [session.targets addObject:token];
        }
        center.changePlaybackRateCommand.supportedPlaybackRates = @[@0.25, @0.5, @1, @1.5, @2, @4];
        id visibility = [NSNotificationCenter.defaultCenter addObserverForName:NSWindowDidChangeOcclusionStateNotification
            object:session.window queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification*) { [weak visibility]; }];
        [session.notifications addObject:visibility];
        for (NSNotificationName name in @[NSWorkspaceScreensDidSleepNotification, NSWorkspaceScreensDidWakeNotification,
                NSWorkspaceWillSleepNotification, NSWorkspaceDidWakeNotification]) {
            BOOL sleeping = [name isEqual:NSWorkspaceScreensDidSleepNotification] || [name isEqual:NSWorkspaceWillSleepNotification];
            id observer = [NSWorkspace.sharedWorkspace.notificationCenter addObserverForName:name object:nil
                queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification*) {
                    ATSystem* strong = weak;
                    if (strong && !strong.closed) { strong.sleeping = sleeping; [strong visibility]; }
                }];
            [session.notifications addObject:observer];
        }
        [session visibility];
    });
    return session ? reinterpret_cast<jlong>(CFBridgingRetain(session)) : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_update(
    JNIEnv* env, jobject, jlong handle, jstring track, jstring title, jstring artist, jstring album, jstring status,
    jlong position, jlong duration, jdouble speed, jboolean seek, jboolean next, jboolean previous, jboolean advancing) {
    ATSystem* session = (__bridge ATSystem*)reinterpret_cast<void*>(handle);
    NSString* trackValue = string(env, track);
    NSString* titleValue = string(env, title);
    NSString* artistValue = string(env, artist);
    NSString* albumValue = string(env, album);
    NSString* statusValue = string(env, status);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (session.closed) return;
        session.track = trackValue; session.canSeek = seek;
        MPNowPlayingInfoCenter* info = MPNowPlayingInfoCenter.defaultCenter;
        NSMutableDictionary* metadata = [@{
            MPMediaItemPropertyTitle: titleValue, MPMediaItemPropertyArtist: artistValue,
            MPMediaItemPropertyAlbumTitle: albumValue, MPMediaItemPropertyPlaybackDuration: @(duration / 1000000.0),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: @(position / 1000000.0),
            MPNowPlayingInfoPropertyPlaybackRate: @([statusValue isEqual:@"Playing"] && advancing ? speed : 0.0),
            MPNowPlayingInfoPropertyDefaultPlaybackRate: @(speed),
            MPNowPlayingInfoPropertyExternalContentIdentifier: trackValue,
        } mutableCopy];
        if ([session.artworkTrack isEqual:trackValue] && session.artwork)
            metadata[MPMediaItemPropertyArtwork] = session.artwork;
        else { session.artwork = nil; session.artworkTrack = nil; }
        info.nowPlayingInfo = metadata;
        info.playbackState = [statusValue isEqual:@"Playing"] ? MPNowPlayingPlaybackStatePlaying :
            [statusValue isEqual:@"Paused"] ? MPNowPlayingPlaybackStatePaused : MPNowPlayingPlaybackStateStopped;
        MPRemoteCommandCenter* center = MPRemoteCommandCenter.sharedCommandCenter;
        center.changePlaybackPositionCommand.enabled = seek;
        center.nextTrackCommand.enabled = next; center.previousTrackCommand.enabled = previous;
    });
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_setArtwork(
    JNIEnv* env, jobject, jlong handle, jstring track, jbyteArray image) {
    ATSystem* session = (__bridge ATSystem*)reinterpret_cast<void*>(handle);
    NSString* trackValue = string(env, track);
    const jsize size = image ? env->GetArrayLength(image) : 0;
    if (size > 4 * 1024 * 1024) return;
    NSMutableData* data = size ? [NSMutableData dataWithLength:size] : nil;
    if (size) env->GetByteArrayRegion(image, 0, size, static_cast<jbyte*>(data.mutableBytes));
    if (env->ExceptionCheck()) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (session.closed || ![session.track isEqual:trackValue]) return;
        NSImage* decoded = data ? [[NSImage alloc] initWithData:data] : nil;
        session.artwork = decoded ? [[MPMediaItemArtwork alloc] initWithBoundsSize:decoded.size
            requestHandler:^NSImage*(CGSize) { return decoded; }] : nil;
        session.artworkTrack = trackValue;
        MPNowPlayingInfoCenter* info = MPNowPlayingInfoCenter.defaultCenter;
        NSMutableDictionary* metadata = [info.nowPlayingInfo mutableCopy];
        if (session.artwork) metadata[MPMediaItemPropertyArtwork] = session.artwork;
        else [metadata removeObjectForKey:MPMediaItemPropertyArtwork];
        info.nowPlayingInfo = metadata;
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_trayAvailable(JNIEnv*, jobject, jlong handle) {
    __block BOOL available = NO;
    ATSystem* session = (__bridge ATSystem*)reinterpret_cast<void*>(handle);
    mainSync(^{ available = !session.closed && session.status != nil && session.status.visible; });
    return available;
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_release(JNIEnv*, jobject, jlong handle) {
    ATSystem* session = CFBridgingRelease(reinterpret_cast<void*>(handle));
    mainSync(^{ [session dispose]; });
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_pickFiles(
    JNIEnv* env, jobject, jlong, jboolean folder, jboolean multiple, jstring title) {
    NSString* titleValue = string(env, title);
    __block NSArray<NSURL*>* urls = nil;
    mainSync(^{
        NSOpenPanel* panel = [NSOpenPanel openPanel];
        panel.title = titleValue; panel.canChooseDirectories = folder;
        panel.canChooseFiles = !folder; panel.allowsMultipleSelection = multiple;
        if ([panel runModal] == NSModalResponseOK) urls = panel.URLs;
    });
    if (!urls) return nullptr;
    auto result = env->NewObjectArray(urls.count, env->FindClass("java/lang/String"), nullptr);
    for (NSUInteger i = 0; i < urls.count; ++i) {
        NSString* path = urls[i].path;
        std::vector<jchar> chars(path.length);
        [path getCharacters:reinterpret_cast<unichar*>(chars.data()) range:NSMakeRange(0, path.length)];
        auto value = env->NewString(chars.data(), chars.size());
        env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
    }
    return result;
}
