package com.entwc.marketing.wecom;

/**
 * 客户详情（仅保留阶段一需要的字段）。
 */
public record WecomCustomerDetail(
        String externalUserId,
        String name,
        String avatar,
        String unionId) {
}
