/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret

/**
 * Personal TIDAL credentials of one admitted account, issued by [VaultProviderAccountStore.personalTidalLease].
 *
 * [accessToken] is the stored `tidalAccessToken`, the bearer that TidalAccess sends. [refreshToken] and [cookie] are the
 * stored `tidalRefreshToken` and `tidalCookie`, or null when they are not stored. No token is invented, so no lease is
 * issued without a stored access token. The lease never refreshes: a refresh is a network operation that the credential
 * owner performs and commits through the store, and that commit makes this lease stale.
 */
class PersonalTidalLease internal constructor(
    /** The opaque account scope that the store issued. Pass it unchanged as the access scope label. */
    val accountScope: String,
    /** The provider account id that the store accepted for this scope. It identifies the account and is not a secret. */
    val accountId: String,
    /** The accepted generation this lease belongs to. Every later commit or sign-out moves the generation. */
    val generation: Long,
    val accessToken: PlatformSecret,
    val refreshToken: PlatformSecret?,
    val cookie: PlatformSecret?,
    private val admitted: () -> Boolean,
) {
    /** Always PERSONAL. Pool, anonymous and Telegram scopes never produce a personal lease. */
    val credentialScope: CredentialScope = CredentialScope.PERSONAL

    /**
     * False once the account is signed out, replaced by another commit, or a value of this lease changed in the vault.
     * Pass it as the access `isActive` check. It reads the latest committed state on every call and never blocks.
     */
    fun isStillAdmitted(): Boolean = admitted()

    override fun toString(): String = "PersonalTidalLease(REDACTED)"
}

/**
 * Personal Deezer ARL of one admitted account, issued by [VaultProviderAccountStore.personalDeezerLease]. [arl] is the
 * stored `deezerArl`. The store refuses any value that DeezerAccess would reject, so construction never fails on a lease
 * that the store handed out.
 */
class PersonalDeezerLease internal constructor(
    /** The opaque account scope that the store issued. Pass it unchanged as the access scope label. */
    val accountScope: String,
    /** The provider account id that the store accepted for this scope. It identifies the account and is not a secret. */
    val accountId: String,
    /** The accepted generation this lease belongs to. Every later commit or sign-out moves the generation. */
    val generation: Long,
    val arl: PlatformSecret,
    private val admitted: () -> Boolean,
) {
    /** Always PERSONAL. Pool, anonymous and Telegram scopes never produce a personal lease. */
    val credentialScope: CredentialScope = CredentialScope.PERSONAL

    /** False once the account is signed out, replaced, or its stored ARL changed. Read it before each access. */
    fun isStillAdmitted(): Boolean = admitted()

    override fun toString(): String = "PersonalDeezerLease(REDACTED)"
}

/**
 * Personal QQ Music ticket of one admitted account, issued by [VaultProviderAccountStore.personalQqMusicLease].
 *
 * [uin] is the account identity that the store accepted: the decimal QQ musicid, never "0". [musicKey] is the stored
 * `qqMusicMusickey` ticket. [loginType] is read from the stored `qqMusicCredential` row exactly as the Android session
 * reads it, so a WeChat ticket keeps type 1. The lease never refreshes the ticket: the Android refresh at half-life needs
 * the network and stays with the credential owner.
 */
class PersonalQqMusicLease internal constructor(
    /** The opaque account scope that the store issued. Pass it unchanged as the access scope label. */
    val accountScope: String,
    /** The QQ musicid that the store accepted for this scope. It identifies the account and is not a secret. */
    val uin: String,
    /** The accepted generation this lease belongs to. Every later commit or sign-out moves the generation. */
    val generation: Long,
    val musicKey: PlatformSecret,
    /** The tmeLoginType the ticket belongs to: 1 for a WeChat ticket, otherwise 2. Never outside 1..2. */
    val loginType: Int,
    private val admitted: () -> Boolean,
) {
    /** Always PERSONAL. Pool, anonymous and Telegram scopes never produce a personal lease. */
    val credentialScope: CredentialScope = CredentialScope.PERSONAL

    /** False once the account is signed out, replaced, or its stored ticket or credential row changed. */
    fun isStillAdmitted(): Boolean = admitted()

    override fun toString(): String = "PersonalQqMusicLease(REDACTED)"
}

/**
 * The QQ login type that the Android session derives from a stored `qqMusicCredential` row and its ticket.
 *
 * A row that is exactly one JSON object yields its `loginType` when that field holds an integer, and 2 otherwise, as the
 * Android decoder does. A blank row, or any text that is not exactly one JSON object, falls back to
 * [inferQqMusicLoginType]. The row is read in place and is never stored or logged.
 */
