package com.github.tvbox.osc.player

object RetryPolicy {

    private const val MANIFEST_MALFORMED = "ERROR_CODE_PARSING_MANIFEST_MALFORMED"

    private const val BAD_HTTP_STATUS = "ERROR_CODE_IO_BAD_HTTP_STATUS"

    fun shouldRefreshResolve(errorCodeName: String?): Boolean =
        errorCodeName == MANIFEST_MALFORMED

    fun isHttpStatusError(errorCodeName: String?): Boolean =
        errorCodeName == BAD_HTTP_STATUS
}
