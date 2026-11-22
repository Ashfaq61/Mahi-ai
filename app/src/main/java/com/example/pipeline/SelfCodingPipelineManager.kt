package com.example.pipeline

import android.content.Context
import com.example.data.MahiDataRepository
import com.example.data.ReleaseRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class SandboxPatchProposal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val targetFileName: String,
    val patchContent: String,
    val securityFindings: List<String>,
    val sha256: String,
    val status: String
)

class SelfCodingPipelineManager(
    private val context: Context,
    private val repository: MahiDataRepository? = null
) {
    private val sandboxDir = File(context.applicationContext.filesDir, "sandbox_patches").apply { mkdirs() }

    private val _proposals = MutableStateFlow<List<SandboxPatchProposal>>(emptyList())
    val proposals: StateFlow<List<SandboxPatchProposal>> = _proposals.asStateFlow()

    fun scanPatchForSecurity(content: String): List<String> {
        val findings = mutableListOf<String>()
        if (Regex("AIza[0-9A-Za-z_-]{20,}").containsMatchIn(content)) {
            findings.add("Hardcoded API key pattern detected")
        }
        if (content.contains("http://") && !content.contains("localhost")) {
            findings.add("Cleartext http:// URL detected")
        }
        if (content.contains("DexClassLoader") || content.contains("PathClassLoader")) {
            findings.add("Prohibited dynamic code loading call detected")
        }
        if (content.contains("Runtime.getRuntime().exec")) {
            findings.add("Shell exec call flagged for manual security audit")
        }
        return findings
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun stagePatchInSandbox(title: String, targetFileName: String, content: String): SandboxPatchProposal {
        val safeName = targetFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(sandboxDir, safeName)
        file.writeText(content)
        val findings = scanPatchForSecurity(content)
        val hash = sha256Hex(content)
        val status = if (findings.isEmpty()) "STAGED_AWAITING_APPROVAL" else "BLOCKED_SECURITY"
        val prop = SandboxPatchProposal(
            title = title.trim().ifBlank { safeName },
            targetFileName = safeName,
            patchContent = content,
            securityFindings = findings,
            sha256 = hash,
            status = status
        )
        _proposals.value = listOf(prop) + _proposals.value
        return prop
    }

    suspend fun approveAndRecordRelease(
        proposalId: String,
        versionName: String,
        versionCode: Int
    ): Result<ReleaseRecordEntity> {
        val prop = _proposals.value.firstOrNull { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("Proposal not found."))
        if (prop.securityFindings.isNotEmpty()) {
            return Result.failure(SecurityException("Cannot approve patch with security findings."))
        }
        val record = ReleaseRecordEntity(
            id = UUID.randomUUID().toString(),
            versionName = versionName,
            versionCode = versionCode,
            branchName = "sandbox/${prop.targetFileName}",
            sha256Checksum = prop.sha256,
            status = "APPROVED_STAGED",
            notes = prop.title,
            createdAtMs = System.currentTimeMillis()
        )
        repository?.recordRelease(record)
        _proposals.value = _proposals.value.map {
            if (it.id == proposalId) it.copy(status = "APPROVED_RELEASED") else it
        }
        return Result.success(record)
    }
}
