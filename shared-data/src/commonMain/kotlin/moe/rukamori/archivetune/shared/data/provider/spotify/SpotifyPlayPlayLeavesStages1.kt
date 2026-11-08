package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.math_cbb870(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x xor (y shr 32L.toInt()))
        b = (table[((a and 255L) + 337L).toInt()] + y)
        c = (x shr 32L.toInt())
        d = (y and 0xffffffffL)
        d = ((a - 22445L) xor (((d shr 8L.toInt()) or (d shl 24L.toInt())) - 10761L))
        e = ((b + 0x54033704L) xor (c - d))
        a = (((a - c) + d) and 0xffffffffL)
        b = ((b and 0xffffffffL) xor a)
        leaf0 = ((x and 0xffffffffL) or (e shl 32L.toInt()))
        leaf1 = (b or (((e + b) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbb8e4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = (y shr 32L.toInt())
        b = ((a + 0x9daaf57eL) xor y)
        c = (x shr 32L.toInt())
        d = (x - (((x shr 55L.toInt()) and 511L) or (c shl 9L.toInt())))
        e = (d and 0xffffffffL)
        f = (-((e shr 14L.toInt()) or (e shl 18L.toInt())))
        g = ((a + f) and 0xffffffffL)
        h = (b xor ((g shr 22L.toInt()) or (g shl 10L.toInt())))
        i = (h + a)
        d = ((((((y + i) - b) + c) + 0x7b3e8cd5L) xor ((((d - (a * 2L)) - (y * 2L)) - (c * 2L)) + b)) and 0xffffffffL)
        e = (((((y - e) + g) + c) + a) and 0xffffffffL)
        e = ((e shr 15L.toInt()) or (e shl 17L.toInt()))
        a = (((((y - b) + (h * 2L)) + c) + a) + e)
        b = (((((i + f) + 0x97b7a1ccL) xor (h + e)) and 0xffffffffL) or ((((((((d shr 19L.toInt()) or (d shl 13L.toInt())) - 27957L) xor (a + 0x97b74153L)) + i) + f) - 0x68485e34L) shl 32L.toInt()))
        leaf0 = (d or ((a + 0x97b7a1ccL) shl 32L.toInt()))
        leaf1 = b
    }

internal fun SpotifyPlayPlayLeaves.math_cbb984(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = table[((y and 255L) + 276L).toInt()]
        c = (x shr 32L.toInt())
        d = ((y shr 32L.toInt()) xor a)
        e = (((d * 0x7185b5c4L) xor y) and 0xffffffffL)
        f = ((b + c) xor e)
        b = ((d - b) - c)
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = (((b and 0xffffffffL) xor e) or ((b - f) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbb9e0(x:Long, y:Long) {
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
        b = (y * 417463390L)
        c = (x shr 32L.toInt())
        d = (((((a * 417463389L) + b) + c) + 0x63146ff5L) and 0xffffffffL)
        e = ((d shr 23L.toInt()) or (d shl 9L.toInt()))
        f = ((((x - (y * 2L)) - a) + 0x9ceb900bL) and 0xffffffffL)
        g = (((((-x) - c) + a) - 768755204L) and 0xffffffffL)
        h = ((((f shr 19L.toInt()) or (f shl 13L.toInt())) + ((g shr 16L.toInt()) or (g shl 16L.toInt()))) xor (a + y))
        d = ((h + d) and 0xffffffffL)
        i = ((d shr 4L.toInt()) or (d shl 28L.toInt()))
        j = (-((f shr 8L.toInt()) or (f shl 24L.toInt())))
        d = (((d + f) + e) xor (g - j))
        leaf0 = ((((f - i) + e) and 0xffffffffL) or (((((((((h * 2L) + (a * 417463388L)) + b) + (c * 2L)) + j) + x) + d) + 0x90e6b5f9L) shl 32L.toInt()))
        leaf1 = (((((h - d) - g) + j) and 0xffffffffL) or ((((d - f) + i) - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbba78(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (y shr 32L.toInt())
        b = (table[((a and 255L) + 502L).toInt()] xor y)
        c = (x shr 32L.toInt())
        d = (c - y)
        e = (table[((b and 255L) + 288L).toInt()] xor d)
        c = (-(((x shr 44L.toInt()) and 0xfffffL) or (c shl 20L.toInt())))
        a = ((((d + a) + x) + c) and 0xffffffffL)
        c = (((x + c) - d) and 0xffffffffL)
        b = (b xor (((a shr 16L.toInt()) or (a shl 16L.toInt())) + ((c shr 31L.toInt()) or (c shl 1L.toInt()))))
        d = (((e + b) and 0xffffffffL) xor c)
        a = ((c + e) xor a)
        b = ((a + b) and 0xffffffffL)
        c = (b xor e)
        leaf0 = (d or (c shl 32L.toInt()))
        leaf1 = (b or (((a + d) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbaf0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = ((y shr 32L.toInt()) and 0xffffffffL)
        c = (table[(((x - a) and 255L) + 401L).toInt()] + b)
        d = table[((c and 255L) + 418L).toInt()]
        e = (y and 0xffffffffL)
        f = ((((((e shr 22L.toInt()) or (e shl 10L.toInt())) - 7958L) xor (b - 27841L)) and 0xffffffffL) xor a)
        b = (b xor e)
        e = ((b shr 16L.toInt()) or (b shl 16L.toInt()))
        g = ((f shr 27L.toInt()) or (f shl 5L.toInt()))
        h = (((((((x - (f * 378959407L)) - (d * 378959408L)) + e) + g) - a) - b) and 0xffffffffL)
        a = ((((e + x) - a) + g) and 0xffffffffL)
        c = (c xor ((a shr 27L.toInt()) or (a shl 5L.toInt())))
        e = (((d + b) - c) and 0xffffffffL)
        a = (((a + f) + c) - b)
        c = (((f - ((((e shr 14L.toInt()) or (e shl 18L.toInt())) - 28086L) xor (a - 26723L))) + d) and 0xffffffffL)
        leaf0 = (h or (c shl 32L.toInt()))
        leaf1 = ((((((h * 2L) + d) + ((f + d) * 378959408L)) + b) and 0xffffffffL) or (((a - ((h shr 23L.toInt()) or (h shl 9L.toInt()))) - ((c shr 7L.toInt()) or (c shl 25L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbbc0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = ((x shr 32L.toInt()) - y)
        d = (((y - b) + 0x731ad17cL) xor c)
        c = table[((c and 255L) + 201L).toInt()]
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((((c + ((c + b) * 0x576d3044L)) + y) + 0x731ad17cL) and 0xffffffffL) or (((c + b) + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbc28(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (((b * (-0x3e442f89L)) xor y) and 0xffffffffL)
        d = (((y + 0x402b951dL) xor (x shr 32L.toInt())) and 0xffffffffL)
        e = (c - table[(((b - d) and 255L) + 142L).toInt()])
        c = (c xor d)
        f = (table[((e and 255L) + 232L).toInt()] xor c)
        b = ((c + b) - d)
        c = ((e xor b) and 0xffffffffL)
        d = (f xor c)
        e = ((d shr 25L.toInt()) or (d shl 7L.toInt()))
        f = (-((((f shr 13L.toInt()) or (f shl 19L.toInt())) + 18932L) xor (c - 16634L)))
        g = (-table[(((b + f) and 255L) + 240L).toInt()])
        h = ((c + g) and 0xffffffffL)
        i = ((h shr 31L.toInt()) or (h shl 1L.toInt()))
        c = ((((((b + e) + f) + i) * 56905101L) + c) + g)
        d = ((h + d) and 0xffffffffL)
        g = ((c and 0xffffffffL) xor d)
        b = ((((b + f) + e) + i) xor ((d shr 26L.toInt()) or (d shl 6L.toInt())))
        leaf0 = (a or (g shl 32L.toInt()))
        leaf1 = (((c - table[((b and 255L) + 2L).toInt()]) and 0xffffffffL) or ((b xor ((g shr 10L.toInt()) or (g shl 22L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbd04(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = ((x xor (((x shr 34L.toInt()) and 0x3fffffffL) or (a shl 30L.toInt()))) and 0xffffffffL)
        c = (y shr 32L.toInt())
        leaf0 = (b or ((a + y) shl 32L.toInt()))
        leaf1 = ((((c * 798456251L) xor y) and 0xffffffffL) or ((b + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbd34(x:Long, y:Long) {
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
        var l = 0L
        a = (x shr 32L.toInt())
        b = (a * 0x571862ddL)
        c = (y * (-0x571862ddL))
        d = (y shr 32L.toInt())
        e = table[((a and 255L) + 505L).toInt()]
        f = (((d - e) - x) and 0xffffffffL)
        a = (a - y)
        e = ((e + x) xor (a - 535091863L))
        g = ((((f shr 13L.toInt()) or (f shl 19L.toInt())) + 24965L) xor (e - 31614L))
        f = (f xor e)
        h = (f * 0x571862ddL)
        a = (a - g)
        e = (a xor e)
        d = (d xor y)
        i = ((a + f) xor ((d - g) - f))
        j = (((((a * 0x571862ddL) + h) + e) xor (i - 0xc5d1060L)) and 0xffffffffL)
        k = table[((e and 255L) + 488L).toInt()]
        f = ((f + k) and 0xffffffffL)
        l = ((f shr 16L.toInt()) or (f shl 16L.toInt()))
        leaf0 = (j or (((((((((i - d) - (g * 0x571862dcL)) + h) - k) + b) + c) + e) - l) shl 32L.toInt()))
        leaf1 = ((((((((((d + (g * 0x571862dcL)) - h) + k) - b) - c) - e) + l) + j) and 0xffffffffL) or (((((f - (a * 0x571862ddL)) - h) - e) xor j) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbdfc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) - (y * 2L))
        c = ((y shr 32L.toInt()) xor a)
        d = (c * 0x6558cc86L)
        c = (table[((((y + b) - 52L) and 255L) + 264L).toInt()] xor c)
        e = (((d + y) - table[((c and 255L) + 383L).toInt()]) and 0xffffffffL)
        c = (c - table[(((((d * 127L) + b) - 97L) and 255L) + 322L).toInt()])
        b = ((b - d) - ((((e shr 7L.toInt()) or (e shl 25L.toInt())) - 2703L) xor (c + 28095L)))
        d = ((e - c) and 0xffffffffL)
        e = ((d shr 9L.toInt()) or (d shl 23L.toInt()))
        f = (b + e)
        b = ((b + 0x41b8759fL) and 0xffffffffL)
        b = (((((b shr 26L.toInt()) or (b shl 6L.toInt())) + e) xor c) and 0xffffffffL)
        c = ((((f + 0x41b8b5beL) and 0xffffffffL) xor d) xor ((((b shr 21L.toInt()) or (b shl 11L.toInt())) + 4311L) and 0xffffffffL))
        b = (b xor ((f + 0x41b8759fL) and 0xffffffffL))
        leaf0 = (a or (((f - c) + 0x41b8759fL) shl 32L.toInt()))
        leaf1 = ((c xor b) or ((((c - f) + b) + 0x4d1c629eL) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbee8(x:Long, y:Long) {
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
        var l = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (table[((((a - b) - y) and 255L) + 55L).toInt()] xor ((x - a) and 0xffffffffL))
        d = (table[((b and 255L) + 19L).toInt()] xor (y and 0xffffffffL))
        a = (((d + a) - b) - y)
        b = (c xor (a and 0xffffffffL))
        e = ((x - y) and 0xffffffffL)
        d = (d xor (((e shr 19L.toInt()) or (e shl 13L.toInt())) and 0xffffffffL))
        f = ((d shr 18L.toInt()) or (d shl 14L.toInt()))
        g = (f * 2L)
        c = (((c + 0x9f41087fL) and 0xffffffffL) xor e)
        e = ((c shr 15L.toInt()) or (c shl 17L.toInt()))
        h = (e * 2L)
        i = ((b shr 26L.toInt()) or (b shl 6L.toInt()))
        j = ((b shr 3L.toInt()) or (b shl 29L.toInt()))
        k = ((c shr 5L.toInt()) or (c shl 27L.toInt()))
        l = (((((((d + c) - k) - j) + i) * 46113590L) - 477444786L) xor (((((a - f) - e) - d) + j) + k))
        e = table[(((((((((-d) - a) + e) + f) + b) + k) + j) and 255L) + 393L).toInt()]
        leaf0 = (((((h - (a * 2L)) + g) + b) and 0xffffffffL) or (l shl 32L.toInt()))
        leaf1 = (((((((((c * 2L) + (i * 2L)) + d) - j) - k) + e) + 0x809059e5L) and 0xffffffffL) or ((((((((l - (a * 2L)) + e) + c) + h) + g) + b) + i) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbbfbc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (((y + 0x6ba2d8e6L) and 0xffffffffL) xor a)
        c = ((y shr 32L.toInt()) and 0xffffffffL)
        a = (table[((a and 255L) + 40L).toInt()] xor (x and 0xffffffffL))
        d = (((((a shr 12L.toInt()) or (a shl 20L.toInt())) + ((b shr 21L.toInt()) or (b shl 11L.toInt()))) and 0xffffffffL) xor c)
        e = ((c + y) and 0xffffffffL)
        f = (d xor e)
        g = (((b + e) + f) and 0xffffffffL)
        a = (((a - ((e shr 4L.toInt()) or (e shl 28L.toInt()))) - b) and 0xffffffffL)
        b = (((((b + c) + y) - f) * 8697063L) + (g xor a))
        c = (b and 0xffffffffL)
        a = (a xor e)
        e = (((g - (f * 2L)) xor ((a shr 13L.toInt()) or (a shl 19L.toInt()))) and 0xffffffffL)
        f = (c xor (((e shr 30L.toInt()) or (e shl 2L.toInt())) and 0xffffffffL))
        d = (g xor d)
        a = ((((((d shr 24L.toInt()) or (d shl 8L.toInt())) + 9896L) xor (b - 2401L)) and 0xffffffffL) xor a)
        b = (e xor ((a shr 21L.toInt()) or (a shl 11L.toInt())))
        c = (d - ((((c shr 2L.toInt()) or (c shl 30L.toInt())) - 3404L) xor (e + 25042L)))
        leaf0 = (f or (b shl 32L.toInt()))
        leaf1 = ((((c + f) and 0xffffffffL) xor a) or (((c - f) - b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc218(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) - y) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((c + y) and 0xffffffffL) or ((c - ((b shr 18L.toInt()) or (b shl 14L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc244(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x shr 32L.toInt())
        b = (x - a)
        c = ((((a + y) * 0x5e9343b1L) xor b) and 0xffffffffL)
        d = (y shr 32L.toInt())
        a = (((c - (d * 0x666c9530L)) - (y * 0x666c952fL)) + a)
        e = ((((x - (y * 0x71baef5eL)) + (b * 94959796L)) - (d * 0x6c11f6aaL)) and 0xffffffffL)
        e = ((e shr 19L.toInt()) or (e shl 13L.toInt()))
        f = ((c shr 30L.toInt()) or (c shl 2L.toInt()))
        g = (((b + d) - f) and 0xffffffffL)
        h = ((((g shr 6L.toInt()) or (g shl 26L.toInt())) - 9699L) xor (a + 13031L))
        leaf0 = (((a - e) and 0xffffffffL) or ((((((((x * 2L) + (y * 668498802L)) + (b * 0xb302f0dL)) + (d * 856205951L)) + h) + f) + c) shl 32L.toInt()))
        leaf1 = (((((((y - a) - h) - (g * 92747354L)) - (f * 92747355L)) + d) and 0xffffffffL) or (((g - (a * 0x4c061b58L)) + (e * 0x4c061b57L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc370(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (y and 0xffffffffL)
        c = ((b - 13367L) xor (((c shr 25L.toInt()) or (c shl 7L.toInt())) + 26566L))
        d = (x shr 32L.toInt())
        e = ((b - table[(((c + d) and 255L) + 29L).toInt()]) and 0xffffffffL)
        f = ((e shr 18L.toInt()) or (e shl 14L.toInt()))
        e = (((((f + (b * 2L)) + (y * 2L)) + c) + d) xor e)
        f = ((f + b) + y)
        g = (f and 0xffffffffL)
        b = ((((((g shr 21L.toInt()) or (g shl 11L.toInt())) + c) + d) + b) + y)
        c = ((f - e) and 0xffffffffL)
        d = (e + table[((b and 255L) + 433L).toInt()])
        e = (-((((c shr 5L.toInt()) or (c shl 27L.toInt())) + 2570L) xor (d + 28513L)))
        f = (((b + c) - d) + e)
        b = ((b + e) and 0xffffffffL)
        b = ((d - ((b shr 29L.toInt()) or (b shl 3L.toInt()))) and 0xffffffffL)
        c = ((c - ((((b shr 7L.toInt()) or (b shl 25L.toInt())) + 16498L) xor (f - 302789436L))) - d)
        d = table[((c and 255L) + 49L).toInt()]
        leaf0 = (a or (((f + d) - 302807892L) shl 32L.toInt()))
        leaf1 = (((c + table[((((b - f) + 84L) and 255L) + 22L).toInt()]) and 0xffffffffL) or ((((b - (f * 2L)) - d) + 605615784L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc47c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (y shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        b = ((((((y shr 62L.toInt()) and 3L) or (a shl 2L.toInt())) + ((b shr 20L.toInt()) or (b shl 12L.toInt()))) and 0xffffffffL) xor c)
        d = ((y - (((((y shr 61L.toInt()) and 7L) or (a shl 3L.toInt())) - 8847L) xor (((x - y) - c) - 17170L))) and 0xffffffffL)
        e = ((((((b shr 24L.toInt()) or (b shl 8L.toInt())) + x) - y) - c) + ((d shr 8L.toInt()) or (d shl 24L.toInt())))
        a = (((((y - x) + c) * 361911188L) + a) and 0xffffffffL)
        c = (table[((e and 255L) + 53L).toInt()] xor a)
        a = ((((e - 3021L) xor (((a shr 28L.toInt()) or (a shl 4L.toInt())) + 26465L)) + d) and 0xffffffffL)
        f = (table[((c and 255L) + 123L).toInt()] xor a)
        b = ((b - ((d shr 1L.toInt()) or (d shl 31L.toInt()))) and 0xffffffffL)
        d = (b xor ((a shr 18L.toInt()) or (a shl 14L.toInt())))
        a = (((((b shr 9L.toInt()) or (b shl 23L.toInt())) + 17034L) xor e) xor (a - 9656L))
        b = (table[((a and 255L) + 508L).toInt()] xor c)
        c = (((f + d) + b) and 0xffffffffL)
        e = (c xor (((d * 0x6edf652bL) + a) and 0xffffffffL))
        f = (f - b)
        g = (c xor table[((f and 255L) + 369L).toInt()])
        a = ((((a + c) + (d * 0x6edf652bL)) and 0xffffffffL) xor b)
        leaf0 = (e or (g shl 32L.toInt()))
        leaf1 = (((f xor ((a shr 13L.toInt()) or (a shl 19L.toInt()))) and 0xffffffffL) or ((a - ((((e shr 11L.toInt()) or (e shl 21L.toInt())) + 28341L) xor (g - 12867L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc5b8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x xor (y shr 32L.toInt()))
        b = (y - table[((a and 255L) + 28L).toInt()])
        c = (((y * 0x7d36d5daL) xor (x shr 32L.toInt())) and 0xffffffffL)
        a = ((((-c) - b) + a) and 0xffffffffL)
        d = (b xor ((a shr 19L.toInt()) or (a shl 13L.toInt())))
        b = (table[((b and 255L) + 53L).toInt()] xor c)
        c = ((d + 0x5ce390adL) xor b)
        a = (a xor b)
        b = ((a - c) and 0xffffffffL)
        e = ((b shr 3L.toInt()) or (b shl 29L.toInt()))
        a = ((((c - 5765L) xor (((a shr 3L.toInt()) or (a shl 29L.toInt())) + 3744L)) + d) and 0xffffffffL)
        d = ((a shr 13L.toInt()) or (a shl 19L.toInt()))
        leaf0 = ((x and 0xffffffffL) or (((c - e) - d) shl 32L.toInt()))
        leaf1 = ((a xor (((b shr 9L.toInt()) or (b shl 23L.toInt())) and 0xffffffffL)) or (((((c - e) - d) * 60078933L) xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc670(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (y - b)
        d = (y and 0xffffffffL)
        d = (((x shr 32L.toInt()) - ((d shr 30L.toInt()) or (d shl 2L.toInt()))) and 0xffffffffL)
        b = ((((d shr 23L.toInt()) or (d shl 9L.toInt())) + 6264L) xor ((c - 1341L) xor b))
        e = (-table[((b and 255L) + 138L).toInt()])
        d = (((c * 341757537L) and 0xffffffffL) xor d)
        f = (((c + e) and 0xffffffffL) xor d)
        b = (b xor d)
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = ((((c + e) - (b * 830537040L)) and 0xffffffffL) or ((((f shr 16L.toInt()) or (f shl 16L.toInt())) + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc700(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (y and 0xffffffffL)
        b = (x shr 32L.toInt())
        a = ((x - ((a shr 23L.toInt()) or (a shl 9L.toInt()))) - (((x shr 40L.toInt()) and 0xffffffL) or (b shl 24L.toInt())))
        c = (y shr 32L.toInt())
        leaf0 = ((a and 0xffffffffL) or ((b - table[((y and 255L) + 226L).toInt()]) shl 32L.toInt()))
        leaf1 = (((c + y) and 0xffffffffL) or ((a + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc748(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y xor ((b shr 23L.toInt()) or (b shl 9L.toInt())))
        d = (-c)
        e = (x shr 32L.toInt())
        f = (e * (-2L))
        g = (y * 2L)
        e = ((((((d - b) + f) + g) + 0x9aec5c59L) xor ((c + e) - y)) and 0xffffffffL)
        b = ((((d - ((((e shr 1L.toInt()) or (e shl 31L.toInt())) - 4150L) xor (c - 21937L))) - b) + f) + g)
        c = (((-(b * 2L)) - d) and 0xffffffffL)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((((b - ((((c shr 20L.toInt()) or (c shl 12L.toInt())) + 2531L) xor (b + 15459L))) - e) - d) and 0xffffffffL) or (((-(b * 3L)) - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc7d4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = (a xor y)
        c = (y shr 32L.toInt())
        a = (((((x - (((x shr 55L.toInt()) and 511L) or (a shl 9L.toInt()))) + b) + c) + y) and 0xffffffffL)
        leaf0 = (a or (((b - c) - y) shl 32L.toInt()))
        leaf1 = (((a - c) and 0xffffffffL) or ((((y - a) + (c * 2L)) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc810(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = ((x - table[((a and 255L) + 21L).toInt()]) and 0xffffffffL)
        c = (y shr 32L.toInt())
        a = (((c + y) and 0xffffffffL) xor a)
        d = (b xor ((a shr 17L.toInt()) or (a shl 15L.toInt())))
        e = ((b shr 3L.toInt()) or (b shl 29L.toInt()))
        b = (-((b - 14836L) xor ((((y shr 52L.toInt()) and 4095L) or (c shl 12L.toInt())) - 15212L)))
        f = (((y + b) + (((-e) - c) * 710334018L)) and 0xffffffffL)
        c = (((e + c) - d) and 0xffffffffL)
        e = (f xor c)
        g = (-((c shr 2L.toInt()) or (c shl 30L.toInt())))
        h = (-((f shr 28L.toInt()) or (f shl 4L.toInt())))
        i = (((d + e) + g) + h)
        a = ((y + b) xor a)
        b = table[(((d - a) and 255L) + 104L).toInt()]
        c = (c xor b)
        a = (((a + g) + h) xor (e + c))
        d = (i + a)
        b = (f xor b)
        e = table[((i and 255L) + 430L).toInt()]
        leaf0 = ((d and 0xffffffffL) or ((a xor ((b shr 20L.toInt()) or (b shl 12L.toInt()))) shl 32L.toInt()))
        leaf1 = (((((e - c) * 0x4dbd6ff9L) and 0xffffffffL) xor b) or (((d + c) - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbc8ec(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (x - a)
        c = (y and 0xffffffffL)
        a = (a - ((c shr 19L.toInt()) or (c shl 13L.toInt())))
        d = (y shr 32L.toInt())
        leaf0 = ((b and 0xffffffffL) or (a shl 32L.toInt()))
        leaf1 = ((((d + 0x7d9e587L) and 0xffffffffL) xor c) or ((((a + 15319L) xor d) xor (b + 2882L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcabc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = ((table[(((b + y) and 255L) + 473L).toInt()] + (y * 433106463L)) + c)
        c = (((b + (y * 433106464L)) + c) xor b)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((((table[((c and 255L) + 136L).toInt()] + b) + y) and 0xffffffffL) or ((d + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcb28(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((y xor (x shr 32L.toInt())) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        d = (c + y)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or ((((((b shr 15L.toInt()) or (b shl 17L.toInt())) + 13139L) xor (d + 8119L)) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcb64(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = ((a + x) and 0xffffffffL)
        a = (a - y)
        c = (y shr 32L.toInt())
        leaf0 = (b or (a shl 32L.toInt()))
        leaf1 = (((y - (((y shr 35L.toInt()) and 536870911L) or (c shl 29L.toInt()))) and 0xffffffffL) or ((((((b shr 23L.toInt()) or (b shl 9L.toInt())) + 14721L) xor (a - 651L)) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcba0(x:Long, y:Long) {
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
        b = y
        c = (a xor b)
        a = (((x - y) - a) and 0xffffffffL)
        d = (table[((c and 255L) + 11L).toInt()] xor a)
        e = (y shr 32L.toInt())
        f = (a + e)
        a = ((((a shr 3L.toInt()) or (a shl 29L.toInt())) + (((y shr 50L.toInt()) and 16383L) or (e shl 14L.toInt()))) xor b)
        b = (a xor (f + 44579393L))
        e = (((((a - d) + f) - c) - 893238740L) xor b)
        a = (((c - a) + b) - e)
        c = ((((d + e) + 716936927L) xor (a + 0x62a18ef4L)) and 0xffffffffL)
        g = (e xor table[(((((f - (d * 2L)) + b) + 65L) and 255L) + 211L).toInt()])
        h = table[((g and 255L) + 277L).toInt()]
        i = ((((a + c) + h) + 0x62a18ef4L) and 0xffffffffL)
        a = (a + h)
        b = (((((f - (d * 3L)) + b) - e) + 420088476L) and 0xffffffffL)
        d = (g - ((b shr 8L.toInt()) or (b shl 24L.toInt())))
        e = (((a - table[((d and 255L) + 504L).toInt()]) + 0x62a18ef4L) and 0xffffffffL)
        a = (-((((c shr 6L.toInt()) or (c shl 26L.toInt())) - 27490L) xor (a + 0x62a1c926L)))
        leaf0 = (i or (e shl 32L.toInt()))
        leaf1 = (((((b + d) + a) + 35429099L) and 0xffffffffL) or ((((((i shr 10L.toInt()) or (i shl 22L.toInt())) + ((e shr 12L.toInt()) or (e shl 20L.toInt()))) + b) + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbccd0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (y shr 32L.toInt())
        b = (y xor (((y shr 46L.toInt()) and 262143L) or (a shl 18L.toInt())))
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (((b + a) - c) - x)
        e = (((y * 0x403dd1b5L) and 0xffffffffL) xor c)
        f = ((((e shr 25L.toInt()) or (e shl 7L.toInt())) + 32632L) xor (b - 32766L))
        c = (((a - (c * 2L)) - (x * 2L)) - f)
        g = (-table[((c and 255L) + 386L).toInt()])
        h = (d + g)
        b = (((b + e) xor d) and 0xffffffffL)
        d = ((h and 0xffffffffL) xor b)
        a = ((((f + b) - e) + g) + a)
        b = (((a - h) xor c) and 0xffffffffL)
        c = (h - ((a + 22053L) xor (((b shr 1L.toInt()) or (b shl 31L.toInt())) + 18957L)))
        e = ((((((d shr 4L.toInt()) or (d shl 28L.toInt())) - 15364L) xor a) xor (c - 12962L)) and 0xffffffffL)
        f = (c and 0xffffffffL)
        a = ((-(a * 0xa365e71L)) xor b)
        b = ((((f shr 21L.toInt()) or (f shl 11L.toInt())) - 8747L) xor (a - 17002L))
        leaf0 = (e or ((b + d) shl 32L.toInt()))
        leaf1 = (((c - a) and 0xffffffffL) or ((((a - e) - b) - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcdc0(x:Long, y:Long) {
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
        var l = 0L
        a = ((y shr 32L.toInt()) xor x)
        b = (((a * 0x5726da80L) + (y * 960238320L)) xor (x shr 32L.toInt()))
        c = (table[((b and 255L) + 439L).toInt()] + x)
        d = (c and 0xffffffffL)
        e = ((d shr 31L.toInt()) or (d shl 1L.toInt()))
        f = (e + a)
        g = (f and 0xffffffffL)
        h = ((g shr 31L.toInt()) or (g shl 1L.toInt()))
        c = (c - (h * 2L))
        i = (y * (-2L))
        j = table[(((((c + (a * 80L)) + i) - b) and 255L) + 352L).toInt()]
        k = (j * (-0x78835e58L))
        e = (((((y + k) + (g * 0x61a49480L)) + h) + (e * 634916136L)) and 0xffffffffL)
        l = (-((e shr 26L.toInt()) or (e shl 6L.toInt())))
        h = table[((((((h - (a * 40L)) + y) + b) + l) and 255L) + 21L).toInt()]
        a = ((((c + (a * 0x4bb01a50L)) + i) - b) + h)
        b = (a xor (f + j))
        c = (((((((k - (g * 0x78835e58L)) + h) + d) + b) + l) - 0x5d6b4a31L) and 0xffffffffL)
        d = (a xor ((c shr 28L.toInt()) or (c shl 4L.toInt())))
        f = ((b - d) and 0xffffffffL)
        a = ((((a + e) + b) - ((f shr 23L.toInt()) or (f shl 9L.toInt()))) and 0xffffffffL)
        b = (((((a shr 9L.toInt()) or (a shl 23L.toInt())) + ((f shr 12L.toInt()) or (f shl 20L.toInt()))) and 0xffffffffL) xor c)
        c = ((d - ((a shr 16L.toInt()) or (a shl 16L.toInt()))) - ((b shr 2L.toInt()) or (b shl 30L.toInt())))
        d = ((c * 869855497L) xor f)
        a = (((a + c) + d) and 0xffffffffL)
        b = (a xor b)
        leaf0 = (((c - b) and 0xffffffffL) or (b shl 32L.toInt()))
        leaf1 = (a or (d shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbced4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (y shr 32L.toInt())
        b = (a xor y)
        c = (x shr 32L.toInt())
        d = (c + y)
        c = (d + (c xor x))
        a = (c + a)
        e = (b - table[((a and 255L) + 426L).toInt()])
        f = table[((e and 255L) + 309L).toInt()]
        g = (c and 0xffffffffL)
        a = (((((d - b) + 30541L) xor a) xor (((g shr 16L.toInt()) or (g shl 16L.toInt())) - 20031L)) and 0xffffffffL)
        e = (e - ((a shr 22L.toInt()) or (a shl 10L.toInt())))
        g = ((((f - b) + d) and 0xffffffffL) xor table[((e and 255L) + 310L).toInt()])
        a = (a xor (((c - b) + d) and 0xffffffffL))
        b = ((a shr 4L.toInt()) or (a shl 28L.toInt()))
        c = ((c - f) and 0xffffffffL)
        d = ((c shr 2L.toInt()) or (c shl 30L.toInt()))
        f = ((g shr 27L.toInt()) or (g shl 5L.toInt()))
        leaf0 = (((c + g) and 0xffffffffL) or (((e + g) + b) shl 32L.toInt()))
        leaf1 = ((((((e + b) - a) + d) + f) and 0xffffffffL) or ((((a - d) - f) xor (((-c) - g) * 0x6a2c336bL)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbcfa4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = ((y shr 32L.toInt()) and 0xffffffffL)
        b = (a + y)
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (((((x shr 62L.toInt()) and 3L) or (c shl 2L.toInt())) + x) and 0xffffffffL)
        c = (((y * 603504426L) and 0xffffffffL) xor c)
        e = ((((b - 1549L) and 0xffffffffL) xor d) xor ((((c shr 13L.toInt()) or (c shl 19L.toInt())) - 4722L) and 0xffffffffL))
        a = (d xor a)
        leaf0 = (e or ((c + b) shl 32L.toInt()))
        leaf1 = ((a xor (b and 0xffffffffL)) or ((table[((e and 255L) + 325L).toInt()] + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd018(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (a - table[((y and 255L) + 102L).toInt()])
        a = (((a * (-924335779L)) xor x) and 0xffffffffL)
        c = ((b and 0xffffffffL) xor a)
        d = (y shr 32L.toInt())
        e = table[((d and 255L) + 75L).toInt()]
        a = ((a + 0xa00f0eL) xor d)
        leaf0 = (c or (((b - y) + e) shl 32L.toInt()))
        leaf1 = (((((y - e) - a) + 993413803L) and 0xffffffffL) or ((c + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd08c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = ((((((x shr 42L.toInt()) and 4194303L) or (a shl 22L.toInt())) + x) xor (y shr 32L.toInt())) and 0xffffffffL)
        c = ((y xor ((b shr 14L.toInt()) or (b shl 18L.toInt()))) and 0xffffffffL)
        a = (a xor ((c shr 9L.toInt()) or (c shl 23L.toInt())))
        d = (a xor x)
        b = (b - table[((d and 255L) + 178L).toInt()])
        e = ((((b + c) - a) + d) xor b)
        f = (e xor (b + c))
        d = ((e + ((((b + c) - a) - f) * 0x4b26b12fL)) - d)
        g = (d and 0xffffffffL)
        a = (((((-((g shr 23L.toInt()) or (g shl 9L.toInt()))) - b) - c) + a) + f)
        b = ((((e - g) + f) and 0xffffffffL) xor table[((a and 255L) + 197L).toInt()])
        c = (((g - ((b shr 19L.toInt()) or (b shl 13L.toInt()))) - f) and 0xffffffffL)
        d = ((d - ((c shr 28L.toInt()) or (c shl 4L.toInt()))) and 0xffffffffL)
        e = ((d shr 2L.toInt()) or (d shl 30L.toInt()))
        leaf0 = (((((a - e) * 814336005L) + b) and 0xffffffffL) or ((a - e) shl 32L.toInt()))
        leaf1 = (d or (c shl 32L.toInt()))
    }
