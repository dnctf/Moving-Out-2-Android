package com.dnctf.movingout2.compatibility

data class UnityProfile(val version: String?, val il2cppMetadataVersion: Int?, val confidence: Confidence) {
    enum class Confidence { CONFIRMED, REPORTED, UNKNOWN }
}