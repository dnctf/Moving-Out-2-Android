package com.dnctf.movingout2.core.logging

interface LogSink {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, cause: Throwable? = null)
}