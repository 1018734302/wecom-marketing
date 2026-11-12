package com.entwc.marketing.event;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 事件流水，兼作回调幂等表。
 */
@Repository
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private static final int PAYLOAD_LIMIT = 2000;

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入事件，若 event_key 已存在则返回 false（说明是重复投递）。
     */
    public boolean insertIfAbsent(String eventKey, String type, String payload, LocalDateTime occurredAt) {
        String safePayload = payload != null && payload.length() > PAYLOAD_LIMIT
                ? payload.substring(0, PAYLOAD_LIMIT)
                : payload;
        try {
            jdbc.sql("INSERT INTO ev_event (event_key, type, payload, occurred_at) VALUES (?, ?, ?, ?)")
                    .params(eventKey, type, safePayload, Timestamp.valueOf(occurredAt))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("重复事件已忽略: {}", eventKey);
            return false;
        }
    }
}
