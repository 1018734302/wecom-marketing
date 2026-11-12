package com.entwc.marketing.contact;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.entwc.marketing.common.PageResult;

/**
 * 客户档案业务逻辑。
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final TagRepository tags;

    public CustomerService(CustomerRepository customers, TagRepository tags) {
        this.customers = customers;
        this.tags = tags;
    }

    /**
     * 添加好友时落档：不存在则新建，曾被删除则重新激活。
     */
    public Customer upsertOnAdd(String externalUid, String followUser, LocalDateTime addTime) {
        Optional<Customer> existing = customers.findByExternalUid(externalUid);
        if (existing.isPresent()) {
            Customer customer = existing.get();
            if ("deleted".equals(customer.status())) {
                customers.reactivate(externalUid, followUser);
                return customers.findByExternalUid(externalUid).orElseThrow();
            }
            return customer;
        }
        customers.insert(externalUid, followUser, addTime);
        return customers.findByExternalUid(externalUid)
                .orElseThrow(() -> new IllegalStateException("客户写入失败: " + externalUid));
    }

    public void markWelcomed(Long customerId) {
        customers.markWelcomed(customerId);
    }

    public void updateProfile(Long customerId, String name, String avatar) {
        if ((name == null || name.isBlank()) && (avatar == null || avatar.isBlank())) {
            return;
        }
        customers.updateProfile(customerId, name == null ? "" : name, avatar == null ? "" : avatar);
    }

    public void markDeleted(String externalUid) {
        customers.markDeleted(externalUid);
    }

    /**
     * 绑定标签，标签不存在时自动创建。
     */
    public void bindTag(Long customerId, String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return;
        }
        long tagId = tags.findOrCreate(tagName);
        tags.bind(customerId, tagId);
    }

    public PageResult<Customer> page(String status, String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        long total = customers.count(status, keyword);
        List<Customer> items = customers.findPage(status, keyword, (safePage - 1) * safeSize, safeSize);
        return new PageResult<>(total, items);
    }
}
