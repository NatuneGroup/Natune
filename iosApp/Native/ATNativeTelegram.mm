/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#import "ATNativeTelegram.h"

#include <td/telegram/td_json_client.h>

#include <new>

/*
 * One TDLib JSON client. The td_json_client_* symbols and their header come from the tdlight-team/tdlight pin
 * 2b51b33c8a905be96c40508e68485f8a719e26a3 (TDLib 1.8.66). The native producer must put that header on the include path
 * and link the matching JSON library, so no other TDLib build is mixed in. This file adds no behaviour of its own.
 */
struct at_telegram_client {
    void *handle;
};

at_telegram_client *at_telegram_client_create(void) {
    void *handle = td_json_client_create();
    if (handle == nullptr) {
        return nullptr;
    }
    at_telegram_client *client = new (std::nothrow) at_telegram_client;
    if (client == nullptr) {
        td_json_client_destroy(handle);
        return nullptr;
    }
    client->handle = handle;
    return client;
}

int at_telegram_client_send(at_telegram_client *client, const char *request) {
    if (client == nullptr || client->handle == nullptr || request == nullptr) {
        return -1;
    }
    td_json_client_send(client->handle, request);
    return 0;
}

const char *at_telegram_client_receive(at_telegram_client *client, double timeout_seconds) {
    if (client == nullptr || client->handle == nullptr) {
        return nullptr;
    }
    return td_json_client_receive(client->handle, timeout_seconds);
}

const char *at_telegram_client_execute(at_telegram_client *client, const char *request) {
    if (request == nullptr) {
        return nullptr;
    }
    return td_json_client_execute(client == nullptr ? nullptr : client->handle, request);
}

void at_telegram_client_destroy(at_telegram_client *client) {
    if (client == nullptr) {
        return;
    }
    if (client->handle != nullptr) {
        td_json_client_destroy(client->handle);
    }
    delete client;
}
