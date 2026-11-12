package com.entwc.marketing.contact;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 客户档案数据访问。
 */
@Repository
public class CustomerRepository {

    private static final String COLUMNS = "id, external_uid, name, avatar, follow_user, source, status, add_time, welcomed_at";

    private static final RowMapper<Customer> MAPPER = (rs, rowNum) -> {
        Timestamp welcomed = rs.getTimestamp("welcomed_at");
        return new Customer(
                rs.getLong("id"),
                rs.getString("external_uid"),
                rs.getString("name"),
                rs.getString("avatar"),
                rs.getString("follow_user"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getTimestamp("add_time").toLocalDateTime(),
                welcomed == null ? null : welcomed.toLocalDateTime());
    };

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findByExternalUid(String externalUid) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ct_customer WHERE external_uid = ?")
                .params(externalUid)
                .query(MAPPER)
                .optional();
    }

    public void insert(String externalUid, String followUser, LocalDateTime addTime) {
        jdbc.sql("""
                        INSERT INTO ct_customer (external_uid, follow_user, add_time, status)
                        VALUES (?, ?, ?, 'active')
                        """)
                .params(externalUid, followUser, Timestamp.valueOf(addTime))
                .update();
    }

    public void reactivate(String externalUid, String followUser) {
        jdbc.sql("UPDATE ct_customer SET status = 'active', follow_user = ? WHERE external_uid = ?")
                .params(followUser, externalUid)
                .update();
    }

    public void updateProfile(long id, String name, String avatar) {
        jdbc.sql("UPDATE ct_customer SET name = ?, avatar = ? WHERE id = ?")
                .params(name, avatar, id)
                .update();
    }

    public void markWelcomed(long id) {
        jdbc.sql("UPDATE ct_customer SET welcomed_at = NOW() WHERE id = ?")
                .params(id)
                .update();
    }

    public void markDeleted(String externalUid) {
        jdbc.sql("UPDATE ct_customer SET status = 'deleted' WHERE external_uid = ?")
                .params(externalUid)
                .update();
    }

    public List<Customer> findPage(String status, String keyword, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM ct_customer WHERE 1 = 1" + where(status, keyword, args)
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.sql(sql).params(args.toArray()).query(MAPPER).list();
    }

    public long count(String status, String keyword) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM ct_customer WHERE 1 = 1" + where(status, keyword, args);
        Long total = jdbc.sql(sql).params(args.toArray()).query(Long.class).single();
        return total == null ? 0 : total;
    }

    private static String where(String status, String keyword, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (name LIKE ? OR external_uid LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        return sb.toString();
    }
}
