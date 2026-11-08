package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.math_cc2944(x:Long, y:Long) {
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
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = (c * 2L)
        e = ((b + y) and 0xffffffffL)
        f = ((e shr 5L.toInt()) or (e shl 27L.toInt()))
        b = ((((b * 2L) + y) + c) and 0xffffffffL)
        g = ((b shr 30L.toInt()) or (b shl 2L.toInt()))
        h = (((((((e * 3L) - y) - g) - f) + d) and 0xffffffffL) xor e)
        i = ((h shr 22L.toInt()) or (h shl 10L.toInt()))
        j = (f * (-2L))
        k = (g * (-2L))
        d = (table[(((((((h - e) + i) + g) + f) - c) and 255L) + 392L).toInt()] xor ((((((e * 2L) + d) + j) + k) - (i * 2L)) - h))
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((((((((y - (e * 5L)) - (c * 4L)) + (f * 3L)) + (g * 3L)) + (i * 2L)) + h) and 0xffffffffL) or ((d xor (((((y + (b * 2L)) + k) + j) - i) + c)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc29d0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        b = (((x shr 32L.toInt()) xor ((b shr 21L.toInt()) or (b shl 11L.toInt()))) and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((y - c) and 0xffffffffL)
        c = (((((d shr 6L.toInt()) or (d shl 26L.toInt())) + ((b shr 14L.toInt()) or (b shl 18L.toInt()))) and 0xffffffffL) xor c)
        b = ((b - ((d shr 18L.toInt()) or (d shl 14L.toInt()))) - ((c shr 9L.toInt()) or (c shl 23L.toInt())))
        e = (b and 0xffffffffL)
        f = (e xor d)
        d = (c xor d)
        b = (b - ((d shr 28L.toInt()) or (d shl 4L.toInt())))
        c = ((c xor e) xor table[((b and 255L) + 158L).toInt()])
        d = ((f - ((c shr 28L.toInt()) or (c shl 4L.toInt()))) and 0xffffffffL)
        e = (d xor (b + f))
        b = ((c - b) - f)
        c = (d xor table[((b and 255L) + 244L).toInt()])
        d = ((e - ((c shr 22L.toInt()) or (c shl 10L.toInt()))) and 0xffffffffL)
        b = ((e + 0xab86327dL) xor b)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((b + c) and 0xffffffffL) or ((((d shr 29L.toInt()) or (d shl 3L.toInt())) + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2a94(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (a + y)
        a = (a xor x)
        c = (b + a)
        d = (y shr 32L.toInt())
        a = (d - a)
        e = (table[((c and 255L) + 148L).toInt()] xor (a and 0xffffffffL))
        f = ((d - 807067134L) xor y)
        a = (e xor (((a + f) + 0xa2a66ef8L) and 0xffffffffL))
        g = ((a shr 23L.toInt()) or (a shl 9L.toInt()))
        d = (((c - f) - d) and 0xffffffffL)
        c = (c xor ((d shr 15L.toInt()) or (d shl 17L.toInt())))
        b = (((((e - d) + b) - 0x5d599108L) and 0xffffffffL) xor d)
        d = ((b shr 12L.toInt()) or (b shl 20L.toInt()))
        leaf0 = ((((g + c) + d) and 0xffffffffL) or ((b - a) shl 32L.toInt()))
        leaf1 = (((a + table[(((e - c) and 255L) + 120L).toInt()]) and 0xffffffffL) or (((e - c) + ((((-c) - d) - g) * 886352152L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2b80(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (((table[(((((a * (-70L)) - (x * 70L)) + y) and 255L) + 452L).toInt()] + a) - (y * 2L)) + b)
        d = (((a - y) + 0xe53ebd20L) and 0xffffffffL)
        e = ((y - b) and 0xffffffffL)
        f = ((((d shr 20L.toInt()) or (d shl 12L.toInt())) + ((e shr 2L.toInt()) or (e shl 30L.toInt()))) xor (a + x))
        d = (((d - e) - 0x6af47c36L) and 0xffffffffL)
        a = (((-a) - x) * 955387974L)
        d = (f - ((((d shr 26L.toInt()) or (d shl 6L.toInt())) + 17626L) xor ((y + a) - 27797L)))
        e = (((c - 930516717L) xor d) and 0xffffffffL)
        b = (((f + 0xb98155c9L) xor (a + b)) and 0xffffffffL)
        a = ((((b shr 3L.toInt()) or (b shl 29L.toInt())) + y) + a)
        b = ((d + b) and 0xffffffffL)
        leaf0 = (e or (((c + 0x7a4a40eaL) xor (b + a)) shl 32L.toInt()))
        leaf1 = (((a and 0xffffffffL) xor b) or ((e xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2c58(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = ((((b * 3L) + (y * 3L)) + (c * 2L)) xor b)
        e = (((((((-b) - y) - c) - d) xor (((d * 444957028L) + ((b + y) * 0x4f90842cL)) + (c * 889914056L))) and 0xffffffffL) or ((((((-b) - y) * 77496861L) - (c * 0x4376e196L)) + (d * 0x3ed85f79L)) shl 32L.toInt()))
        leaf0 = (a or (((((b * 4L) + (y * 4L)) + (c * 3L)) + d) shl 32L.toInt()))
        leaf1 = e
    }

internal fun SpotifyPlayPlayLeaves.math_cc2cb0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (b xor (y and 0xffffffffL))
        d = ((b + y) xor (x shr 32L.toInt()))
        b = (c - (d xor b))
        d = (d + ((c shr 9L.toInt()) or (c shl 23L.toInt())))
        c = ((d + c) and 0xffffffffL)
        d = ((b xor d) and 0xffffffffL)
        b = ((b - ((c shr 25L.toInt()) or (c shl 7L.toInt()))) - ((d shr 31L.toInt()) or (d shl 1L.toInt())))
        c = (c + d)
        e = (b xor c)
        f = table[((e and 255L) + 443L).toInt()]
        b = (b xor d)
        c = ((b + e) xor c)
        d = (e - c)
        c = (d xor c)
        e = (((((f * 2L) - d) - c) + (b * 2L)) and 0xffffffffL)
        b = (((d - f) - b) - c)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = ((b and 0xffffffffL) or ((((b - 22767L) xor c) xor (((e shr 1L.toInt()) or (e shl 31L.toInt())) + 23423L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2db0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = ((y shr 32L.toInt()) and 0xffffffffL)
        b = (((y shr 52L.toInt()) and 4095L) or (a shl 12L.toInt()))
        c = (x shr 32L.toInt())
        d = (c xor y)
        c = (((c + x) + d) and 0xffffffffL)
        a = (c xor a)
        e = ((a shr 12L.toInt()) or (a shl 20L.toInt()))
        d = (((((y * 2L) - (b * 2L)) + d) + a) + e)
        f = ((d and 0xffffffffL) xor c)
        a = ((a - c) and 0xffffffffL)
        leaf0 = (f or ((d + 0x6caf6740L) shl 32L.toInt()))
        leaf1 = (((((e + y) - b) xor ((a shr 7L.toInt()) or (a shl 25L.toInt()))) and 0xffffffffL) or ((((f shr 25L.toInt()) or (f shl 7L.toInt())) + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2e10(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (((y * (-913054607L)) and 0xffffffffL) xor a)
        c = (y shr 32L.toInt())
        d = (-(((y shr 37L.toInt()) and 0x7ffffffL) or (c shl 27L.toInt())))
        a = (x - a)
        e = (a and 0xffffffffL)
        f = (-((e shr 13L.toInt()) or (e shl 19L.toInt())))
        g = (((b + y) + d) + f)
        e = (e xor b)
        h = (table[(((g + 31L) and 255L) + 494L).toInt()] xor e)
        a = (table[((a and 255L) + 83L).toInt()] + c)
        c = table[((a and 255L) + 443L).toInt()]
        b = ((((b - g) + c) * 0x4ff06b03L) xor (g + 0x491ff71fL))
        e = ((e shr 17L.toInt()) or (e shl 15L.toInt()))
        leaf0 = (h or (b shl 32L.toInt()))
        leaf1 = (((((h + a) + e) xor (((y + d) + f) - c)) and 0xffffffffL) or (((b + h) xor (a + e)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2ec4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (((((x shr 35L.toInt()) and 536870911L) or (a shl 29L.toInt())) - 20018L) xor (y - 30569L))
        c = (y and 0xffffffffL)
        d = (y shr 32L.toInt())
        leaf0 = (((x - b) and 0xffffffffL) or ((a - ((((c shr 27L.toInt()) or (c shl 5L.toInt())) + 4245L) xor (d + 7859L))) shl 32L.toInt()))
        leaf1 = ((((d * (-0x3d6f4790L)) and 0xffffffffL) xor c) or ((((b - x) + d) - 75482139L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2f30(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = ((b shr 7L.toInt()) or (b shl 25L.toInt()))
        d = (x shr 32L.toInt())
        e = (d * (-0x4957e450L))
        d = (((((c + (y * 0x4957e451L)) + e) + b) xor (d - y)) and 0xffffffffL)
        f = (d xor (((e + (y * 0x4957e450L)) + b) and 0xffffffffL))
        b = ((table[(((((y * 80L) + e) + b) and 255L) + 193L).toInt()] + c) + y)
        c = (b and 0xffffffffL)
        d = ((((f shr 8L.toInt()) or (f shl 24L.toInt())) + d) + ((c shr 12L.toInt()) or (c shl 20L.toInt())))
        e = (-((f shr 27L.toInt()) or (f shl 5L.toInt())))
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((b + e) and 0xffffffffL) or ((((d + c) + e) xor f) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2fb4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((((b shr 29L.toInt()) or (b shl 3L.toInt())) + (x shr 32L.toInt())) + ((c shr 17L.toInt()) or (c shl 15L.toInt())))
        e = (c xor b)
        f = (d + e)
        b = (f xor b)
        c = (f xor c)
        d = ((c + e) xor d)
        e = (d * (-0x5a3c97c4L))
        f = table[((b and 255L) + 8L).toInt()]
        g = (f * (-0x5a3c97c2L))
        h = ((((b + e) - (c * 2L)) + g) and 0xffffffffL)
        b = ((((f + (d * 2L)) - b) + c) and 0xffffffffL)
        d = ((((h shr 29L.toInt()) or (h shl 3L.toInt())) + ((b shr 2L.toInt()) or (b shl 30L.toInt()))) xor ((d + ((f + d) * 0x5a3c97c2L)) + c))
        f = ((h + d) and 0xffffffffL)
        b = (((f + b) and 0xffffffffL) xor f)
        c = (((((e * 271407165L) + c) + (g * 990129773L)) + ((c - d) * 571076581L)) and 0xffffffffL)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((((((c shr 6L.toInt()) or (c shl 26L.toInt())) + ((b shr 13L.toInt()) or (b shl 19L.toInt()))) and 0xffffffffL) xor f) or ((c xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc3064(x:Long, y:Long) {
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
        b = (((x shr 32L.toInt()) + y) and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = (((((b shr 12L.toInt()) or (b shl 20L.toInt())) + y) + ((c shr 9L.toInt()) or (c shl 23L.toInt()))) and 0xffffffffL)
        e = ((d shr 27L.toInt()) or (d shl 5L.toInt()))
        f = table[(((((d * 2L) + b) - c) and 255L) + 471L).toInt()]
        g = (((((e - (d * 0x4d00c1d2L)) + (b * 3L)) - (c * 2L)) + f) and 0xffffffffL)
        h = (((b - (d * 0x4d00c1d3L)) - c) and 0xffffffffL)
        i = ((h shr 31L.toInt()) or (h shl 1L.toInt()))
        b = (((((d + f) - c) + e) + (b * 2L)) and 0xffffffffL)
        b = ((b shr 24L.toInt()) or (b shl 8L.toInt()))
        leaf0 = (a or (g shl 32L.toInt()))
        leaf1 = (((((h - d) - i) - b) and 0xffffffffL) or ((((d - ((g shr 15L.toInt()) or (g shl 17L.toInt()))) + b) + i) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc30fc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (((b shr 20L.toInt()) or (b shl 12L.toInt())) + (x shr 32L.toInt()))
        d = ((y shr 32L.toInt()) xor a)
        b = ((c + (d * 2L)) xor b)
        e = (table[(((c + d) and 255L) + 94L).toInt()] xor d)
        f = (b - e)
        e = (e xor ((((-c) - b) - d) * 0xd5aa72bL))
        g = (f - e)
        f = (-table[((f and 255L) + 287L).toInt()])
        h = (g xor (((c + b) + f) + d))
        b = (((((g + c) + b) + f) + d) xor e)
        c = (((-g) - b) + h)
        d = (b xor h)
        b = (((d + 0x49fd3915L) xor ((g + b) + 421459341L)) and 0xffffffffL)
        e = (((c - ((b shr 19L.toInt()) or (b shl 13L.toInt()))) + 0xe6e10a73L) and 0xffffffffL)
        c = (d xor table[(((c + 115L) and 255L) + 175L).toInt()])
        b = (b + c)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = ((b and 0xffffffffL) or (((b + 31220L) xor ((((e shr 18L.toInt()) or (e shl 14L.toInt())) - 19977L) xor c)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc31e8(x:Long, y:Long) {
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
        c = ((y shr 32L.toInt()) and 0xffffffffL)
        d = (a - ((((b shr 15L.toInt()) or (b shl 17L.toInt())) + 4138L) xor (c - 29269L)))
        a = (x - a)
        b = ((a + c) xor b)
        e = (a and 0xffffffffL)
        c = ((((((e shr 5L.toInt()) or (e shl 27L.toInt())) - 26683L) xor (d + 17547L)) and 0xffffffffL) xor c)
        e = ((d + (b * 2L)) - c)
        d = (d and 0xffffffffL)
        a = ((((d shr 25L.toInt()) or (d shl 7L.toInt())) + a) and 0xffffffffL)
        d = (e xor a)
        a = (c xor (((a shr 11L.toInt()) or (a shl 21L.toInt())) and 0xffffffffL))
        f = table[(((e + a) and 255L) + 101L).toInt()]
        b = ((((-((d - 20126L) xor (((a shr 8L.toInt()) or (a shl 24L.toInt())) - 21356L))) - c) + b) and 0xffffffffL)
        c = ((b shr 16L.toInt()) or (b shl 16L.toInt()))
        g = (((((-e) - a) - f) + d) + c)
        h = (g and 0xffffffffL)
        i = (d xor a)
        b = (b xor i)
        a = ((((table[((b and 255L) + 145L).toInt()] + e) + a) - c) and 0xffffffffL)
        c = (((a shr 5L.toInt()) or (a shl 27L.toInt())) and 0xffffffffL)
        d = ((((h + (f * 2L)) - (d * 2L)) + i) and 0xffffffffL)
        b = (b - ((((d shr 18L.toInt()) or (d shl 14L.toInt())) - 7887L) xor (g + 15737L)))
        leaf0 = ((h xor c) or ((a xor table[((b and 255L) + 89L).toInt()]) shl 32L.toInt()))
        leaf1 = (((b + (d xor h)) and 0xffffffffL) or ((d xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc3304(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = ((y + 0x43a8c11cL) xor (x shr 32L.toInt()))
        b = (x xor (y shr 32L.toInt()))
        c = (-table[((b and 255L) + 256L).toInt()])
        b = (((a * 2L) + b) and 0xffffffffL)
        d = ((b shr 1L.toInt()) or (b shl 31L.toInt()))
        leaf0 = ((x and 0xffffffffL) or ((((a - y) + c) - d) shl 32L.toInt()))
        leaf1 = ((table[(((((b * 2L) - y) + c) and 255L) + 504L).toInt()] xor b) or ((((b * 2L) - a) + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc336c(x:Long, y:Long) {
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
        c = (-(x shr 32L.toInt()))
        d = (((b + c) - y) and 0xffffffffL)
        e = ((b + y) xor ((d shr 29L.toInt()) or (d shl 3L.toInt())))
        f = (b * 7L)
        g = (y * 2L)
        h = (((((e * 3L) + (b * 5L)) + c) + y) and 0xffffffffL)
        i = ((h shr 11L.toInt()) or (h shl 21L.toInt()))
        c = (((((((f + g) + (e * 4L)) - i) + c) + 0x6ade67cL) xor (((((e * 5L) + f) + c) + g) + i)) and 0xffffffffL)
        leaf0 = (a or ((((h * 2L) - d) + 0x6ade67cL) shl 32L.toInt()))
        leaf1 = (c or (((((c + (h * 2L)) - d) + 0x6ade67cL) xor ((((b * (-2L)) - y) - (e * 2L)) - i)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc33dc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x xor (y shr 32L.toInt()))
        b = ((table[((a and 255L) + 188L).toInt()] + y) and 0xffffffffL)
        c = (x shr 32L.toInt())
        d = (((((b shr 7L.toInt()) or (b shl 25L.toInt())) + c) - y) and 0xffffffffL)
        a = ((a - table[(((c - y) and 255L) + 17L).toInt()]) and 0xffffffffL)
        b = (b xor ((a shr 29L.toInt()) or (a shl 3L.toInt())))
        c = (d xor table[((b and 255L) + 359L).toInt()])
        d = ((d shr 23L.toInt()) or (d shl 9L.toInt()))
        leaf0 = ((x and 0xffffffffL) or (c shl 32L.toInt()))
        leaf1 = (((((b - a) - d) - 329327389L) and 0xffffffffL) or (((a + c) + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc34d0(x:Long, y:Long) {
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
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y and 0xffffffffL)
        c = (((c shr 17L.toInt()) or (c shl 15L.toInt())) + (x shr 32L.toInt()))
        d = table[((c and 255L) + 506L).toInt()]
        e = ((((b shr 13L.toInt()) or (b shl 19L.toInt())) + y) and 0xffffffffL)
        c = ((c xor ((e shr 10L.toInt()) or (e shl 22L.toInt()))) and 0xffffffffL)
        e = (((c + (e * 0x70ae7845L)) + ((b - d) * 0x60a43536L)) and 0xffffffffL)
        f = ((e shr 13L.toInt()) or (e shl 19L.toInt()))
        g = (((c - e) * 270935411L) and 0xffffffffL)
        c = (-((c shr 1L.toInt()) or (c shl 31L.toInt())))
        h = (-((g shr 5L.toInt()) or (g shl 27L.toInt())))
        g = ((((((g - d) + b) + h) + c) - 0x7a20b302L) and 0xffffffffL)
        i = ((g shr 19L.toInt()) or (g shl 13L.toInt()))
        e = (((((((g - d) + b) + i) + h) + f) + c) xor e)
        b = (((((f + b) - d) + c) + h) + i)
        c = (b xor g)
        d = ((e + c) and 0xffffffffL)
        b = (e xor b)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((b + c) and 0xffffffffL) or ((((b * 2L) + c) + ((d shr 18L.toInt()) or (d shl 14L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc3590(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) - y) and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = (c xor (y and 0xffffffffL))
        e = ((((b shr 30L.toInt()) or (b shl 2L.toInt())) + 4462L) xor (d - 29986L))
        f = (d * (-0x75b48ad0L))
        d = (((((e + f) + c) + b) and 0xffffffffL) xor d)
        c = ((((e + f) + d) + c) + b)
        b = (((c + 9137L) xor (f + b)) xor (((d shr 13L.toInt()) or (d shl 19L.toInt())) - 8556L))
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((c + d) and 0xffffffffL) or (((c - b) + 0xabb69b66L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbc2e4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = y
        c = ((x shr 32L.toInt()) xor b)
        d = ((y shr 32L.toInt()) xor a)
        b = ((d + 0x650e09ccL) xor b)
        e = (c xor table[((b and 255L) + 152L).toInt()])
        c = (d xor (b + c))
        d = ((c - e) and 0xffffffffL)
        b = ((b - (c * 0x4267b287L)) and 0xffffffffL)
        c = (e xor (((d shr 21L.toInt()) or (d shl 11L.toInt())) + ((b shr 24L.toInt()) or (b shl 8L.toInt()))))
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (((b + d) and 0xffffffffL) or ((d - (c * 0x59a9a185L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbd25c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (x - a)
        c = (y and 0xffffffffL)
        d = (y shr 32L.toInt())
        leaf0 = ((b and 0xffffffffL) or ((a xor ((c shr 15L.toInt()) or (c shl 17L.toInt()))) shl 32L.toInt()))
        leaf1 = ((((y - b) - d) and 0xffffffffL) or (((b + d) + 0xbe90e19L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbd28c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = ((b + c) + ((b - y) * 0x49a3f9d0L))
        e = ((((b * 2L) - c) - (y * 2L)) and 0xffffffffL)
        b = ((((y - ((((e shr 2L.toInt()) or (e shl 30L.toInt())) - 3756L) xor (d + 3901L))) - b) and 0xffffffffL) or ((((y * 0x49a3f9ceL) - (b * 0x49a3f9cfL)) - (c * 2L)) shl 32L.toInt()))
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = b
    }

internal fun SpotifyPlayPlayLeaves.mix_cbd8a8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((y + 278999759L) xor (x shr 32L.toInt()))
        c = ((y shr 32L.toInt()) xor a)
        d = (c xor b)
        b = (b xor ((c + y) + d))
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((((b + c) + y) + d) and 0xffffffffL) or ((d - b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbda60(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (((x shr 32L.toInt()) xor ((b shr 18L.toInt()) or (b shl 14L.toInt()))) and 0xffffffffL)
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        b = ((((c - 19832L) and 0xffffffffL) xor b) xor ((((d shr 26L.toInt()) or (d shl 6L.toInt())) - 7061L) and 0xffffffffL))
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (b or ((((b - 6100L) xor (((c shr 16L.toInt()) or (c shl 16L.toInt())) - 7259L)) + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbe3c4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (y shr 32L.toInt())
        c = (x shr 32L.toInt())
        d = ((((y + c) - b) - 345624790L) and 0xffffffffL)
        e = ((d shr 4L.toInt()) or (d shl 28L.toInt()))
        b = ((((-e) - c) * 0x81a2145L) xor ((b - c) + 619238523L))
        d = ((((((e * 2L) + (c * 2L)) + (d xor b)) - b) + 0x6ef401eL) and 0xffffffffL)
        f = ((((d shr 7L.toInt()) or (d shl 25L.toInt())) + 376L) xor (((b - e) - c) + 0x67b28816L))
        b = (((f - d) + b) + 0x6ef401eL)
        leaf0 = (a or ((d - table[(((b + 38L) and 255L) + 105L).toInt()]) shl 32L.toInt()))
        leaf1 = (((b - 815507162L) and 0xffffffffL) or ((((((f - d) + e) + c) + 0x6ea16cb1L) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbe484(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = (((y + (x shr 32L.toInt())) - 760974399L) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((y - c) and 0xffffffffL) or ((c - ((b shr 18L.toInt()) or (b shl 14L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbe4bc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (((b shr 19L.toInt()) or (b shl 13L.toInt())) + y)
        d = (-(x shr 32L.toInt()))
        leaf0 = (a or ((c + b) shl 32L.toInt()))
        leaf1 = (((c xor (d - y)) and 0xffffffffL) or ((((((-y) - b) - c) + d) - 0x6f19bc44L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbefa8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = (b - y)
        d = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (((d + b) and 0xffffffffL) or ((table[((c and 255L) + 393L).toInt()] xor d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbf548(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (((a + 0x67e17e93L) xor x) and 0xffffffffL)
        a = ((a xor y) and 0xffffffffL)
        c = (y shr 32L.toInt())
        d = (((((y shr 39L.toInt()) and 33554431L) or (c shl 25L.toInt())) + y) and 0xffffffffL)
        c = ((((((a shr 26L.toInt()) or (a shl 6L.toInt())) + c) + ((b shr 19L.toInt()) or (b shl 13L.toInt()))) + b) - a)
        leaf0 = (((b - a) and 0xffffffffL) or ((a - ((d shr 23L.toInt()) or (d shl 9L.toInt()))) shl 32L.toInt()))
        leaf1 = (((c + d) and 0xffffffffL) or (c shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbf6e8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = ((a + x) and 0xffffffffL)
        a = (a + y)
        c = (y shr 32L.toInt())
        leaf0 = (b or (a shl 32L.toInt()))
        leaf1 = (((table[((c and 255L) + 206L).toInt()] + y) and 0xffffffffL) or ((c - ((((b shr 18L.toInt()) or (b shl 14L.toInt())) - 15432L) xor (a + 6137L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbf8d0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = ((y shr 32L.toInt()) + b)
        d = (table[((c and 255L) + 299L).toInt()] xor y)
        b = (table[((d and 255L) + 167L).toInt()] xor b)
        c = (c - b)
        d = (d xor table[((c and 255L) + 233L).toInt()])
        e = ((c + d) and 0xffffffffL)
        b = (d xor b)
        d = (((e shr 2L.toInt()) or (e shl 30L.toInt())) + b)
        b = ((c - b) xor table[((d and 255L) + 47L).toInt()])
        c = (e xor b)
        d = ((d - (c * 0x7c7cdf0aL)) and 0xffffffffL)
        b = (((((d shr 17L.toInt()) or (d shl 15L.toInt())) + 13720L) xor (c + 31021L)) + b)
        leaf0 = (a or (((b + d) + c) shl 32L.toInt()))
        leaf1 = (((b + c) and 0xffffffffL) or ((b xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbf990(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (x shr 32L.toInt())
        d = table[((b and 255L) + 388L).toInt()]
        b = ((b + c) + d)
        e = table[((b and 255L) + 413L).toInt()]
        d = (d + y)
        f = (d and 0xffffffffL)
        c = ((((f shr 26L.toInt()) or (f shl 6L.toInt())) - 23059L) xor ((c - y) xor (b + 26083L)))
        leaf0 = (a or (((((-b) - f) - e) + 0x6394a376L) shl 32L.toInt()))
        leaf1 = ((((d + e) xor ((b + c) + 0x9c6b5c8aL)) and 0xffffffffL) or (((c - f) - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cbff20(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = ((-y) - b)
        d = ((x shr 32L.toInt()) xor y)
        b = (b xor d)
        d = ((d - c) xor table[((b and 255L) + 107L).toInt()])
        e = ((c + d) and 0xffffffffL)
        b = (b - table[((c and 255L) + 494L).toInt()])
        c = (d - table[(b and 255L).toInt()])
        d = (c and 0xffffffffL)
        d = (e xor ((d shr 13L.toInt()) or (d shl 19L.toInt())))
        b = (b xor ((e shr 9L.toInt()) or (e shl 23L.toInt())))
        c = ((c - table[((b and 255L) + 360L).toInt()]) and 0xffffffffL)
        e = (d xor c)
        f = ((((((-c) - b) - d) * 767472894L) and 0xffffffffL) xor c)
        g = table[((f and 255L) + 434L).toInt()]
        b = ((((c + b) + d) and 0xffffffffL) xor table[((e and 255L) + 477L).toInt()])
        c = (f xor (((b shr 28L.toInt()) or (b shl 4L.toInt())) and 0xffffffffL))
        leaf0 = (a or ((e - g) shl 32L.toInt()))
        leaf1 = (c or ((((b - c) - e) + g) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0474(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = ((y xor (x shr 32L.toInt())) and 0xffffffffL)
        c = ((y shr 32L.toInt()) xor a)
        d = (y - c)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or ((((d + 14629L) xor (((b shr 7L.toInt()) or (b shl 25L.toInt())) + 1742L)) xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc06c0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = (x - table[((a and 255L) + 477L).toInt()])
        c = (y shr 32L.toInt())
        leaf0 = ((b and 0xffffffffL) or (((c + y) xor a) shl 32L.toInt()))
        leaf1 = (((table[((c and 255L) + 97L).toInt()] + y) and 0xffffffffL) or ((b + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc09b4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = (table[((b and 255L) + 96L).toInt()] xor y)
        d = (table[((y and 255L) + 439L).toInt()] xor (x shr 32L.toInt()))
        e = (table[((c and 255L) + 457L).toInt()] xor d)
        c = (((e + c) - b) + d)
        b = ((e * (-0x6e37e9edL)) xor (b - d))
        d = (((c - e) xor (b * (-0xb46cd0dL))) and 0xffffffffL)
        e = (-((d shr 9L.toInt()) or (d shl 23L.toInt())))
        b = (b xor c)
        d = (d + b)
        f = (((c + e) - table[((d and 255L) + 406L).toInt()]) and 0xffffffffL)
        b = ((c + b) + e)
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = ((((b + 0x42c8cf2dL) xor d) and 0xffffffffL) or (((b - ((f shr 30L.toInt()) or (f shl 2L.toInt()))) + 0x42c8cf2dL) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0cc0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (y and 0xffffffffL)
        d = (((((y shr 51L.toInt()) and 8191L) or (b shl 13L.toInt())) + ((c shr 14L.toInt()) or (c shl 18L.toInt()))) xor a)
        a = (x - (((x shr 36L.toInt()) and 0xfffffffL) or (a shl 28L.toInt())))
        e = (a xor b)
        f = ((((d - a) * 0x618aea69L) + e) and 0xffffffffL)
        b = (((a + b) xor c) + e)
        c = ((b + 36106196L) and 0xffffffffL)
        e = ((((f shr 12L.toInt()) or (f shl 20L.toInt())) + ((c shr 31L.toInt()) or (c shl 1L.toInt()))) and 0xffffffffL)
        a = ((a - d) and 0xffffffffL)
        g = (e xor a)
        b = (b + d)
        d = (b xor e)
        c = ((f + c) and 0xffffffffL)
        a = (f xor ((((b xor a) + 29250L) xor (d + 2395L)) and 0xffffffffL))
        leaf0 = (g or ((d - ((c shr 6L.toInt()) or (c shl 26L.toInt()))) shl 32L.toInt()))
        leaf1 = ((a xor c) or ((a xor ((g shr 31L.toInt()) or (g shl 1L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.mix_cc0d5c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (y - (((y shr 39L.toInt()) and 33554431L) or (b shl 25L.toInt())))
        d = (c and 0xffffffffL)
        e = (a xor x)
        a = (a + y)
        b = (b xor (e + 0x7b5d37d5L))
        f = ((a - ((d shr 31L.toInt()) or (d shl 1L.toInt()))) and 0xffffffffL)
        c = (table[(((c - b) and 255L) + 299L).toInt()] xor f)
        a = ((e - table[((a and 255L) + 443L).toInt()]) and 0xffffffffL)
        e = ((((a shr 22L.toInt()) or (a shl 10L.toInt())) + ((f shr 12L.toInt()) or (f shl 20L.toInt()))) xor b)
        leaf0 = (((a - (f * 0x50278c2aL)) and 0xffffffffL) or (c shl 32L.toInt()))
        leaf1 = (((((((f * 0x50278c2aL) - e) - a) + d) - b) and 0xffffffffL) or ((((e - (f * 0x50278c2aL)) + a) + c) shl 32L.toInt()))
    }
