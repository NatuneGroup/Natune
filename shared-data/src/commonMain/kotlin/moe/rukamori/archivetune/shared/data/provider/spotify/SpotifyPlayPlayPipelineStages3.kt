package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.

internal fun SpotifyPlayPlayPipeline.fn_response_slot150(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["x1"] = 0L
        e["x2"] = 0L
        e["a"] = 0L
        e["b"] = 0L
        e["c"] = 0L
        e["d"] = 0L
        e["w23"] = 0L
        e["w9"] = 0L
        e["w8"] = 0L
        e["w12"] = 0L
        e["w10"] = 0L
        e["w13"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w11"] = 0L
        e["lookup_index"] = 0L
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
        e["w23"] = 0L
        e["w9"] = 0L
        e["w8"] = 0L
        e["w12"] = 0L
        e["w10"] = 0L
        e["w13"] = 0L
        e["w20"] = 0L
        e["w21"] = 0L
        e["w22"] = 0L
        e["w11"] = 0L
        e["lookup_index"] = 0L
        e["rot1"] = 0L
        e["rot2"] = 0L
        s(e,"a", (r(e,"x1") and 0xffffffffL))
        s(e,"b", ((r(e,"x1") shr 32L.toInt()) and 0xffffffffL))
        s(e,"c", (r(e,"x2") and 0xffffffffL))
        s(e,"d", ((r(e,"x2") shr 32L.toInt()) and 0xffffffffL))
        call("response_ror32", r(e,"a"), 9L)
        s(e,"rot1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"b"), 20L)
        s(e,"rot2", r(e,"ROR_RESULT"))
        s(e,"w23", (((r(e,"d") - r(e,"rot1")) - r(e,"rot2")) and 0xffffffffL))
        call("selected_wrapper", ((r(e,"w23") + 0xc9a1f079L) and 0xffffffffL), ((r(e,"a") shl 32L.toInt()) or r(e,"w23")), ((-0x59bafb5d00000000L) or r(e,"b")))
        s(e,"w9", ((r(e,"WR_X0") and 0xffffffffL) xor r(e,"c")))
        call("response_table32", 0x770L, r(e,"w9"))
        s(e,"w8", ((r(e,"TABLE_RESULT") + r(e,"b")) and 0xffffffffL))
        s(e,"w12", ((r(e,"a") - r(e,"w8")) and 0xffffffffL))
        s(e,"w10", (((r(e,"w23") + 0xdc5ef0daL) + r(e,"w12")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w10")) and 0xffffffffL))
        s(e,"w13", ((r(e,"w9") + 0x75703f78L) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") + 0x75702f04L) and 0xffffffffL))
        s(e,"w8", (r(e,"w13") xor r(e,"w8")))
        call("response_ror32", r(e,"w8"), 21L)
        s(e,"w9", (((((r(e,"ROR_RESULT") + 3386L) and 0xffffffffL) xor r(e,"w9")) + r(e,"w12")) and 0xffffffffL))
        call("response_ror32", r(e,"w9"), 28L)
        s(e,"rot1", r(e,"ROR_RESULT"))
        call("response_ror32", r(e,"w8"), 2L)
        s(e,"rot2", r(e,"ROR_RESULT"))
        s(e,"w10", (r(e,"w10") xor ((r(e,"rot1") + r(e,"rot2")) and 0xffffffffL)))
        call("response_ror32", r(e,"w10"), 13L)
        s(e,"w12", (((r(e,"ROR_RESULT") + 6252L) xor (r(e,"w9") - 2923L)) and 0xffffffffL))
        s(e,"w20", ((r(e,"w13") - r(e,"w12")) and 0xffffffffL))
        s(e,"w21", (((r(e,"w20") + 558434014L) and 0xffffffffL) xor r(e,"w8")))
        s(e,"w22", ((r(e,"w9") + (r(e,"w21") * 0xa5001aebL)) and 0xffffffffL))
        s(e,"w23", (((r(e,"w22") + r(e,"w21")) and 0xffffffffL) xor r(e,"w10")))
        call("selected_wrapper", ((r(e,"w23") + 0x6ac38a1dL) and 0xffffffffL), ((r(e,"w22") shl 32L.toInt()) or r(e,"w23")), (0x3ccff0f400000000L or r(e,"w21")))
        s(e,"w9", (r(e,"w20") xor (r(e,"WR_X0") and 0xffffffffL)))
        s(e,"w10", ((r(e,"w21") - r(e,"w9")) and 0xffffffffL))
        s(e,"lookup_index", (r(e,"w22") xor r(e,"w10")))
        call("response_table32", 0x604L, r(e,"lookup_index"))
        s(e,"w11", ((r(e,"w23") - r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"w12", ((r(e,"w11") - r(e,"w22")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w9") - r(e,"w11")) and 0xffffffffL))
        s(e,"w10", ((r(e,"w10") - r(e,"w9")) and 0xffffffffL))
        call("response_table32", 0x158L, r(e,"w12"))
        s(e,"w8", ((r(e,"w9") - r(e,"TABLE_RESULT")) and 0xffffffffL))
        s(e,"w9", (r(e,"w10") xor r(e,"w8")))
        call("response_ror32", r(e,"w9"), 23L)
        s(e,"w10", (r(e,"w22") xor r(e,"ROR_RESULT")))
        s(e,"LAST_X0", ((r(e,"w9") shl 32L.toInt()) or r(e,"w10")))
        s(e,"LAST_X1", ((r(e,"w12") shl 32L.toInt()) or r(e,"w8")))
    }

internal fun SpotifyPlayPlayPipeline.fn_response_mixing_complete(args:LongArray) {
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
        call("response_mixing_exceptions", r(e,"hex"))
        s(e,"first", r(e,"EX_MIX_FIRST"))
        s(e,"second", r(e,"EX_MIX_SECOND"))
        if ((r(e,"first") and (1L shl 11L.toInt())) != 0L) {
            call("response_slot116", r(e,"first"), r(e,"second"))
            s(e,"first", r(e,"LAST_X0"))
            s(e,"second", r(e,"LAST_X1"))
        }
        if ((r(e,"first") and (1L shl 48L.toInt())) != 0L) {
            call("wrapper_swap", r(e,"first"))
            call("wrapper_leaf", 193L, r(e,"SWAPPED"), r(e,"second"))
            call("wrapper_swap", r(e,"WR_X0"))
            s(e,"first", r(e,"SWAPPED"))
            s(e,"second", r(e,"WR_X1"))
        }
        call("response_slot150", r(e,"first"), r(e,"second"))
        s(e,"WR_X0", r(e,"LAST_X0"))
        s(e,"WR_X1", r(e,"LAST_X1"))
        call("wrapper_repeat", 260L, 8L, 0L)
        call("wrapper_repeat", 81L, 20L, 1L)
        if (((r(e,"WR_X0") shr 32L.toInt()) and 1L) != 0L) {
            call("wrapper_swapped_leaf", 123L)
            call("wrapper_swap", r(e,"WR_X0"))
            s(e,"WR_X0", r(e,"SWAPPED"))
        }
        s(e,"MIX_FINISHED_X1", r(e,"WR_X0"))
        s(e,"MIX_FINISHED_X2", r(e,"WR_X1"))
    }

internal fun SpotifyPlayPlayPipeline.fn_response_prefix_math(args:LongArray) {
        val e = hashMapOf<String,Long>()
        e["w21"] = 0L
        e["w22"] = 0L
        e["w23"] = 0L
        e["w8"] = 0L
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w13"] = 0L
        e["w24"] = 0L
        e["v"] = 0L
        e["rot26"] = 0L
        e["rot31"] = 0L
        e["rot10"] = 0L
        e["i"] = 0L
        e["passes"] = 0L
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
        s(e,"w21", r(e,"1"))
        s(e,"w22", r(e,"2"))
        s(e,"w23", r(e,"3"))
        s(e,"w8", r(e,"4"))
        e["w9"] = 0L
        e["w10"] = 0L
        e["w11"] = 0L
        e["w12"] = 0L
        e["w13"] = 0L
        e["w24"] = 0L
        e["v"] = 0L
        e["rot26"] = 0L
        e["rot31"] = 0L
        e["rot10"] = 0L
        e["i"] = 0L
        e["passes"] = 0L
        if ((r(e,"w21") and 0x01000000L) != 0L) {
            s(e,"w24", ((r(e,"w8") + r(e,"w22")) and 0xffffffffL))
            call("selected_wrapper", ((r(e,"w24") + 0x95b5ea08L) and 0xffffffffL), (r(e,"w24") or (r(e,"w22") shl 32L.toInt())), (r(e,"w21") or 0x2acda9c400000000L))
            s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
            s(e,"w8", ((r(e,"w23") - r(e,"v")) and 0xffffffffL))
            call("response_table32", 0x69cL, r(e,"w8"))
            s(e,"w10", (r(e,"TABLE_RESULT") xor r(e,"w22")))
            call("response_table32", 0x34L, r(e,"w10"))
            s(e,"w9", ((r(e,"TABLE_RESULT") + r(e,"w24")) and 0xffffffffL))
            s(e,"w8", ((r(e,"w8") - r(e,"w9")) and 0xffffffffL))
            s(e,"w12", ((r(e,"w10") - r(e,"w8")) and 0xffffffffL))
            s(e,"w9", (r(e,"w9") xor r(e,"w12")))
            s(e,"w12", ((r(e,"w9") * 0xd9bff066L) and 0xffffffffL))
            s(e,"w10", ((r(e,"w12") + r(e,"w10")) and 0xffffffffL))
            s(e,"w8", ((r(e,"w12") + r(e,"w8")) and 0xffffffffL))
            s(e,"w9", (r(e,"w9") xor r(e,"w10")))
            call("response_ror32", r(e,"w10"), 26L)
            s(e,"rot26", r(e,"ROR_RESULT"))
            call("response_ror32", r(e,"w9"), 31L)
            s(e,"rot31", r(e,"ROR_RESULT"))
            s(e,"w23", (((r(e,"w8") + r(e,"rot26")) + r(e,"rot31")) and 0xffffffffL))
            s(e,"w22", ((r(e,"w10") - r(e,"w23")) and 0xffffffffL))
            call("response_ror32", r(e,"w22"), 26L)
            s(e,"w8", (((r(e,"ROR_RESULT") - 12228L) and 0xffffffffL) xor ((r(e,"w23") - 18792L) and 0xffffffffL)))
            s(e,"w24", ((r(e,"w9") - r(e,"w8")) and 0xffffffffL))
            call("selected_wrapper", ((r(e,"w24") + 0xddf91026L) and 0xffffffffL), (r(e,"w24") or (r(e,"w22") shl 32L.toInt())), (r(e,"w21") or 0x8fe98e5500000000UL.toLong()))
            s(e,"v", (r(e,"WR_X0") and 0xffffffffL))
            s(e,"w23", ((r(e,"w23") + r(e,"v")) and 0xffffffffL))
            call("response_ror32", r(e,"w23"), 29L)
            s(e,"w22", (((r(e,"ROR_RESULT") + 6864L) and 0xffffffffL) xor (((r(e,"w24") + 14906L) and 0xffffffffL) xor r(e,"w22"))))
            s(e,"w8", (r(e,"w24") xor r(e,"w21")))
        }
        if ((r(e,"w22") and (1L shl 19L.toInt())) != 0L) {
            s(e,"w8", (((r(e,"w21") * 0x5f92c7edL) + r(e,"w8")) and 0xffffffffL))
            call("response_table32", 0x130L, r(e,"w8"))
            s(e,"w9", ((r(e,"w23") - r(e,"TABLE_RESULT")) and 0xffffffffL))
            s(e,"w10", ((r(e,"w21") + 0x9d53be8eL) and 0xffffffffL))
            s(e,"w8", (r(e,"w8") xor r(e,"w10")))
            s(e,"w11", (((r(e,"w8") + r(e,"w10")) - r(e,"w9")) and 0xffffffffL))
            s(e,"w8", (r(e,"w8") xor r(e,"w22")))
            s(e,"w23", ((r(e,"w9") - r(e,"w11")) and 0xffffffffL))
            s(e,"w21", ((r(e,"w10") - r(e,"w11")) and 0xffffffffL))
        }
        s(e,"passes", (((r(e,"w21") shr 23L.toInt()) and 7L) + 1L))
        e["i"] = 0L
        s(e,"i", 0L)
        while ((if (r(e,"i") < r(e,"passes")) 1L else 0L) != 0L) {
            s(e,"w8", (r(e,"w8") xor r(e,"w22")))
            s(e,"w23", (((r(e,"w23") + r(e,"w22")) + r(e,"w8")) and 0xffffffffL))
            s(e,"w8", (r(e,"w8") xor r(e,"w21")))
            s(e,"w22", ((r(e,"w23") + r(e,"w22")) and 0xffffffffL))
            s(e,"i",r(e,"i") + 1L)
        }
        call("response_table32", 0x5bcL, r(e,"w22"))
        s(e,"w11", (r(e,"TABLE_RESULT") xor r(e,"w8")))
        s(e,"w10", ((r(e,"w11") + r(e,"w23")) and 0xffffffffL))
        s(e,"w9", ((r(e,"w10") + r(e,"w21")) and 0xffffffffL))
        s(e,"w8", (((r(e,"w9") + 0x6bad4b80L) and 0xffffffffL) xor r(e,"w22")))
        if ((r(e,"w8") and (1L shl 16L.toInt())) != 0L) {
            call("response_table32", 0x74cL, r(e,"w9"))
            s(e,"w11", ((r(e,"TABLE_RESULT") + r(e,"w11")) and 0xffffffffL))
            s(e,"w10", (((r(e,"w11") * 0xb1199e45L) + r(e,"w10")) and 0xffffffffL))
            call("response_ror32", r(e,"w10"), 16L)
            s(e,"w13", (((r(e,"ROR_RESULT") + 27176L) and 0xffffffffL) xor ((r(e,"w11") - 3965L) and 0xffffffffL)))
            s(e,"w9", ((r(e,"w9") - r(e,"w13")) and 0xffffffffL))
            s(e,"w11", (r(e,"w11") xor r(e,"w9")))
            call("response_ror32", r(e,"w11"), 21L)
            s(e,"w13", (((r(e,"ROR_RESULT") + 11464L) and 0xffffffffL) xor ((r(e,"w9") + 9576L) and 0xffffffffL)))
            s(e,"w10", ((r(e,"w13") + r(e,"w10")) and 0xffffffffL))
            call("response_ror32", r(e,"w10"), 26L)
            s(e,"rot10", r(e,"ROR_RESULT"))
            call("response_ror32", r(e,"w11"), 9L)
            s(e,"w9", (r(e,"w9") xor ((r(e,"rot10") + r(e,"ROR_RESULT")) and 0xffffffffL)))
            call("response_table32", 0x624L, r(e,"w9"))
            s(e,"w11", ((r(e,"w11") - r(e,"TABLE_RESULT")) and 0xffffffffL))
            s(e,"w10", (r(e,"w10") xor r(e,"w11")))
            s(e,"w9", (r(e,"w9") xor r(e,"w10")))
            s(e,"w10", ((r(e,"w10") + r(e,"w11")) and 0xffffffffL))
            s(e,"w12", ((r(e,"w11") - r(e,"w9")) and 0xffffffffL))
            s(e,"w9", (r(e,"w9") xor r(e,"w10")))
            s(e,"w11", (r(e,"w12") xor r(e,"w8")))
        }
        s(e,"RESPONSE_CALL_X1", ((r(e,"w9") and 0xffffffffL) or (r(e,"w8") shl 32L.toInt())))
        s(e,"RESPONSE_CALL_X2", ((r(e,"w10") and 0xffffffffL) or (r(e,"w11") shl 32L.toInt())))
    }
