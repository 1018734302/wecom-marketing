package com.entwc.marketing.wecom;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.entwc.marketing.common.BizException;
import com.entwc.marketing.config.WecomProperties;

/**
 * access_token 缓存。
 * 单实例部署下使用进程内缓存即可；若将来多实例部署，需换成 Redis 并加分布式锁。
 */
@Component
public class WecomTokenHolder {

    private static final Logger log = LoggerFactory.getLogger(WecomTokenHolder.class);
    private static final long REFRESH_AHEAD_MILLIS = 5 * 60 * 1000L;

    private final WecomProperties props;
    private final RestClient restClient;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String token;
    private volatile long expireAt;

    public WecomTokenHolder(WecomProperties props, RestClient wecomRestClient) {
        this.props = props;
        this.restClient = wecomRestClient;
    }

    public String get() {
        if (token != null && System.currentTimeMillis() < expireAt) {
            return token;
        }
        lock.lock();
        try {
            if (token != null && System.currentTimeMillis() < expireAt) {
                return token;
            }
            refresh();
            return token;
        } finally {
            lock.unlock();
        }
    }

    /** 主动失效，用于收到 token 失效错误码后重试 */
    public void invalidate() {
        lock.lock();
        try {
            token = null;
            expireAt = 0;
        } finally {
            lock.unlock();
        }
    }

    private void refresh() {
        if (props.getCorpId() == null || props.getCorpId().isBlank()
                || props.getSecret() == null || props.getSecret().isBlank()) {
            throw new BizException(3001, "企微 corp-id 或 secret 未配置");
        }
        Map<String, Object> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/cgi-bin/gettoken")
                        .queryParam("corpid", props.getCorpId())
                        .queryParam("corpsecret", props.getSecret())
                        .build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                });

        int errcode = errcodeOf(resp);
        if (errcode != 0) {
            throw new BizException(3002, "获取 access_token 失败: " + resp.get("errmsg"));
        }
        String newToken = String.valueOf(resp.get("access_token"));
        long expiresIn = ((Number) resp.getOrDefault("expires_in", 7200)).longValue();
        this.token = newToken;
        this.expireAt = System.currentTimeMillis() + expiresIn * 1000L - REFRESH_AHEAD_MILLIS;
        log.info("企微 access_token 已刷新，有效期 {} 秒", expiresIn);
    }

    static int errcodeOf(Map<String, Object> resp) {
        Object code = resp == null ? null : resp.get("errcode");
        return code instanceof Number n ? n.intValue() : 0;
    }
}
