package com.entwc.marketing.callback;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.entwc.marketing.config.WecomProperties;
import com.entwc.marketing.contact.Customer;
import com.entwc.marketing.contact.CustomerService;
import com.entwc.marketing.event.EventRepository;
import com.entwc.marketing.wecom.WecomApi;
import com.entwc.marketing.wecom.WecomCustomerDetail;

/**
 * 企微事件处理：阶段一只处理"添加/删除外部联系人"。
 */
@Service
public class WecomEventService {

    private static final Logger log = LoggerFactory.getLogger(WecomEventService.class);

    private final WecomProperties props;
    private final CustomerService customerService;
    private final EventRepository eventRepository;
    private final WecomApi wecomApi;

    public WecomEventService(WecomProperties props,
                             CustomerService customerService,
                             EventRepository eventRepository,
                             WecomApi wecomApi) {
        this.props = props;
        this.customerService = customerService;
        this.eventRepository = eventRepository;
        this.wecomApi = wecomApi;
    }

    public void handle(Map<String, String> msg, String raw) {
        if (!props.isEnabled()) {
            log.debug("企微对接已禁用，忽略回调");
            return;
        }
        if (!"event".equalsIgnoreCase(msg.getOrDefault("MsgType", ""))) {
            log.debug("忽略非事件消息: {}", msg.get("MsgType"));
            return;
        }
        if (!"change_external_contact".equalsIgnoreCase(msg.getOrDefault("Event", ""))) {
            log.debug("忽略事件类型: {}", msg.get("Event"));
            return;
        }

        String changeType = msg.getOrDefault("ChangeType", "");
        switch (changeType) {
            case "add_external_contact" -> handleAdd(msg, raw);
            case "del_external_contact" -> handleDelete(msg, raw);
            default -> log.debug("忽略变更类型: {}", changeType);
        }
    }

    private void handleAdd(Map<String, String> msg, String raw) {
        String externalUserId = msg.get("ExternalUserID");
        String userId = msg.getOrDefault("UserID", "");
        if (externalUserId == null || externalUserId.isBlank()) {
            log.warn("添加客户事件缺少 ExternalUserID，已跳过");
            return;
        }

        LocalDateTime occurredAt = toTime(msg.get("CreateTime"));
        String eventKey = "add:" + externalUserId + ":" + msg.getOrDefault("CreateTime", "");
        if (!eventRepository.insertIfAbsent(eventKey, "customer.add", raw, occurredAt)) {
            return;
        }

        Customer customer = customerService.upsertOnAdd(externalUserId, userId, occurredAt);

        // 1. 欢迎语优先：企微要求添加好友后 20 秒内发送，这里不重试
        sendWelcomeIfNeeded(msg, customer);

        // 2. 补全客户资料
        fillProfile(userId, externalUserId, customer);

        // 3. 自动打标
        autoTag(userId, externalUserId, customer);

        log.info("新客户已处理: externalUserId={}, followUser={}", externalUserId, userId);
    }

    private void sendWelcomeIfNeeded(Map<String, String> msg, Customer customer) {
        if (customer.welcomedAt() != null) {
            return;
        }
        String welcomeCode = msg.get("WelcomeCode");
        if (welcomeCode == null || welcomeCode.isBlank()) {
            log.warn("客户 {} 未返回 welcome_code，跳过欢迎语", customer.externalUid());
            return;
        }
        try {
            wecomApi.sendWelcome(welcomeCode, props.getWelcomeText());
            customerService.markWelcomed(customer.id());
        } catch (Exception e) {
            // 窗口过期后重试必然失败，这里只记录
            log.error("发送欢迎语失败，客户={}", customer.externalUid(), e);
        }
    }

    private void fillProfile(String userId, String externalUserId, Customer customer) {
        if (userId.isBlank()) {
            return;
        }
        try {
            WecomCustomerDetail detail = wecomApi.getCustomer(userId, externalUserId);
            customerService.updateProfile(customer.id(), detail.name(), detail.avatar());
        } catch (Exception e) {
            log.warn("获取客户详情失败，客户={}", externalUserId, e);
        }
    }

    private void autoTag(String userId, String externalUserId, Customer customer) {
        if (userId.isBlank()) {
            return;
        }
        try {
            String corpTagId = wecomApi.ensureCorpTag(props.getAutoTag());
            wecomApi.markTag(userId, externalUserId, corpTagId);
            customerService.bindTag(customer.id(), props.getAutoTag());
        } catch (Exception e) {
            log.warn("自动打标失败，客户={}", externalUserId, e);
        }
    }

    private void handleDelete(Map<String, String> msg, String raw) {
        String externalUserId = msg.get("ExternalUserID");
        if (externalUserId == null || externalUserId.isBlank()) {
            return;
        }
        LocalDateTime occurredAt = toTime(msg.get("CreateTime"));
        String eventKey = "del:" + externalUserId + ":" + msg.getOrDefault("CreateTime", "");
        if (!eventRepository.insertIfAbsent(eventKey, "customer.delete", raw, occurredAt)) {
            return;
        }
        customerService.markDeleted(externalUserId);
        log.info("客户已流失: {}", externalUserId);
    }

    private static LocalDateTime toTime(String epochSecond) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(epochSecond)), ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
