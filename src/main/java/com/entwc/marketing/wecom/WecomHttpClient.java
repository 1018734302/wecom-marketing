package com.entwc.marketing.wecom;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.entwc.marketing.common.BizException;

/**
 * 企微接口实现，基于 REST 直连。
 * 若后续引入 WxJava，可替换本类实现而无需改动业务代码。
 */
@Component
public class WecomHttpClient implements WecomApi {

    private static final Logger log = LoggerFactory.getLogger(WecomHttpClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WecomTokenHolder tokenHolder;
    private final RestClient restClient;
    private final Map<String, String> tagCache = new ConcurrentHashMap<>();

    public WecomHttpClient(WecomTokenHolder tokenHolder, RestClient wecomRestClient) {
        this.tokenHolder = tokenHolder;
        this.restClient = wecomRestClient;
    }

    @Override
    public String accessToken() {
        return tokenHolder.get();
    }

    @Override
    public void sendWelcome(String welcomeCode, String text) {
        Map<String, Object> resp = restClient.post()
                .uri(b -> b.path("/cgi-bin/externalcontact/send_welcome_msg")
                        .queryParam("access_token", accessToken())
                        .build())
                .body(Map.of(
                        "welcome_code", welcomeCode,
                        "text", Map.of("content", text)))
                .retrieve()
                .body(MAP_TYPE);
        check(resp, "发送欢迎语");
    }

    @Override
    public String ensureCorpTag(String tagName) {
        String cached = tagCache.get(tagName);
        if (cached != null) {
            return cached;
        }
        String existing = findCorpTagId(tagName);
        if (existing != null) {
            tagCache.put(tagName, existing);
            return existing;
        }

        Map<String, Object> resp = restClient.post()
                .uri(b -> b.path("/cgi-bin/externalcontact/add_corp_tag")
                        .queryParam("access_token", accessToken())
                        .build())
                .body(Map.of(
                        "group_name", "营销标签",
                        "tag", List.of(Map.of("name", tagName))))
                .retrieve()
                .body(MAP_TYPE);
        check(resp, "创建企业标签");

        Map<String, Object> group = asMap(resp.get("tag_group"));
        List<?> tags = asList(group.get("tag"));
        if (tags.isEmpty()) {
            throw new BizException(3004, "创建企业标签后未返回标签 id");
        }
        String id = String.valueOf(asMap(tags.get(0)).get("id"));
        tagCache.put(tagName, id);
        return id;
    }

    @Override
    public void markTag(String userId, String externalUserId, String corpTagId) {
        Map<String, Object> resp = restClient.post()
                .uri(b -> b.path("/cgi-bin/externalcontact/mark_tag")
                        .queryParam("access_token", accessToken())
                        .build())
                .body(Map.of(
                        "userid", userId,
                        "external_userid", externalUserId,
                        "add_tag", List.of(corpTagId)))
                .retrieve()
                .body(MAP_TYPE);
        check(resp, "客户打标签");
    }

    @Override
    public WecomCustomerDetail getCustomer(String userId, String externalUserId) {
        Map<String, Object> resp = restClient.get()
                .uri(b -> b.path("/cgi-bin/externalcontact/get")
                        .queryParam("access_token", accessToken())
                        .queryParam("userid", userId)
                        .queryParam("external_userid", externalUserId)
                        .build())
                .retrieve()
                .body(MAP_TYPE);
        check(resp, "获取客户详情");

        Map<String, Object> contact = asMap(resp.get("external_contact"));
        return new WecomCustomerDetail(
                stringOf(contact.get("external_userid")),
                stringOf(contact.getOrDefault("name", "")),
                stringOf(contact.getOrDefault("avatar", "")),
                stringOf(contact.getOrDefault("unionid", "")));
    }

    private String findCorpTagId(String tagName) {
        Map<String, Object> resp = restClient.get()
                .uri(b -> b.path("/cgi-bin/externalcontact/get_corp_tag_list")
                        .queryParam("access_token", accessToken())
                        .build())
                .retrieve()
                .body(MAP_TYPE);
        check(resp, "查询企业标签");

        for (Object groupObj : asList(resp.get("tag_group"))) {
            for (Object tagObj : asList(asMap(groupObj).get("tag"))) {
                Map<String, Object> tag = asMap(tagObj);
                if (tagName.equals(stringOf(tag.get("name")))) {
                    return stringOf(tag.get("id"));
                }
            }
        }
        return null;
    }

    private void check(Map<String, Object> resp, String action) {
        if (resp == null) {
            throw new BizException(3003, action + "失败：企微未返回内容");
        }
        int errcode = WecomTokenHolder.errcodeOf(resp);
        if (errcode != 0) {
            log.warn("企微接口调用失败 action={} errcode={} errmsg={}", action, errcode, resp.get("errmsg"));
            throw new BizException(3003, action + "失败：" + errcode + " " + resp.getOrDefault("errmsg", ""));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
