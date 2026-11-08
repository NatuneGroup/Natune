package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayPipeline.fn_reduced_cbb02c(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x"] = 0L
        e["y"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["g"] = 0L
        e["h"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"x", r(e,"1"))
        s(e,"y", r(e,"2"))
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["g"] = 0L
        e["h"] = 0L
        s(e,"a", (r(e,"y") shr 32L.toInt()))
        s(e,"b", (r(e,"y") and 0xffffffffL))
        s(e,"c", ((r(e,"a") + r(e,"b")) and 0xffffffffL))
        s(e,"d", ((r(e,"x") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", ((((r(e,"c") shr 8L.toInt()) or (r(e,"c") shl 24L.toInt())) xor (r(e,"d") - r(e,"b"))) and 0xffffffffL))
        s(e,"e", ((r(e,"d") shr 28L.toInt()) or (r(e,"d") shl 4L.toInt())))
        s(e,"f", r(e,"x"))
        s(e,"g", ((r(e,"b") shr 11L.toInt()) or (r(e,"b") shl 21L.toInt())))
        s(e,"h", (((((r(e,"c") - r(e,"b")) - r(e,"e")) - r(e,"f")) - r(e,"g")) and 0xffffffffL))
        s(e,"d", (((((r(e,"e") + r(e,"f")) + r(e,"g")) + r(e,"d")) - r(e,"b")) and 0xffffffffL))
        s(e,"a", ((((r(e,"a") - r(e,"e")) - r(e,"f")) - r(e,"g")) xor r(e,"d")))
        s(e,"b", ((((r(e,"b") + r(e,"e")) + r(e,"f")) + r(e,"g")) + r(e,"a")))
        s(e,"e", (r(e,"b") and 0xffffffffL))
        s(e,"c", (r(e,"d") xor r(e,"c")))
        s(e,"a", ((r(e,"c") * 0x9ae6c54L) xor r(e,"a")))
        s(e,"b", ((r(e,"b") - r(e,"a")) and 0xffffffffL))
        s(e,"d", (((r(e,"h") xor r(e,"e")) - ((r(e,"b") shr 11L.toInt()) or (r(e,"b") shl 21L.toInt()))) and 0xffffffffL))
        s(e,"e", (r(e,"e") xor r(e,"c")))
        s(e,"f", ((((r(e,"d") shr 18L.toInt()) or (r(e,"d") shl 14L.toInt())) and 0xffffffffL) xor r(e,"e")))
        s(e,"c", (r(e,"h") xor r(e,"c")))
        s(e,"c", ((r(e,"c") shr 28L.toInt()) or (r(e,"c") shl 4L.toInt())))
        s(e,"b", ((r(e,"b") - r(e,"a")) - r(e,"c")))
        s(e,"g", (r(e,"b") and 0xffffffffL))
        s(e,"a", (((r(e,"c") + r(e,"a")) xor ((r(e,"e") shr 12L.toInt()) or (r(e,"e") shl 20L.toInt()))) and 0xffffffffL))
        s(e,"c", (((((r(e,"g") shr 5L.toInt()) or (r(e,"g") shl 27L.toInt())) + 18022L) xor (r(e,"a") - 14545L)) + r(e,"d")))
        s(e,"O0", r(e,"f"))
        s(e,"O1", (r(e,"c") and 0xffffffffL))
        s(e,"O2", ((r(e,"b") - ((r(e,"a") shr 1L.toInt()) or (r(e,"a") shl 31L.toInt()))) and 0xffffffffL))
        s(e,"O3", (((r(e,"c") + r(e,"f")) and 0xffffffffL) xor r(e,"a")))
    }

internal fun SpotifyPlayPlayPipeline.fn_reduced_cbb0b0(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x"] = 0L
        e["y"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["g"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"x", r(e,"1"))
        s(e,"y", r(e,"2"))
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["g"] = 0L
        s(e,"a", r(e,"y"))
        s(e,"b", (r(e,"x") shr 32L.toInt()))
        s(e,"c", (((r(e,"a") + r(e,"b")) xor r(e,"x")) and 0xffffffffL))
        s(e,"d", (r(e,"y") shr 32L.toInt()))
        s(e,"e", (r(e,"a") + leaves.lookup(((r(e,"d") and 255L) + 433L).toInt())))
        s(e,"a", ((r(e,"b") - r(e,"a")) and 0xffffffffL))
        s(e,"b", (leaves.lookup(((r(e,"e") and 255L) + 442L).toInt()) xor r(e,"a")))
        s(e,"f", (-((r(e,"a") shr 22L.toInt()) or (r(e,"a") shl 10L.toInt()))))
        s(e,"g", (-((r(e,"c") shr 22L.toInt()) or (r(e,"c") shl 10L.toInt()))))
        s(e,"O0", ((r(e,"c") - r(e,"a")) and 0xffffffffL))
        s(e,"O1", r(e,"b"))
        s(e,"O2", ((((r(e,"e") + r(e,"d")) + r(e,"f")) + r(e,"g")) and 0xffffffffL))
        s(e,"O3", ((((((r(e,"c") + r(e,"b")) + r(e,"g")) + r(e,"f")) - r(e,"a")) + r(e,"d")) and 0xffffffffL))
    }

internal fun SpotifyPlayPlayPipeline.fn_reduced_cbb134(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x"] = 0L
        e["y"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"x", r(e,"1"))
        s(e,"y", r(e,"2"))
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["e"] = 0L
        s(e,"a", (r(e,"x") and 0xffffffffL))
        s(e,"b", (r(e,"y") and 0xffffffffL))
        s(e,"c", ((r(e,"x") shr 32L.toInt()) - ((r(e,"b") shr 21L.toInt()) or (r(e,"b") shl 11L.toInt()))))
        s(e,"d", (r(e,"a") xor ((r(e,"y") shr 32L.toInt()) and 0xffffffffL)))
        s(e,"b", ((((r(e,"d") shr 28L.toInt()) or (r(e,"d") shl 4L.toInt())) and 0xffffffffL) xor r(e,"b")))
        s(e,"d", ((r(e,"c") + 0x82a561a4L) xor r(e,"d")))
        s(e,"c", ((r(e,"c") - ((((r(e,"b") shr 16L.toInt()) or (r(e,"b") shl 16L.toInt())) + 3450L) xor (r(e,"d") - 23999L))) and 0xffffffffL))
        s(e,"b", ((r(e,"b") - r(e,"d")) and 0xffffffffL))
        s(e,"e", (r(e,"c") xor r(e,"b")))
        s(e,"c", ((r(e,"d") - ((r(e,"b") shr 1L.toInt()) or (r(e,"b") shl 31L.toInt()))) - ((r(e,"c") shr 13L.toInt()) or (r(e,"c") shl 19L.toInt()))))
        s(e,"b", ((r(e,"c") and 0xffffffffL) xor r(e,"b")))
        s(e,"c", (r(e,"c") - ((r(e,"b") + 26447L) xor (((r(e,"e") shr 7L.toInt()) or (r(e,"e") shl 25L.toInt())) + 13892L))))
        s(e,"d", (r(e,"e") - ((r(e,"c") - 10012L) xor (((r(e,"b") shr 29L.toInt()) or (r(e,"b") shl 3L.toInt())) - 6866L))))
        s(e,"e", (r(e,"d") and 0xffffffffL))
        s(e,"d", ((((r(e,"e") shr 14L.toInt()) or (r(e,"e") shl 18L.toInt())) xor r(e,"c")) + r(e,"d")))
        s(e,"O0", r(e,"a"))
        s(e,"O1", (((r(e,"c") + r(e,"e")) - r(e,"b")) and 0xffffffffL))
        s(e,"O2", (r(e,"d") and 0xffffffffL))
        s(e,"O3", (((r(e,"c") + r(e,"d")) - r(e,"b")) and 0xffffffffL))
    }

internal fun SpotifyPlayPlayPipeline.fn_record_input(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["u"] = 0L
        e["v"] = 0L
        e["r"] = 0L
        e["s"] = 0L
        e["t"] = 0L
        e["q"] = 0L
        e["h"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["w"] = 0L
        e["z"] = 0L
        e["k"] = 0L
        e["p"] = 0L
        e["rr"] = 0L
        e["pr"] = 0L
        e["ar"] = 0L
        e["ur"] = 0L
        e["state_a"] = 0L
        e["state_c"] = 0L
        e["state_d"] = 0L
        e["state_e"] = 0L
        e["n"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"a", (r(e,"1") and 0xffffffffL))
        s(e,"b", ((r(e,"1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"2") and 0xffffffffL))
        s(e,"d", ((r(e,"2") shr 32L.toInt()) and 0xffffffffL))
        e["u"] = 0L
        e["v"] = 0L
        e["r"] = 0L
        e["s"] = 0L
        e["t"] = 0L
        e["q"] = 0L
        e["h"] = 0L
        e["e"] = 0L
        e["f"] = 0L
        e["w"] = 0L
        e["z"] = 0L
        e["k"] = 0L
        e["p"] = 0L
        e["rr"] = 0L
        e["pr"] = 0L
        e["ar"] = 0L
        e["ur"] = 0L
        e["state_a"] = 0L
        e["state_c"] = 0L
        e["state_d"] = 0L
        e["state_e"] = 0L
        e["n"] = 0L
        s(e,"u", ((r(e,"c") - r(e,"d")) and 0xffffffffL))
        s(e,"v", ((((r(e,"u") + (r(e,"a") * 0x4f3f8e14L)) + r(e,"b")) + leaves.lookup((9L + (((((2L * r(e,"c")) - r(e,"d")) + (21L * r(e,"a"))) + r(e,"b")) and 255L)).toInt())) and 0xffffffffL))
        call("response_ror32", r(e,"v"), 28L)
        s(e,"r", (r(e,"ROR_RESULT") xor (((r(e,"u") + (r(e,"a") * 0x4f3f8e15L)) + r(e,"b")) and 0xffffffffL)))
        s(e,"s", (((((r(e,"a") + r(e,"c")) + r(e,"b")) - r(e,"r")) + 0x95a645aaL) and 0xffffffffL))
        call("response_ror32", r(e,"s"), 5L)
        s(e,"t", ((((((((r(e,"ROR_RESULT") + 19187L) and 0xffffffffL) xor ((r(e,"r") + 32370L) and 0xffffffffL)) + (2L * r(e,"c"))) - r(e,"d")) + (r(e,"a") * 0x4f3f8e15L)) + r(e,"b")) and 0xffffffffL))
        call("response_ror32", r(e,"t"), 4L)
        s(e,"q", (r(e,"ROR_RESULT") xor r(e,"v")))
        s(e,"h", ((r(e,"r") + leaves.lookup((423L + (r(e,"q") and 255L)).toInt())) and 0xffffffffL))
        if ((r(e,"h") and 64L) != 0L) {
            call("response_ror32", r(e,"t"), 6L)
            s(e,"e", (r(e,"s") - r(e,"ROR_RESULT")))
            call("response_ror32", r(e,"q"), 14L)
            s(e,"e", ((r(e,"e") - r(e,"ROR_RESULT")) and 0xffffffffL))
            call("response_ror32", r(e,"e"), 1L)
            s(e,"f", ((((r(e,"q") + 30139L) and 0xffffffffL) xor r(e,"t")) xor ((r(e,"ROR_RESULT") + 11696L) and 0xffffffffL)))
            call("selected_wrapper", ((r(e,"f") + 0xfb306605L) and 0xffffffffL), (r(e,"f") or (r(e,"e") shl 32L.toInt())), (r(e,"h") or 0xd31306e500000000UL.toLong()))
            s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
            s(e,"e", ((((r(e,"e") - r(e,"q")) + r(e,"v")) + 0x93e4f0a5L) and 0xffffffffL))
            s(e,"q", ((r(e,"q") - r(e,"v")) and 0xffffffffL))
            call("response_ror32", r(e,"e"), 9L)
            s(e,"t", ((((r(e,"q") - 31456L) and 0xffffffffL) xor r(e,"f")) xor ((r(e,"ROR_RESULT") + 16560L) and 0xffffffffL)))
            s(e,"q", (r(e,"t") xor r(e,"q")))
            s(e,"s", (r(e,"e") xor r(e,"h")))
        }
        call("response_ror32", r(e,"q"), 23L)
        s(e,"v", (r(e,"ROR_RESULT") xor r(e,"s")))
        s(e,"u", ((r(e,"t") - r(e,"v")) and 0xffffffffL))
        call("response_ror32", r(e,"u"), 30L)
        s(e,"w", ((((r(e,"v") - 14916L) and 0xffffffffL) xor r(e,"h")) xor ((r(e,"ROR_RESULT") + 30661L) and 0xffffffffL)))
        s(e,"z", (leaves.lookup((132L + (r(e,"w") and 255L)).toInt()) xor r(e,"q")))
        s(e,"v", (r(e,"z") xor r(e,"v")))
        s(e,"w", (r(e,"w") xor ((r(e,"u") - r(e,"v")) and 0xffffffffL)))
        s(e,"z", (r(e,"w") xor r(e,"z")))
        s(e,"k", (((r(e,"v") + r(e,"z")) + 0xc334e9c7L) and 0xffffffffL))
        call("response_ror32", r(e,"k"), 31L)
        s(e,"rr", r(e,"ROR_RESULT"))
        s(e,"p", ((((r(e,"u") - (2L * r(e,"v"))) - r(e,"z")) + 0x3ccb1639L) and 0xffffffffL))
        call("response_ror32", r(e,"p"), 24L)
        s(e,"pr", r(e,"ROR_RESULT"))
        s(e,"a", (((((((2L * r(e,"z")) - r(e,"rr")) - r(e,"w")) - r(e,"pr")) + r(e,"v")) + 0xfacb6cf0L) and 0xffffffffL))
        call("response_ror32", r(e,"a"), 31L)
        s(e,"ar", r(e,"ROR_RESULT"))
        s(e,"b", (((((((r(e,"u") - r(e,"v")) + (2L * r(e,"z"))) - (2L * r(e,"rr"))) - (2L * r(e,"w"))) - (2L * r(e,"pr"))) + 0x6f2d0652L) and 0xffffffffL))
        call("response_ror32", r(e,"b"), 26L)
        s(e,"ur", r(e,"ROR_RESULT"))
        s(e,"c", leaves.lookup((195L + ((((r(e,"ar") + r(e,"ur")) + r(e,"z")) + 41L) and 255L)).toInt()))
        s(e,"state_a", ((r(e,"a") - r(e,"c")) and 0xffffffffL))
        s(e,"v", (r(e,"b") xor r(e,"state_a")))
        s(e,"state_c", ((((r(e,"ar") + r(e,"ur")) + r(e,"z")) + 932610857L) and 0xffffffffL))
        s(e,"state_d", (((((r(e,"ar") + r(e,"rr")) + r(e,"w")) + r(e,"pr")) + r(e,"ur")) and 0xffffffffL))
        s(e,"state_e", ((r(e,"v") + r(e,"state_a")) and 0xffffffffL))
        s(e,"n", ((r(e,"state_d") - r(e,"state_e")) and 0xffffffffL))
        call("response_ror32", r(e,"n"), 1L)
        s(e,"r", r(e,"ROR_RESULT"))
        s(e,"a", (((r(e,"state_a") - r(e,"state_c")) + r(e,"r")) and 0xffffffffL))
        call("response_ror32", r(e,"a"), 5L)
        s(e,"b", (r(e,"ROR_RESULT") xor r(e,"v")))
        s(e,"c", leaves.lookup((365L + (r(e,"b") and 255L)).toInt()))
        s(e,"d", ((r(e,"n") + r(e,"c")) and 0xffffffffL))
        s(e,"RECORD_INPUT_X", (r(e,"b") or (r(e,"a") shl 32L.toInt())))
        s(e,"RECORD_INPUT_Y", ((r(e,"d") shl 32L.toInt()) or (((r(e,"state_c") - r(e,"r")) and 0xffffffffL) xor ((r(e,"d") * 0x741bac9fL) and 0xffffffffL))))
    }

internal fun SpotifyPlayPlayPipeline.fn_wrapper_run(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["entry"] = 0L
        e["first"] = 0L
        e["second"] = 0L
        e["trigger1"] = 0L
        e["trigger2"] = 0L
        e["low"] = 0L
        e["high"] = 0L
        e["residual"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"entry", r(e,"1"))
        s(e,"first", r(e,"2"))
        s(e,"second", r(e,"3"))
        e["trigger1"] = 0L
        e["trigger2"] = 0L
        e["low"] = 0L
        e["high"] = 0L
        e["residual"] = 0L
        s(e,"WR_X0", r(e,"first"))
        s(e,"WR_X1", r(e,"second"))
        when (r(e,"entry")) {
            13383192L -> {
                call("reduced_cbb02c", r(e,"WR_X0"), r(e,"WR_X1"))
                g["PROTECTED_X0"] = r(e,"O0") or (r(e,"O1") shl 32)
                g["PROTECTED_X1"] = r(e,"O2") or (r(e,"O3") shl 32)
                call("reduced_cbb0b0", r(e,"PROTECTED_X0"), r(e,"PROTECTED_X1"))
                g["PROTECTED_X0"] = r(e,"O0") or (r(e,"O1") shl 32)
                g["PROTECTED_X1"] = r(e,"O2") or (r(e,"O3") shl 32)
                s(e,"WR_X0", r(e,"PROTECTED_X0"))
                s(e,"WR_X1", r(e,"PROTECTED_X1"))
            }
            13383252L -> {
                s(e,"trigger1", ((r(e,"first") shr 62L.toInt()) and 1L))
                if (r(e,"trigger1") != 0L) {
                    call("wrapper_swap", r(e,"WR_X0"))
                    call("reduced_cbb134", r(e,"SWAPPED"), r(e,"WR_X1"))
                    g["PROTECTED_X0"] = r(e,"O0") or (r(e,"O1") shl 32)
                    g["PROTECTED_X1"] = r(e,"O2") or (r(e,"O3") shl 32)
                    s(e,"WR_X0", r(e,"PROTECTED_X0"))
                    s(e,"WR_X1", r(e,"PROTECTED_X1"))
                }
                call("wrapper_repeat", 168L, 8L, r(e,"trigger1"))
                call("wrapper_leaf", 49L, r(e,"WR_X0"), r(e,"WR_X1"))
            }
            13383368L -> {
                s(e,"low", (r(e,"first") and 0xffffffffL))
                s(e,"high", ((r(e,"first") shr 32L.toInt()) and 0xffffffffL))
                s(e,"residual", r(e,"first"))
                if (((r(e,"high") shr 21L.toInt()) and 1L) != 0L) {
                    call("wrapper_swapped_leaf", 83L)
                    s(e,"high", (r(e,"WR_X0") and 0xffffffffL))
                    s(e,"low", ((r(e,"WR_X0") shr 32L.toInt()) and 0xffffffffL))
                    s(e,"residual", r(e,"low"))
                }
                if (((r(e,"low") shr 11L.toInt()) and 1L) != 0L) {
                    call("wrapper_leaf", 44L, ((r(e,"high") shl 32L.toInt()) or r(e,"low")), r(e,"WR_X1"))
                    s(e,"high", ((r(e,"WR_X0") shr 32L.toInt()) and 0xffffffffL))
                    s(e,"low", (r(e,"WR_X0") and 0xffffffffL))
                    s(e,"residual", r(e,"low"))
                }
                if (((r(e,"high") shr 31L.toInt()) and 1L) != 0L) {
                    call("wrapper_leaf", 175L, ((r(e,"low") shl 32L.toInt()) or r(e,"high")), r(e,"WR_X1"))
                    s(e,"high", (r(e,"WR_X0") and 0xffffffffL))
                    s(e,"low", ((r(e,"WR_X0") shr 32L.toInt()) and 0xffffffffL))
                    s(e,"residual", r(e,"low"))
                }
                s(e,"WR_X0", (((r(e,"low") xor r(e,"high")) + ((r(e,"WR_X1") and 0xffffffffL) xor ((r(e,"WR_X1") shr 32L.toInt()) and 0xffffffffL))) and 0xffffffffL))
                s(e,"WR_X1", r(e,"residual"))
                return
            }
            13383512L -> {
                call("wrapper_chain", 215L, 261L)
            }
            13383576L -> {
                call("wrapper_repeat", 274L, 18L, 1L)
                call("wrapper_optional_prefix", 45L, 232L)
                call("wrapper_chain", 5L)
            }
            13383684L -> {
                call("wrapper_chain", 224L)
                val pair_1694 = wrapperPair(60L, 255L, 31L, 33L)
                s(e,"trigger1",pair_1694.first); s(e,"trigger2",pair_1694.second)
            }
            13383796L -> {
                call("wrapper_optional_prefix", 58L, 94L)
                call("wrapper_chain", 166L)
            }
            13383864L -> {
                call("wrapper_repeat", 222L, 17L, 1L)
                call("wrapper_chain", 127L)
                call("wrapper_repeat", 90L, 30L, 1L, 4L)
                call("wrapper_chain", 31L)
            }
            13383984L -> {
                call("wrapper_repeat", 273L, 8L, 1L)
                call("wrapper_chain", 107L, 23L)
            }
            13384072L -> {
                call("wrapper_optional_prefix", 57L, 154L)
                call("wrapper_chain", 197L, 164L)
            }
            13384156L -> {
                call("wrapper_optional_prefix", 62L, 241L)
                call("wrapper_chain", 148L, 0L)
            }
            13384232L -> {
                call("wrapper_repeat", 43L, 18L, 1L)
                call("wrapper_chain", 62L, 79L)
            }
            13384336L -> {
                call("wrapper_repeat", 96L, 16L, 1L)
                call("wrapper_repeat", 50L, 4L, 1L)
                call("wrapper_optional_leaf", 52L, 10L)
            }
            13384492L -> {
                call("wrapper_chain", 101L, 13L)
                call("wrapper_repeat", 104L, 0L, 1L)
                call("wrapper_repeat", 117L, 29L, 1L)
            }
            13384616L -> {
                call("wrapper_optional_prefix", 52L, 29L)
                call("wrapper_chain", 14L)
                call("wrapper_repeat", 212L, 13L, 1L)
            }
            13384720L -> {
                val pair_2827 = wrapperPair(34L, 99L, 22L, 171L)
                s(e,"trigger1",pair_2827.first); s(e,"trigger2",pair_2827.second)
                call("wrapper_repeat", 219L, 12L, (if ((if (r(e,"trigger1") == 0L) 1L else 0L) != 0L || r(e,"trigger2") != 0L) 1L else 0L))
                call("wrapper_optional_leaf", 34L, 20L)
            }
            13384880L -> {
                val pair_3015 = wrapperPair(38L, 3L, 6L, 37L)
                s(e,"trigger1",pair_3015.first); s(e,"trigger2",pair_3015.second)
                s(e,"residual", r(e,"first"))
                if (r(e,"trigger2") != 0L) {
                    s(e,"residual", (r(e,"WR_X0") and 0xffffffffL))
                } else {
                    if (r(e,"trigger1") != 0L) {
                        s(e,"residual", ((r(e,"WR_X0") shr 32L.toInt()) and 0xffffffffL))
                    }
                }
                call("wrapper_fold")
                s(e,"WR_X1", r(e,"residual"))
                return
            }
            13384980L -> {
                call("wrapper_chain", 179L)
                call("wrapper_optional_prefix", 47L, 67L)
                call("wrapper_chain", 12L)
            }
            13385060L -> {
                val pair_3413 = wrapperPair(49L, 6L, 17L, 15L)
                s(e,"trigger1",pair_3413.first); s(e,"trigger2",pair_3413.second)
                call("wrapper_repeat", 167L, 30L, (if ((if (r(e,"trigger1") == 0L) 1L else 0L) != 0L || r(e,"trigger2") != 0L) 1L else 0L))
            }
            13385204L -> {
                call("wrapper_chain", 183L)
                call("wrapper_repeat", 1L, 20L, 1L)
            }
            13385284L -> {
                call("wrapper_chain", 28L)
                call("wrapper_optional_prefix", 48L, 93L)
                call("wrapper_chain", 120L)
                call("wrapper_optional_leaf", 34L, 191L)
            }
            13385392L -> {
                call("wrapper_chain", 8L, 272L)
                call("wrapper_repeat", 173L, 31L, 1L)
                call("wrapper_chain", 17L)
                call("wrapper_optional_leaf", 45L, 47L)
            }
            13385524L -> {
                call("wrapper_chain", 230L, 256L, 110L)
            }
            13385600L -> {
                call("wrapper_repeat", 118L, 6L, 1L)
                call("wrapper_repeat", 240L, 2L, 1L)
                call("wrapper_chain", 233L)
                call("wrapper_repeat", 129L, 10L, 1L)
                call("wrapper_chain", 11L)
            }
            13385764L -> {
                call("wrapper_chain", 221L)
                call("wrapper_repeat", 56L, 17L, 1L)
                call("wrapper_chain", 147L)
            }
            13385864L -> {
                call("wrapper_chain", 239L, 89L, 140L)
            }
            13385940L -> {
                call("wrapper_repeat", 236L, 4L, 1L)
                val pair_4459 = wrapperPair(53L, 209L, 8L, 24L)
                s(e,"trigger1",pair_4459.first); s(e,"trigger2",pair_4459.second)
            }
            13386068L -> {
                call("wrapper_optional_prefix", 47L, 55L)
                call("wrapper_chain", 71L)
            }
            13386136L -> {
                call("wrapper_repeat", 109L, 16L, 1L)
                call("wrapper_repeat", 211L, 20L, 1L)
                call("wrapper_repeat", 46L, 30L, 1L)
            }
            13386288L -> {
                call("wrapper_repeat", 270L, 7L, 1L)
                call("wrapper_chain", 228L)
                val pair_4840 = wrapperPair(63L, 234L, 10L, 72L)
                s(e,"trigger1",pair_4840.first); s(e,"trigger2",pair_4840.second)
            }
            13386428L -> {
                call("wrapper_chain", 262L, 32L)
            }
            13386488L -> {
                call("wrapper_chain", 251L)
                call("wrapper_optional_leaf", 41L, 106L)
            }
            13386560L -> {
                call("wrapper_chain", 204L, 207L)
            }
            13386624L -> {
                call("wrapper_chain", 139L, 27L)
                call("wrapper_optional_leaf", 63L, 180L)
            }
            13386700L -> {
                call("wrapper_chain", 70L)
                call("wrapper_repeat", 85L, 7L, 1L)
                call("wrapper_optional_leaf", 56L, 264L)
            }
            13386800L -> {
                call("wrapper_chain", 203L, 165L, 216L)
                call("wrapper_repeat", 82L, 10L, 1L)
            }
            13386920L -> {
                call("wrapper_chain", 80L, 200L)
                call("wrapper_repeat", 103L, 22L, 1L)
            }
            13387016L -> {
                call("wrapper_repeat", 86L, 29L, 1L)
                call("wrapper_repeat", 73L, 3L, 1L)
                call("wrapper_chain", 153L)
            }
            13387136L -> {
                s(e,"trigger1", ((r(e,"WR_X0") shr 47L.toInt()) and 1L))
                if (r(e,"trigger1") != 0L) {
                    call("wrapper_swapped_leaf", 111L)
                }
                call("wrapper_repeat", 4L, 29L, r(e,"trigger1"))
                call("wrapper_chain", 95L)
            }
            13387240L -> {
                call("wrapper_repeat", 41L, 26L, 1L)
                call("wrapper_optional_prefix", 50L, 92L)
                call("wrapper_chain", 145L)
                call("wrapper_optional_leaf", 33L, 74L)
            }
            13387364L -> {
                call("wrapper_chain", 57L, 68L)
                call("wrapper_optional_leaf", 40L, 156L)
            }
            13387452L -> {
                call("wrapper_chain", 69L)
                call("wrapper_repeat", 188L, 14L, 1L)
                call("wrapper_repeat", 249L, 6L, 1L)
            }
            13387564L -> {
                call("wrapper_chain", 259L)
                call("wrapper_repeat", 108L, 10L, 1L)
                call("wrapper_chain", 208L)
            }
            13387664L -> {
                call("wrapper_repeat", 195L, 7L, 1L)
                call("wrapper_chain", 192L)
                call("wrapper_repeat", 124L, 17L, 1L)
            }
            13387792L -> {
                call("wrapper_optional_prefix", 33L, 48L)
                call("wrapper_chain", 227L)
                call("wrapper_optional_leaf", 40L, 115L)
            }
            13387888L -> {
                call("wrapper_optional_prefix", 52L, 75L)
                call("wrapper_chain", 187L, 119L, 238L)
            }
            13387988L -> {
                call("wrapper_repeat", 114L, 12L, 1L)
                call("wrapper_repeat", 105L, 2L, 1L)
            }
            13388120L -> {
                call("wrapper_optional_prefix", 50L, 223L)
                call("wrapper_chain", 198L)
            }
            13388172L -> {
                call("wrapper_chain", 231L, 268L)
                call("wrapper_repeat", 63L, 5L, 1L)
            }
            13388268L -> {
                call("wrapper_optional_prefix", 45L, 45L)
                call("wrapper_chain", 162L, 26L)
            }
            13388352L -> {
                call("wrapper_chain", 52L, 87L, 132L)
            }
            13388428L -> {
                call("wrapper_chain", 9L, 18L)
            }
            13388492L -> {
                call("wrapper_repeat", 237L, 1L, 1L)
                call("wrapper_optional_leaf", 44L, 152L)
            }
            13388580L -> {
                s(e,"trigger1", ((r(e,"WR_X0") shr 56L.toInt()) and 1L))
                if (r(e,"trigger1") != 0L) {
                    call("wrapper_swapped_leaf", 102L)
                }
                call("wrapper_repeat", 253L, 24L, r(e,"trigger1"))
                call("wrapper_optional_leaf", 37L, 265L)
            }
            13388696L -> {
                call("wrapper_chain", 53L)
                call("wrapper_optional_leaf", 51L, 100L)
            }
            13388768L -> {
                call("wrapper_chain", 39L, 246L, 190L)
            }
            13388848L -> {
                call("wrapper_repeat", 170L, 0L, 1L)
                call("wrapper_chain", 138L)
            }
            13388928L -> {
                call("wrapper_repeat", 189L, 5L, 1L)
                call("wrapper_chain", 134L, 38L)
            }
            13389024L -> {
                call("wrapper_chain", 185L)
                call("wrapper_optional_leaf", 58L, 64L)
            }
            13389096L -> {
                call("wrapper_chain", 137L)
                call("wrapper_repeat", 128L, 30L, 1L)
            }
            13389172L -> {
                call("wrapper_chain", 98L, 60L, 77L)
                call("wrapper_optional_prefix", 43L, 21L)
                call("wrapper_chain", 213L)
            }
            13389284L -> {
                call("wrapper_repeat", 243L, 12L, 1L)
                s(e,"trigger1", ((r(e,"WR_X0") shr 58L.toInt()) and 1L))
                if (r(e,"trigger1") != 0L) {
                    call("wrapper_swapped_leaf", 247L)
                }
                call("wrapper_repeat", 40L, 27L, r(e,"trigger1"))
                call("wrapper_chain", 163L, 201L)
            }
            13389432L -> {
                call("wrapper_repeat", 177L, 30L, 1L)
                call("wrapper_optional_prefix", 58L, 218L)
                call("wrapper_chain", 66L)
            }
            13389532L -> {
                call("wrapper_chain", 25L, 202L)
            }
        }
        call("wrapper_fold")
    }

internal fun SpotifyPlayPlayPipeline.fn_response_cc1558_complete(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x"] = 0L
        e["y"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["t"] = 0L
        e["u"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"x", r(e,"1"))
        s(e,"y", r(e,"2"))
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["t"] = 0L
        e["u"] = 0L
        s(e,"a", ((r(e,"x") shr 32L.toInt()) and 0xffffffffL))
        s(e,"b", (((r(e,"y") shr 32L.toInt()) - r(e,"x")) and 0xffffffffL))
        s(e,"c", ((r(e,"y") - r(e,"b")) and 0xffffffffL))
        s(e,"t", ((r(e,"a") - r(e,"c")) and 0xffffffffL))
        s(e,"d", (((((r(e,"t") shr 13L.toInt()) or (r(e,"t") shl 19L.toInt())) + r(e,"c")) xor r(e,"x")) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"d") + 675191532L) and 0xffffffffL), ((r(e,"t") shl 32L.toInt()) or r(e,"d")), (0xe8a1d80c00000000UL.toLong() or r(e,"c")))
        s(e,"t", (r(e,"WR_X0") xor r(e,"b")))
        s(e,"u", ((((r(e,"d") shr 10L.toInt()) or (r(e,"d") shl 22L.toInt())) + ((r(e,"t") shr 12L.toInt()) or (r(e,"t") shl 20L.toInt()))) and 0xffffffffL))
        s(e,"a", ((r(e,"a") - r(e,"u")) and 0xffffffffL))
        s(e,"u", ((r(e,"c") - r(e,"u")) and 0xffffffffL))
        s(e,"b", (((r(e,"a") * 0x3e249231L) + r(e,"d")) and 0xffffffffL))
        s(e,"c", ((r(e,"t") - r(e,"b")) and 0xffffffffL))
        s(e,"d", (r(e,"c") xor r(e,"u")))
        call("selected_wrapper", ((r(e,"d") + 912666244L) and 0xffffffffL), ((r(e,"c") shl 32L.toInt()) or r(e,"d")), (0x650b4d1c00000000L or r(e,"b")))
        s(e,"t", ((r(e,"a") - r(e,"WR_X0")) and 0xffffffffL))
        s(e,"u", ((r(e,"b") + r(e,"t")) and 0xffffffffL))
        s(e,"c", ((r(e,"c") - r(e,"u")) and 0xffffffffL))
        s(e,"d", (((r(e,"c") + r(e,"d")) + 415868188L) and 0xffffffffL))
        s(e,"t", (((r(e,"t") - ((r(e,"c") shr 6L.toInt()) or (r(e,"c") shl 26L.toInt()))) - ((r(e,"d") shr 28L.toInt()) or (r(e,"d") shl 4L.toInt()))) and 0xffffffffL))
        s(e,"CC_RESULT_X0", ((r(e,"t") shl 32L.toInt()) or ((r(e,"t") + r(e,"u")) and 0xffffffffL)))
        s(e,"CC_RESULT_X1", ((r(e,"c") shl 32L.toInt()) or r(e,"d")))
    }

internal fun SpotifyPlayPlayPipeline.fn_nested_cbf5e0_complete(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w13"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["1"] = args.getOrElse(0) { 0L }
        e["2"] = args.getOrElse(1) { 0L }
        e["3"] = args.getOrElse(2) { 0L }
        e["4"] = args.getOrElse(3) { 0L }
        e["5"] = args.getOrElse(4) { 0L }
        e["6"] = args.getOrElse(5) { 0L }
        e["7"] = args.getOrElse(6) { 0L }
        e["8"] = args.getOrElse(7) { 0L }
        e["9"] = args.getOrElse(8) { 0L }
        e["10"] = args.getOrElse(9) { 0L }
        e["11"] = args.getOrElse(10) { 0L }
        e["12"] = args.getOrElse(11) { 0L }
        e["13"] = args.getOrElse(12) { 0L }
        e["14"] = args.getOrElse(13) { 0L }
        s(e,"x1", r(e,"1"))
        s(e,"x2", r(e,"2"))
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w13"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w9", ((r(e,"d") - r(e,"b")) and 0xffffffffL))
        call("response_table32", 0x2ccL, (r(e,"w9") + 157L))
        s(e,"w10", ((r(e,"TABLE_RESULT") + r(e,"c")) and 0xffffffffL))
        s(e,"w8", ((r(e,"b") + 0x80e1463L) and 0xffffffffL))
        s(e,"w11", (((r(e,"w8") - r(e,"w9")) + 549781394L) and 0xffffffffL))
        s(e,"w9", (((r(e,"w8") - r(e,"w9")) - r(e,"w10")) and 0xffffffffL))
        s(e,"w13", ((r(e,"w10") + 684921845L) and 0xffffffffL))
        s(e,"w8", (((r(e,"w13") + r(e,"w10")) - r(e,"w8")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w8") + r(e,"w9")) and 0xffffffffL))
        call("response_ror32", r(e,"w11"), 8L)
        s(e,"w8", ((r(e,"w8") - r(e,"ROR_RESULT")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w8")) and 0xffffffffL))
        call("response_ror32", r(e,"w9"), 23L)
        s(e,"w21", ((r(e,"w11") - r(e,"ROR_RESULT")) and 0xffffffffL))
        s(e,"w22", ((r(e,"w21") + r(e,"w8")) and 0xffffffffL))
        s(e,"w23", (r(e,"w22") xor r(e,"w9")))
        call("selected_wrapper", ((r(e,"w23") + 0xdfd974f7L) and 0xffffffffL), ((r(e,"w22") shl 32L.toInt()) or r(e,"w23")), (0x4179e55e00000000L or r(e,"a")))
        s(e,"w9", ((r(e,"w21") - (r(e,"WR_X0") and 0xffffffffL)) and 0xffffffffL))
        s(e,"w10", (r(e,"w22") xor r(e,"w9")))
        call("response_table32", 0x7a4L, r(e,"w10"))
        s(e,"w8", ((r(e,"w23") - r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w8")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") - r(e,"w9")) and 0xffffffffL))
        s(e,"w9", (r(e,"w9") xor r(e,"a")))
        call("response_ror32", r(e,"w10"), 21L)
        s(e,"w8", ((r(e,"ROR_RESULT") + r(e,"w8")) and 0xffffffffL))
        s(e,"NEST_X0", ((r(e,"w8") shl 32L.toInt()) or r(e,"a")))
        s(e,"NEST_X1", ((r(e,"w9") shl 32L.toInt()) or r(e,"w10")))
    }
