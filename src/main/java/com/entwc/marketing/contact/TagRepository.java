package com.entwc.marketing.contact;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 标签定义与客户标签关联。
 */
@Repository
public class TagRepository {

    private final JdbcClient jdbc;

    public TagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findIdByName(String name) {
        return jdbc.sql("SELECT id FROM ct_tag WHERE name = ?")
                .params(name)
                .query(Long.class)
                .optional();
    }

    public long findOrCreate(String name) {
        Optional<Long> existing = findIdByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql("INSERT IGNORE INTO ct_tag (name) VALUES (?)")
                .params(name)
                .update();
        return findIdByName(name).orElseThrow(() -> new IllegalStateException("标签创建失败: " + name));
    }

    public void bind(long customerId, long tagId) {
        jdbc.sql("INSERT IGNORE INTO ct_customer_tag (customer_id, tag_id) VALUES (?, ?)")
                .params(customerId, tagId)
                .update();
    }
}
