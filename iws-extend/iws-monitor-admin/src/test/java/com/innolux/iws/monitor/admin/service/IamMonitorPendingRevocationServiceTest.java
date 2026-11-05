package com.innolux.iws.monitor.admin.service;

import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class IamMonitorPendingRevocationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void retryRemovesOnlyConfirmedItemAndPreservesConcurrentEnqueue() throws Exception {
        Path queueFile = tempDir.resolve("pending.txt");
        IamMonitorProperties properties = properties(queueFile.toString());
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        AtomicBoolean retryEntered = new AtomicBoolean();
        AtomicBoolean releaseRetry = new AtomicBoolean();
        AtomicInteger aCalls = new AtomicInteger();

        doAnswer(invocation -> {
            String rootSid = invocation.getArgument(0);
            if ("root-a".equals(rootSid)) {
                if (aCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("模拟首次撤销失败");
                }
                retryEntered.set(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!releaseRetry.get() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                if (!releaseRetry.get()) {
                    throw new IllegalStateException("测试等待超时");
                }
                return true;
            }
            throw new IllegalStateException("模拟新增撤销失败");
        }).when(client).revoke(anyString(), anyString(), anyString(), anyLong(), anyString());

        IamMonitorPendingRevocationService service =
            new IamMonitorPendingRevocationService(properties, client);
        assertTrue(service.revokeOrQueue(request("root-a")).pending());
        assertTrue(Files.readString(queueFile).contains("root-a"));

        Thread retry = new Thread(service::retryPending);
        retry.start();
        long retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!retryEntered.get() && System.nanoTime() < retryDeadline) {
            Thread.sleep(10L);
        }
        assertTrue(retryEntered.get());
        assertTrue(service.revokeOrQueue(request("root-b")).pending());
        assertTrue(Files.readString(queueFile).contains("root-b"));

        releaseRetry.set(true);
        retry.join(10_000);
        assertFalse(retry.isAlive());

        String remaining = Files.readString(queueFile);
        assertFalse(remaining.contains("root-a"));
        assertTrue(remaining.contains("root-b"));
    }

    @Test
    void writeFailureIsNotReportedAsDurablyQueued() throws Exception {
        Path queueDirectory = tempDir.resolve("not-a-queue-file");
        Files.createDirectories(queueDirectory);
        IamMonitorProperties properties = properties(queueDirectory.toString());
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("模拟中心不可达");
        }).when(client).revoke(anyString(), anyString(), anyString(), anyLong(), anyString());

        IamMonitorPendingRevocationService service =
            new IamMonitorPendingRevocationService(properties, client);
        IamMonitorPendingRevocationService.PendingRevocationResult result =
            service.revokeOrQueue(request("root-write-failure"));

        assertFalse(result.centerRevoked());
        assertFalse(result.pending());
        assertTrue(result.persistenceFailed());
        assertTrue(Files.isDirectory(queueDirectory));
    }

    @Test
    void readFailurePreservesExistingQueueAndDoesNotReportPersisted() throws Exception {
        Path queueFile = tempDir.resolve("pending-invalid-utf8.txt");
        byte[] original = new byte[]{
            'r', 'o', 'o', 't', '-', 'o', 'l', 'd', '\n', (byte) 0xC3, (byte) 0x28
        };
        Files.write(queueFile, original);
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("模拟中心不可达");
        }).when(client).revoke(anyString(), anyString(), anyString(), anyLong(), anyString());

        IamMonitorPendingRevocationService service =
            new IamMonitorPendingRevocationService(properties(queueFile.toString()), client);
        IamMonitorPendingRevocationService.PendingRevocationResult result =
            service.revokeOrQueue(request("root-new"));

        assertFalse(result.centerRevoked());
        assertFalse(result.pending());
        assertTrue(result.persistenceFailed());
        assertArrayEquals(original, Files.readAllBytes(queueFile));
    }

    @Test
    void atomicReplacementFailurePreservesPreviousQueueFile() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        Path queueFile = tempDir.resolve("pending-readonly.txt");
        String original = "root-old\n";
        Files.writeString(queueFile, original, StandardCharsets.UTF_8);
        DosFileAttributeView attributes =
            Files.getFileAttributeView(queueFile, DosFileAttributeView.class);
        attributes.setReadOnly(true);
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("模拟中心不可达");
        }).when(client).revoke(anyString(), anyString(), anyString(), anyLong(), anyString());

        try {
            IamMonitorPendingRevocationService service =
                new IamMonitorPendingRevocationService(properties(queueFile.toString()), client);
            IamMonitorPendingRevocationService.PendingRevocationResult result =
                service.revokeOrQueue(request("root-new"));

            assertFalse(result.centerRevoked());
            assertFalse(result.pending());
            assertTrue(result.persistenceFailed());
        } finally {
            attributes.setReadOnly(false);
        }
        assertEquals(original, Files.readString(queueFile, StandardCharsets.UTF_8));
    }

    @Test
    void centerRecoveryRetriesPersistedItemAndClearsQueue() throws Exception {
        Path queueFile = tempDir.resolve("pending-recovery.txt");
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟首次中心不可达");
            }
            return true;
        }).when(client).revoke(
            "root-recover",
            "monitor-main",
            "monitor-session-root-recover",
            1L,
            "WEB"
        );

        IamMonitorPendingRevocationService service =
            new IamMonitorPendingRevocationService(properties(queueFile.toString()), client);
        IamMonitorPendingRevocationService.PendingRevocationResult queued =
            service.revokeOrQueue(request("root-recover"));
        assertTrue(queued.pending());
        assertTrue(Files.readString(queueFile).contains("root-recover"));

        service.retryPending();

        assertEquals("", Files.readString(queueFile));
        verify(client, times(2)).revoke(
            "root-recover",
            "monitor-main",
            "monitor-session-root-recover",
            1L,
            "WEB"
        );
    }

    @Test
    void legacyRootOnlyQueueEntryIsDiscardedWithoutRevokingCenter() throws Exception {
        Path queueFile = tempDir.resolve("pending-legacy.txt");
        Files.writeString(queueFile, "root-legacy\n", StandardCharsets.UTF_8);
        IamMonitorCenterClient client = mock(IamMonitorCenterClient.class);
        IamMonitorPendingRevocationService service =
            new IamMonitorPendingRevocationService(properties(queueFile.toString()), client);

        service.retryPending();

        verifyNoInteractions(client);
        assertEquals("", Files.readString(queueFile, StandardCharsets.UTF_8));
    }

    private IamMonitorProperties properties(String queueFile) {
        IamMonitorProperties properties = new IamMonitorProperties();
        properties.setApplicationId("monitor-test");
        properties.setPendingRevokeFile(queueFile);
        return properties;
    }

    private IamSessionStatusRequest request(String rootSid) {
        return new IamSessionStatusRequest(
            rootSid,
            "monitor-test",
            "monitor-main",
            "monitor-session-" + rootSid,
            1L,
            "WEB"
        );
    }
}
