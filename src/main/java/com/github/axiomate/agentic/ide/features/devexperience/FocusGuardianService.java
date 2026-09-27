package com.github.axiomate.agentic.ide.features.devexperience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 49: Focus guardian.
 * It batches agent questions and notifications so they don't interrupt deep work.
 */
public class FocusGuardianService {

    private static final Logger log = LoggerFactory.getLogger(FocusGuardianService.class);
    private static FocusGuardianService instance;

    public interface BatchReleaseListener {
        void onBatchReleased(List<QueuedNotification> notifications);
    }

    private boolean focusModeActive = true;
    private final List<QueuedNotification> queuedNotifications = new CopyOnWriteArrayList<>();
    private final List<BatchReleaseListener> listeners = new CopyOnWriteArrayList<>();

    private FocusGuardianService() {}

    public static synchronized FocusGuardianService getInstance() {
        if (instance == null) {
            instance = new FocusGuardianService();
        }
        return instance;
    }

    public boolean isFocusModeActive() { return focusModeActive; }
    public void setFocusModeActive(boolean active) {
        this.focusModeActive = active;
        log.info("Focus Guardian mode active: {}", active);
        if (!active) {
            flushBatch();
        }
    }

    public void addListener(BatchReleaseListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(BatchReleaseListener listener) {
        listeners.remove(listener);
    }

    /**
     * Attempts to post an agent question or notification.
     * If Focus Mode is active and priority is not URGENT, queues it.
     * Returns true if displayed immediately, false if queued.
     */
    public boolean postOrQueue(String category, String title, String message, String priority) {
        if (!focusModeActive || "URGENT".equalsIgnoreCase(priority)) {
            log.info("Notification bypasses focus queue (urgent or focus disabled): {}", title);
            return true;
        }

        QueuedNotification item = new QueuedNotification(
                "notif-" + System.currentTimeMillis() + "-" + (queuedNotifications.size() + 1),
                category,
                title,
                message,
                priority != null ? priority : "NORMAL",
                Instant.now()
        );
        queuedNotifications.add(item);
        log.info("Focus Guardian queued notification: {} (Total queued: {})", title, queuedNotifications.size());
        return false;
    }

    public List<QueuedNotification> getQueuedNotifications() {
        return new ArrayList<>(queuedNotifications);
    }

    public int getQueueCount() {
        return queuedNotifications.size();
    }

    /**
     * Flushes and releases all batched notifications to the developer in one pass.
     */
    public List<QueuedNotification> flushBatch() {
        List<QueuedNotification> batch = new ArrayList<>(queuedNotifications);
        queuedNotifications.clear();

        for (BatchReleaseListener l : listeners) {
            try {
                l.onBatchReleased(batch);
            } catch (Exception e) {
                log.warn("Error notifying batch release listener", e);
            }
        }

        log.info("Focus Guardian released batch of {} notification(s)", batch.size());
        return batch;
    }
}
