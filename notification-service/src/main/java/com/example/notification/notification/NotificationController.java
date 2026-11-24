package com.example.notification.notification;

import com.example.notification.common.CurrentUser;
import com.example.notification.common.Ids;
import com.example.notification.common.Pagination;
import com.example.notification.common.Pagination.Page;
import com.example.notification.error.ApiException;
import com.example.notification.event.Stats;
import com.example.notification.notification.NotificationRepository.NotificationResponse;
import com.example.notification.notification.NotificationRepository.Row;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NTF-3 and Section 6.5 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final Stats stats;

    public NotificationController(NotificationRepository notifications, Stats stats) {
        this.notifications = notifications;
        this.stats = stats;
    }

    @GetMapping
    public Page<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "false") boolean unreadOnly,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String cursor) {
        int pageSize = Pagination.limit(limit);
        List<Row> rows = notifications.list(CurrentUser.from(jwt).id(), unreadOnly, pageSize + 1,
                Pagination.decode(cursor));
        boolean more = rows.size() > pageSize;
        List<Row> page = more ? rows.subList(0, pageSize) : rows;
        String next = more ? Pagination.encode(page.get(page.size() - 1).seq()) : null;
        return new Page<>(page.stream().map(Row::notification).toList(), next);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", notifications.unreadCount(CurrentUser.from(jwt).id()));
    }

    /** Marking it again changes nothing and still answers 204. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        UUID notificationId = Ids.parse(id).orElseThrow(ApiException::notFound);
        if (!notifications.markRead(notificationId, CurrentUser.from(jwt).id())) {
            throw ApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(CurrentUser.from(jwt).id());
        return ResponseEntity.noContent().build();
    }

    /** NTF-5; only for ADMIN (see SecurityConfig) */
    @GetMapping("/admin/stats")
    public Stats.View stats() {
        return stats.view();
    }
}
