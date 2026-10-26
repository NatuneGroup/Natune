/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#ifndef AT_NATIVE_TELEGRAM_H
#define AT_NATIVE_TELEGRAM_H

#ifdef __cplusplus
extern "C" {
#endif

/* One TDLib JSON client, the native half of the iOS Telegram session. It wraps only the td_json_client_* interface of the
 * tdlight-team/tdlight pin 2b51b33c8a905be96c40508e68485f8a719e26a3 (TDLib 1.8.66). It is not the audio engine and shares
 * no state with it. */
typedef struct at_telegram_client at_telegram_client;

/* Creates the client. Returns NULL when the native library refuses it. The session creates at most one per process. */
at_telegram_client *at_telegram_client_create(void);

/* Queues one UTF-8 JSON request. Returns 0 when it was queued and -1 when the client is absent. */
int at_telegram_client_send(at_telegram_client *client, const char *request);

/* Waits up to timeout_seconds for one UTF-8 JSON update or reply and returns it, or NULL on timeout. Only one thread may
 * receive. TDLib owns the returned string, which stays valid only until the next receive or execute on that thread, so the
 * caller copies it at once. */
const char *at_telegram_client_receive(at_telegram_client *client, double timeout_seconds);

/* Runs one synchronous request. The returned string follows the same lifetime rule as receive. */
const char *at_telegram_client_execute(at_telegram_client *client, const char *request);

/* Destroys the client. Call it once, after no thread is receiving from it and no send or receive is still running. */
void at_telegram_client_destroy(at_telegram_client *client);

#ifdef __cplusplus
}
#endif

#endif
