package com.example.mailclient

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ConcurrentSyncAndLockingTest {

    @Test
    fun `AccountSyncLocks normalizes email keys so different cases return the same mutex`() {
        val mutex1 = AccountSyncLocks.getLock("User@Example.com")
        val mutex2 = AccountSyncLocks.getLock("user@example.com ")
        val mutex3 = AccountSyncLocks.getLock("USER@EXAMPLE.COM")

        assertSame(mutex1, mutex2)
        assertSame(mutex2, mutex3)
    }

    @Test
    fun `concurrent coroutines using AccountSyncLocks execute serially for same account`() = runBlocking {
        val email = "locktest@example.com"
        val lock = AccountSyncLocks.getLock(email)
        var counter = 0

        val jobs = (1..20).map {
            async {
                lock.withLock {
                    val current = counter
                    kotlinx.coroutines.delay(2)
                    counter = current + 1
                }
            }
        }

        jobs.awaitAll()
        assertEquals(20, counter)
    }
}
