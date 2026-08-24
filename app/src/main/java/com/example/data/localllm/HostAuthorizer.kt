package com.example.data.localllm

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.example.R
import java.security.MessageDigest

internal data class ApprovedHost(
    val packageName: String,
    val approvedLineageDigest: String,
)

internal data class HostSigningIdentity(
    val packageName: String,
    val currentSignerDigests: Set<String>,
    val signingLineageDigests: Set<String>,
)

internal object HostAuthorizationPolicy {
    fun isAuthorized(
        identities: List<HostSigningIdentity>,
        approvedHosts: Set<ApprovedHost>,
    ): Boolean {
        if (identities.size != 1) return false
        val identity = identities.single()
        if (identity.currentSignerDigests.size != 1) return false
        if (identity.signingLineageDigests.isEmpty()) return false
        if (!identity.signingLineageDigests.containsAll(identity.currentSignerDigests)) return false
        return approvedHosts.any { approved ->
            approved.packageName == identity.packageName &&
                approved.approvedLineageDigest in identity.signingLineageDigests
        }
    }
}

internal class HostAuthorizer(context: Context) {
    private val packageManager = context.packageManager
    private val approvedHosts = context.resources
        .getStringArray(R.array.localllm_host_approved_callers)
        .map(::parseApprovedHost)
        .toSet()

    fun enforceAuthorizedHost(uid: Int): String {
        val packages = packageManager.getPackagesForUid(uid).orEmpty().distinct()
        val identities = packages.mapNotNull { packageName ->
            packageManager.signingIdentityOrNull(packageName)
        }
        if (packages.size != 1 || identities.size != 1 ||
            !HostAuthorizationPolicy.isAuthorized(identities, approvedHosts)
        ) {
            throw SecurityException("UID $uid is not an approved LocalLLM host")
        }
        return packages.single()
    }
}

private fun parseApprovedHost(encoded: String): ApprovedHost {
    val pieces = encoded.split('|', limit = 2)
    require(
        pieces.size == 2 &&
            pieces[0].isNotBlank() &&
            pieces[1].length == 64 &&
            pieces[1].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' },
    ) {
        "Malformed LocalLLM approved-host entry"
    }
    return ApprovedHost(pieces[0], pieces[1].lowercase())
}

@SuppressLint("NewApi")
@Suppress("DEPRECATION")
private fun PackageManager.signingIdentityOrNull(packageName: String): HostSigningIdentity? {
    val packageInfo = try {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val signingInfo = packageInfo.signingInfo ?: return null
    val current = signingInfo.apkContentsSigners.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    val lineage = if (signingInfo.hasMultipleSigners()) {
        current
    } else {
        signingInfo.signingCertificateHistory.orEmpty().mapTo(mutableSetOf(), Signature::sha256)
    }
    return HostSigningIdentity(packageName, current, lineage)
}

private fun Signature.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
