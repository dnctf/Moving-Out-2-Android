package com.dnctf.movingout2.compatibility

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal bounds-checked PE32+ x64 import-directory reader; pure JVM. */
object GameBinaryAnalyzer {
    data class Analysis(val machine:Int,val isPe32Plus:Boolean,val imports:Set<String>,val errors:List<String>)
    fun analyze(input:InputStream):Analysis = analyze(input.readBytes())
    fun analyze(bytes:ByteArray):Analysis {
        val errors=mutableListOf<String>(); val imports=linkedSetOf<String>()
        fun u16(o:Int):Int { if(o<0||o+2>bytes.size) throw IllegalArgumentException("out of bounds u16"); return ByteBuffer.wrap(bytes,o,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff }
        fun u32(o:Int):Long { if(o<0||o+4>bytes.size) throw IllegalArgumentException("out of bounds u32"); return ByteBuffer.wrap(bytes,o,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
        fun cstr(o:Int):String { if(o<0||o>=bytes.size) throw IllegalArgumentException("invalid string offset"); var e=o; while(e<bytes.size&&bytes[e].toInt()!=0)e++; return String(bytes,o,e-o,Charsets.US_ASCII) }
        try {
            require(bytes.size>=64 && u16(0)==0x5a4d){"Invalid DOS MZ"}
            val pe=u32(0x3c).toInt(); require(pe>=0 && pe+24<=bytes.size && u32(pe)==0x00004550L){"Invalid PE signature"}
            val machine=u16(pe+4); val sections=u16(pe+6); val optSize=u16(pe+20); val opt=pe+24
            require(opt+optSize<=bytes.size){"Truncated optional header"}
            val magic=u16(opt); require(magic==0x20b){"Not PE32+"}
            val dirs=opt+112; val importRva=u32(dirs+8).toInt(); val importSize=u32(dirs+12).toInt()
            val secStart=opt+optSize
            fun rvaToOffset(rva:Int):Int {
                for(i in 0 until sections) { val s=secStart+i*40; if(s+40>bytes.size) break
                    val va=u32(s+12).toInt(); val vsize=u32(s+8).toInt(); val rawSize=u32(s+16).toInt(); val raw=u32(s+20).toInt()
                    val span=maxOf(vsize,rawSize); if(rva>=va && rva<va+span) return raw+(rva-va)
                }; throw IllegalArgumentException("RVA not mapped: $rva")
            }
            if(importRva!=0&&importSize>0) {
                var d=rvaToOffset(importRva); var count=0
                while(d+20<=bytes.size && count++<4096) {
                    val original=u32(d); val nameRva=u32(d+12); val first=u32(d+16)
                    if(original==0L&&nameRva==0L&&first==0L) break
                    imports.add(cstr(rvaToOffset(nameRva.toInt()))); d+=20
                }
            }
            if(machine!=0x8664) errors.add("Machine is not AMD64 (0x8664)")
            return Analysis(machine,true,imports,errors)
        } catch(e:Exception) { errors.add(e.message ?: "PE parse failure"); return Analysis(-1,false,imports,errors) }
    }
}
