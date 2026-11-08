package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.mix_cc0df4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x - y)
        b = (x shr 32L.toInt())
        c = table[((y and 255L) + 85L).toInt()]
        d = (((a - (b * 2L)) + c) and 0xffffffffL)
        e = (y shr 32L.toInt())
        f = (e xor y)
        a = ((a - b) + e)
        leaf0 = (d or (((b - c) + table[((f and 255L) + 367L).toInt()]) shl 32L.toInt()))
        leaf1 = (((a + f) and 0xffffffffL) or ((a xor d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0e54(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (y shr 32L.toInt())
        b = ((x shr 32L.toInt()) and 0xffffffffL)
        c = (((y * (-969387324L)) and 0xffffffffL) xor b)
        d = ((c + ((y + a) * 945028798L)) - 43427200L)
        e = (y + a)
        b = (-table[((b and 255L) + 157L).toInt()])
        a = ((((a + x) + b) + 0x8e293cf5L) and 0xffffffffL)
        f = (((e + 0x8a2a9ac0L) xor ((a shr 31L.toInt()) or (a shl 1L.toInt()))) and 0xffffffffL)
        g = (d xor ((f shr 4L.toInt()) or (f shl 28L.toInt())))
        b = (((((c shr 31L.toInt()) or (c shl 1L.toInt())) - 10034L) xor ((e + 0x8a2a809aL) xor (x + b))) and 0xffffffffL)
        c = (b xor d)
        b = ((b shr 29L.toInt()) or (b shl 3L.toInt()))
        d = (f xor ((((-a) - b) * 0x69557badL) and 0xffffffffL))
        e = (-((d shr 6L.toInt()) or (d shl 26L.toInt())))
        a = ((a + b) xor table[((c and 255L) + 203L).toInt()])
        leaf0 = ((((g * 0x4e5c1a7fL) + c) and 0xffffffffL) or ((g + e) shl 32L.toInt()))
        leaf1 = (((d + a) and 0xffffffffL) or (((((g * 0x4e5c1a80L) + e) + a) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0f34(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (a xor (y shr 32L.toInt()))
        c = (table[((b and 255L) + 290L).toInt()] + y)
        d = (table[((y and 255L) + 417L).toInt()] xor (x shr 32L.toInt()))
        e = (c xor d)
        c = (((c - d) - b) and 0xffffffffL)
        f = (e - ((c shr 31L.toInt()) or (c shl 1L.toInt())))
        b = (e xor (d + b))
        leaf0 = (a or (((f + b) + table[((f and 255L) + 228L).toInt()]) shl 32L.toInt()))
        leaf1 = (((-f) and 0xffffffffL) or (((((-c) - (f * 2L)) + b) + 85603006L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0fc8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = ((x - table[((a and 255L) + 428L).toInt()]) and 0xffffffffL)
        c = (y shr 32L.toInt())
        a = (((c + y) and 0xffffffffL) xor a)
        d = (b xor a)
        e = (b xor c)
        leaf0 = (d or (((((y - b) + a) - c) + e) shl 32L.toInt()))
        leaf1 = (((((y - b) - c) - e) and 0xffffffffL) or ((e - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1010(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = (y and 0xffffffffL)
        b = ((a - ((b shr 2L.toInt()) or (b shl 30L.toInt()))) and 0xffffffffL)
        c = (((((b shr 10L.toInt()) or (b shl 22L.toInt())) + a) + x) and 0xffffffffL)
        d = (y shr 32L.toInt())
        e = (table[((d and 255L) + 270L).toInt()] + y)
        f = (e and 0xffffffffL)
        a = ((a + x) and 0xffffffffL)
        a = (d - ((a shr 24L.toInt()) or (a shl 8L.toInt())))
        d = (a and 0xffffffffL)
        leaf0 = (c or ((b xor (((f shr 8L.toInt()) or (f shl 24L.toInt())) + ((d shr 5L.toInt()) or (d shl 27L.toInt())))) shl 32L.toInt()))
        leaf1 = (((e - table[((a and 255L) + 346L).toInt()]) and 0xffffffffL) or ((d xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1080(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (y shr 32L.toInt())
        b = (a + y)
        c = (b and 0xffffffffL)
        d = (x shr 32L.toInt())
        e = (c xor d)
        f = table[((e and 255L) + 409L).toInt()]
        d = (((y - 17428L) xor x) xor ((((x shr 51L.toInt()) and 8191L) or (d shl 13L.toInt())) + 5053L))
        c = ((c shr 28L.toInt()) or (c shl 4L.toInt()))
        g = (-table[((((e + a) + d) and 255L) + 12L).toInt()])
        h = ((((y + g) + f) + ((f + d) * 0x5d765e37L)) - e)
        b = ((b + g) and 0xffffffffL)
        b = ((((-((((b shr 2L.toInt()) or (b shl 30L.toInt())) - 13319L) xor ((b - h) - 17783L))) - c) + e) and 0xffffffffL)
        e = ((((((f + d) - e) + c) xor (h - 14814L)) xor (((b shr 27L.toInt()) or (b shl 5L.toInt())) + 24998L)) and 0xffffffffL)
        a = (((d + (((-f) - d) * 0x5d765e37L)) + a) + c)
        c = (a and 0xffffffffL)
        leaf0 = (e or ((b xor table[((h and 255L) + 210L).toInt()]) shl 32L.toInt()))
        leaf1 = (((h xor ((c shr 4L.toInt()) or (c shl 28L.toInt()))) and 0xffffffffL) or ((a - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1160(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) + y)
        c = ((y shr 32L.toInt()) xor a)
        d = (((b + c) xor y) and 0xffffffffL)
        c = ((c - b) and 0xffffffffL)
        b = ((((d shr 16L.toInt()) or (d shl 16L.toInt())) + ((c shr 23L.toInt()) or (c shl 9L.toInt()))) xor b)
        e = ((c shr 8L.toInt()) or (c shl 24L.toInt()))
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((d + e) and 0xffffffffL) or ((((b + d) + e) xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1290(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = ((b shr 5L.toInt()) or (b shl 27L.toInt()))
        d = (y shr 32L.toInt())
        e = (((y shr 57L.toInt()) and 127L) or (d shl 7L.toInt()))
        f = (a xor x)
        g = ((((a - c) - e) * 909085917L) xor f)
        b = ((f + d) xor b)
        a = (b xor ((a - c) - e))
        c = (d - f)
        d = (c and 0xffffffffL)
        e = ((d shr 5L.toInt()) or (d shl 27L.toInt()))
        leaf0 = (((g + a) and 0xffffffffL) or (((((d - g) + a) + b) + e) shl 32L.toInt()))
        leaf1 = ((((((-d) - a) + b) + e) and 0xffffffffL) or (((c - (g * 2L)) - a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1384(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (y and 0xffffffffL)
        c = (((b + 8085L) xor ((((c shr 14L.toInt()) or (c shl 18L.toInt())) - 2831L) xor (x shr 32L.toInt()))) and 0xffffffffL)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((((y - b) - c) and 0xffffffffL) or ((b xor ((c shr 30L.toInt()) or (c shl 2L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc13c4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) - y)
        c = (b and 0xffffffffL)
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((((((d shr 27L.toInt()) or (d shl 5L.toInt())) + ((c shr 19L.toInt()) or (c shl 13L.toInt()))) xor y) and 0xffffffffL) or ((b + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc165c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = ((b * 0x4f8d62d4L) + y)
        d = (((x shr 32L.toInt()) xor y) and 0xffffffffL)
        e = (((c + 0x5e6ea6c4L) and 0xffffffffL) xor d)
        b = ((d * (-0x44ddcce4L)) xor b)
        c = (((b + 339250517L) xor c) and 0xffffffffL)
        d = (e xor c)
        b = (b - ((e shr 25L.toInt()) or (e shl 7L.toInt())))
        c = ((c - table[((b and 255L) + 174L).toInt()]) and 0xffffffffL)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (c or (((b - ((d shr 5L.toInt()) or (d shl 27L.toInt()))) - ((c shr 15L.toInt()) or (c shl 17L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1acc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = table[((y and 255L) + 253L).toInt()]
        c = (x shr 32L.toInt())
        d = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or ((b + c) shl 32L.toInt()))
        leaf1 = (((y - (d * 0x492c0926L)) and 0xffffffffL) or (((d - b) - c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1c40(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (table[((y and 255L) + 285L).toInt()] + (x shr 32L.toInt()))
        c = ((y shr 32L.toInt()) xor a)
        d = ((b - c) - y)
        b = (b and 0xffffffffL)
        b = (((b shr 24L.toInt()) or (b shl 8L.toInt())) + c)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((((c + y) - table[((b and 255L) + 306L).toInt()]) and 0xffffffffL) or ((b - table[((d and 255L) + 314L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc1fa4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = (table[((y and 255L) + 218L).toInt()] xor (x shr 32L.toInt()))
        c = (b + ((y shr 32L.toInt()) xor a))
        d = (c + y)
        e = table[((c and 255L) + 366L).toInt()]
        f = (d xor e)
        d = (d xor b)
        g = ((d - c) + f)
        b = (e xor b)
        leaf0 = (a or ((g + b) shl 32L.toInt()))
        leaf1 = (((g xor ((c - d) - b)) and 0xffffffffL) or ((f + 59758798L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc2530(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (a xor (y shr 32L.toInt()))
        c = (table[((b and 255L) + 490L).toInt()] + y)
        d = (c and 0xffffffffL)
        d = ((d shr 18L.toInt()) or (d shl 14L.toInt()))
        e = (x shr 32L.toInt())
        f = ((((b - e) + y) + 0xa8c3ece9L) and 0xffffffffL)
        leaf0 = (a or ((((d + e) - y) + 0x573c1317L) shl 32L.toInt()))
        leaf1 = (((c - ((f shr 26L.toInt()) or (f shl 6L.toInt()))) and 0xffffffffL) or ((f + (((b - f) + d) * 49389384L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc2b40(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (y xor (x shr 32L.toInt()))
        c = (a xor (y shr 32L.toInt()))
        d = (y - table[((c and 255L) + 20L).toInt()])
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or ((c xor (d + b)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc2d70(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = (y xor (x shr 32L.toInt()))
        c = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((y - table[((c and 255L) + 186L).toInt()]) and 0xffffffffL) or ((c xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc3460(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y and 0xffffffffL)
        d = (((x shr 32L.toInt()) and 0xffffffffL) xor c)
        e = (b xor d)
        b = ((b + 0x7ee7eb4eL) xor c)
        c = ((e + d) + b)
        d = (c and 0xffffffffL)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((((b - ((e shr 3L.toInt()) or (e shl 29L.toInt()))) - ((d shr 28L.toInt()) or (d shl 4L.toInt()))) and 0xffffffffL) or ((e xor table[((c and 255L) + 317L).toInt()]) shl 32L.toInt()))
    }
