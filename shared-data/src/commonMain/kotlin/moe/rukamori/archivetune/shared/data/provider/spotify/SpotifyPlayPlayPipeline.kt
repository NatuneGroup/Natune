package moe.rukamori.archivetune.shared.data.provider.spotify

// Retained Android Echo transform; attribution and lineage: docs/CREDITS.md.
class SpotifyPlayPlayPipeline {
    internal val leaves = SpotifyPlayPlayLeaves()
    internal val g = HashMap<String,Long>()
    internal fun r(e:Map<String,Long>,name:String):Long = e[name] ?: g[name] ?: 0L
    internal fun s(e:MutableMap<String,Long>,name:String,value:Long) {
        if (e.containsKey(name)) e[name] = value else g[name] = value
    }
    internal fun g(name:String):Long = g[name] ?: 0L
    internal val wrappers = longArrayOf(
        0xcc3618L, 0xcc3654L, 0xcc36c8L, 0xcc3758L, 0xcc3798L, 0xcc3804L, 0xcc3874L, 0xcc38b8L,
        0xcc3930L, 0xcc3988L, 0xcc39dcL, 0xcc3a28L, 0xcc3a90L, 0xcc3b2cL, 0xcc3ba8L, 0xcc3c10L,
        0xcc3cb0L, 0xcc3d14L, 0xcc3d64L, 0xcc3df4L, 0xcc3e44L, 0xcc3eb0L, 0xcc3f34L, 0xcc3f80L,
        0xcc4024L, 0xcc4088L, 0xcc40d4L, 0xcc4154L, 0xcc4198L, 0xcc4230L, 0xcc42bcL, 0xcc42f8L,
        0xcc4340L, 0xcc4380L, 0xcc43ccL, 0xcc4430L, 0xcc44a8L, 0xcc4508L, 0xcc4580L, 0xcc45e8L,
        0xcc4664L, 0xcc46bcL, 0xcc472cL, 0xcc4790L, 0xcc4810L, 0xcc4870L, 0xcc48d4L, 0xcc4958L,
        0xcc498cL, 0xcc49ecL, 0xcc4a40L, 0xcc4a8cL, 0xcc4accL, 0xcc4b24L, 0xcc4b98L, 0xcc4be0L,
        0xcc4c30L, 0xcc4c80L, 0xcc4ce0L, 0xcc4d28L, 0xcc4d74L, 0xcc4de4L, 0xcc4e78L, 0xcc4edcL,
    )
    internal fun leaf(slot:Int,x:Long,y:Long) {
        val result=leaves.evaluate(slot,x,y)
        g["WR_X0"]=result.first
        g["WR_X1"]=result.second
    }
    internal fun swap(x:Long) {g["SWAPPED"] = (x shl 32) or ((x shr 32) and 0xffffffffL)}
    internal fun fold() {
        val a=g("WR_X0");val b=g("WR_X1")
        g["WR_X0"] = (((a and 0xffffffffL) xor ((a shr 32) and 0xffffffffL)) +
            ((b and 0xffffffffL) xor ((b shr 32) and 0xffffffffL))) and 0xffffffffL
    }
    internal fun chain(vararg slots:Long) {for (slot in slots)leaf(slot.toInt(),g("WR_X0"),g("WR_X1"))}
    internal fun swappedLeaf(slot:Int) {swap(g("WR_X0"));leaf(slot,g("SWAPPED"),g("WR_X1"))}
    internal fun optionalLeaf(bit:Int, slot:Int) {if (((g("WR_X0") shr bit) and 1L)!=0L)swappedLeaf(slot)}
    internal fun repeatLeaf(slot:Int, shift:Int, swapFirst:Boolean, limit:Int=8) {
        if (swapFirst) {swap(g("WR_X0"));g["WR_X0"]=g("SWAPPED")}
        for (count in 0 until limit) {
            leaf(slot,g("WR_X0"),g("WR_X1"))
            if (count >= (((g("WR_X0") and 0xffffffffL) shr shift) and 7L).toInt())return
        }
        error("PlayPlay repeated wrapper did not converge at slot $slot")
    }
    internal fun optionalPrefix(bit:Int,slot:Int) {
        val trigger=(g("WR_X0") shr bit) and 1L
        swap(g("WR_X0"));g["WR_X0"]=g("SWAPPED")
        if (trigger!=0L)leaf(slot,g("WR_X0"),g("WR_X1"))
    }
    internal fun wrapperPair(shiftA:Long,slotA:Long,shiftB:Long,slotB:Long):Pair<Long,Long> {
        val first=(g("WR_X0") shr shiftA.toInt()) and 1L
        if (first!=0L)swappedLeaf(slotA.toInt())
        val x=if(first!=0L) (g("WR_X0") shr 32) and 0xffffffffL else g("WR_X0")
        val second=(x shr shiftB.toInt()) and 1L
        if(second!=0L){
            if(first!=0L)swappedLeaf(slotB.toInt())
            else leaf(slotB.toInt(),g("WR_X0"),g("WR_X1"))
        }
        return first to second
    }
    internal fun selected(selector:Long,x:Long,y:Long) {
        call("wrapper_run",wrappers[(selector and 63L).toInt()],x,y)
    }
    internal fun ror32(word:Long,shift:Long) {
        g["ROR_RESULT"] = playPlayIntRotateRight(word.toInt(),shift.toInt() and 31).toLong() and 0xffffffffL
    }
    internal fun table32(offset:Long,index:Long) {
        val i=(offset / 4L + (index and 255L)).toInt()
        require(i in 0 until 768) {"Response table index outside verified range: $i"}
        g["TABLE_RESULT"] = leaves.lookup(i)
    }
    internal fun exceptionStep(name:String, swapped:Long) {
        val x=if (swapped!=0L) (g("EX1") or (g("EX0") shl 32)) else (g("EX0") or (g("EX1") shl 32))
        call(name,x,g("EX2") or (g("EX3") shl 32))
    }
    internal fun responseMixingEntry() {
        responsePrefix()
        call("response_cc1558_complete",g("RESPONSE_CALL_X1"),g("RESPONSE_CALL_X2"))
        leaf(271,g("CC_RESULT_X0"),g("CC_RESULT_X1"))
        g["MIX_NEXT_SLOT"]=54L;g["MIX_NEXT_X1"]=g("WR_X0");g["MIX_NEXT_X2"]=g("WR_X1")
    }
    internal fun responsePrefix() {
        val field = responseInput
        require(field.size == 16)
        fun w(pos:Int):Long = (field[pos].toLong() and 255L) or
            ((field[pos+1].toLong() and 255L) shl 8) or
            ((field[pos+2].toLong() and 255L) shl 16) or
            ((field[pos+3].toLong() and 255L) shl 24)
        fn_response_prefix_math(longArrayOf(w(0),w(4),w(8),w(12)))
    }
    internal lateinit var responseInput:ByteArray
    internal fun responseMixingExceptions() {
        call("response_mixing_preexception")
        call("throw_cbc930",g("MIX_NEXT_X1"),g("MIX_NEXT_X2"))
        if ((g("EX0") and (1L shl 12)) !=0L)exceptionStep("throw_cbe7a8",0L)
        exceptionStep("throw_cbe868",1L)
        exceptionStep("throw_cc05f0",0L)
        exceptionStep("throw_cc01a8",0L)
        val first=g("EX0") or (g("EX1") shl 32)
        val second=g("EX2") or (g("EX3") shl 32)
        g["EX_MIX_FIRST"]=first;g["EX_MIX_SECOND"]=second
        g["MIX_NEXT_X1"]=first;g["MIX_NEXT_X2"]=second
        if ((g("EX0") and (1L shl 11)) != 0L) g["MIX_NEXT_SLOT"]=116L
        else if ((g("EX1") and (1L shl 16)) != 0L) {
            g["MIX_NEXT_SLOT"]=193L;g["MIX_NEXT_X1"]=g("EX1") or (g("EX0") shl 32)
        }else g["MIX_NEXT_SLOT"]=150L
    }
    internal fun round(input:Long,key:Long,bias:Long):Long {
        var b=input xor 0x0f859104ff7029e3L
        var a=(input xor 0x13245352688a24a6L)+b
        var c=a+key
        var d=playPlayRotateLeft(b,3) xor a
        b=d+bias;a=c xor playPlayRotateLeft(key,16)
        var sum=a+b
        d=playPlayRotateLeft(d,30) xor b
        b=playPlayRotateLeft(a,1) xor sum
        a=d+playPlayRotateLeft(c,1)
        c=a+b;d=playPlayRotateLeft(d,3) xor a
        b=playPlayRotateLeft(b,16) xor c
        a=d+playPlayRotateLeft(sum,33)
        b=a xor b;sum=playPlayRotateLeft(d,30) xor a
        return b xor playPlayRotateLeft(c,1) xor sum
    }
    internal fun computeKey():ByteArray {
        call("record_input",g("MIX_FINISHED_X1"),g("MIX_FINISHED_X2"))
        var k=0x97ef94a432921a68UL.toLong()
        var l=0x121066ec1ecc292dL
        var x=g("RECORD_INPUT_X")
        var y=g("RECORD_INPUT_Y")
        for(i in 0 until 32){
            x=(playPlayRotateRight(x,8)+y) xor k
            y=playPlayRotateLeft(y,3) xor x
            l=(playPlayRotateRight(l,8)+k) xor i.toLong()
            k=playPlayRotateLeft(k,3) xor l
        }
        var left=y
        var right=x
        val keys=longArrayOf(
            0x73e2b993de29abadL,0x0cfdfadcc532b265L,
            0xf3a3a8f312885bacUL.toLong(),0x0d01fd18bccecd8dL,
            0x7327a9f17e888924L,0x0f0602dcaaedbe7dL,
            0xd3e3a9917ef8eb35UL.toLong(),0xed09fd18d4cec184UL.toLong(),
        )
        for(i in keys.indices step 2){
            val next=round(left,keys[i],keys[i+1]) xor right
            right=left;left=next
        }
        return ByteArray(16){i->if(i<8) (left ushr (8*i)).toByte() else (right ushr (8*(i-8))).toByte()}
    }
    fun decode(field:ByteArray):ByteArray {
        responseInput=field
        g.clear()
        call("response_mixing_complete")
        return computeKey()
    }
    internal fun call(name:String,vararg args:Long) {
        when(name) {
            "reduced_cbb02c" -> fn_reduced_cbb02c(args)
            "reduced_cbb0b0" -> fn_reduced_cbb0b0(args)
            "reduced_cbb134" -> fn_reduced_cbb134(args)
            "record_input" -> fn_record_input(args)
            "wrapper_run" -> fn_wrapper_run(args)
            "response_cc1558_complete" -> fn_response_cc1558_complete(args)
            "nested_cbf5e0_complete" -> fn_nested_cbf5e0_complete(args)
            "nested_cbf0d8_complete" -> fn_nested_cbf0d8_complete(args)
            "nested_cc1cac_complete" -> fn_nested_cc1cac_complete(args)
            "response_mixing_extended" -> fn_response_mixing_extended(args)
            "response_mixing_preexception" -> fn_response_mixing_preexception(args)
            "throw_cbe868" -> fn_throw_cbe868(args)
            "throw_cc01a8" -> fn_throw_cc01a8(args)
            "throw_cc05f0" -> fn_throw_cc05f0(args)
            "throw_cbe7a8" -> fn_throw_cbe7a8(args)
            "throw_cbc930" -> fn_throw_cbc930(args)
            "response_slot116" -> fn_response_slot116(args)
            "response_slot150" -> fn_response_slot150(args)
            "response_mixing_complete" -> fn_response_mixing_complete(args)
            "wrapper_leaf" -> leaf(args[0].toInt(),args[1],args[2])
            "wrapper_chain" -> chain(*args)
            "wrapper_swapped_leaf" -> swappedLeaf(args[0].toInt())
            "wrapper_optional_leaf" -> optionalLeaf(args[0].toInt(),args[1].toInt())
            "wrapper_swap" -> swap(args[0])
            "wrapper_fold" -> fold()
            "wrapper_repeat" -> repeatLeaf(args[0].toInt(),args[1].toInt(),args[2]!=0L,if(args.size>3)args[3].toInt() else 8)
            "wrapper_optional_prefix" -> optionalPrefix(args[0].toInt(),args[1].toInt())
            "selected_wrapper" -> selected(args[0],args[1],args[2])
            "response_ror32" -> ror32(args[0],args[1])
            "response_table32" -> table32(args[0],args[1])
            "response_mixing_entry" -> responseMixingEntry()
            "response_mixing_exceptions" -> responseMixingExceptions()
            "response_prefix" -> responsePrefix()
            else -> error("Unknown response operation $name")
        }
    }













}
