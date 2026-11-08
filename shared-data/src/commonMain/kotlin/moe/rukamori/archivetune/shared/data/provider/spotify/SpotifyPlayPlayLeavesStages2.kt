package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.math_cbd144(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = ((((y - 18032L) xor x) xor ((((x shr 61L.toInt()) and 7L) or (a shl 3L.toInt())) - 9443L)) and 0xffffffffL)
        c = (b xor (((y - a) * 0x565701f6L) + 0x7622bb7aL))
        d = (y shr 32L.toInt())
        e = (y - d)
        f = (e and 0xffffffffL)
        f = ((f shr 5L.toInt()) or (f shl 27L.toInt()))
        b = (d - ((b shr 22L.toInt()) or (b shl 10L.toInt())))
        d = (b and 0xffffffffL)
        d = ((d shr 21L.toInt()) or (d shl 11L.toInt()))
        g = ((((c - a) + y) + f) + d)
        e = ((e - table[((b and 255L) + 474L).toInt()]) and 0xffffffffL)
        b = (((b - c) + 0xd3f61154L) and 0xffffffffL)
        leaf0 = (((g - 916484441L) and 0xffffffffL) or ((e xor ((((a - y) - f) - d) + 916484441L)) shl 32L.toInt()))
        leaf1 = ((e xor b) or ((table[(((g - 89L) and 255L) + 190L).toInt()] xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd1fc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = ((((x + y) - (a * 2L)) - 808657780L) and 0xffffffffL)
        c = (y shr 32L.toInt())
        d = ((c + y) and 0xffffffffL)
        e = (((a - y) + 0xdaaa3481L) and 0xffffffffL)
        a = (((x - a) + 0xaa77110dL) and 0xffffffffL)
        a = ((((e shr 9L.toInt()) or (e shl 23L.toInt())) + ((a shr 22L.toInt()) or (a shl 10L.toInt()))) xor c)
        leaf0 = (b or ((((d shr 11L.toInt()) or (d shl 21L.toInt())) + e) shl 32L.toInt()))
        leaf1 = (((a + d) and 0xffffffffL) or ((a + ((b shr 23L.toInt()) or (b shl 9L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd2dc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = (b xor ((b - y) - ((y shr 32L.toInt()) xor a)))
        d = ((c + b) - y)
        b = ((((b * 2L) - y) - 428390508L) and 0xffffffffL)
        c = ((((b shr 26L.toInt()) or (b shl 6L.toInt())) + c) and 0xffffffffL)
        b = ((d and 0xffffffffL) xor b)
        e = (-((((c shr 30L.toInt()) or (c shl 2L.toInt())) + 8830L) xor (b - 28343L)))
        f = (((d + e) and 0xffffffffL) xor b)
        b = (c xor b)
        c = (((((((f shr 19L.toInt()) or (f shl 13L.toInt())) + d) + e) - b) + 0x95d43006L) xor b)
        b = ((((((-d) - e) + f) + b) + 0x6a2bcffaL) and 0xffffffffL)
        d = ((b shr 9L.toInt()) or (b shl 23L.toInt()))
        leaf0 = (a or ((c + d) shl 32L.toInt()))
        leaf1 = ((((b + c) + f) and 0xffffffffL) or ((((c * 2L) + f) + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd384(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (y shr 32L.toInt())
        b = (table[((a and 255L) + 200L).toInt()] xor (y and 0xffffffffL))
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (((y * 67953449L) and 0xffffffffL) xor c)
        e = ((b + d) and 0xffffffffL)
        c = (((c + y) xor x) and 0xffffffffL)
        f = (e xor c)
        a = ((a - ((c shr 31L.toInt()) or (c shl 1L.toInt()))) - ((d shr 17L.toInt()) or (d shl 15L.toInt())))
        leaf0 = (f or (e shl 32L.toInt()))
        leaf1 = ((b xor (a and 0xffffffffL)) or ((a - f) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd3dc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) - y)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = (((b + c) xor y) and 0xffffffffL)
        b = (b and 0xffffffffL)
        e = (d xor b)
        b = (c xor (((b shr 21L.toInt()) or (b shl 11L.toInt())) and 0xffffffffL))
        c = (d xor b)
        d = ((e - ((c shr 4L.toInt()) or (c shl 28L.toInt()))) and 0xffffffffL)
        b = ((b - e) and 0xffffffffL)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((((((b shr 8L.toInt()) or (b shl 24L.toInt())) + c) + ((d shr 20L.toInt()) or (d shl 12L.toInt()))) and 0xffffffffL) or ((((d shr 16L.toInt()) or (d shl 16L.toInt())) + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd434(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (y shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = (x shr 32L.toInt())
        b = (((((y shr 61L.toInt()) and 7L) or (a shl 3L.toInt())) + ((b shr 22L.toInt()) or (b shl 10L.toInt()))) xor c)
        c = (c + x)
        d = ((b xor c) and 0xffffffffL)
        e = (y - (((y shr 35L.toInt()) and 536870911L) or (a shl 29L.toInt())))
        leaf0 = (d or ((b xor e) shl 32L.toInt()))
        leaf1 = ((((e + c) + a) and 0xffffffffL) or (((c + a) - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd478(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (table[((y and 255L) + 497L).toInt()] xor (x shr 32L.toInt()))
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = (c xor y)
        c = (((b * (-322597551L)) and 0xffffffffL) xor c)
        b = ((b + d) + c)
        d = ((((b - 2785L) xor (((c shr 23L.toInt()) or (c shl 9L.toInt())) - 8138L)) + d) and 0xffffffffL)
        c = ((b and 0xffffffffL) xor c)
        e = (d xor c)
        b = (((b - ((c shr 6L.toInt()) or (c shl 26L.toInt()))) - ((d shr 22L.toInt()) or (d shl 10L.toInt()))) and 0xffffffffL)
        c = (((b shr 14L.toInt()) or (b shl 18L.toInt())) + c)
        d = ((((b + c) + e) - 0x5c10ced3L) and 0xffffffffL)
        c = ((e - table[((c and 255L) + 70L).toInt()]) and 0xffffffffL)
        b = (((((d shr 19L.toInt()) or (d shl 13L.toInt())) + ((c shr 21L.toInt()) or (c shl 11L.toInt()))) + b) + e)
        c = (c xor (((d shr 20L.toInt()) or (d shl 12L.toInt())) and 0xffffffffL))
        d = (((b + 0xa3ef312dL) and 0xffffffffL) xor d)
        leaf0 = (a or (((b + c) + 0xa3ef312dL) shl 32L.toInt()))
        leaf1 = ((d xor c) or ((((b + d) + c) + 0xa3ef312dL) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd550(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x shr 32L.toInt())
        b = ((y * 0x6944f4e5L) xor a)
        c = (y shr 32L.toInt())
        d = ((b + y) - c)
        a = table[((a and 255L) + 435L).toInt()]
        b = ((((y - c) - x) + a) xor b)
        e = (d - b)
        f = (x * (-2L))
        g = (a * 2L)
        b = (b xor ((y + f) + g))
        h = (e + b)
        d = (d and 0xffffffffL)
        a = ((((c - x) + a) xor ((d shr 9L.toInt()) or (d shl 23L.toInt()))) and 0xffffffffL)
        c = (((((a shr 4L.toInt()) or (a shl 28L.toInt())) + y) + f) + g)
        a = (a xor e)
        leaf0 = ((h and 0xffffffffL) or (((c + a) xor b) shl 32L.toInt()))
        leaf1 = (((c - a) and 0xffffffffL) or ((a xor (h + 0xbeec91fbL)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd5e8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (y and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = ((x - ((a shr 11L.toInt()) or (a shl 21L.toInt()))) - (((x shr 59L.toInt()) and 31L) or (b shl 5L.toInt())))
        d = (y shr 32L.toInt())
        leaf0 = ((c and 0xffffffffL) or (((b - ((a shr 25L.toInt()) or (a shl 7L.toInt()))) - d) shl 32L.toInt()))
        leaf1 = (((y - (d * 0x6147ab45L)) and 0xffffffffL) or ((c + d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd624(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (table[((a and 255L) + 376L).toInt()] xor x)
        c = (y and 0xffffffffL)
        a = (a xor (((c shr 8L.toInt()) or (c shl 24L.toInt())) and 0xffffffffL))
        c = (y shr 32L.toInt())
        d = (c + y)
        e = (d and 0xffffffffL)
        f = (b xor (((a shr 10L.toInt()) or (a shl 22L.toInt())) + ((e shr 27L.toInt()) or (e shl 5L.toInt()))))
        a = (a xor e)
        e = ((f + a) and 0xffffffffL)
        b = (b xor c)
        c = (d - b)
        a = (table[((c and 255L) + 75L).toInt()] + a)
        d = (e xor (a and 0xffffffffL))
        b = ((f * 0x5aea52e5L) xor b)
        c = (b xor c)
        e = ((e shr 26L.toInt()) or (e shl 6L.toInt()))
        leaf0 = (d or ((a - table[((c and 255L) + 25L).toInt()]) shl 32L.toInt()))
        leaf1 = (((((c - b) + e) - d) and 0xffffffffL) or (((b - e) - table[((d and 255L) + 309L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd6d4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (x xor (((x shr 61L.toInt()) and 7L) or (a shl 3L.toInt())))
        c = (y shr 32L.toInt())
        d = ((b + c) xor (y - c))
        e = (((table[((((((y * 11L) - (a * 12L)) + (b * 2L)) + (c * 2L)) and 255L) + 117L).toInt()] + d) and 0xffffffffL) or ((((((a * 2L) - (y * 4L)) + b) + (c * 3L)) + d) shl 32L.toInt()))
        leaf0 = ((((((a + ((a - y) * 0x569dd8f1L)) + b) - c) - d) and 0xffffffffL) or ((d xor ((a - (y * 2L)) + c)) shl 32L.toInt()))
        leaf1 = e
    }

internal fun SpotifyPlayPlayLeaves.math_cbd744(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = (a xor ((b shr 11L.toInt()) or (b shl 21L.toInt())))
        d = ((((c + a) + x) + 0xa22aa618L) and 0xffffffffL)
        a = ((a + x) and 0xffffffffL)
        e = (y shr 32L.toInt())
        b = ((((a shr 3L.toInt()) or (a shl 29L.toInt())) + (((y shr 39L.toInt()) and 33554431L) or (e shl 25L.toInt()))) xor b)
        a = (a xor e)
        leaf0 = (d or (((b + 0x418cabc5L) xor c) shl 32L.toInt()))
        leaf1 = (((b + a) and 0xffffffffL) or ((a - ((d shr 4L.toInt()) or (d shl 28L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd7a0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (((x shr 32L.toInt()) - y) and 0xffffffffL)
        b = (x xor (y shr 32L.toInt()))
        c = (table[((b and 255L) + 370L).toInt()] xor (y and 0xffffffffL))
        b = (((((a shr 28L.toInt()) or (a shl 4L.toInt())) + b) + ((c shr 3L.toInt()) or (c shl 29L.toInt()))) and 0xffffffffL)
        d = ((b shr 28L.toInt()) or (b shl 4L.toInt()))
        e = table[((c and 255L) + 160L).toInt()]
        f = (((d + c) xor (e + a)) and 0xffffffffL)
        a = ((b - e) - a)
        b = (a - ((f shr 17L.toInt()) or (f shl 15L.toInt())))
        e = (b and 0xffffffffL)
        a = (((f + d) + c) + table[((a and 255L) + 241L).toInt()])
        c = ((((e shr 22L.toInt()) or (e shl 10L.toInt())) + 9284L) xor (a + 13767L))
        a = (a and 0xffffffffL)
        b = ((b + ((a shr 12L.toInt()) or (a shl 20L.toInt()))) and 0xffffffffL)
        leaf0 = ((x and 0xffffffffL) or ((f - c) shl 32L.toInt()))
        leaf1 = (((((c + a) - f) xor ((b shr 25L.toInt()) or (b shl 7L.toInt()))) and 0xffffffffL) or ((b + ((c - f) * 0x70266231L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd860(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (((x shr 32L.toInt()) + y) and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((y - ((c shr 14L.toInt()) or (c shl 18L.toInt()))) - ((b shr 21L.toInt()) or (b shl 11L.toInt())))
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or ((((((b shr 6L.toInt()) or (b shl 26L.toInt())) - 31052L) xor c) xor (d - 2985L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd8e8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (y and 0xffffffffL)
        c = (a xor b)
        d = (y shr 32L.toInt())
        b = ((b shr 8L.toInt()) or (b shl 24L.toInt()))
        a = (((x shr 60L.toInt()) and 15L) or (a shl 4L.toInt()))
        e = (-(((y shr 51L.toInt()) and 8191L) or (d shl 13L.toInt())))
        f = ((y + e) + c)
        g = ((((f + b) + x) + a) and 0xffffffffL)
        f = (((((-((((g shr 2L.toInt()) or (g shl 30L.toInt())) + 6474L) xor (f - 28645L))) - y) + g) - e) + d)
        g = (f xor ((g + (((y - g) + e) * 439153684L)) - (d * 439153683L)))
        c = (((((g * 0x7b58ce70L) + c) + (((((c + d) + b) + x) + a) * 474994629L)) + (y * 758444090L)) + (e * 758444090L))
        a = (((b + x) + a) and 0xffffffffL)
        b = (((c - (g * 0x7b58ce70L)) + 16655L) xor ((((a shr 2L.toInt()) or (a shl 30L.toInt())) - 23045L) xor f))
        d = (table[((b and 255L) + 101L).toInt()] xor g)
        e = (c - d)
        a = (table[(((c - (g * 112L)) and 255L) + 145L).toInt()] xor a)
        f = ((e xor (a - c)) and 0xffffffffL)
        c = (((c + b) + a) and 0xffffffffL)
        c = (d xor ((c shr 4L.toInt()) or (c shl 28L.toInt())))
        a = ((e + b) + (a * 2L))
        leaf0 = (f or ((e - table[((c and 255L) + 129L).toInt()]) shl 32L.toInt()))
        leaf1 = (((a + c) and 0xffffffffL) or ((a xor (f * (-0x7692b55L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbd9fc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = (((a + 531353778L) xor x) and 0xffffffffL)
        a = (table[((y and 255L) + 60L).toInt()] xor a)
        c = ((y shr 32L.toInt()) and 0xffffffffL)
        leaf0 = (b or (a shl 32L.toInt()))
        leaf1 = (((y and 0xffffffffL) xor c) or ((((a - 5120L) xor (((b shr 1L.toInt()) or (b shl 31L.toInt())) + 16906L)) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdab8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        b = ((b shr 20L.toInt()) or (b shl 12L.toInt()))
        c = (x shr 32L.toInt())
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        e = ((d shr 11L.toInt()) or (d shl 21L.toInt()))
        f = ((((((d + y) * 0x6d6e34f4L) + b) + c) + e) and 0xffffffffL)
        d = ((((y * 0x5f027074L) - (f * 0x6c01f861L)) + (d * 0x5f027075L)) xor f)
        b = (y + (((b + c) + e) * 0x6c01f861L))
        c = ((b and 0xffffffffL) xor f)
        e = (d - c)
        f = ((e - 0xb9a9cbfL) xor ((b - d) - c))
        b = (table[((((b - d) - c) and 255L) + 2L).toInt()] xor c)
        c = ((((e + (f * 2L)) + b) + 0x8fc38305L) and 0xffffffffL)
        d = (c xor b)
        b = ((((e + f) + b) - ((c shr 22L.toInt()) or (c shl 10L.toInt()))) - ((d shr 30L.toInt()) or (d shl 2L.toInt())))
        d = (d - ((b + 0x8fc36e8fL) xor (((c shr 23L.toInt()) or (c shl 9L.toInt())) - 24050L)))
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = ((c xor table[(((b + 5L) and 255L) + 505L).toInt()]) or ((d xor (b + 0x8fc38305L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdbac(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (table[((y and 255L) + 215L).toInt()] xor a)
        a = ((x xor (((x shr 58L.toInt()) and 63L) or (a shl 6L.toInt()))) + b)
        c = (y shr 32L.toInt())
        d = (y - c)
        b = (b xor table[((d and 255L) + 228L).toInt()])
        e = (a - b)
        c = ((c - a) and 0xffffffffL)
        d = (d - ((((c shr 4L.toInt()) or (c shl 28L.toInt())) + 24666L) xor (a - 29040L)))
        a = (c xor a)
        leaf0 = ((e and 0xffffffffL) or ((d xor b) shl 32L.toInt()))
        leaf1 = (((d - a) and 0xffffffffL) or (((e + a) + 0x8b0d5e8eL) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdc38(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (y shr 32L.toInt())
        b = (table[((a and 255L) + 286L).toInt()] xor (y and 0xffffffffL))
        c = (x shr 32L.toInt())
        d = (c xor x)
        e = ((((b shr 15L.toInt()) or (b shl 17L.toInt())) + 8105L) xor ((a - d) - 32545L))
        f = ((e + d) and 0xffffffffL)
        b = (b xor (((d - a) * 464892719L) and 0xffffffffL))
        leaf0 = (f or ((((table[((y and 255L) + 258L).toInt()] xor c) - b) - a) shl 32L.toInt()))
        leaf1 = ((((((-e) - a) * 0x9f87d00L) and 0xffffffffL) xor b) or (((((f shr 8L.toInt()) or (f shl 24L.toInt())) + e) + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdcd4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) + y)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = (((y xor ((c shr 19L.toInt()) or (c shl 13L.toInt()))) and 0xffffffffL) or (((b + c) + 0x945313e1L) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdd08(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (y and 0xffffffffL)
        b = (x xor (y shr 32L.toInt()))
        a = ((((((a shr 29L.toInt()) or (a shl 3L.toInt())) - 20029L) xor (x shr 32L.toInt())) xor (b + 9582L)) and 0xffffffffL)
        c = table[((b and 255L) + 61L).toInt()]
        d = ((a - y) + c)
        a = ((((((a shr 15L.toInt()) or (a shl 17L.toInt())) + 31122L) xor ((y - c) + 30535L)) xor b) and 0xffffffffL)
        b = ((a + y) - c)
        c = ((d - table[((b and 255L) + 101L).toInt()]) and 0xffffffffL)
        a = (a xor table[((d and 255L) + 142L).toInt()])
        leaf0 = ((x and 0xffffffffL) or (c shl 32L.toInt()))
        leaf1 = (((b - ((a shr 31L.toInt()) or (a shl 1L.toInt()))) and 0xffffffffL) or ((a - ((c shr 27L.toInt()) or (c shl 5L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbddb0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (x shr 32L.toInt())
        b = (((y shr 32L.toInt()) - ((b shr 30L.toInt()) or (b shl 2L.toInt()))) - (((x shr 36L.toInt()) and 0xfffffffL) or (c shl 28L.toInt())))
        d = ((b + y) and 0xffffffffL)
        b = (b and 0xffffffffL)
        c = ((((d shr 16L.toInt()) or (d shl 16L.toInt())) + ((b shr 8L.toInt()) or (b shl 24L.toInt()))) xor c)
        d = (((y + (b * 2L)) - c) and 0xffffffffL)
        leaf0 = (a or ((c - ((d shr 8L.toInt()) or (d shl 24L.toInt()))) shl 32L.toInt()))
        leaf1 = (d or (((b - c) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbde04(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = ((y shr 32L.toInt()) xor a)
        d = ((c + b) xor c)
        c = ((b - (y * 2L)) - c)
        e = (((((y - b) - d) * 297119187L) xor c) and 0xffffffffL)
        c = (((c + d) + 0xb19a49e3L) and 0xffffffffL)
        b = (((b - y) + d) xor c)
        d = (e - table[((b and 255L) + 204L).toInt()])
        c = ((((b + 10152L) and 0xffffffffL) xor c) xor ((((e shr 11L.toInt()) or (e shl 21L.toInt())) - 30101L) and 0xffffffffL))
        e = ((d and 0xffffffffL) xor c)
        d = ((d - table[(((c + b) and 255L) + 430L).toInt()]) and 0xffffffffL)
        f = (e xor d)
        b = (((table[((e and 255L) + 194L).toInt()] + c) + b) and 0xffffffffL)
        c = ((((f - 7266L) and 0xffffffffL) xor d) xor ((((b shr 18L.toInt()) or (b shl 14L.toInt())) + 16319L) and 0xffffffffL))
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (((((c + 31878L) xor (((f shr 8L.toInt()) or (f shl 24L.toInt())) + 13926L)) + b) and 0xffffffffL) or ((f - ((c shr 12L.toInt()) or (c shl 20L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdf08(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x shr 32L.toInt())
        b = (x xor a)
        a = (a xor y)
        c = (y shr 32L.toInt())
        d = (((((y shr 51L.toInt()) and 8191L) or (c shl 13L.toInt())) + 5666L) xor (b - 19594L))
        e = (((b - (a * 2L)) - d) - y)
        c = (table[((b and 255L) + 497L).toInt()] xor c)
        leaf0 = ((e and 0xffffffffL) or ((a - (c * 2L)) shl 32L.toInt()))
        leaf1 = (((((d + y) + (c * 2L)) + 0x8a0b58c8L) and 0xffffffffL) or ((((c - b) + a) xor table[((e and 255L) + 331L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdf98(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x and 0xffffffffL)
        b = (y xor (x shr 32L.toInt()))
        c = ((y shr 32L.toInt()) xor a)
        leaf0 = (a or (b shl 32L.toInt()))
        leaf1 = ((((c * (-0x4044e6edL)) xor y) and 0xffffffffL) or (((b + 281575501L) xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbdfd4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (table[((y and 255L) + 90L).toInt()] xor (x shr 32L.toInt()))
        b = (x xor (y shr 32L.toInt()))
        c = (b xor y)
        d = ((a + table[((c and 255L) + 376L).toInt()]) and 0xffffffffL)
        a = (((a * 0x631c838L) xor b) and 0xffffffffL)
        b = (table[((a and 255L) + 317L).toInt()] xor (c and 0xffffffffL))
        c = (d xor ((b shr 12L.toInt()) or (b shl 20L.toInt())))
        a = ((((((d shr 12L.toInt()) or (d shl 20L.toInt())) - 18111L) and 0xffffffffL) xor a) xor ((b + 13198L) and 0xffffffffL))
        b = ((((c - 2286L) and 0xffffffffL) xor b) xor ((((a shr 31L.toInt()) or (a shl 1L.toInt())) - 16371L) and 0xffffffffL))
        leaf0 = ((x and 0xffffffffL) or (c shl 32L.toInt()))
        leaf1 = (b or ((a - ((b - 23472L) xor (c + 30114L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe07c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = ((x xor (((x shr 42L.toInt()) and 4194303L) or (a shl 22L.toInt()))) and 0xffffffffL)
        a = ((a + y) and 0xffffffffL)
        c = (y shr 32L.toInt())
        d = (y xor (((y shr 34L.toInt()) and 0x3fffffffL) or (c shl 30L.toInt())))
        e = ((b - ((((a shr 29L.toInt()) or (a shl 3L.toInt())) - 20801L) xor (d - 23043L))) and 0xffffffffL)
        f = (-((a shr 23L.toInt()) or (a shl 9L.toInt())))
        b = (-((b shr 3L.toInt()) or (b shl 29L.toInt())))
        leaf0 = (e or ((d xor a) shl 32L.toInt()))
        leaf1 = (((d - table[((((c + f) + b) and 255L) + 341L).toInt()]) and 0xffffffffL) or ((((table[((e and 255L) + 335L).toInt()] + c) + f) + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe0fc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (y shr 32L.toInt())
        b = (x shr 32L.toInt())
        c = ((a + y) xor b)
        d = ((c + b) + x)
        e = (d and 0xffffffffL)
        a = ((b + x) + a)
        b = ((y - a) and 0xffffffffL)
        leaf0 = (e or ((((a + 11736L) xor c) xor (((b shr 1L.toInt()) or (b shl 31L.toInt())) - 10746L)) shl 32L.toInt()))
        leaf1 = (((d + y) and 0xffffffffL) or ((a xor ((e shr 1L.toInt()) or (e shl 31L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe14c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = (x - a)
        c = (b and 0xffffffffL)
        a = (((y + a) + 0x6b99666bL) and 0xffffffffL)
        d = (c xor (((a shr 3L.toInt()) or (a shl 29L.toInt())) and 0xffffffffL))
        e = (y shr 32L.toInt())
        c = (((((c shr 8L.toInt()) or (c shl 24L.toInt())) + (((y shr 61L.toInt()) and 7L) or (e shl 3L.toInt()))) xor y) and 0xffffffffL)
        b = (b + e)
        e = (c xor (b and 0xffffffffL))
        a = ((a - table[((c and 255L) + 242L).toInt()]) and 0xffffffffL)
        c = ((d - ((e shr 16L.toInt()) or (e shl 16L.toInt()))) - ((a shr 4L.toInt()) or (a shl 28L.toInt())))
        f = ((e shr 28L.toInt()) or (e shl 4L.toInt()))
        b = ((b - ((a + 2462L) xor (((d shr 15L.toInt()) or (d shl 17L.toInt())) + 16455L))) and 0xffffffffL)
        d = ((b shr 13L.toInt()) or (b shl 19L.toInt()))
        e = (b xor e)
        b = (b xor c)
        leaf0 = (((((c - a) - d) - f) and 0xffffffffL) or ((((a + d) + f) xor (e * 560290945L)) shl 32L.toInt()))
        leaf1 = ((((b - 0xc41700aL) and 0xffffffffL) xor e) or (((((a - c) + b) + d) + f) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe218(x:Long, y:Long) {
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
        a = (y shr 32L.toInt())
        b = ((table[((a and 255L) + 327L).toInt()] + y) and 0xffffffffL)
        c = ((x shr 32L.toInt()) and 0xffffffffL)
        d = (y and 0xffffffffL)
        d = ((((a - 22855L) and 0xffffffffL) xor c) xor ((((d shr 31L.toInt()) or (d shl 1L.toInt())) + 22826L) and 0xffffffffL))
        c = (c xor (x and 0xffffffffL))
        a = (((d - 17015L) xor (((c shr 25L.toInt()) or (c shl 7L.toInt())) - 7816L)) + a)
        e = (a and 0xffffffffL)
        f = (((((b shr 14L.toInt()) or (b shl 18L.toInt())) + ((e shr 31L.toInt()) or (e shl 1L.toInt()))) and 0xffffffffL) xor d)
        b = (b xor e)
        e = (f xor table[((b and 255L) + 7L).toInt()])
        c = ((c - ((d shr 18L.toInt()) or (d shl 14L.toInt()))) and 0xffffffffL)
        d = (table[((f and 255L) + 64L).toInt()] xor c)
        a = ((a - ((c shr 2L.toInt()) or (c shl 30L.toInt()))) - ((f shr 24L.toInt()) or (f shl 8L.toInt())))
        c = table[((a and 255L) + 106L).toInt()]
        f = (((e + d) xor ((e + c) + b)) and 0xffffffffL)
        g = (c * (-751333365L))
        h = (b * (-751333365L))
        a = (a xor d)
        i = table[((a and 255L) + 40L).toInt()]
        j = (i * 751333366L)
        k = (f xor ((((g + h) + j) + e) and 0xffffffffL))
        a = (a xor ((((e * 2L) + d) + c) + b))
        b = (((a + c) + b) - i)
        c = (b and 0xffffffffL)
        a = (a - f)
        leaf0 = (k or (((((((((c shr 5L.toInt()) or (c shl 27L.toInt())) + 16528L) xor (a - 24275L)) + g) + j) + e) + h) shl 32L.toInt()))
        leaf1 = (((b - table[((a and 255L) + 247L).toInt()]) and 0xffffffffL) or ((a - k) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe350(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (y and 0xffffffffL)
        a = (-((a shr 7L.toInt()) or (a shl 25L.toInt())))
        b = (x shr 32L.toInt())
        c = (-(((x shr 34L.toInt()) and 0x3fffffffL) or (b shl 30L.toInt())))
        d = ((((x + a) + c) xor ((b - y) + 0xb66844c8L)) and 0xffffffffL)
        e = (y shr 32L.toInt())
        f = ((((d - b) - e) + 0x4997bb38L) and 0xffffffffL)
        a = ((((e + x) + a) + c) + 0x49b9d7e5L)
        c = (a xor (e + y))
        b = (((b + e) + 0xb66844c8L) and 0xffffffffL)
        a = ((((d shr 12L.toInt()) or (d shl 20L.toInt())) + ((b shr 20L.toInt()) or (b shl 12L.toInt()))) xor a)
        leaf0 = (f or ((b + c) shl 32L.toInt()))
        leaf1 = (((c - a) and 0xffffffffL) or ((a xor ((f shr 15L.toInt()) or (f shl 17L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe500(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((b - y) + c)
        c = (y xor ((c shr 25L.toInt()) or (c shl 7L.toInt())))
        e = (table[((d and 255L) + 136L).toInt()] xor c)
        f = table[((e and 255L) + 126L).toInt()]
        g = ((((((y + (d * 647451223L)) - b) + ((e - f) * 647451225L)) - c) + 0xaba74672L) and 0xffffffffL)
        b = ((((((y - (d * 2L)) - b) - c) + 0xaba74672L) xor ((((c - y) + d) + f) + b)) and 0xffffffffL)
        c = (b xor (((d + e) - f) and 0xffffffffL))
        d = (g xor (((c shr 5L.toInt()) or (c shl 27L.toInt())) and 0xffffffffL))
        b = (b xor (((g shr 11L.toInt()) or (g shl 21L.toInt())) and 0xffffffffL))
        e = ((d * (-0x4092c61eL)) xor b)
        b = ((b shr 25L.toInt()) or (b shl 7L.toInt()))
        f = ((d shr 12L.toInt()) or (d shl 20L.toInt()))
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = (((((d - c) + b) + f) and 0xffffffffL) or ((((c - b) - f) - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe5b4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (b + y)
        d = (c and 0xffffffffL)
        e = (((x shr 32L.toInt()) - (y * 679533843L)) and 0xffffffffL)
        b = (((((d shr 12L.toInt()) or (d shl 20L.toInt())) + ((e shr 6L.toInt()) or (e shl 26L.toInt()))) and 0xffffffffL) xor b)
        c = (c - table[((b and 255L) + 279L).toInt()])
        d = ((d + e) and 0xffffffffL)
        e = (table[((c and 255L) + 460L).toInt()] xor d)
        b = (b xor (((d shr 24L.toInt()) or (d shl 8L.toInt())) and 0xffffffffL))
        c = ((((((b shr 15L.toInt()) or (b shl 17L.toInt())) + 24336L) xor c) xor (e + 30818L)) and 0xffffffffL)
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = (c or ((((((e shr 29L.toInt()) or (e shl 3L.toInt())) + 27563L) xor (c + 12553L)) xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe664(x:Long, y:Long) {
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
        a = ((x shr 32L.toInt()) and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (a xor b)
        d = (y shr 32L.toInt())
        b = (d xor b)
        a = (a xor (x and 0xffffffffL))
        d = (d xor ((a shr 19L.toInt()) or (a shl 13L.toInt())))
        a = (a xor c)
        e = ((((((-c) - b) - d) * 0x443791c2L) and 0xffffffffL) xor a)
        f = ((c + (d * 2L)) + a)
        g = (e xor (f and 0xffffffffL))
        h = (a * 0x551fd469L)
        i = ((h + d) and 0xffffffffL)
        b = ((((e shr 11L.toInt()) or (e shl 21L.toInt())) + ((i shr 5L.toInt()) or (i shl 27L.toInt()))) xor ((b - a) - d))
        f = ((f - b) and 0xffffffffL)
        j = (e * 989857078L)
        k = (((b + j) + (i * 989857078L)) and 0xffffffffL)
        l = ((g - ((f shr 24L.toInt()) or (f shl 8L.toInt()))) - ((k shr 3L.toInt()) or (k shl 29L.toInt())))
        a = (((((l + j) - (a * 958604505L)) + (d * 989857080L)) + c) and 0xffffffffL)
        g = ((g shr 3L.toInt()) or (g shl 29L.toInt()))
        j = table[((((e + i) + g) and 255L) + 141L).toInt()]
        f = ((((k * 2L) + f) + j) and 0xffffffffL)
        b = ((((((((((a + e) + j) - d) + g) - c) - (h * 745733080L)) + b) + 429408374L) and 0xffffffffL) or ((((((((a shr 19L.toInt()) or (a shl 13L.toInt())) + ((f shr 1L.toInt()) or (f shl 31L.toInt()))) + e) + l) + i) + g) shl 32L.toInt()))
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = b
    }

internal fun SpotifyPlayPlayLeaves.math_cbe740(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        b = ((((b shr 31L.toInt()) or (b shl 1L.toInt())) + (x shr 32L.toInt())) + ((c shr 23L.toInt()) or (c shl 9L.toInt())))
        d = table[((b and 255L) + 5L).toInt()]
        leaf0 = (a or ((((b - (c * 2L)) - y) + d) shl 32L.toInt()))
        leaf1 = (((((y * 2L) - b) + (c * 2L)) and 0xffffffffL) or ((((y - b) - (d * 2L)) + (c * 3L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe988(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y - b)
        d = table[((c and 255L) + 470L).toInt()]
        e = (((x shr 32L.toInt()) xor y) and 0xffffffffL)
        b = (b xor e)
        leaf0 = (a or ((d + e) shl 32L.toInt()))
        leaf1 = (((c and 0xffffffffL) xor b) or (((b - d) - e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbe9e0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        b = ((((b shr 11L.toInt()) or (b shl 21L.toInt())) + (x shr 32L.toInt())) and 0xffffffffL)
        c = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        d = ((((b shr 31L.toInt()) or (b shl 1L.toInt())) + y) + ((c shr 13L.toInt()) or (c shl 19L.toInt())))
        e = (d xor b)
        b = (((-d) - b) + c)
        c = (-table[(((b - e) and 255L) + 310L).toInt()])
        d = ((b * 0x40073b50L) xor d)
        f = ((d + c) xor table[(((b - d) and 255L) + 461L).toInt()])
        g = (((e + c) + f) and 0xffffffffL)
        b = (((f - b) + d) + ((c + e) * 84804955L))
        leaf0 = (a or (g shl 32L.toInt()))
        leaf1 = (((b + 588658100L) and 0xffffffffL) or (((((g shr 17L.toInt()) or (g shl 15L.toInt())) + 14475L) xor ((b + 588669695L) xor (f - b))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cbeab4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (y and 0xffffffffL)
        c = ((x shr 32L.toInt()) xor ((b shr 1L.toInt()) or (b shl 31L.toInt())))
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        b = (d xor b)
        e = ((((b shr 17L.toInt()) or (b shl 15L.toInt())) + 3749L) xor ((d - c) + 22407L))
        leaf0 = (a or ((c - e) shl 32L.toInt()))
        leaf1 = ((((b + e) - d) and 0xffffffffL) or (((d - (c * 2L)) + e) shl 32L.toInt()))
    }
