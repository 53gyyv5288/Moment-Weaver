package com.momentweaver.timeline.config;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.timeline.dto.AiNarrativeRequest;
import com.momentweaver.timeline.dto.AiNarrativeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * AI narrative 客户端（M4）。
 * 复用 module-memory 里已经配置好的 aiWebClient（base URL / 超时 / header 全在那边统一）。
 */
@Slf4j
@Component
public class AiNarrativeClient {

    private final WebClient aiWebClient;

    public AiNarrativeClient(@Qualifier("aiWebClient") WebClient aiWebClient) {
        this.aiWebClient = aiWebClient;
    }

    /**
     * 整篇生成：POST /api/v1/narrative/generate
     * AI service 在 30s 内同步返回 title + sections。
     */
    public AiNarrativeResponse generate(AiNarrativeRequest req) {
        try {
            // 家族成稿 family-template-v1 (3 subjects) 实测 4~6 分钟，
            // AI service 端 llm_timeout_s=600s；这里留 2x = 1200s
            // 跟 application.yml 的 read-timeout-ms=1200000 保持一致
            AiNarrativeResponse resp = aiWebClient.post()
                .uri("/api/v1/narrative/generate")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(AiNarrativeResponse.class)
                .block(Duration.ofSeconds(1200));
            if (resp == null) {
                throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 生成返回为空");
            }
            log.info("AI narrative.generate ok: template={}, title={}, sections={}",
                resp.getTemplateId(), resp.getTitle(),
                resp.getSections() == null ? 0 : resp.getSections().size());
            return resp;
        } catch (BusinessException e) {
            throw e;
        } catch (WebClientResponseException e) {
            String errBody = e.getResponseBodyAsString();
            log.error("AI narrative.generate upstream {} body={}", e.getStatusCode(), errBody);
            // 把 AI service 返回的具体校验错误透出，便于排查 422
            String detail = errBody == null || errBody.isBlank() ? e.getStatusCode().toString() : errBody;
            throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR,
                "AI 生成失败：" + detail);
        } catch (Exception e) {
            log.error("AI narrative.generate failed", e);
            throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR,
                "AI 生成失败：" + e.getMessage());
        }
    }

    /**
     * 单章节重写：POST /api/v1/narrative/regenerate-section
     * AI service 返回纯文本（不是 JSON 包装）。
     */
    public String regenerateSection(String templateId, String sectionId, String sectionTitle,
                                    String currentContent, String style,
                                    AiNarrativeRequest req) {
        // 用一个简单的 record 转 JSON，避免单独写一个 RegenerateRequest DTO
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("templateId", templateId);
        body.put("sectionId", sectionId);
        body.put("sectionTitle", sectionTitle);
        body.put("currentContent", currentContent == null ? "" : currentContent);
        body.put("style", style);
        body.put("subjects", req.getSubjects());
        body.put("facts", req.getFacts());
        try {
            java.util.Map resp = aiWebClient.post()
                .uri("/api/v1/narrative/regenerate-section")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .block(Duration.ofSeconds(300));
            if (resp == null || !resp.containsKey("content")) {
                throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 重写返回为空");
            }
            String content = String.valueOf(resp.get("content"));
            log.info("AI narrative.regenerate ok: section={}, chars={}", sectionId, content.length());
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (WebClientResponseException e) {
            String errBody = e.getResponseBodyAsString();
            log.error("AI narrative.regenerate upstream {} body={}", e.getStatusCode(), errBody);
            String detail = errBody == null || errBody.isBlank() ? e.getStatusCode().toString() : errBody;
            throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR,
                "AI 重写失败：" + detail);
        } catch (Exception e) {
            log.error("AI narrative.regenerate failed", e);
            throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR,
                "AI 重写失败：" + e.getMessage());
        }
    }
}
