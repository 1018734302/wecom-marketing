package com.entwc.marketing.contact;

import java.time.LocalDateTime;

/**
 * 客户档案。
 */
public record Customer(
        Long id,
        String externalUid,
        String name,
        String avatar,
        String followUser,
        String source,
        String status,
        LocalDateTime addTime,
        LocalDateTime welcomedAt) {
}
