package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudLaunchDatabaseRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createAndObserveLaunchProfile_authenticatedOwner_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = CloudLaunchDatabaseRepository(firestore)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createLaunchProfile(
                profileName = "Vanilla 1.21.4 ARM64",
                versionId = "1.21.4",
                javaMajor = 21,
                allocatedMemoryMb = 2048,
                rendererId = "gl4es_114",
                gcAlgorithm = "G1GC",
                customJvmFlags = "-XX:+UseG1GC"
            )
        }
        assertTrue(createResult.isSuccess)
        val profileId = createResult.getOrThrow()
        assertTrue(profileId.isNotEmpty())

        val emittedProfiles = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeLaunchProfiles().first { list -> list.any { it.id == profileId } }
        }
        assertTrue(emittedProfiles.any { it.id == profileId && it.versionId == "1.21.4" })
    }

    @Test
    fun saveAndObserveDiagnosticReport_authenticatedOwner_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = CloudLaunchDatabaseRepository(firestore)

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveDiagnosticReport(
                versionId = "1.21.4",
                javaMajor = 21,
                totalChecks = 8,
                repairedIssuesCount = 2,
                summary = "Repaired options.txt fullscreen flag and verified OpenJDK 21 chmod +x",
                status = "REPAIRED"
            )
        }
        assertTrue(saveResult.isSuccess)
        val reportId = saveResult.getOrThrow()

        val emittedReports = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeDiagnosticReports().first { list -> list.any { it.id == reportId } }
        }
        assertTrue(emittedReports.any { it.id == reportId && it.status == "REPAIRED" })
    }

    @Test
    fun getLaunchProfileById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudLaunchDatabaseRepository(firestore)
        val profileId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createLaunchProfile(
                profileName = "Alice Private Profile",
                versionId = "1.16.5",
                javaMajor = 17,
                allocatedMemoryMb = 2048,
                rendererId = "gl4es_114",
                gcAlgorithm = "G1GC",
                customJvmFlags = ""
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = CloudLaunchDatabaseRepository(firestore)
        val crossResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getLaunchProfileById(aliceUid, profileId)
        }
        assertTrue(crossResult.isFailure)
        val fsEx = crossResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeLaunchProfiles_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = CloudLaunchDatabaseRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeLaunchProfiles().first()
            }
            fail("Expected exception for unauthenticated query")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
