package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayLeaves.math_cc0708(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((x shr 32L.toInt()) and 0xffffffffL)
        c = ((b + 0x6b7da39dL) xor (y shr 32L.toInt()))
        d = (c + y)
        e = (d and 0xffffffffL)
        b = (b xor (((e shr 4L.toInt()) or (e shl 28L.toInt())) and 0xffffffffL))
        c = (((b shr 19L.toInt()) or (b shl 13L.toInt())) + c)
        e = (c and 0xffffffffL)
        d = (((d - ((b shr 13L.toInt()) or (b shl 19L.toInt()))) - ((e shr 9L.toInt()) or (e shl 23L.toInt()))) and 0xffffffffL)
        e = ((d shr 6L.toInt()) or (d shl 26L.toInt()))
        c = (((c - b) + e) and 0xffffffffL)
        b = ((b - e) and 0xffffffffL)
        e = ((b shr 3L.toInt()) or (b shl 29L.toInt()))
        f = ((c shr 2L.toInt()) or (c shl 30L.toInt()))
        b = (((((d + b) + f) + e) - 795441803L) and 0xffffffffL)
        c = (c xor ((b shr 22L.toInt()) or (b shl 10L.toInt())))
        d = ((((d + (c * 871204998L)) + f) + e) and 0xffffffffL)
        e = (d xor b)
        c = (e + c)
        f = (c and 0xffffffffL)
        e = (((f shr 19L.toInt()) or (f shl 13L.toInt())) + ((e shr 31L.toInt()) or (e shl 1L.toInt())))
        b = (e xor b)
        f = table[((b and 255L) + 356L).toInt()]
        d = (((e xor d) - c) + f)
        leaf0 = (a or ((table[((d and 255L) + 375L).toInt()] + b) shl 32L.toInt()))
        leaf1 = ((d and 0xffffffffL) or (((c - f) xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc07e4(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = ((a + y) xor x)
        c = (table[(((a - y) and 255L) + 357L).toInt()] + b)
        d = (y shr 32L.toInt())
        e = ((y - (d * 0x418f2331L)) and 0xffffffffL)
        f = ((e shr 19L.toInt()) or (e shl 13L.toInt()))
        b = (((d - b) + 0x80f2cd35L) and 0xffffffffL)
        d = ((b shr 10L.toInt()) or (b shl 22L.toInt()))
        e = (((b - 650093350L) and 0xffffffffL) xor e)
        g = ((((((c - f) - a) + y) - d) - e) and 0xffffffffL)
        a = ((((((e shr 3L.toInt()) or (e shl 29L.toInt())) + f) + a) - y) + d)
        c = (-table[((c and 255L) + 165L).toInt()])
        leaf0 = (g or (a shl 32L.toInt()))
        leaf1 = ((((b + c) and 0xffffffffL) xor e) or (((((((g shr 20L.toInt()) or (g shl 12L.toInt())) - 3279L) xor (a + 8752L)) + b) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc089c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        a = (x shr 32L.toInt())
        b = ((x - y) - a)
        c = (y shr 32L.toInt())
        leaf0 = ((b and 0xffffffffL) or ((a - table[((y and 255L) + 449L).toInt()]) shl 32L.toInt()))
        leaf1 = ((((c * 909968610L) + y) and 0xffffffffL) or ((table[((b and 255L) + 41L).toInt()] + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc08f0(x:Long, y:Long) {
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
        c = table[((b and 255L) + 269L).toInt()]
        d = (a xor x)
        e = table[(((b - d) and 255L) + 32L).toInt()]
        f = (((a - (c * 2L)) + e) + y)
        g = (a * 0x58d21d39L)
        h = (c * (-0x58d21d39L))
        a = (table[(((a - y) and 255L) + 322L).toInt()] xor d)
        i = (((f + 884175767L) xor ((g + h) + a)) and 0xffffffffL)
        a = (((a * 2L) + b) - d)
        b = (((a + g) + h) xor ((e + y) - c))
        c = (((b + 0xa2fb62e8L) xor (f + 0x7241c4daL)) and 0xffffffffL)
        a = (((((a + f) + h) + g) + 0x7241c4daL) and 0xffffffffL)
        leaf0 = (i or (c shl 32L.toInt()))
        leaf1 = (((b - ((a shr 22L.toInt()) or (a shl 10L.toInt()))) and 0xffffffffL) or (((((i shr 21L.toInt()) or (i shl 11L.toInt())) + ((c shr 16L.toInt()) or (c shl 16L.toInt()))) + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc0a70(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (((((y - a) + b) * 0x727a586dL) xor ((a * 0x460dbc0aL) + x)) and 0xffffffffL)
        d = (((((a * 0x460dbc0bL) + x) - b) - y) xor b)
        leaf0 = (c or (((a - (b * 2L)) + 477507669L) shl 32L.toInt()))
        leaf1 = ((table[((d and 255L) + 473L).toInt()] xor (((y - b) + 477507669L) and 0xffffffffL)) or ((d xor c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc0ae8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = ((b shr 29L.toInt()) or (b shl 3L.toInt()))
        d = ((((table[((y and 255L) + 118L).toInt()] xor (x shr 32L.toInt())) + c) + y) and 0xffffffffL)
        e = ((d shr 26L.toInt()) or (d shl 6L.toInt()))
        b = (b - d)
        c = (((c + y) - table[((b and 255L) + 311L).toInt()]) and 0xffffffffL)
        f = ((((b + e) * 0x782c4a7cL) and 0xffffffffL) xor c)
        b = ((b + e) and 0xffffffffL)
        c = (((((b shr 21L.toInt()) or (b shl 11L.toInt())) + ((c shr 6L.toInt()) or (c shl 26L.toInt()))) and 0xffffffffL) xor d)
        b = (((b + c) + f) and 0xffffffffL)
        c = (((((b shr 23L.toInt()) or (b shl 9L.toInt())) + ((f shr 23L.toInt()) or (f shl 9L.toInt()))) and 0xffffffffL) xor c)
        d = ((f - (b * 0x793341e9L)) and 0xffffffffL)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (d or ((((d + 13637L) xor b) xor (((c shr 13L.toInt()) or (c shl 19L.toInt())) + 18125L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc0bac(x:Long, y:Long) {
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
        b = (a - y)
        c = (y shr 32L.toInt())
        d = (y - (((((y shr 40L.toInt()) and 0xffffffL) or (c shl 24L.toInt())) - 13704L) xor ((x - a) + 0x9f2e171cL)))
        e = (d and 0xffffffffL)
        e = (b - ((e shr 22L.toInt()) or (e shl 10L.toInt())))
        b = (b and 0xffffffffL)
        b = ((((((b shr 15L.toInt()) or (b shl 17L.toInt())) + x) - a) + 0x9f2dfe66L) and 0xffffffffL)
        f = (table[((e and 255L) + 299L).toInt()] xor b)
        g = ((b shr 16L.toInt()) or (b shl 16L.toInt()))
        e = (e and 0xffffffffL)
        h = ((e shr 11L.toInt()) or (e shl 21L.toInt()))
        a = ((((c - x) + a) + 0x60d2019aL) and 0xffffffffL)
        b = ((d - ((a shr 18L.toInt()) or (a shl 14L.toInt()))) - ((b shr 6L.toInt()) or (b shl 26L.toInt())))
        c = (((((a + f) + h) + g) xor b) and 0xffffffffL)
        d = (-((f shr 4L.toInt()) or (f shl 28L.toInt())))
        i = (c xor table[(((((a + h) + d) + g) and 255L) + 455L).toInt()])
        b = ((b + 0x5c41681eL) xor e)
        c = (b - table[((c and 255L) + 415L).toInt()])
        e = (c and 0xffffffffL)
        b = (f + b)
        e = (((i + ((e shr 15L.toInt()) or (e shl 17L.toInt()))) xor b) and 0xffffffffL)
        a = ((((((b + a) + h) + d) + g) + 635274038L) and 0xffffffffL)
        leaf0 = (e or (((c - ((a shr 12L.toInt()) or (a shl 20L.toInt()))) - ((i shr 9L.toInt()) or (i shl 23L.toInt()))) shl 32L.toInt()))
        leaf1 = (((i - a) and 0xffffffffL) or ((e xor a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc11ac(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        var i = 0L
        a = (y and 0xffffffffL)
        b = ((a shr 5L.toInt()) or (a shl 27L.toInt()))
        c = (x shr 32L.toInt())
        d = (((x shr 45L.toInt()) and 524287L) or (c shl 19L.toInt()))
        e = ((y shr 32L.toInt()) and 0xffffffffL)
        f = (((((b + x) + d) * 465265831L) and 0xffffffffL) xor e)
        a = (e xor a)
        e = (a xor ((f shr 29L.toInt()) or (f shl 3L.toInt())))
        b = (((((f + b) + x) + d) - c) - y)
        d = table[((b and 255L) + 115L).toInt()]
        g = ((((-f) - (e * 2L)) - d) and 0xffffffffL)
        a = ((a + 0x6d826726L) xor (c + y))
        c = (d + e)
        d = (c and 0xffffffffL)
        e = (((b + a) + e) - ((d shr 10L.toInt()) or (d shl 22L.toInt())))
        h = (e and 0xffffffffL)
        i = (g xor (((h shr 31L.toInt()) or (h shl 1L.toInt())) and 0xffffffffL))
        a = (b xor ((b - f) + a))
        b = ((g shr 7L.toInt()) or (g shl 25L.toInt()))
        e = (-((((g shr 15L.toInt()) or (g shl 17L.toInt())) - 13138L) xor (e + 11041L)))
        leaf0 = (i or ((h + (((d - a) - b) * 560761575L)) shl 32L.toInt()))
        leaf1 = (((((c - b) + e) + 0x5b3db5c5L) and 0xffffffffL) or (((a + e) xor i) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc12fc(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        a = (x shr 32L.toInt())
        b = ((((y - 5973L) xor x) xor ((((x shr 43L.toInt()) and 2097151L) or (a shl 21L.toInt())) + 11005L)) and 0xffffffffL)
        a = (((a - y) + 0xca22dd41L) and 0xffffffffL)
        c = ((b - ((a shr 25L.toInt()) or (a shl 7L.toInt()))) and 0xffffffffL)
        d = (y shr 32L.toInt())
        b = ((d - ((b shr 31L.toInt()) or (b shl 1L.toInt()))) and 0xffffffffL)
        d = ((y - d) and 0xffffffffL)
        a = ((((b shr 14L.toInt()) or (b shl 18L.toInt())) + ((d shr 7L.toInt()) or (d shl 25L.toInt()))) xor a)
        leaf0 = (c or (a shl 32L.toInt()))
        leaf1 = (((((-c) - b) + d) and 0xffffffffL) or ((b - ((a + 25459L) xor (((c shr 26L.toInt()) or (c shl 6L.toInt())) - 8653L))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc13f8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = ((x shr 32L.toInt()) xor y)
        d = (y - ((((b shr 24L.toInt()) or (b shl 8L.toInt())) - 7921L) xor (c - 7370L)))
        b = (b xor c)
        c = ((d + b) xor c)
        e = ((d + c) xor c)
        d = ((((e + c) + b) xor (d - b)) and 0xffffffffL)
        b = (((c + b) - e) and 0xffffffffL)
        c = ((((d shr 22L.toInt()) or (d shl 10L.toInt())) + ((b shr 12L.toInt()) or (b shl 20L.toInt()))) xor e)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = (((((-b) - c) + d) and 0xffffffffL) or ((c xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1478(x:Long, y:Long) {
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
        a = (y shr 32L.toInt())
        b = (x shr 32L.toInt())
        c = table[((b and 255L) + 8L).toInt()]
        d = ((a - x) + c)
        c = (x - c)
        e = (y and 0xffffffffL)
        f = ((e shr 19L.toInt()) or (e shl 13L.toInt()))
        g = (((y shr 37L.toInt()) and 0x7ffffffL) or (a shl 27L.toInt()))
        h = table[(((((c + f) + b) + g) and 255L) + 262L).toInt()]
        i = ((d - h) and 0xffffffffL)
        e = ((c + a) xor e)
        j = (table[((e and 255L) + 307L).toInt()] xor (((f + b) + g) and 0xffffffffL))
        k = (i xor j)
        e = (e + table[((d and 255L) + 390L).toInt()])
        l = (e xor i)
        a = (((((-(c * 0x6a5ed7e3L)) - (a * 284061982L)) + ((((j - g) - b) - f) * 0x7b4d4901L)) - (l * 284061982L)) + (k * 284061982L))
        b = table[(((e xor j) and 255L) + 472L).toInt()]
        c = ((k shr 26L.toInt()) or (k shl 6L.toInt()))
        e = (((((i - (a * 401197552L)) + b) + k) + (((d - k) + l) * 0x435765e0L)) xor ((((-(a * 761664239L)) - h) + k) + (((d - k) + l) * 360466687L)))
        f = ((((((((e + (a * 40730865L)) - (d * 0x713282c3L)) + h) - (b * 2L)) + c) - (l * 0x713282c2L)) + (k * 0x713282c1L)) and 0xffffffffL)
        leaf0 = (((((a + (h * 284061982L)) + b) - c) and 0xffffffffL) or (((((a * 761664239L) + (e * 0xc764572L)) + h) - (((d - k) + l) * 360466687L)) shl 32L.toInt()))
        leaf1 = (f or ((((((((d * 0x713282c3L) - (a * 40730866L)) - (h * 284061983L)) + b) - (k * 0x713282c1L)) + (l * 0x604411a4L)) + (l * 284061982L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc16f8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = ((((y + 29274L) xor (x shr 32L.toInt())) xor (b - 28152L)) and 0xffffffffL)
        d = ((c shr 21L.toInt()) or (c shl 11L.toInt()))
        e = ((y + (b * 2L)) + d)
        c = (((c + y) + b) + 0x547304aaL)
        b = ((d + b) xor c)
        c = (((e + 0x6cb10a83L) xor (b - 27444L)) xor c)
        d = ((e + 0x6cb166a5L) xor b)
        e = ((((c * 3L) - (b * 2L)) - (d * 2L)) and 0xffffffffL)
        f = (((((c * 2L) - b) - d) + 0x536215beL) xor (b - c))
        leaf0 = (a or (e shl 32L.toInt()))
        leaf1 = (((((table[((f and 255L) + 474L).toInt()] - c) + d) + b) and 0xffffffffL) or ((f - ((e shr 22L.toInt()) or (e shl 10L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc17b8(x:Long, y:Long) {
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
        b = ((x + y) + a)
        c = ((y shr 32L.toInt()) and 0xffffffffL)
        d = ((b and 0xffffffffL) xor c)
        e = (((y - c) + 521847744L) and 0xffffffffL)
        f = (d xor e)
        g = (y and 0xffffffffL)
        a = (((((g shr 8L.toInt()) or (g shl 24L.toInt())) - 1819L) xor (c + 24335L)) xor a)
        c = (-((d shr 2L.toInt()) or (d shl 30L.toInt())))
        e = (-((e shr 30L.toInt()) or (e shl 2L.toInt())))
        g = table[((((((f * 108L) + a) + c) + e) and 255L) + 58L).toInt()]
        h = (((g + b) + c) + e)
        i = (table[((((b - a) + d) and 255L) + 293L).toInt()] xor f)
        b = (((((((a - (h * 0x47482b6eL)) + (g * 0x47482b6dL)) + i) - d) - b) and 0xffffffffL) or (((((table[((h and 255L) + 379L).toInt()] - a) + b) + d) + (((b + c) + e) * 0x47482b6dL)) shl 32L.toInt()))
        leaf0 = ((h and 0xffffffffL) or ((((((f * 509723500L) + a) + c) + e) - i) shl 32L.toInt()))
        leaf1 = b
    }

internal fun SpotifyPlayPlayLeaves.math_cc1884(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (a + x)
        c = (y shr 32L.toInt())
        d = (table[((c and 255L) + 331L).toInt()] xor y)
        e = ((a + y) and 0xffffffffL)
        e = ((b - ((d - 29481L) xor (((e shr 19L.toInt()) or (e shl 13L.toInt())) + 28248L))) and 0xffffffffL)
        a = ((d + a) + y)
        b = ((b + c) and 0xffffffffL)
        c = (d xor b)
        d = ((a + c) and 0xffffffffL)
        f = (e xor d)
        a = (a xor c)
        g = (((f - a) + 0xc0b27f45L) and 0xffffffffL)
        e = (e xor b)
        c = (c xor ((e shr 28L.toInt()) or (e shl 4L.toInt())))
        b = (d xor b)
        leaf0 = (g or ((a - c) shl 32L.toInt()))
        leaf1 = ((((c - ((b shr 7L.toInt()) or (b shl 25L.toInt()))) - ((g shr 26L.toInt()) or (g shl 6L.toInt()))) and 0xffffffffL) or ((((b + c) - f) + 0x3f4d80bbL) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1934(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = (b - y)
        d = (c xor b)
        c = (table[((d and 255L) + 398L).toInt()] xor (c + ((y shr 32L.toInt()) xor a)))
        e = (c xor b)
        f = ((((-e) - c) - 35044951L) and 0xffffffffL)
        b = (-((c - 11403L) xor ((((x shr 63L.toInt()) and 1L) or (b shl 1L.toInt())) + 3844L)))
        e = (e - table[(((((c + d) + b) + 87L) and 255L) + 4L).toInt()])
        b = (((((((f shr 15L.toInt()) or (f shl 17L.toInt())) - 9517L) xor (e + 5102L)) + c) + d) + b)
        c = (e and 0xffffffffL)
        d = (c xor table[(((b + 87L) and 255L) + 125L).toInt()])
        c = ((((b + 35035043L) xor (((c shr 1L.toInt()) or (c shl 31L.toInt())) + 5393L)) + f) and 0xffffffffL)
        b = (((b + 35044951L) xor ((c shr 3L.toInt()) or (c shl 29L.toInt()))) and 0xffffffffL)
        leaf0 = (a or ((c + d) shl 32L.toInt()))
        leaf1 = ((b xor d) or ((b xor ((c + d) * 962921762L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1a28(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x shr 32L.toInt())
        b = (y shr 32L.toInt())
        c = (((a + x) + b) and 0xffffffffL)
        b = ((b + y) and 0xffffffffL)
        d = (c xor b)
        b = (((c shr 22L.toInt()) or (c shl 10L.toInt())) + ((b shr 12L.toInt()) or (b shl 20L.toInt())))
        c = (((b + a) + x) xor c)
        e = (d - c)
        f = (a xor y)
        d = (((b + f) + d) and 0xffffffffL)
        g = ((e + 0x658dd0bfL) xor d)
        d = ((d shr 27L.toInt()) or (d shl 5L.toInt()))
        h = (f * (-2L))
        f = ((((((b - x) + c) + (f * 2L)) - a) - 0x533b6e4aL) and 0xffffffffL)
        e = (f xor e)
        c = (((((c - f) + d) + 707157098L) and 0xffffffffL) xor f)
        leaf0 = (((((((x - b) + g) + h) + d) + a) and 0xffffffffL) or ((g - e) shl 32L.toInt()))
        leaf1 = (((e - ((c shr 20L.toInt()) or (c shl 12L.toInt()))) and 0xffffffffL) or (((((((x + c) - b) + g) + h) + d) + a) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1b18(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y and 0xffffffffL)
        d = ((((c shr 31L.toInt()) or (c shl 1L.toInt())) + (x shr 32L.toInt())) and 0xffffffffL)
        c = (((((b - c) * 603764625L) - 0x565fdf27L) and 0xffffffffL) xor d)
        d = (d xor b)
        b = (((((y - b) - ((d shr 4L.toInt()) or (d shl 28L.toInt()))) - ((c shr 13L.toInt()) or (c shl 19L.toInt()))) + 0xad0bff37L) and 0xffffffffL)
        e = ((b shr 13L.toInt()) or (b shl 19L.toInt()))
        d = ((table[((c and 255L) + 95L).toInt()] + d) and 0xffffffffL)
        leaf0 = (a or ((c - e) shl 32L.toInt()))
        leaf1 = (((b - ((d shr 7L.toInt()) or (d shl 25L.toInt()))) and 0xffffffffL) or ((d + ((e - c) * 452581705L)) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1bb0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (y xor (x shr 32L.toInt()))
        b = (x xor (y shr 32L.toInt()))
        c = table[((b and 255L) + 157L).toInt()]
        d = table[((a and 255L) + 44L).toInt()]
        a = ((((a - y) + c) - b) + d)
        e = (a and 0xffffffffL)
        b = ((b - d) and 0xffffffffL)
        c = (((((e shr 10L.toInt()) or (e shl 22L.toInt())) + ((b shr 7L.toInt()) or (b shl 25L.toInt()))) xor (y - c)) and 0xffffffffL)
        a = (a + table[((c and 255L) + 115L).toInt()])
        b = (e xor b)
        leaf0 = ((x and 0xffffffffL) or (a shl 32L.toInt()))
        leaf1 = (((((((b shr 27L.toInt()) or (b shl 5L.toInt())) - 23914L) and 0xffffffffL) xor c) xor ((a + 23732L) and 0xffffffffL)) or ((a + b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1e08(x:Long, y:Long) {
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
        c = ((y - ((y shr 32L.toInt()) xor a)) and 0xffffffffL)
        d = (((b + y) xor ((c shr 3L.toInt()) or (c shl 29L.toInt()))) and 0xffffffffL)
        e = ((-c) - b)
        f = (e and 0xffffffffL)
        f = ((d - 4625L) xor (((f shr 28L.toInt()) or (f shl 4L.toInt())) + 29513L))
        g = ((f + c) and 0xffffffffL)
        h = ((g shr 13L.toInt()) or (g shl 19L.toInt()))
        g = (-((g - 1691L) xor (((d shr 26L.toInt()) or (d shl 6L.toInt())) + 23762L)))
        e = (e + g)
        f = (table[((e and 255L) + 146L).toInt()] + f)
        c = (((d - h) - table[(((f + c) and 255L) + 115L).toInt()]) and 0xffffffffL)
        e = (c xor ((e - d) + h))
        b = ((((f + g) - d) - b) + h)
        c = (c xor table[((b and 255L) + 215L).toInt()])
        b = ((e + c) xor b)
        d = ((b + 0x982cf918L) xor c)
        c = (((((c shr 3L.toInt()) or (c shl 29L.toInt())) - 2013L) xor (b + 17625L)) xor e)
        leaf0 = (a or (d shl 32L.toInt()))
        leaf1 = (((b - c) and 0xffffffffL) or ((c - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc1ef8(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (y and 0xffffffffL)
        d = (((b - 11495L) xor (((c shr 16L.toInt()) or (c shl 16L.toInt())) + 2771L)) + (x shr 32L.toInt()))
        e = ((b shr 26L.toInt()) or (b shl 6L.toInt()))
        f = (d and 0xffffffffL)
        f = ((f shr 14L.toInt()) or (f shl 18L.toInt()))
        d = ((((d + b) + y) - e) - f)
        g = (table[((d and 255L) + 195L).toInt()] xor (((y - e) - f) and 0xffffffffL))
        b = ((((f * 2L) + (e * 2L)) - (c * 2L)) - b)
        c = (table[((g and 255L) + 257L).toInt()] xor b)
        b = (((b + 0x8b9420a9L) xor d) and 0xffffffffL)
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((g xor (((b shr 11L.toInt()) or (b shl 21L.toInt())) and 0xffffffffL)) or (((c - 862862694L) xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2020(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x shr 32L.toInt())
        b = table[((a and 255L) + 33L).toInt()]
        c = (((b + x) xor ((a + y) + 0xb872e32L)) and 0xffffffffL)
        d = (y shr 32L.toInt())
        e = (y xor (((y shr 47L.toInt()) and 131071L) or (d shl 17L.toInt())))
        a = (((e + a) + y) and 0xffffffffL)
        f = (c xor a)
        b = ((d - b) - x)
        c = (b + c)
        d = (c and 0xffffffffL)
        leaf0 = (f or ((table[(((b + e) and 255L) + 20L).toInt()] xor a) shl 32L.toInt()))
        leaf1 = ((((((d shr 31L.toInt()) or (d shl 1L.toInt())) + b) + e) and 0xffffffffL) or ((c - ((f shr 23L.toInt()) or (f shl 9L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc209c(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        a = (y and 0xffffffffL)
        b = ((a shr 17L.toInt()) or (a shl 15L.toInt()))
        c = (x shr 32L.toInt())
        d = (((x shr 47L.toInt()) and 131071L) or (c shl 17L.toInt()))
        e = (y shr 32L.toInt())
        leaf0 = ((((b + x) + d) and 0xffffffffL) or (((((a shr 31L.toInt()) or (a shl 1L.toInt())) + c) + (((y shr 54L.toInt()) and 1023L) or (e shl 10L.toInt()))) shl 32L.toInt()))
        leaf1 = ((table[((e and 255L) + 467L).toInt()] xor a) or ((((((-b) - x) - d) * 0x530e2bbcL) + e) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc20ec(x:Long, y:Long) {
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
        b = (y + a)
        c = (y shr 32L.toInt())
        a = (a xor x)
        d = (y xor (((y shr 38L.toInt()) and 67108863L) or (c shl 26L.toInt())))
        e = ((((b + a) + d) + 621531993L) and 0xffffffffL)
        f = (-((e shr 11L.toInt()) or (e shl 21L.toInt())))
        g = (((b - c) + a) + f)
        h = (((e - (d * 2L)) - c) and 0xffffffffL)
        e = ((((h shr 4L.toInt()) or (h shl 28L.toInt())) + e) and 0xffffffffL)
        h = (table[(((g + 89L) and 255L) + 185L).toInt()] xor e)
        i = (((c - a) + f) and 0xffffffffL)
        a = ((((((-d) - g) + h) + c) - a) - ((i shr 29L.toInt()) or (i shl 3L.toInt())))
        c = (((g - h) + 621531993L) and 0xffffffffL)
        d = (((a - 621531993L) and 0xffffffffL) xor c)
        e = ((e shr 29L.toInt()) or (e shl 3L.toInt()))
        g = ((g + 621531993L) and 0xffffffffL)
        g = ((g shr 11L.toInt()) or (g shl 21L.toInt()))
        i = ((((i + h) + g) + e) and 0xffffffffL)
        b = ((((((((-((i shr 28L.toInt()) or (i shl 4L.toInt()))) - a) + h) - g) - e) - (f * 2L)) - b) and 0xffffffffL)
        a = (i xor table[(((a - 89L) and 255L) + 405L).toInt()])
        leaf0 = (d or ((b xor c) shl 32L.toInt()))
        leaf1 = ((b xor a) or ((a - d) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc21ac(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        a = (x shr 32L.toInt())
        b = (-table[((a and 255L) + 436L).toInt()])
        c = (y shr 32L.toInt())
        d = (table[((c and 255L) + 271L).toInt()] xor (y and 0xffffffffL))
        e = (d xor ((a - y) and 0xffffffffL))
        f = (((((x + b) - a) + y) xor ((e shr 16L.toInt()) or (e shl 16L.toInt()))) and 0xffffffffL)
        c = (((x + b) * 0x503c209cL) xor c)
        d = (c xor d)
        e = ((e - d) and 0xffffffffL)
        g = (f xor (((e shr 22L.toInt()) or (e shl 10L.toInt())) and 0xffffffffL))
        a = (((((c + x) + b) - a) + y) and 0xffffffffL)
        b = (((a shr 8L.toInt()) or (a shl 24L.toInt())) + d)
        a = (f xor a)
        c = ((b + a) xor e)
        d = (g + table[((c and 255L) + 406L).toInt()])
        b = (b xor ((a shr 30L.toInt()) or (a shl 2L.toInt())))
        e = (c xor b)
        f = ((d - table[((e and 255L) + 493L).toInt()]) and 0xffffffffL)
        a = (a - ((((g shr 28L.toInt()) or (g shl 4L.toInt())) + 16146L) xor (c - 20672L)))
        c = (a and 0xffffffffL)
        b = ((b - ((c shr 24L.toInt()) or (c shl 8L.toInt()))) and 0xffffffffL)
        a = (a - table[((d and 255L) + 67L).toInt()])
        leaf0 = (f or ((b xor e) shl 32L.toInt()))
        leaf1 = ((b xor ((a + 0x55a83d7aL) and 0xffffffffL)) or ((a xor ((f shr 10L.toInt()) or (f shl 22L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2290(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = ((y shr 32L.toInt()) xor a)
        c = y
        d = (b xor c)
        c = (-((x shr 32L.toInt()) xor c))
        e = (((b + c) + 0x7d7f7e50L) xor d)
        f = ((d + b) - e)
        b = (((d + (b * 2L)) + c) and 0xffffffffL)
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = (((e - ((b shr 25L.toInt()) or (b shl 7L.toInt()))) and 0xffffffffL) or ((f xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc22e0(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        c = (x shr 32L.toInt())
        d = ((y - ((b shr 4L.toInt()) or (b shl 28L.toInt()))) and 0xffffffffL)
        e = ((c + y) - ((d shr 3L.toInt()) or (d shl 29L.toInt())))
        f = (e and 0xffffffffL)
        b = ((((y + d) + b) + c) and 0xffffffffL)
        c = table[((b and 255L) + 498L).toInt()]
        d = ((((c - ((f shr 7L.toInt()) or (f shl 25L.toInt()))) - f) + d) and 0xffffffffL)
        c = (d xor (e - c))
        leaf0 = (a or (c shl 32L.toInt()))
        leaf1 = ((b xor d) or ((b + table[((c and 255L) + 375L).toInt()]) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2360(x:Long, y:Long) {
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
        b = ((((((x shr 38L.toInt()) and 67108863L) or (a shl 26L.toInt())) + ((b shr 3L.toInt()) or (b shl 29L.toInt()))) xor x) and 0xffffffffL)
        c = ((b shr 2L.toInt()) or (b shl 30L.toInt()))
        d = ((a + y) and 0xffffffffL)
        e = ((d shr 19L.toInt()) or (d shl 13L.toInt()))
        f = (y shr 32L.toInt())
        g = ((c + e) + f)
        b = ((b - d) and 0xffffffffL)
        d = ((g + ((b shr 5L.toInt()) or (b shl 27L.toInt()))) and 0xffffffffL)
        h = (g and 0xffffffffL)
        h = ((y - f) - ((h shr 5L.toInt()) or (h shl 27L.toInt())))
        i = (h and 0xffffffffL)
        c = ((((a - c) - e) - ((d shr 20L.toInt()) or (d shl 12L.toInt()))) - ((i shr 31L.toInt()) or (i shl 1L.toInt())))
        a = ((b + (((f - g) + a) * 881886977L)) and 0xffffffffL)
        b = (table[((c and 255L) + 438L).toInt()] xor a)
        e = ((h + ((d shr 24L.toInt()) or (d shl 8L.toInt()))) and 0xffffffffL)
        a = (a xor d)
        c = ((c - ((e shr 29L.toInt()) or (e shl 3L.toInt()))) - ((a shr 19L.toInt()) or (a shl 13L.toInt())))
        d = (b xor c)
        e = (e xor a)
        f = (((c + d) - e) and 0xffffffffL)
        b = (-table[((b and 255L) + 80L).toInt()])
        g = (((a + b) and 0xffffffffL) xor e)
        a = ((b - (d * 317612791L)) + a)
        b = (a and 0xffffffffL)
        leaf0 = (f or (((g * 939226899L) xor (c - e)) shl 32L.toInt()))
        leaf1 = (((((f - 2712L) and 0xffffffffL) xor g) xor ((((b shr 5L.toInt()) or (b shl 27L.toInt())) - 28676L) and 0xffffffffL)) or ((a - ((f shr 28L.toInt()) or (f shl 4L.toInt()))) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2470(x:Long, y:Long) {
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
        c = (((b - y) - 271318121L) and 0xffffffffL)
        d = (((y shr 32L.toInt()) and 0xffffffffL) xor a)
        e = ((((c shr 14L.toInt()) or (c shl 18L.toInt())) + ((d shr 4L.toInt()) or (d shl 28L.toInt()))) xor y)
        f = (e * 2L)
        d = (c xor d)
        g = ((d shr 3L.toInt()) or (d shl 29L.toInt()))
        h = (e + g)
        e = ((e + c) and 0xffffffffL)
        d = (((((e shr 3L.toInt()) or (e shl 29L.toInt())) - 9703L) xor (h - 12341L)) + d)
        e = (h - table[((d and 255L) + 245L).toInt()])
        h = (e and 0xffffffffL)
        c = (((f + c) + g) xor ((h shr 23L.toInt()) or (h shl 9L.toInt())))
        b = ((((d + f) + b) - y) + g)
        d = table[(((b + 151L) and 255L) + 37L).toInt()]
        b = (((b - c) - 271318121L) and 0xffffffffL)
        f = (-((b shr 22L.toInt()) or (b shl 10L.toInt())))
        leaf0 = (a or (((c - h) + d) shl 32L.toInt()))
        leaf1 = ((((e - d) + f) and 0xffffffffL) or (((c + f) xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2598(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        a = (x and 0xffffffffL)
        b = (x shr 32L.toInt())
        c = ((y shr 32L.toInt()) xor a)
        d = (((b * 2L) - (c * 4L)) - (y * 3L))
        e = (d and 0xffffffffL)
        b = (((c * 2L) - b) + y)
        f = (b and 0xffffffffL)
        c = ((((e shr 7L.toInt()) or (e shl 25L.toInt())) + ((f shr 29L.toInt()) or (f shl 3L.toInt()))) xor (c + y))
        d = (d - c)
        f = (d and 0xffffffffL)
        b = ((b - ((e shr 10L.toInt()) or (e shl 22L.toInt()))) and 0xffffffffL)
        leaf0 = (a or (f shl 32L.toInt()))
        leaf1 = ((((c - ((b shr 1L.toInt()) or (b shl 31L.toInt()))) - ((f shr 30L.toInt()) or (f shl 2L.toInt()))) and 0xffffffffL) or ((table[((d and 255L) + 361L).toInt()] xor b) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc2618(x:Long, y:Long) {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var e = 0L
        var f = 0L
        var g = 0L
        var h = 0L
        a = (x shr 32L.toInt())
        b = (table[((y and 255L) + 361L).toInt()] xor a)
        c = (y shr 32L.toInt())
        d = (c xor y)
        a = (table[((a and 255L) + 392L).toInt()] + x)
        e = (a and 0xffffffffL)
        e = ((((e shr 29L.toInt()) or (e shl 3L.toInt())) - 13044L) xor (b + 28311L))
        f = ((((b - d) - e) - c) and 0xffffffffL)
        g = (((d - e) - c) and 0xffffffffL)
        a = (a - b)
        g = (((((f shr 7L.toInt()) or (f shl 25L.toInt())) + ((g shr 12L.toInt()) or (g shl 20L.toInt()))) xor a) and 0xffffffffL)
        h = (g - table[(((b - (d * 2L)) and 255L) + 421L).toInt()])
        a = table[((a and 255L) + 431L).toInt()]
        g = ((g shr 11L.toInt()) or (g shl 21L.toInt()))
        c = (((g + e) + c) - (a * 2L))
        e = (h and 0xffffffffL)
        e = (-((e shr 3L.toInt()) or (e shl 29L.toInt())))
        h = ((((c + h) - b) + e) + (d * 3L))
        c = ((h xor (c + d)) and 0xffffffffL)
        f = (((((((h - c) - b) - (a * 2L)) + (d * 4L)) - e) and 0xffffffffL) or (((((((((c shr 30L.toInt()) or (c shl 2L.toInt())) - 25587L) xor (h - 9884L)) + f) + (a * 3L)) - (d * 3L)) - g) shl 32L.toInt()))
        leaf0 = f
        leaf1 = (((c - h) and 0xffffffffL) or ((((((a * 2L) + e) + b) - (d * 4L)) + c) shl 32L.toInt()))
    }

internal fun SpotifyPlayPlayLeaves.math_cc285c(x:Long, y:Long) {
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
        var m = 0L
        a = (y shr 32L.toInt())
        b = (x - y)
        c = (x shr 32L.toInt())
        d = ((b - c) and 0xffffffffL)
        e = ((d shr 21L.toInt()) or (d shl 11L.toInt()))
        f = (e * (-0x6990d4ceL))
        g = table[((y and 255L) + 31L).toInt()]
        c = ((c - g) and 0xffffffffL)
        h = ((c shr 1L.toInt()) or (c shl 31L.toInt()))
        i = (h * (-0x6990d4ceL))
        j = table[((((((-g) - y) + a) + b) and 255L) + 365L).toInt()]
        k = (((((((d * 2L) - y) - h) - e) + (a * 2L)) + 0x4ec5a28cL) and 0xffffffffL)
        c = ((j - c) + d)
        l = (-((((k shr 25L.toInt()) or (k shl 7L.toInt())) + 10523L) xor (c + 0x4ec5f05fL)))
        m = (((((y + l) + ((i + f) * 0x7fffffffL)) - d) - (a * 0x6990d4cfL)) and 0xffffffffL)
        c = table[(((c + 140L) and 255L) + 407L).toInt()]
        b = ((((((((j - (y * 2L)) + i) + f) + (a * 0x6990d4d0L)) + (d * 3L)) + 0x4ec5a28cL) and 0xffffffffL) or ((((((((-y) - ((((m shr 9L.toInt()) or (m shl 23L.toInt())) + 22529L) xor ((k + c) + 10308L))) - m) + l) - g) + a) + b) shl 32L.toInt()))
        a = ((((((((l - j) - c) + (k * 752768611L)) - (d * 0x59bcacccL)) + ((y - a) * 752768615L)) - 0x7dfc293cL) and 0xffffffffL) or ((((((y - d) - a) + (((e + h) - a) * 0x6990d4cdL)) + c) - j) shl 32L.toInt()))
        leaf0 = b
        leaf1 = a
    }
