package com.example.notification.notification;

import com.example.notification.common.Time;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationRepository {

    /** Section 6.5 */
    public record NotificationResponse(String id, String type, String text, boolean read, String referenceId,
                                       OffsetDateTime createdAt) {
    }

    public record Row(long seq, NotificationResponse notification) {
    }

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, UUID userId, String type, String text, UUID referenceId) {
        jdbc.sql("""
                INSERT INTO notifications (id, event_id, user_id, type, text, reference_id, created_at)
                VALUES (:id, :eventId, :userId, :type, :text, :referenceId, :createdAt)
                ON CONFLICT (event_id, user_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eventId", eventId)
                .param("userId", userId)
                .param("type", type)
                .param("text", text)
                .param("referenceId", referenceId)
                .param("createdAt", Time.now())
                .update();
    }

    public List<Row> list(UUID userId, boolean unreadOnly, int limit, Long cursor) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, seq, type, text, read, reference_id, created_at FROM notifications WHERE user_id = :userId");
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        if (unreadOnly) {
            sql.append(" AND read = FALSE");
        }
        if (cursor != null) {
            sql.append(" AND seq < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY seq DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> new Row(rs.getLong("seq"),
                new NotificationResponse(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("type"),
                        rs.getString("text"),
                        rs.getBoolean("read"),
                        rs.getObject("reference_id", UUID.class).toString(),
                        rs.getObject("created_at", OffsetDateTime.class)))).list();
    }

    public long unreadCount(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND read = FALSE")
                .param("userId", userId).query(Long.class).single();
    }

    /** Returns false when the notification does not exist or belongs to another user (API-4). */
    public boolean markRead(UUID id, UUID userId) {
        return jdbc.sql("UPDATE notifications SET read = TRUE WHERE id = :id AND user_id = :userId")
                .param("id", id).param("userId", userId).update() > 0;
    }

    public void markAllRead(UUID userId) {
        jdbc.sql("UPDATE notifications SET read = TRUE WHERE user_id = :userId AND read = FALSE")
                .param("userId", userId).update();
    }
}
