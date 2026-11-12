package com.entwc.marketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企微自建应用配置。
 */
@Component
@ConfigurationProperties(prefix = "wecom")
public class WecomProperties {

    /** 是否启用企微对接 */
    private boolean enabled = true;

    /** 企业 ID */
    private String corpId;

    /** 自建应用 AgentId */
    private String agentId;

    /** 自建应用 Secret */
    private String secret;

    /** 回调 Token */
    private String token;

    /** 回调 EncodingAESKey */
    private String aesKey;

    /** 企微接口域名 */
    private String baseUrl = "https://qyapi.weixin.qq.com";

    /** 添加好友后的欢迎语 */
    private String welcomeText = "你好，很高兴认识你！";

    /** 新客户自动标签名 */
    private String autoTag = "新客户";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWelcomeText() {
        return welcomeText;
    }

    public void setWelcomeText(String welcomeText) {
        this.welcomeText = welcomeText;
    }

    public String getAutoTag() {
        return autoTag;
    }

    public void setAutoTag(String autoTag) {
        this.autoTag = autoTag;
    }
}
