package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.math_cbeb00(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (b + y)
        d = (x shr 32L.toInt())
        e = (y and 0xffffffffL)
        e = ((e shr 17L.toInt()) or (e shl 15L.toInt()))
        b = (((c + (d * 2L)) - (e * 2L)) xor b)
        f = ((((b * (-0x74b2131L)) + c) + d) - e)
        c = (((c + d) - e) and 0xffffffffL)
        c = ((b - 25438L) xor (((c shr 30L.toInt()) or (c shl 2L.toInt())) - 12731L))
        b = ((((b - d) + e) + c) and 0xffffffffL)
        g = (f and 0xffffffffL)
        c = ((((d - e) - c) xor ((g shr 15L.toInt()) or (g shl 17L.toInt()))) and 0xffffffffL)
        d = ((f - ((b shr 25L.toInt()) or (b shl 7L.toInt()))) - ((c shr 11L.toInt()) or (c shl 21L.toInt())))
        b = (((c * 0x711f2c40L) and 0xffffffffL) xor b)
        e = ((d and 0xffffffffL) xor b)
        f = ((e shr 8L.toInt()) or (e shl 24L.toInt()))
        c = ((d + c) and 0xffffffffL)
        b = ((b - ((c shr 23L.toInt()) or (c shl 9L.toInt()))) - ((e shr 23L.toInt()) or (e shl 9L.toInt())))
        d = (((b + 0x627549b6L) and 0xffffffffL) xor e)
        leaf0 = (a or ((c + f) shl 32L.toInt()))
        leaf1 = (d or ((((c + d) + f) xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbebbc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (y + (x shr 32L.toInt()))
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = (((b - 0x6ee09feL) and 0xffffffffL) xor c)
        c = ((((c shr 31L.toInt()) or (c shl 1L.toInt())) + y) and 0xffffffffL)
        b = ((((b - ((d shr 8L.toInt()) or (d shl 24L.toInt()))) - ((c shr 7L.toInt()) or (c shl 25L.toInt()))) + 0xf911f602L) and 0xffffffffL)
        e = ((b shr 1L.toInt()) or (b shl 31L.toInt()))
        f = (((((d * 2L) + c) + e) - 959493488L) and 0xffffffffL)
        c = (((d + c) - 959493488L) and 0xffffffffL)
        b = ((((f shr 10L.toInt()) or (f shl 22L.toInt())) + ((c shr 12L.toInt()) or (c shl 20L.toInt()))) xor b)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((((c * 2L) + d) + e) and 0xffffffffL) or ((f + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbec38(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (y shr 32L.toInt())
        b = (table[((a and 255L) + 306L).toInt()] xor y)
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (((a + y) and 0xffffffffL) xor c)
        e = (((b + 317499725L) and 0xffffffffL) xor d)
        c = (((x shr 61L.toInt()) and 7L) or (c shl 3L.toInt()))
        f = (e xor (((c + x) - d) and 0xffffffffL))
        a = ((((c + x) * 0x551cb6e9L) + a) and 0xffffffffL)
        g = ((a shr 26L.toInt()) or (a shl 6L.toInt()))
        a = ((((a - x) - e) - c) + d)
        leaf0 = (f or (((e - b) - g) shl 32L.toInt()))
        leaf1 = ((((a + f) xor (b + g)) and 0xffffffffL) or (((f + 0xa13a0748L) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbecc8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (y shr 32L.toInt())
        b = ((x shr 32L.toInt()) and 0xffffffffL)
        c = (((a + y) and 0xffffffffL) xor b)
        d = (a xor y)
        b = (b xor (x and 0xffffffffL))
        e = (-((((b shr 26L.toInt()) or (b shl 6L.toInt())) + 10030L) xor (c + 29527L)))
        f = (((c + d) + a) + e)
        c = ((c shr 20L.toInt()) or (c shl 12L.toInt()))
        a = ((a + e) and 0xffffffffL)
        e = (a xor (c + b))
        a = (d - ((a shr 29L.toInt()) or (a shl 3L.toInt())))
        d = (a and 0xffffffffL)
        g = (((e + 27414L) xor f) xor (((d shr 30L.toInt()) or (d shl 2L.toInt())) - 31366L))
        h = (((f + c) + b) - g)
        leaf0 = ((h and 0xffffffffL) or ((((g - d) + e) - 0x4d3c3801L) shl 32L.toInt()))
        leaf1 = ((((((h + g) - e) * 879032851L) xor (a - e)) and 0xffffffffL) or (((h + 0x4c84056aL) xor (((e - f) - b) - c)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbed80(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (((((((x shr 49L.toInt()) and 32767L) or (a shl 15L.toInt())) + 10976L) xor (y - 448L)) + x) and 0xffffffffL)
        c = (y and 0xffffffffL)
        d = (y shr 32L.toInt())
        leaf0 = (b or ((((((c shr 13L.toInt()) or (c shl 19L.toInt())) + 31072L) xor (d + 24374L)) + a) shl 32L.toInt()))
        leaf1 = ((((b + d) and 0xffffffffL) xor c) or ((d - table[((b and 255L) + 214L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbedf0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (y shr 32L.toInt())
        c = (((b + y) and 0xffffffffL) xor a)
        d = ((c shr 2L.toInt()) or (c shl 30L.toInt()))
        e = (-(((y shr 49L.toInt()) and 32767L) or (b shl 15L.toInt())))
        b = ((((y + e) - table[(((((a - x) + b) - 9L) and 255L) + 231L).toInt()]) and 0xffffffffL) or ((((((x - a) * 0x3c01eb09L) - (d * 0x3c01eb0aL)) + b) + 0x5e6319f7L) shl 32L.toInt()))
        leaf0 = ((((x - a) - d) and 0xffffffffL) or (((c + y) + e) shl 32L.toInt()))
        leaf1 = b
    }

internal fun SpotifyPlayPlayLeaves.math_cbee50(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (((y shr 52L.toInt()) and 4095L) or (b shl 12L.toInt()))
        d = ((x - (((x shr 41L.toInt()) and 8388607L) or (a shl 23L.toInt()))) and 0xffffffffL)
        e = ((d shr 24L.toInt()) or (d shl 8L.toInt()))
        a = (((y * 850925706L) + a) and 0xffffffffL)
        b = (((b - ((a shr 22L.toInt()) or (a shl 10L.toInt()))) - e) and 0xffffffffL)
        d = (d xor (((a shr 28L.toInt()) or (a shl 4L.toInt())) and 0xffffffffL))
        f = (((((b shr 28L.toInt()) or (b shl 4L.toInt())) + ((d shr 17L.toInt()) or (d shl 15L.toInt()))) xor ((y - c) - e)) and 0xffffffffL)
        g = (-((f shr 10L.toInt()) or (f shl 22L.toInt())))
        h = (table[((((((((-y) - b) + c) + g) + e) + a) and 255L) + 426L).toInt()] xor ((((((d - y) - b) + c) + a) + e) + 0x46c9dc64L))
        i = (((((((((-f) - y) - (b * 2L)) - (d * 502034873L)) + c) + g) + a) + e) and 0xffffffffL)
        i = ((i shr 11L.toInt()) or (i shl 21L.toInt()))
        leaf0 = (((h - i) and 0xffffffffL) or ((((((((((-h) - (y * 2L)) - (d * 502034872L)) - (b * 3L)) + (c * 2L)) + (e * 2L)) + g) + (a * 2L)) + 939929255L) shl 32L.toInt()))
        leaf1 = ((((((((h - f) - d) + g) * 0x48f84e6bL) - 0x769461cdL) xor (((((((((-h) - y) + f) + d) - b) + c) + a) + e) + 939929255L)) and 0xffffffffL) or ((((((h - f) - d) + ((h - i) * 0x617ece52L)) + g) - 939929255L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbef30(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (((b shr 10L.toInt()) or (b shl 22L.toInt())) + (x shr 32L.toInt()))
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        b = (((c + d) and 0xffffffffL) xor b)
        e = (c and 0xffffffffL)
        d = (((((b shr 25L.toInt()) or (b shl 7L.toInt())) + ((e shr 23L.toInt()) or (e shl 9L.toInt()))) and 0xffffffffL) xor d)
        c = ((((b shr 18L.toInt()) or (b shl 14L.toInt())) + ((d shr 22L.toInt()) or (d shl 10L.toInt()))) + c)
        e = (c xor (((-d) - b) * 0x74401debL))
        c = (((c + d) + b) xor d)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = (((((c * 352211913L) + d) + b) and 0xffffffffL) or ((c + e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbeff0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) + y) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        d = (((c * 0x5d8357a4L) xor y) and 0xffffffffL)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (d or (((((b shr 24L.toInt()) or (b shl 8L.toInt())) + c) + ((d shr 31L.toInt()) or (d shl 1L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf02c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (-(y xor ((b shr 28L.toInt()) or (b shl 4L.toInt()))))
        d = (b + c)
        e = (table[((y and 255L) + 433L).toInt()] xor (x shr 32L.toInt()))
        c = ((e + c) and 0xffffffffL)
        c = (-((c shr 16L.toInt()) or (c shl 16L.toInt())))
        f = (((((d * 2L) - e) + c) + 0xa2dcab41L) and 0xffffffffL)
        g = ((f shr 10L.toInt()) or (f shl 22L.toInt()))
        c = ((d - e) + c)
        h = (c and 0xffffffffL)
        b = ((((b - e) + 0xa2dcab41L) xor ((h shr 11L.toInt()) or (h shl 21L.toInt()))) and 0xffffffffL)
        e = ((b shr 19L.toInt()) or (b shl 13L.toInt()))
        b = (b xor f)
        d = ((((d - g) - e) + 0xa2dcab41L) xor table[((b and 255L) + 307L).toInt()])
        c = ((c + g) + e)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((b - table[((c and 255L) + 390L).toInt()]) and 0xffffffffL) or ((c xor d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf1e8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (y xor (x shr 32L.toInt()))
        c = (b and 0xffffffffL)
        d = ((y shr 32L.toInt()) xor a)
        b = (((d + b) xor y) and 0xffffffffL)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (b or (((((c shr 17L.toInt()) or (c shl 15L.toInt())) + d) + ((b shr 15L.toInt()) or (b shl 17L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf21c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) + y) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        d = (y - c)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((d + 0x954a5575L) and 0xffffffffL) or ((c - ((((b shr 10L.toInt()) or (b shl 22L.toInt())) + 14118L) xor (d + 0x954aa816L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf26c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        var j = 0L
        var k = 0L
        a = (x and 0xffffffffL)
        b = ((y - 361479757L) xor (x shr 32L.toInt()))
        c = ((y shr 32L.toInt()) xor a)
        d = ((b + c) xor y)
        e = table[((b and 255L) + 40L).toInt()]
        f = (-table[(((c - e) and 255L) + 267L).toInt()])
        g = (d + f)
        h = (g and 0xffffffffL)
        i = ((b - d) and 0xffffffffL)
        i = ((i shr 10L.toInt()) or (i shl 22L.toInt()))
        j = ((h shr 25L.toInt()) or (h shl 7L.toInt()))
        b = (((h shr 27L.toInt()) or (h shl 5L.toInt())) + b)
        d = (b - d)
        k = (((((d + (c * 2L)) - (e * 2L)) - (i * 2L)) - (j * 2L)) and 0xffffffffL)
        c = (((((g - c) + e) + i) + j) and 0xffffffffL)
        e = ((((k shr 23L.toInt()) or (k shl 9L.toInt())) + ((c shr 13L.toInt()) or (c shl 19L.toInt()))) xor ((b - c) + f))
        d = (((e - (c * 3L)) + (d * 2L)) + (h * 4L))
        b = ((((b - c) + h) + f) and 0xffffffffL)
        b = (-((d - 14110L) xor (((b shr 31L.toInt()) or (b shl 1L.toInt())) - 17639L)))
        c = (((b - k) xor d) and 0xffffffffL)
        d = (((-k) - e) and 0xffffffffL)
        b = ((((c shr 6L.toInt()) or (c shl 26L.toInt())) + ((d shr 28L.toInt()) or (d shl 4L.toInt()))) xor (e + b))
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((d + (c * 0x6ba61d4eL)) and 0xffffffffL) or ((b + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf350(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) - y)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((((b + 8086L) xor y) xor (((c shr 5L.toInt()) or (c shl 27L.toInt())) + 5907L)) and 0xffffffffL)
        e = ((d + 485851411L) xor b)
        b = (table[((b and 255L) + 23L).toInt()] + c)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = ((table[((b and 255L) + 20L).toInt()] xor d) or ((b xor e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf3d4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        var j = 0L
        var k = 0L
        a = ((y shr 32L.toInt()) and 0xffffffffL)
        b = (table[((a and 255L) + 327L).toInt()] xor y)
        c = (x shr 32L.toInt())
        a = ((((x - y) - c) and 0xffffffffL) xor a)
        d = (y * 0x669790deL)
        e = (c * (-2L))
        f = ((x + d) + e)
        g = ((((a shr 5L.toInt()) or (a shl 27L.toInt())) + 4044L) xor (f - 12011L))
        h = (((x - g) + b) - a)
        i = (-table[((b and 255L) + 26L).toInt()])
        j = (((y * (-0x669790dfL)) + c) + i)
        k = ((j xor f) and 0xffffffffL)
        f = (k xor ((a - f) and 0xffffffffL))
        a = (((((((((-x) - y) + (h * 2L)) - k) + a) + i) - c) and 0xffffffffL) xor k)
        c = (((((h + d) + e) - k) - ((f shr 26L.toInt()) or (f shl 6L.toInt()))) - ((a shr 15L.toInt()) or (a shl 17L.toInt())))
        b = (((j + b) - g) xor table[(((((h + d) + e) - k) and 255L) + 247L).toInt()])
        d = (((c + b) and 0xffffffffL) xor a)
        e = (c and 0xffffffffL)
        a = (((a * 0x755a5cdfL) + f) and 0xffffffffL)
        leaf0 = (d or (((b - ((e shr 30L.toInt()) or (e shl 2L.toInt()))) - ((a shr 12L.toInt()) or (a shl 20L.toInt()))) shl 32L.toInt()))
        leaf1 = (((c + table[((a and 255L) + 370L).toInt()]) and 0xffffffffL) or ((d xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf4c0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) xor y) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        d = ((((((-b) - y) - c) * 0x6f8e05c0L) and 0xffffffffL) xor b)
        e = (b + y)
        b = (((e + c) - 22776L) xor (((b shr 7L.toInt()) or (b shl 25L.toInt())) - 25032L))
        c = (((b + c) - ((d shr 11L.toInt()) or (d shl 21L.toInt()))) and 0xffffffffL)
        f = ((c shr 19L.toInt()) or (c shl 13L.toInt()))
        c = ((((c + (b * 3L)) + (d * 5L)) - (e * 3L)) + f)
        leaf0 = (a or (((((b * 2L) + (d * 3L)) - (e * 2L)) + f) shl 32L.toInt()))
        leaf1 = (((c xor (((e - b) - d) - f)) and 0xffffffffL) or ((c + 0x6b590600L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf59c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = ((x - (((x shr 55L.toInt()) and 511L) or (a shl 9L.toInt()))) and 0xffffffffL)
        a = ((a xor y) and 0xffffffffL)
        c = (((a shr 28L.toInt()) or (a shl 4L.toInt())) and 0xffffffffL)
        d = (b xor c)
        e = (y shr 32L.toInt())
        f = (table[((e and 255L) + 476L).toInt()] + y)
        leaf0 = (d or ((f xor a) shl 32L.toInt()))
        leaf1 = ((((f - (b xor e)) - d) and 0xffffffffL) or ((e xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf73c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = ((x shr 32L.toInt()) + y)
        b = (a xor x)
        c = ((y shr 32L.toInt()) and 0xffffffffL)
        d = (table[((b and 255L) + 412L).toInt()] xor c)
        leaf0 = ((((b + a) - 0xadd7fccL) and 0xffffffffL) or (((((y + d) + b) + a) + c) shl 32L.toInt()))
        leaf1 = (((((c + y) + b) - ((d shr 17L.toInt()) or (d shl 15L.toInt()))) and 0xffffffffL) or (((((((y + d) + (b * 2L)) + (a * 2L)) + c) - 0xadd7fccL) xor d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf7a8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = ((y xor ((b shr 5L.toInt()) or (b shl 27L.toInt()))) and 0xffffffffL)
        d = (((x shr 32L.toInt()) - table[((y and 255L) + 480L).toInt()]) and 0xffffffffL)
        b = ((b - ((c shr 13L.toInt()) or (c shl 19L.toInt()))) - ((d shr 8L.toInt()) or (d shl 24L.toInt())))
        d = (((b + (c * 2L)) xor (((-b) - (c * 2L)) + d)) and 0xffffffffL)
        b = (b + c)
        e = (d - table[((b and 255L) + 376L).toInt()])
        c = ((-c) xor ((d shr 17L.toInt()) or (d shl 15L.toInt())))
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = ((((c + 413084238L) xor b) and 0xffffffffL) or ((c xor e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbf828(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = table[(((c + y) and 255L) + 447L).toInt()]
        e = ((((b - (y * 757237768L)) + ((b - c) * 378618884L)) - d) + ((b - d) xor (y - b)))
        f = ((((y * 0x5a451012L) - (b * 757237771L)) + (c * 757237769L)) + (d * 2L))
        g = (f and 0xffffffffL)
        h = (e and 0xffffffffL)
        b = ((((b - (y * 757237770L)) + ((b - c) * 378618885L)) - d) xor ((h shr 24L.toInt()) or (h shl 8L.toInt())))
        c = (e - ((((g shr 11L.toInt()) or (g shl 21L.toInt())) + 6098L) xor (b + 11551L)))
        d = (f - b)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or ((((d - 12171L) xor b) xor (c - 18095L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfa20(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = ((b shr 30L.toInt()) or (b shl 2L.toInt()))
        d = (a xor (x and 0xffffffffL))
        e = ((((a - c) + 0x52973518L) and 0xffffffffL) xor d)
        d = (d + (y shr 32L.toInt()))
        b = (d xor b)
        leaf0 = (e or ((((d + (a * 2L)) - (c * 2L)) + b) shl 32L.toInt()))
        leaf1 = (((((b - d) + c) - a) and 0xffffffffL) or ((((c - a) - e) - b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfa70(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x shr 32L.toInt())
        b = (table[((a and 255L) + 7L).toInt()] xor x)
        c = (y shr 32L.toInt())
        d = (b + c)
        e = table[((d and 255L) + 338L).toInt()]
        c = (-(((y shr 45L.toInt()) and 524287L) or (c shl 19L.toInt())))
        f = (((y + c) xor (a + y)) and 0xffffffffL)
        g = ((((e + y) + c) and 0xffffffffL) xor f)
        d = (d xor table[(((((a * 17L) + (y * 17L)) + b) and 255L) + 366L).toInt()])
        a = (a * 0x496df211L)
        f = table[((f and 255L) + 448L).toInt()]
        c = (((((((d + e) + (y * 0x496df212L)) + c) + a) + b) - f) and 0xffffffffL)
        e = (g xor (((c shr 26L.toInt()) or (c shl 6L.toInt())) and 0xffffffffL))
        g = (((((a + (y * 0x496df211L)) + b) - f) xor (g * (-501667575L))) and 0xffffffffL)
        h = (e xor g)
        a = (((((d - a) - (y * 0x496df211L)) - b) + f) and 0xffffffffL)
        b = (((((g shr 14L.toInt()) or (g shl 18L.toInt())) - 25581L) and 0xffffffffL) xor (((e - 31563L) and 0xffffffffL) xor a))
        a = ((((a shr 12L.toInt()) or (a shl 20L.toInt())) + ((g shr 28L.toInt()) or (g shl 4L.toInt()))) xor c)
        c = (h + table[((((b + a) + e) and 255L) + 484L).toInt()])
        d = (b xor h)
        leaf0 = ((c and 0xffffffffL) or (((a * 2L) + e) shl 32L.toInt()))
        leaf1 = ((((a - b) + ((d shr 20L.toInt()) or (d shl 12L.toInt()))) and 0xffffffffL) or ((d xor table[((c and 255L) + 316L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfb80(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = (x shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = ((b shr 29L.toInt()) or (b shl 3L.toInt()))
        d = (y shr 32L.toInt())
        b = (d xor b)
        e = ((a - c) xor b)
        a = ((((a * 2L) + x) - c) xor d)
        d = table[((((e - a) - b) and 255L) + 42L).toInt()]
        f = table[((((x + c) - b) and 255L) + 19L).toInt()]
        g = (((((((f + a) + e) + x) + c) - b) + 0x87d799b5L) xor (a + b))
        h = (g * 0x4f831f8fL)
        i = ((((((d + (e * 2L)) + x) + c) - (b * 2L)) + h) - a)
        f = (((((f + (a * 2L)) - (e * 2L)) + (b * 2L)) - x) - c)
        g = (g xor table[(((f + 75L) and 255L) + 415L).toInt()])
        a = ((((h + e) - a) - b) xor table[((g and 255L) + 238L).toInt()])
        h = (((table[((a and 255L) + 100L).toInt()] + i) - 746763283L) and 0xffffffffL)
        b = (((((((d + e) + x) + c) - b) + 0x4f0d4d22L) xor (f + 0x7828664bL)) and 0xffffffffL)
        c = (((i - 746763283L) and 0xffffffffL) xor b)
        leaf0 = (h or (((((c + b) + g) + 0xa0896692L) xor a) shl 32L.toInt()))
        leaf1 = ((c xor (((b + g) + 0xa0896692L) and 0xffffffffL)) or ((c xor h) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfc90(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (table[((a and 255L) + 68L).toInt()] xor x)
        c = (y and 0xffffffffL)
        c = ((c shr 25L.toInt()) or (c shl 7L.toInt()))
        d = (y shr 32L.toInt())
        e = (b xor d)
        leaf0 = ((((b + a) - c) and 0xffffffffL) or (((((a - c) - e) - y) + d) shl 32L.toInt()))
        leaf1 = (((((((e + y) - d) + b) + a) - c) and 0xffffffffL) or (((((((e * 2L) - b) - (a * 2L)) + (c * 2L)) + y) - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfce8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = (x xor a)
        c = (y shr 32L.toInt())
        leaf0 = ((b and 0xffffffffL) or (((y * 0x5e2e179eL) xor a) shl 32L.toInt()))
        leaf1 = (((y - c) and 0xffffffffL) or ((c - b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfd18(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = ((b + y) and 0xffffffffL)
        d = ((c shr 22L.toInt()) or (c shl 10L.toInt()))
        e = ((y shr 32L.toInt()) xor a)
        f = (y * 2L)
        c = (table[((((e + f) + b) and 255L) + 158L).toInt()] xor c)
        g = ((c shr 12L.toInt()) or (c shl 20L.toInt()))
        h = ((((e + ((d + e) * 0x86c45ddL)) + f) + b) - g)
        b = ((((((d * 469398682L) - (e * 553731299L)) + (f * 0x43044683L)) - (b * 0x3cfbb97dL)) + c) and 0xffffffffL)
        c = (((h + 0x65395eccL) and 0xffffffffL) xor b)
        b = ((((g + d) + e) xor ((b shr 2L.toInt()) or (b shl 30L.toInt()))) and 0xffffffffL)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((((((b shr 18L.toInt()) or (b shl 14L.toInt())) - 15853L) xor ((c + 30538L) xor h)) and 0xffffffffL) or ((b xor ((c shr 1L.toInt()) or (c shl 31L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfdc4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = ((b shr 21L.toInt()) or (b shl 11L.toInt()))
        d = (x shr 32L.toInt())
        e = ((y shr 32L.toInt()) xor a)
        f = ((((-c) - d) * 0x772b450fL) xor e)
        b = (((e - 497023739L) and 0xffffffffL) xor b)
        e = ((table[((f and 255L) + 47L).toInt()] + b) and 0xffffffffL)
        b = ((c + d) xor ((b shr 21L.toInt()) or (b shl 11L.toInt())))
        c = (f - b)
        b = (((((e shr 20L.toInt()) or (e shl 12L.toInt())) + 19204L) xor (c + 14122L)) + b)
        d = (e xor c)
        e = table[((d and 255L) + 460L).toInt()]
        f = (b and 0xffffffffL)
        c = (c xor ((f shr 20L.toInt()) or (f shl 12L.toInt())))
        leaf0 = (a or ((b + e) shl 32L.toInt()))
        leaf1 = ((((d - c) + 976531470L) and 0xffffffffL) or ((((((c * 2L) - f) - e) - d) - 976531470L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfe80(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x xor (y shr 32L.toInt()))
        b = table[((a and 255L) + 454L).toInt()]
        c = ((y - b) and 0xffffffffL)
        d = (y and 0xffffffffL)
        d = ((d shr 9L.toInt()) or (d shl 23L.toInt()))
        e = (x shr 32L.toInt())
        f = (a and 0xffffffffL)
        f = ((f shr 29L.toInt()) or (f shl 3L.toInt()))
        a = ((a - table[((((d + e) + f) and 255L) + 260L).toInt()]) and 0xffffffffL)
        d = (((((((c shr 20L.toInt()) or (c shl 12L.toInt())) + d) + e) + f) + ((a shr 27L.toInt()) or (a shl 5L.toInt()))) and 0xffffffffL)
        e = ((d shr 30L.toInt()) or (d shl 2L.toInt()))
        f = ((a + e) and 0xffffffffL)
        f = ((((f shr 23L.toInt()) or (f shl 9L.toInt())) + a) + c)
        a = (((((y + d) + a) - b) + table[((f and 255L) + 363L).toInt()]) and 0xffffffffL)
        b = (((-d) - c) + e)
        c = (b and 0xffffffffL)
        leaf0 = ((x and 0xffffffffL) or (a shl 32L.toInt()))
        leaf1 = (((f xor ((c shr 14L.toInt()) or (c shl 18L.toInt()))) and 0xffffffffL) or ((b + ((a shr 9L.toInt()) or (a shl 23L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbfff4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        var j = 0L
        a = (y shr 32L.toInt())
        b = (y - table[((a and 255L) + 289L).toInt()])
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (c xor (x and 0xffffffffL))
        e = (y and 0xffffffffL)
        c = ((((a + 17777L) and 0xffffffffL) xor c) xor ((((e shr 26L.toInt()) or (e shl 6L.toInt())) - 18895L) and 0xffffffffL))
        a = (((a - ((d shr 22L.toInt()) or (d shl 10L.toInt()))) - ((c shr 12L.toInt()) or (c shl 20L.toInt()))) and 0xffffffffL)
        d = (((b + c) and 0xffffffffL) xor d)
        e = ((b - ((a shr 9L.toInt()) or (a shl 23L.toInt()))) - ((d shr 6L.toInt()) or (d shl 26L.toInt())))
        a = (((d shr 27L.toInt()) or (d shl 5L.toInt())) + a)
        f = ((e + table[((a and 255L) + 444L).toInt()]) and 0xffffffffL)
        g = ((f shr 17L.toInt()) or (f shl 15L.toInt()))
        e = (-table[((e and 255L) + 56L).toInt()])
        b = ((c + (b * 0x7e7f6eedL)) and 0xffffffffL)
        c = ((b shr 29L.toInt()) or (b shl 3L.toInt()))
        a = ((((a - c) - d) - 658322603L) and 0xffffffffL)
        h = ((a shr 18L.toInt()) or (a shl 14L.toInt()))
        c = ((c + d) xor table[(((b + e) and 255L) + 31L).toInt()])
        d = (a xor table[((c and 255L) + 87L).toInt()])
        i = (((a + f) + d) and 0xffffffffL)
        j = ((((b + e) + h) + g) xor i)
        a = (((((((a + b) + f) + e) + h) + g) xor c) + j)
        b = ((i shr 30L.toInt()) or (i shl 2L.toInt()))
        c = (a xor d)
        leaf0 = (((i + a) and 0xffffffffL) or ((j - b) shl 32L.toInt()))
        leaf1 = ((((-a) - c) and 0xffffffffL) or ((((((-i) - a) - j) + c) + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc0120(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = ((y * 903520235L) xor a)
        c = (y shr 32L.toInt())
        a = (x - a)
        d = (a and 0xffffffffL)
        e = (((y - (((y shr 48L.toInt()) and 65535L) or (c shl 16L.toInt()))) - ((d shr 30L.toInt()) or (d shl 2L.toInt()))) and 0xffffffffL)
        c = (d xor c)
        d = (e xor c)
        e = (((b - ((e shr 18L.toInt()) or (e shl 14L.toInt()))) + d) and 0xffffffffL)
        a = (a - b)
        b = (e xor ((a - 846859022L) and 0xffffffffL))
        a = (a + c)
        leaf0 = (b or (e shl 32L.toInt()))
        leaf1 = (((table[(((a - 14L) and 255L) + 15L).toInt()] + d) and 0xffffffffL) or (((a - b) - 846859022L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc02c0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (y shr 32L.toInt())
        b = ((x shr 32L.toInt()) and 0xffffffffL)
        c = (table[((y and 255L) + 493L).toInt()] xor b)
        d = (-((((c shr 18L.toInt()) or (c shl 14L.toInt())) + 24154L) xor ((y + a) - 26738L)))
        b = (((((x shr 54L.toInt()) and 1023L) or (b shl 10L.toInt())) - 783L) xor (y - 18928L))
        c = ((c + a) + y)
        e = ((((b + x) + d) xor c) and 0xffffffffL)
        b = ((table[((((a - b) - x) and 255L) + 380L).toInt()] + a) + y)
        f = ((((a + d) + e) xor b) and 0xffffffffL)
        b = (-table[((b and 255L) + 444L).toInt()])
        g = ((((c + f) + b) and 0xffffffffL) xor e)
        a = (((((e shr 29L.toInt()) or (e shl 3L.toInt())) + a) + d) and 0xffffffffL)
        leaf0 = (g or (((c + b) - f) shl 32L.toInt()))
        leaf1 = ((f xor (((a shr 24L.toInt()) or (a shl 8L.toInt())) and 0xffffffffL)) or ((g xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc037c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = (((b + (y * 2L)) + c) and 0xffffffffL)
        e = (((((d shr 20L.toInt()) or (d shl 12L.toInt())) + c) + y) and 0xffffffffL)
        c = ((c + y) and 0xffffffffL)
        b = (((((c shr 20L.toInt()) or (c shl 12L.toInt())) + 12904L) xor (d - 1648L)) xor b)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = (((table[((b and 255L) + 26L).toInt()] + d) and 0xffffffffL) or ((b xor ((e shr 7L.toInt()) or (e shl 25L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc03ec(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((y xor x) shr 32L.toInt())
        c = (((-(x shr 32L.toInt())) - b) and 0xffffffffL)
        d = (b + ((c shr 4L.toInt()) or (c shl 28L.toInt())))
        e = table[((d and 255L) + 466L).toInt()]
        b = (((b xor y) - d) + e)
        d = (b xor d)
        c = (((d * 0x4855d9e3L) xor (c + e)) and 0xffffffffL)
        d = ((b + d) and 0xffffffffL)
        e = (c xor (((d shr 18L.toInt()) or (d shl 14L.toInt())) and 0xffffffffL))
        leaf0 = (a or (((b - c) - e) shl 32L.toInt()))
        leaf1 = (e or ((d xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc04ac(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (table[((a and 255L) + 5L).toInt()] + x)
        c = (y shr 32L.toInt())
        d = (((b + c) xor y) and 0xffffffffL)
        a = (((c + y) and 0xffffffffL) xor a)
        e = (d xor a)
        c = (c - b)
        f = (c and 0xffffffffL)
        d = (d - ((f shr 7L.toInt()) or (f shl 25L.toInt())))
        f = (e xor d)
        a = ((b and 0xffffffffL) xor table[((a and 255L) + 291L).toInt()])
        b = (e xor a)
        a = (c - a)
        c = (d xor (a + 0xa92e3cfeL))
        d = (f - c)
        e = ((((f + b) + c) xor d) and 0xffffffffL)
        a = ((a xor ((b shr 4L.toInt()) or (b shl 28L.toInt()))) and 0xffffffffL)
        g = (c xor ((a shr 22L.toInt()) or (a shl 10L.toInt())))
        leaf0 = (e or (((d - g) + 683798371L) shl 32L.toInt()))
        leaf1 = ((((((g + a) - f) - b) - c) and 0xffffffffL) or ((((((a - b) - (c * 2L)) + e) - g) + 683798371L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc0560(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y and 0xffffffffL)
        c = (((c shr 13L.toInt()) or (c shl 19L.toInt())) + (x shr 32L.toInt()))
        d = (y - ((((b shr 4L.toInt()) or (b shl 28L.toInt())) - 19578L) xor (c - 4258L)))
        c = (c and 0xffffffffL)
        b = (b xor ((c shr 10L.toInt()) or (c shl 22L.toInt())))
        c = ((d + b) xor c)
        e = ((c * (-0x6ed3d75eL)) xor b)
        c = (((d - b) + e) xor c)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((((d - b) - e) and 0xffffffffL) or ((e - table[((c and 255L) + 241L).toInt()]) shl 32L.toInt()))
    }
