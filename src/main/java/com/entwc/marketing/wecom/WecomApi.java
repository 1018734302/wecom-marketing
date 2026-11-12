package com.entwc.marketing.wecom;

/**
 * 企微接口网关。业务代码只依赖本接口，不直接调用 HTTP。
 */
public interface WecomApi {

    /** 获取缓存中的 access_token */
    String accessToken();

    /** 发送新客户欢迎语，必须在添加好友后 20 秒内调用 */
    void sendWelcome(String welcomeCode, String text);

    /** 确保企业标签存在，返回企业标签 id */
    String ensureCorpTag(String tagName);

    /** 给客户打标签 */
    void markTag(String userId, String externalUserId, String corpTagId);

    /** 获取客户详情 */
    WecomCustomerDetail getCustomer(String userId, String externalUserId);
}
