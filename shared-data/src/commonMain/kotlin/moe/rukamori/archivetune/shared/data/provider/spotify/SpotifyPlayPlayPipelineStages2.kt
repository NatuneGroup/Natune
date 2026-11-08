package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayPipeline.fn_nested_cbf0d8_complete(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["r1"] = 0L
        e["r2"] = 0L
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
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["r1"] = 0L
        e["r2"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w21", (((r(e,"d") - r(e,"a")) + 0x42ef069cL) and 0xffffffffL))
        call("response_ror32", r(e,"w21"), 9L)
        s(e,"r1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"a"), 11L)
        s(e,"r2", r(e,"ROR_RESULT"))
        s(e,"w22", (((r(e,"r1") + r(e,"r2")) and 0xffffffffL) xor r(e,"c")))
        s(e,"w8", ((r(e,"b") + r(e,"w22")) and 0xffffffffL))
        s(e,"w23", ((r(e,"w8") - 0x49595569L) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w8") + 0xa9cafcc6L) and 0xffffffffL), ((r(e,"w22") shl 32L.toInt()) or r(e,"w23")), ((-0x75dd8c5100000000L) or r(e,"w21")))
        s(e,"w8", ((r(e,"WR_X0") and 0xffffffffL) xor r(e,"a")))
        s(e,"w9", ((r(e,"w8") + r(e,"w21")) and 0xffffffffL))
        s(e,"w10", (r(e,"w9") xor r(e,"w22")))
        s(e,"w11", ((r(e,"w10") + r(e,"w23")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w11") + r(e,"w8")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w8") + r(e,"w9")) and 0xffffffffL))
        s(e,"w10", (((r(e,"w10") - r(e,"w8")) - r(e,"w9")) and 0xffffffffL))
        call("response_ror32", r(e,"w10"), 5L)
        s(e,"w12", (((r(e,"ROR_RESULT") + 23741L) and 0xffffffffL) xor ((r(e,"w9") + 8271L) and 0xffffffffL)))
        s(e,"w11", ((r(e,"w11") - r(e,"w12")) and 0xffffffffL))
        s(e,"w8", (r(e,"w8") xor r(e,"w11")))
        call("response_table32", 0x7ccL, r(e,"w8"))
        s(e,"w9", ((r(e,"w9") + r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") - r(e,"w9")) and 0xffffffffL))
        s(e,"w11", ((r(e,"w11") + (r(e,"w10") * 0x809436ebL)) and 0xffffffffL))
        s(e,"w8", (((r(e,"w8") - r(e,"w11")) + 0x947ae2b7L) and 0xffffffffL))
        s(e,"NEST_X0", ((r(e,"w11") shl 32L.toInt()) or r(e,"w8")))
        s(e,"NEST_X1", ((r(e,"w9") shl 32L.toInt()) or r(e,"w10")))
    }

internal fun SpotifyPlayPlayPipeline.fn_nested_cc1cac_complete(args:LongArray) {
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
        e["w12"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["lookup"] = 0L
        e["r1"] = 0L
        e["r2"] = 0L
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
        e["w12"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["lookup"] = 0L
        e["r1"] = 0L
        e["r2"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        call("response_ror32", r(e,"a"), 5L)
        s(e,"w8", ((r(e,"d") - r(e,"ROR_RESULT")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w8") + r(e,"c")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w9") + r(e,"b")) and 0xffffffffL))
        call("response_ror32", r(e,"w10"), 6L)
        s(e,"w20", ((r(e,"a") - r(e,"ROR_RESULT")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - r(e,"w20")) and 0xffffffffL))
        s(e,"w21", ((r(e,"w8") + 0xeac46f06L) and 0xffffffffL))
        call("response_table32", 0x4f4L, r(e,"w21"))
        s(e,"w22", (r(e,"TABLE_RESULT") xor r(e,"w9")))
        s(e,"w23", ((r(e,"w10") - r(e,"w22")) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w23") + 0x577a834dL) and 0xffffffffL), ((r(e,"w22") shl 32L.toInt()) or r(e,"w23")), ((-0x5bb3b8af00000000L) or r(e,"w21")))
        s(e,"w8", ((r(e,"w21") - r(e,"w23")) and 0xffffffffL))
        s(e,"w9", ((r(e,"WR_X0") and 0xffffffffL) xor r(e,"w20")))
        s(e,"w8", ((r(e,"w8") - r(e,"w9")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w22") - r(e,"w8")) and 0xffffffffL))
        s(e,"w11", (((r(e,"w10") * 0x48529b6bL) and 0xffffffffL) xor r(e,"w23")))
        call("response_ror32", r(e,"w11"), 29L)
        s(e,"r1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"w10"), 1L)
        s(e,"r2", r(e,"ROR_RESULT"))
        s(e,"w10", ((r(e,"r1") + r(e,"r2")) and 0xffffffffL))
        s(e,"w9", (r(e,"w9") xor r(e,"w10")))
        call("response_table32", 0x1fcL, r(e,"w9"))
        s(e,"lookup", r(e,"TABLE_RESULT"))
        s(e,"w12", ((r(e,"lookup") + r(e,"w22")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") + r(e,"lookup")) and 0xffffffffL))
        s(e,"w11", ((r(e,"w11") - r(e,"w12")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w11")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - r(e,"w9")) and 0xffffffffL))
        call("response_ror32", r(e,"w8"), 18L)
        s(e,"r1", ((r(e,"ROR_RESULT") - 18135L) and 0xffffffffL))
        s(e,"r2", ((r(e,"w9") - 9080L) and 0xffffffffL))
        s(e,"w10", ((r(e,"w12") - (r(e,"r1") xor r(e,"r2"))) and 0xffffffffL))
        call("response_ror32", r(e,"w10"), 19L)
        s(e,"r1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"w8"), 7L)
        s(e,"r2", r(e,"ROR_RESULT"))
        s(e,"w11", (r(e,"w11") xor ((r(e,"r1") + r(e,"r2")) and 0xffffffffL)))
        s(e,"w9", (r(e,"w9") xor ((r(e,"w11") + r(e,"w10")) and 0xffffffffL)))
        call("response_ror32", r(e,"w11"), 16L)
        s(e,"r1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"w9"), 20L)
        s(e,"r2", r(e,"ROR_RESULT"))
        s(e,"w8", (((r(e,"w8") - r(e,"r1")) - r(e,"r2")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w8") + r(e,"w10")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w10")) and 0xffffffffL))
        call("response_ror32", r(e,"w10"), 16L)
        s(e,"r1", ((r(e,"ROR_RESULT") + 4399L) and 0xffffffffL))
        s(e,"r2", ((r(e,"w8") - 30881L) and 0xffffffffL))
        s(e,"w11", ((r(e,"w11") + (r(e,"r1") xor r(e,"r2"))) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w11")) and 0xffffffffL))
        s(e,"NEST_X0", ((r(e,"w11") shl 32L.toInt()) or r(e,"w9")))
        s(e,"NEST_X1", ((r(e,"w8") shl 32L.toInt()) or r(e,"w10")))
    }

internal fun SpotifyPlayPlayPipeline.fn_response_mixing_extended(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["hex"] = 0L
        e["first"] = 0L
        e["second"] = 0L
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
        s(e,"hex", r(e,"1"))
        e["first"] = 0L
        e["second"] = 0L
        responseMixingEntry()
        call("nested_cbf0d8_complete", r(e,"MIX_NEXT_X1"), r(e,"MIX_NEXT_X2"))
        s(e,"first", r(e,"NEST_X0"))
        s(e,"second", r(e,"NEST_X1"))
        if ((r(e,"first") and (1L shl 11L.toInt())) != 0L) {
            call("wrapper_leaf", 158L, r(e,"first"), r(e,"second"))
            s(e,"first", r(e,"WR_X0"))
            s(e,"second", r(e,"WR_X1"))
        }
        call("wrapper_swap", r(e,"first"))
        call("wrapper_leaf", 257L, r(e,"SWAPPED"), r(e,"second"))
        call("nested_cc1cac_complete", r(e,"WR_X0"), r(e,"WR_X1"))
        s(e,"MIX_NEXT_SLOT", 84L)
        s(e,"MIX_NEXT_X1", r(e,"NEST_X0"))
        s(e,"MIX_NEXT_X2", r(e,"NEST_X1"))
    }

internal fun SpotifyPlayPlayPipeline.fn_response_mixing_preexception(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["hex"] = 0L
        e["first"] = 0L
        e["second"] = 0L
        e["count"] = 0L
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
        s(e,"hex", r(e,"1"))
        e["first"] = 0L
        e["second"] = 0L
        e["count"] = 0L
        call("response_mixing_extended", r(e,"hex"))
        s(e,"first", r(e,"MIX_NEXT_X1"))
        s(e,"second", r(e,"MIX_NEXT_X2"))
        s(e,"count", 0L)
        while ((if (r(e,"count") < 8L) 1L else 0L) != 0L) {
            call("nested_cbf5e0_complete", r(e,"first"), r(e,"second"))
            s(e,"first", r(e,"NEST_X0"))
            s(e,"second", r(e,"NEST_X1"))
            if ((if (r(e,"count") >= ((r(e,"first") shr 26L.toInt()) and 7L)) 1L else 0L) != 0L) {
                break
            }
            s(e,"count",r(e,"count") + 1L)
        }
        (if (r(e,"count") < 8L) 1L else 0L)
        call("wrapper_swap", r(e,"first"))
        s(e,"MIX_NEXT_SLOT", 194L)
        s(e,"MIX_NEXT_X1", r(e,"SWAPPED"))
        s(e,"MIX_NEXT_X2", r(e,"second"))
    }

internal fun SpotifyPlayPlayPipeline.fn_throw_cbe868(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["v"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
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
        e["v"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w8", (leaves.lookup((268L + (r(e,"a") and 255L)).toInt()) xor r(e,"d")))
        s(e,"w10", (((r(e,"c") - (((r(e,"a") shr 16L.toInt()) or (r(e,"a") shl 16L.toInt())) and 0xffffffffL)) - (((r(e,"w8") shr 2L.toInt()) or (r(e,"w8") shl 30L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w11", (r(e,"w10") xor r(e,"b")))
        s(e,"w20", (((r(e,"w11") * 0x7cb83902L) and 0xffffffffL) xor r(e,"a")))
        s(e,"w21", ((r(e,"w8") - r(e,"w20")) and 0xffffffffL))
        s(e,"w22", (((r(e,"w10") + 0xaa5839f5L) + r(e,"w21")) and 0xffffffffL))
        s(e,"w23", ((r(e,"w11") - r(e,"w22")) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w23") + 0xf0368075L) and 0xffffffffL), (r(e,"w23") or (r(e,"w22") shl 32L.toInt())), (r(e,"w21") or 0x25d0a3b000000000L))
        s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
        s(e,"w20", ((r(e,"w20") + r(e,"v")) and 0xffffffffL))
        s(e,"w21", ((r(e,"w21") + (r(e,"w20") * 0xcb3ec842L)) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w21") + 0x64b115a1L) and 0xffffffffL), (r(e,"w21") or (r(e,"w20") shl 32L.toInt())), (r(e,"w23") or 0xbbf8180100000000UL.toLong()))
        s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
        s(e,"w9", ((r(e,"w22") + r(e,"v")) and 0xffffffffL))
        s(e,"w10", (((r(e,"w9") + r(e,"w21")) and 0xffffffffL) xor r(e,"w23")))
        s(e,"w8", ((r(e,"w20") + (r(e,"w10") * 0x6ecec79cL)) and 0xffffffffL))
        s(e,"w20", ((r(e,"w21") - r(e,"w8")) and 0xffffffffL))
        s(e,"w19", ((r(e,"w9") - leaves.lookup((497L + (r(e,"w20") and 255L)).toInt())) and 0xffffffffL))
        s(e,"w21", (r(e,"w10") xor (((r(e,"w19") shr 23L.toInt()) or (r(e,"w19") shl 9L.toInt())) and 0xffffffffL)))
        s(e,"w22", (r(e,"w8") xor ((r(e,"w21") + 0xf91db811L) and 0xffffffffL)))
        s(e,"EX0", r(e,"w22"))
        s(e,"EX1", (r(e,"w21") and 0xffffffffL))
        s(e,"EX2", r(e,"w19"))
        s(e,"EX3", r(e,"w20"))
    }

internal fun SpotifyPlayPlayPipeline.fn_throw_cc01a8(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["v"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
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
        e["v"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w8", (((r(e,"a") + 0x73cf39e8L) and 0xffffffffL) xor r(e,"d")))
        s(e,"w9", ((r(e,"w8") + r(e,"c")) and 0xffffffffL))
        s(e,"w11", ((leaves.lookup((244L + (r(e,"w9") and 255L)).toInt()) + r(e,"b")) and 0xffffffffL))
        s(e,"w12", (leaves.lookup((195L + (r(e,"w11") and 255L)).toInt()) xor r(e,"a")))
        s(e,"w8", (r(e,"w8") xor r(e,"w12")))
        s(e,"w9", (r(e,"w9") xor r(e,"w8")))
        s(e,"w11", (r(e,"w11") xor ((r(e,"w9") + r(e,"w8")) and 0xffffffffL)))
        s(e,"w12", (r(e,"w12") xor (((r(e,"w11") shr 13L.toInt()) or (r(e,"w11") shl 19L.toInt())) and 0xffffffffL)))
        s(e,"w9", ((r(e,"w9") + r(e,"w12")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - leaves.lookup((299L + (r(e,"w12") and 255L)).toInt())) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") + r(e,"w8")) and 0xffffffffL))
        s(e,"w11", ((r(e,"w9") + r(e,"w11")) and 0xffffffffL))
        s(e,"w12", (((r(e,"w12") - (((r(e,"w9") shr 7L.toInt()) or (r(e,"w9") shl 25L.toInt())) and 0xffffffffL)) - (((r(e,"w11") shr 19L.toInt()) or (r(e,"w11") shl 13L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - r(e,"w12")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w8") + r(e,"w9")) and 0xffffffffL))
        s(e,"w11", (((r(e,"w8") + r(e,"w11")) + r(e,"w9")) and 0xffffffffL))
        s(e,"w12", (r(e,"w12") xor ((r(e,"w11") + 559643004L) and 0xffffffffL)))
        s(e,"w9", ((r(e,"w12") + r(e,"w9")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - (((r(e,"w12") shr 6L.toInt()) or (r(e,"w12") shl 26L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") + r(e,"w8")) and 0xffffffffL))
        s(e,"w11", ((r(e,"w9") + r(e,"w11")) and 0xffffffffL))
        s(e,"w12", (r(e,"w12") xor ((r(e,"w11") + 0xe0586060L) and 0xffffffffL)))
        s(e,"w19", ((r(e,"w8") - r(e,"w12")) and 0xffffffffL))
        s(e,"w20", ((r(e,"w9") - (((r(e,"w19") shr 28L.toInt()) or (r(e,"w19") shl 4L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w21", ((r(e,"w11") - leaves.lookup((380L + (r(e,"w20") and 255L)).toInt())) and 0xffffffffL))
        s(e,"w22", ((r(e,"w12") - r(e,"w21")) and 0xffffffffL))
        s(e,"EX0", r(e,"w22"))
        s(e,"EX1", r(e,"w21"))
        s(e,"EX2", r(e,"w20"))
        s(e,"EX3", r(e,"w19"))
    }

internal fun SpotifyPlayPlayPipeline.fn_throw_cc05f0(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["v"] = 0L
        e["w11"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w24"] = 0L
        e["w8"] = 0L
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
        e["v"] = 0L
        e["w11"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w24"] = 0L
        e["w8"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w8", (r(e,"a") xor r(e,"d")))
        s(e,"w20", (leaves.lookup((193L + (r(e,"w8") and 255L)).toInt()) xor r(e,"c")))
        s(e,"w11", (((r(e,"b") - r(e,"w8")) - r(e,"w20")) and 0xffffffffL))
        s(e,"w21", ((r(e,"w11") + r(e,"w20")) and 0xffffffffL))
        s(e,"w11", (r(e,"w11") xor r(e,"a")))
        s(e,"w23", ((r(e,"w8") - r(e,"w11")) and 0xffffffffL))
        s(e,"w22", ((r(e,"w11") - leaves.lookup((23L + (r(e,"w21") and 255L)).toInt())) and 0xffffffffL))
        s(e,"w24", (((r(e,"w22") + 0x49f10213L) and 0xffffffffL) xor r(e,"w23")))
        call("selected_wrapper", ((r(e,"w24") + 0x3d105af3L) and 0xffffffffL), (r(e,"w24") or (r(e,"w22") shl 32L.toInt())), (r(e,"w21") or 0xbbb7393600000000UL.toLong()))
        s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
        s(e,"w20", (((r(e,"w20") - r(e,"w23")) - r(e,"v")) and 0xffffffffL))
        s(e,"w19", (leaves.lookup((382L + (r(e,"w20") and 255L)).toInt()) xor r(e,"w21")))
        s(e,"EX0", ((r(e,"w22") - r(e,"w19")) and 0xffffffffL))
        s(e,"EX1", (r(e,"w19") and 0xffffffffL))
        s(e,"EX2", r(e,"w20"))
        s(e,"EX3", (r(e,"w24") and 0xffffffffL))
    }

internal fun SpotifyPlayPlayPipeline.fn_throw_cbe7a8(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["v"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
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
        e["v"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w9", ((r(e,"d") + r(e,"b")) and 0xffffffffL))
        s(e,"w10", ((r(e,"c") - (((r(e,"w9") shr 2L.toInt()) or (r(e,"w9") shl 30L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w8", ((r(e,"w10") + r(e,"b")) and 0xffffffffL))
        s(e,"w9", (r(e,"w9") xor leaves.lookup((162L + (r(e,"w8") and 255L)).toInt())))
        s(e,"w10", ((r(e,"w10") + (r(e,"w9") * 0x95355a9fL)) and 0xffffffffL))
        s(e,"w11", ((r(e,"w10") + r(e,"w9")) and 0xffffffffL))
        s(e,"w8", (r(e,"w11") xor r(e,"w8")))
        s(e,"w9", (r(e,"w9") xor r(e,"w8")))
        s(e,"w10", ((r(e,"w10") + r(e,"w9")) and 0xffffffffL))
        s(e,"w11", ((((((r(e,"w10") shr 16L.toInt()) or (r(e,"w10") shl 16L.toInt())) and 0xffffffffL) + 8107L) xor (r(e,"w9") - 2874L)) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") + r(e,"w11")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w8")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") + 0x861773eeL) and 0xffffffffL))
        s(e,"w22", (r(e,"w9") xor r(e,"a")))
        s(e,"w20", ((r(e,"w10") + r(e,"w9")) and 0xffffffffL))
        s(e,"w21", ((r(e,"w8") - r(e,"w20")) and 0xffffffffL))
        s(e,"EX0", r(e,"a"))
        s(e,"EX1", r(e,"w21"))
        s(e,"EX2", r(e,"w20"))
        s(e,"EX3", r(e,"w22"))
    }

internal fun SpotifyPlayPlayPipeline.fn_throw_cbc930(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["v"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["lookup"] = 0L
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
        e["v"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w19"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["lookup"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w8", (r(e,"d") xor r(e,"a")))
        s(e,"w9", (r(e,"w8") xor r(e,"c")))
        s(e,"w10", ((r(e,"b") - (((r(e,"w9") shr 28L.toInt()) or (r(e,"w9") shl 4L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w11", ((r(e,"a") - (((r(e,"w10") shr 19L.toInt()) or (r(e,"w10") shl 13L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w12", ((((((r(e,"w11") shr 21L.toInt()) or (r(e,"w11") shl 11L.toInt())) and 0xffffffffL) - 6739L) xor (r(e,"w10") - 6863L)) and 0xffffffffL))
        s(e,"w8", ((r(e,"w12") + r(e,"w8")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") + 0xd67799e9L) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - (((r(e,"w8") shr 28L.toInt()) or (r(e,"w8") shl 4L.toInt())) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w11", ((r(e,"w11") - leaves.lookup((176L + ((r(e,"w10") - r(e,"w9")) and 255L)).toInt())) and 0xffffffffL))
        s(e,"w8", (r(e,"w8") xor leaves.lookup((425L + (r(e,"w11") and 255L)).toInt())))
        s(e,"lookup", leaves.lookup((378L + (r(e,"w8") and 255L)).toInt()))
        s(e,"w10", ((r(e,"w10") + r(e,"lookup")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") + r(e,"lookup")) and 0xffffffffL))
        s(e,"w11", ((r(e,"w11") + ((((((r(e,"w10") shr 20L.toInt()) or (r(e,"w10") shl 12L.toInt())) and 0xffffffffL) - 30566L) xor (r(e,"w9") - 18716L)) and 0xffffffffL)) and 0xffffffffL))
        s(e,"w20", ((r(e,"w11") + r(e,"w8")) and 0xffffffffL))
        s(e,"w21", ((r(e,"w9") - r(e,"w20")) and 0xffffffffL))
        s(e,"w22", ((((((((r(e,"w21") shr 4L.toInt()) or (r(e,"w21") shl 28L.toInt())) and 0xffffffffL) - 25677L) xor (r(e,"w20") + 8679L)) and 0xffffffffL) + r(e,"w10")) and 0xffffffffL))
        s(e,"w23", (((r(e,"w22") * 679256774L) and 0xffffffffL) xor r(e,"w11")))
        call("selected_wrapper", ((r(e,"w23") + 75544404L) and 0xffffffffL), ((r(e,"w22") shl 32L.toInt()) or r(e,"w23")), (0xfce2632500000000UL.toLong() or r(e,"w21")))
        s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
        s(e,"w9", ((r(e,"w20") + r(e,"v")) and 0xffffffffL))
        s(e,"w10", (r(e,"w21") xor (((r(e,"w9") shr 19L.toInt()) or (r(e,"w9") shl 13L.toInt())) and 0xffffffffL)))
        s(e,"w11", (r(e,"w22") xor r(e,"w10")))
        s(e,"w12", (r(e,"w23") xor leaves.lookup((163L + (r(e,"w11") and 255L)).toInt())))
        s(e,"w19", ((r(e,"w9") - leaves.lookup((63L + (r(e,"w12") and 255L)).toInt())) and 0xffffffffL))
        s(e,"w20", (((r(e,"w12") + r(e,"w19")) and 0xffffffffL) xor r(e,"w10")))
        s(e,"w21", (((((((((r(e,"w20") and 0xffffffffL) shr 2L.toInt()) or ((r(e,"w20") and 0xffffffffL) shl 30L.toInt())) and 0xffffffffL) + 13730L) xor (r(e,"w19") - 29897L)) and 0xffffffffL) + r(e,"w11")) and 0xffffffffL))
        s(e,"w22", (r(e,"w21") xor r(e,"w12")))
        s(e,"EX0", (r(e,"w22") and 0xffffffffL))
        s(e,"EX1", r(e,"w21"))
        s(e,"EX2", (r(e,"w20") and 0xffffffffL))
        s(e,"EX3", r(e,"w19"))
    }

internal fun SpotifyPlayPlayPipeline.fn_response_slot116(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w21"] = 0L
        e["w23"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w8"] = 0L
        e["v"] = 0L
        e["rot1"] = 0L
        e["rot2"] = 0L
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
        e["w21"] = 0L
        e["w23"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w8"] = 0L
        e["v"] = 0L
        e["rot1"] = 0L
        e["rot2"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        s(e,"w21", (r(e,"b") xor r(e,"d")))
        call("response_ror32", r(e,"w21"), 16L)
        s(e,"w23", ((r(e,"c") - (((r(e,"ROR_RESULT") - 7387L) and 0xffffffffL) xor ((r(e,"b") + 24654L) and 0xffffffffL))) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w23") + 0xefbe22fbL) and 0xffffffffL), (r(e,"w23") or (r(e,"w21") shl 32L.toInt())), (r(e,"a") or 0x12f7a8a400000000L))
        s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
        s(e,"w9", ((r(e,"v") + r(e,"b")) and 0xffffffffL))
        call("response_ror32", r(e,"w9"), 29L)
        s(e,"w10", ((r(e,"w21") - r(e,"ROR_RESULT")) and 0xffffffffL))
        s(e,"w8", (((r(e,"w23") + 0xe502eb50L) + r(e,"w10")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") + 890795887L) and 0xffffffffL))
        s(e,"w9", ((r(e,"w8") + r(e,"w9")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") + r(e,"w9")) and 0xffffffffL))
        s(e,"w8", ((r(e,"w8") - r(e,"w10")) and 0xffffffffL))
        call("response_ror32", r(e,"w8"), 5L)
        s(e,"w9", (r(e,"w9") xor r(e,"ROR_RESULT")))
        call("response_table32", 8L, r(e,"w9"))
        s(e,"w10", (r(e,"w10") xor r(e,"TABLE_RESULT")))
        call("response_table32", 0x17cL, r(e,"w10"))
        s(e,"w8", ((r(e,"w8") + r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w8")) and 0xffffffffL))
        s(e,"w10", (r(e,"w10") xor ((r(e,"w9") + 0xea595973L) and 0xffffffffL)))
        s(e,"w8", (r(e,"w8") xor ((r(e,"w10") + 0xc7506af7L) and 0xffffffffL)))
        call("response_ror32", r(e,"w10"), 14L)
        s(e,"rot1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"w8"), 14L)
        s(e,"rot2", r(e,"ROR_RESULT"))
        s(e,"w9", (((r(e,"w9") - r(e,"rot1")) - r(e,"rot2")) and 0xffffffffL))
        s(e,"w10", (((r(e,"w8") + r(e,"w10")) + r(e,"w9")) and 0xffffffffL))
        s(e,"w8", (r(e,"w8") xor ((r(e,"w10") + 0x8aca6d5L) and 0xffffffffL)))
        s(e,"w10", (r(e,"w10") xor r(e,"a")))
        call("response_table32", 0x6e0L, r(e,"w8"))
        s(e,"w9", ((r(e,"w9") + r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"LAST_X0", (r(e,"a") or (r(e,"w9") shl 32L.toInt())))
        s(e,"LAST_X1", (r(e,"w8") or (r(e,"w10") shl 32L.toInt())))
    }
