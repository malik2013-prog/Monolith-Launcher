package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class LaunchProfileRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val profileName: String = "",
    val versionId: String = "",
    val javaMajor: Long = 21L,
    val allocatedMemoryMb: Long = 2048L,
    val rendererId: String = "gl4es_114",
    val gcAlgorithm: String = "G1GC",
    val customJvmFlags: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DiagnosticReportRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val versionId: String = "",
    val javaMajor: Long = 21L,
    val totalChecks: Long = 8L,
    val repairedIssuesCount: Long = 0L,
    val summary: String = "",
    val status: String = "READY",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

class CloudLaunchDatabaseRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth: FirebaseAuth
        get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeLaunchProfiles(): Flow<List<LaunchProfileRecord>> = flow {
        val uid = auth.currentUser?.uid ?: "unauthenticated_uid"
        val path = "users/$uid/launch_profiles"
        emitAll(
            db.collection("users").document(uid).collection("launch_profiles")
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        LaunchProfileRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeDiagnosticReports(): Flow<List<DiagnosticReportRecord>> = flow {
        val uid = auth.currentUser?.uid ?: "unauthenticated_uid"
        val path = "users/$uid/diagnostic_reports"
        emitAll(
            db.collection("users").document(uid).collection("diagnostic_reports")
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DiagnosticReportRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun createLaunchProfile(
        profileName: String,
        versionId: String,
        javaMajor: Int,
        allocatedMemoryMb: Int,
        rendererId: String,
        gcAlgorithm: String,
        customJvmFlags: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("launch_profiles")
        val docRef = colRef.document()
        val payload = mapOf(
            "userId" to uid,
            "profileName" to profileName.take(80).ifBlank { "Vanilla $versionId" },
            "versionId" to versionId.take(64).ifBlank { "1.21.4" },
            "javaMajor" to javaMajor.toLong(),
            "allocatedMemoryMb" to allocatedMemoryMb.coerceIn(512, 16384).toLong(),
            "rendererId" to rendererId.take(64).ifBlank { "gl4es_114" },
            "gcAlgorithm" to gcAlgorithm.take(32).ifBlank { "G1GC" },
            "customJvmFlags" to customJvmFlags.take(500),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun getLaunchProfileById(targetUserId: String, profileId: String): Result<LaunchProfileRecord?> = runCatching {
        val docRef = db.collection("users").document(targetUserId)
            .collection("launch_profiles").document(profileId)
        try {
            val snap = docRef.get().await()
            snap.toObject(LaunchProfileRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun deleteLaunchProfile(profileId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("launch_profiles").document(profileId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    suspend fun saveDiagnosticReport(
        versionId: String,
        javaMajor: Int,
        totalChecks: Int,
        repairedIssuesCount: Int,
        summary: String,
        status: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("diagnostic_reports")
        val docRef = colRef.document()
        val validStatus = if (status in listOf("READY", "REPAIRED", "ACTION_REQUIRED")) status else "READY"
        val payload = mapOf(
            "userId" to uid,
            "versionId" to versionId.take(64).ifBlank { "1.21.4" },
            "javaMajor" to javaMajor.toLong(),
            "totalChecks" to totalChecks.coerceIn(1, 64).toLong(),
            "repairedIssuesCount" to repairedIssuesCount.coerceIn(0, 64).toLong(),
            "summary" to summary.take(1000).ifBlank { "Pre-launch diagnostic verified" },
            "status" to validStatus,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }
}
