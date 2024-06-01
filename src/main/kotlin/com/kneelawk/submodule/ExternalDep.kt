package com.kneelawk.submodule

data class ExternalDep(val getter: (platform: String) -> String, val api: Boolean)
