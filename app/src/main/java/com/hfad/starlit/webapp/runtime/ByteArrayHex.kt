package com.hfad.starlit.webapp.runtime

internal fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
