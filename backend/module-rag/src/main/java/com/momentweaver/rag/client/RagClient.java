package com.momentweaver.rag.client;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.rag.config.RagProperties;
import com.momentweaver.rag.dto.IngestRequest;
import com.momentweaver.rag.dto.SearchRequest;
import com.momentweaver.rag.dto.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * RAG 服务客户端：WebClient 调 FastAPI /api/v1/rag/*。
 *
 * <p>三个核心方法：
 * <ul>
 *   <li>{@link #searchEvidence}  同步检索（带超时控制；用于成稿 grounding）</li>
 *   <li>{@link #searchEvidenceAsync}  异步检索（用于采访流 + 时间线搜索）</li>
 *   <li>{@link #ingest}          写入 chunk（事件监听用）</li>
 * </ul>
 *
 * <p>失败策略：所有路径**不抛异常给上层**，降级返回空结果/忽略；
 * 这样 Spring 主业务（采访 / 时间线 / 成稿）不会被 RAG 拖垮。
 */
@Slf4j
@Component
public class RagClient {

    private final WebClient ragWebClient;
    private final RagProperties props;

    public RagClient(@Qualifier("ragWebClient") WebClient ragWebClient, RagProperties props) {
        this.ragWebClient = ragWebClient;
        this.props = props;
    }

    // ============ Search ============

    /**
     * 同步检索（阻塞等结果）。超时则返回空 list。
     * 适用：成稿 grounding（DraftService.generate）。
     */
    public List<com.momentweaver.rag.dto.EvidenceChunk> searchEvidence(
        String scenario, String query, String subjectId, Long userId) {
        if (!props.isEnabled()) return Collections.emptyList();
        if (subjectId == null || subjectId.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        SearchRequest req = SearchRequest.of(scenario, query, subjectId, userId);
        try {
            SearchResponse resp = ragWebClient.post()
                .uri("/api/v1/rag/search")
                .bodyValue(req)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r ->
                    r.bodyToMono(String.class)
                     .flatMap(b -> Mono.error(new BusinessException(
                         ResultCode.AI_UPSTREAM_ERROR, "rag search upstream " + r.statusCode() + ": " + b))))
                .bodyToMono(SearchResponse.class)
                .block(Duration.ofMillis(props.getReadTimeoutMs()));
            if (resp == null || resp.chunks() == null) {
                return Collections.emptyList();
            }
            log.debug("RAG search ok: scenario={} subject={} query={} n_chunks={}",
                scenario, subjectId, abbreviate(query, 30), resp.chunks().size());
            return resp.chunks();
        } catch (BusinessException e) {
            log.warn("RAG search rejected ({}): {}", e.getMessage(), abbreviate(query, 30));
            return Collections.emptyList();
        } catch (WebClientResponseException e) {
            log.warn("RAG search upstream {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("RAG search failed (non-fatal): {}", e.toString());
            return Collections.emptyList();
        }
    }

    /**
     * 异步 + 软超时（plan §4.3.A 采访流）。
     * <p>行为：
     * <ul>
     *   <li>props.searchSoftTimeoutMs 内拿到结果 → 返回</li>
     *   <li>超时 → 返回空 list，调用方继续 LLM（不阻塞首字）</li>
     *   <li>任何异常 → 返回空 list</li>
     * </ul>
     */
    public Mono<List<com.momentweaver.rag.dto.EvidenceChunk>> searchEvidenceAsync(
        String scenario, String query, String subjectId, Long userId) {
        if (!props.isEnabled()) return Mono.just(Collections.emptyList());
        if (subjectId == null || subjectId.isBlank() || query == null || query.isBlank()) {
            return Mono.just(Collections.emptyList());
        }
        SearchRequest req = SearchRequest.of(scenario, query, subjectId, userId);
        return ragWebClient.post()
            .uri("/api/v1/rag/search")
            .bodyValue(req)
            .retrieve()
            .bodyToMono(SearchResponse.class)
            .timeout(Duration.ofMillis(props.getSearchSoftTimeoutMs()))
            .map(resp -> resp == null || resp.chunks() == null
                ? Collections.<com.momentweaver.rag.dto.EvidenceChunk>emptyList()
                : resp.chunks())
            .onErrorResume(e -> {
                if (log.isDebugEnabled()) {
                    log.debug("RAG async search soft-timeout/fail: scenario={} err={}",
                        scenario, e.toString());
                }
                return Mono.just(Collections.<com.momentweaver.rag.dto.EvidenceChunk>emptyList());
            });
    }

    // ============ Ingest ============

    /**
     * 把 chunk 批量写入 Milvus（FastAPI /api/v1/rag/ingest）。
     * <p>失败仅 log，不抛 — ingest 是「增强」，主业务不被它拖垮。
     */
    public boolean ingest(IngestRequest req) {
        if (!props.isEnabled() || req == null || req.chunks() == null || req.chunks().isEmpty()) {
            return false;
        }
        try {
            ragWebClient.post()
                .uri("/api/v1/rag/ingest")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .block(Duration.ofMillis(props.getReadTimeoutMs() * 5));  // ingest 慢一些
            log.info("RAG ingest ok: collection={} n={}", req.collection(), req.chunks().size());
            return true;
        } catch (Exception e) {
            log.warn("RAG ingest failed (non-fatal): collection={} n={} err={}",
                req.collection(), req.chunks().size(), e.toString());
            return false;
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}