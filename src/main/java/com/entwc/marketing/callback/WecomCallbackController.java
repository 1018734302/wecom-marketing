package com.entwc.marketing.callback;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import com.entwc.marketing.config.WecomProperties;
import com.entwc.marketing.wecom.WecomCrypto;

/**
 * 企微回调入口。
 * GET 用于配置回调地址时的 URL 校验，POST 用于接收事件推送。
 */
@RestController
@RequestMapping("/wecom/callback")
public class WecomCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WecomCallbackController.class);

    private final WecomProperties props;
    private final WecomEventService eventService;

    public WecomCallbackController(WecomProperties props, WecomEventService eventService) {
        this.props = props;
        this.eventService = eventService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(HttpServletRequest request) {
        // 不使用 @RequestParam：Tomcat 会把 Base64 中未转义的 '+' 解码成空格，导致签名不一致
        Map<String, String> query = parseQuery(request.getQueryString());
        String msgSignature = query.get("msg_signature");
        String timestamp = query.get("timestamp");
        String nonce = query.get("nonce");
        String echostr = query.get("echostr");

        if (msgSignature == null || timestamp == null || nonce == null || echostr == null) {
            log.warn("回调 URL 校验缺少必要参数");
            return "invalid signature";
        }
        if (!WecomCrypto.verify(props.getToken(), timestamp, nonce, echostr, msgSignature)) {
            log.warn("回调 URL 校验失败：签名不匹配，echostr 长度={}", echostr.length());
            return "invalid signature";
        }
        return WecomCrypto.decrypt(props.getAesKey(), echostr);
    }

    /** 解析原始 query string，只做百分号解码，不把 '+' 当作空格 */
    private static Map<String, String> parseQuery(String queryString) {
        Map<String, String> result = new HashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return result;
        }
        for (String pair : queryString.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            result.put(percentDecode(key), percentDecode(value));
        }
        return result;
    }

    private static String percentDecode(String raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                out.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String receive(@RequestParam("msg_signature") String msgSignature,
                          @RequestParam("timestamp") String timestamp,
                          @RequestParam("nonce") String nonce,
                          @RequestBody String body) {
        try {
            Map<String, String> outer = WecomXmlParser.parse(body);
            String encrypt = outer.get("Encrypt");
            if (!WecomCrypto.verify(props.getToken(), timestamp, nonce, encrypt, msgSignature)) {
                log.warn("事件推送签名校验失败");
                return "invalid signature";
            }
            String plain = WecomCrypto.decrypt(props.getAesKey(), encrypt);
            eventService.handle(WecomXmlParser.parse(plain), plain);
            return "success";
        } catch (Exception e) {
            // 返回非 success 时企微会重试，最多 3 次
            log.error("处理企微回调失败", e);
            return "fail";
        }
    }
}