internal fun qqMusicLoginTypeOf(credential: String, musicKey: String): Int {
    if (credential.isBlank()) return inferQqMusicLoginType(musicKey)
    val members = StoredJsonObject.topLevelTextOrNull(credential) ?: return inferQqMusicLoginType(musicKey)
    return members["loginType"]?.toIntOrNull() ?: 2
}

/** WeChat tickets start with `W_X`; every other ticket is a QQ login. This is the Android rule, unchanged. */
internal fun inferQqMusicLoginType(musicKey: String): Int = if (musicKey.startsWith("W_X")) 1 else 2

/**
 * A strict reader for one JSON document: exactly one object under the RFC 8259 grammar, with nothing after it. It keeps
 * only the top-level members, each as the trimmed text of a string or number, or null for any other kind. Failures never
 * include the input, so a malformed credential row is never reported or logged.
 */
private class StoredJsonObject private constructor(private val source: String) {
    private var at = 0

    private fun document(): Map<String, String?> {
        skipSpace()
        val members = readObject()
        skipSpace()
        if (at != source.length) fail()
        return members
    }

    private fun readObject(): Map<String, String?> {
        consume('{')
        val members = LinkedHashMap<String, String?>()
        skipSpace()
        if (here('}')) {
            at++
            return members
        }
        var done = false
        while (!done) {
            skipSpace()
            val key = readString()
            skipSpace()
            consume(':')
            skipSpace()
            members[key] = readValue()
            skipSpace()
            when (next()) {
                ',' -> Unit
                '}' -> {
                    done = true
                }
                else -> fail()
            }
        }
        return members
    }

    private fun readArray() {
        consume('[')
        skipSpace()
        if (here(']')) {
            at++
            return
        }
        var done = false
        while (!done) {
            skipSpace()
            readValue()
            skipSpace()
            when (next()) {
                ',' -> Unit
                ']' -> {
                    done = true
                }
                else -> fail()
            }
        }
    }

    /** Validates one value. A string or number returns its trimmed text; every other kind validates and returns null. */
    private fun readValue(): String? =
        when {
            here('{') -> {
                readObject()
                null
            }
            here('[') -> {
                readArray()
                null
            }
            here('"') -> readString().trim()
            here('t') -> literal("true")
            here('f') -> literal("false")
            here('n') -> literal("null")
            else -> readNumber()
        }

    private fun readString(): String {
        consume('"')
        val text = StringBuilder()
        var closed = false
        while (!closed) {
            val c = next()
            when {
                c == '"' -> {
                    closed = true
                }
                c == '\\' -> text.append(readEscape())
                c < ' ' -> fail()
                else -> text.append(c)
            }
        }
        return text.toString()
    }

    private fun readEscape(): Char =
        when (next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readHexScalar()
            else -> fail()
        }

    private fun readHexScalar(): Char {
        var code = 0
        repeat(4) { code = code * 16 + hexValue(next()) }
        return code.toChar()
    }

    private fun hexValue(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> fail()
        }

    private fun readNumber(): String {
        val start = at
        if (here('-')) at++
        if (here('0')) {
            at++
        } else if (digitHere()) {
            skipDigits()
        } else {
            fail()
        }
        if (here('.')) {
            at++
            if (!digitHere()) fail()
            skipDigits()
        }
        if (here('e') || here('E')) {
            at++
            if (here('+') || here('-')) at++
            if (!digitHere()) fail()
            skipDigits()
        }
        return source.substring(start, at)
    }

    private fun literal(word: String): String? {
        if (source.length - at < word.length || source.substring(at, at + word.length) != word) fail()
        at += word.length
        return null
    }

    private fun consume(expected: Char) {
        if (next() != expected) fail()
    }

    private fun next(): Char {
        if (at >= source.length) fail()
        return source[at++]
    }

    private fun here(c: Char): Boolean = at < source.length && source[at] == c

    private fun digitHere(): Boolean = at < source.length && source[at] in '0'..'9'

    private fun skipDigits() {
        while (digitHere()) at++
    }

    private fun skipSpace() {
        while (at < source.length && source[at].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) at++
    }

    private fun fail(): Nothing = throw IllegalArgumentException("Stored QQ credential row is not one JSON object")

    companion object {
        /** The top-level members of [source] when it is exactly one JSON object, or null for any other text. */
        fun topLevelTextOrNull(source: String): Map<String, String?>? =
            try {
                StoredJsonObject(source).document()
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
