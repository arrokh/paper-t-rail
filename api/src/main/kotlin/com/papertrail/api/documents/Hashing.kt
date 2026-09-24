package com.papertrail.api.documents

import java.security.MessageDigest

fun sha256Hex(content: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(content)
    .joinToString("") { byte -> "%02x".format(byte) }
