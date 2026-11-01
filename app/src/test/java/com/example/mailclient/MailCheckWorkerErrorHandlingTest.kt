package com.example.mailclient

import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MailCheckWorkerErrorHandlingTest {

    class FakeErrorFolderAdapter(
        private val throwCancellation: Boolean = false
    ) : ImapConnector.ImapFolderAdapter {
        override fun openFolder(
            host: String,
            port: Int,
            email: String,
            password: String,
            folderName: String,
            mode: Int
        ): ImapConnector.FolderHandle {
            if (throwCancellation) {
                throw CancellationException("Worker coroutine cancelled")
            }
            return object : ImapConnector.FolderHandle {
                override fun getUIDValidity(): Long = 1000L
                override fun getMessageCount(): Int = 0
                override fun getMessages(lowIndex: Int, highIndex: Int): List<ImapConnector.MailMessageInfo> = emptyList()
                override fun close() {}
            }
        }
    }

    private fun createDummyWorkerParameters(): WorkerParameters {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val taskExecutor = object : androidx.work.impl.utils.taskexecutor.TaskExecutor {
            override fun getMainThreadExecutor(): java.util.concurrent.Executor = executor
            override fun getSerialTaskExecutor(): androidx.work.impl.utils.taskexecutor.SerialExecutor {
                return object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
                    override fun execute(r: Runnable) { r.run() }
                    override fun hasPendingTasks(): Boolean = false
                }
            }
        }
        val constructors = WorkerParameters::class.java.declaredConstructors
        val ctor = constructors.first()
        ctor.isAccessible = true

        val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
        for (i in args.indices) {
            val type = ctor.parameterTypes[i]
            when {
                type == java.util.UUID::class.java -> args[i] = java.util.UUID.randomUUID()
                type == androidx.work.Data::class.java -> args[i] = androidx.work.Data.EMPTY
                type == Collection::class.java || type == List::class.java -> args[i] = emptyList<String>()
                type == WorkerParameters.RuntimeExtras::class.java -> args[i] = WorkerParameters.RuntimeExtras()
                type == Int::class.javaPrimitiveType -> args[i] = 1
                type == Long::class.javaPrimitiveType -> args[i] = 0L
                type == java.util.concurrent.Executor::class.java -> args[i] = executor
                type.name.contains("TaskExecutor") -> args[i] = taskExecutor
                type == androidx.work.WorkerFactory::class.java -> args[i] = androidx.work.WorkerFactory.getDefaultWorkerFactory()
                type == androidx.work.ProgressUpdater::class.java -> args[i] = java.lang.reflect.Proxy.newProxyInstance(
                    type.classLoader, arrayOf(type)
                ) { _, _, _ -> null }
                type == androidx.work.ForegroundUpdater::class.java -> args[i] = java.lang.reflect.Proxy.newProxyInstance(
                    type.classLoader, arrayOf(type)
                ) { _, _, _ -> null }
            }
        }
        return ctor.newInstance(*args) as WorkerParameters
    }

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        ImapConnector.folderAdapter = ImapConnector.JavaMailFolderAdapter()
    }

    @Test
    fun `isAuthError detects authentication exception messages`() {
        val authEx = javax.mail.AuthenticationFailedException("AUTHENTICATIONFAILED Incorrect username or password")
        val genericEx = java.io.IOException("Connection reset by peer")

        val dummyContext = CredentialStoreMockkTest.TestContext()
        val dummyParams = createDummyWorkerParameters()

        val worker = MailCheckWorker(dummyContext, dummyParams)

        val method = MailCheckWorker::class.java.getDeclaredMethod("isAuthError", Throwable::class.java)
        method.isAccessible = true

        assertTrue(method.invoke(worker, authEx) as Boolean)
        assertFalse(method.invoke(worker, genericEx) as Boolean)
    }

    @Test
    fun `cancellation exception is rethrown and not caught as transient error`() {
        ImapConnector.folderAdapter = FakeErrorFolderAdapter(throwCancellation = true)

        val dummyContext = CredentialStoreMockkTest.TestContext()
        val dummyParams = createDummyWorkerParameters()

        val testPrefs = CredentialStoreMockkTest.FakeSharedPreferences().apply {
            map["accounts_json"] = "[{\"presetLabel\":\"Work\",\"email\":\"test@example.com\",\"password\":\"pass\"}]"
        }
        CredentialStore.prefsFactory = { testPrefs }

        try {
            val worker = MailCheckWorker(dummyContext, dummyParams)
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    worker.doWork()
                }
            }
        } finally {
            CredentialStore.prefsFactory = null
        }
    }
}
