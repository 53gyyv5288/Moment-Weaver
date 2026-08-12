package com.momentweaver.rag;

/**
 * 模块存在性 marker。Spring Boot 自动扫描机制下，
 * 该类被显式 import / 引用即触发本模块 Bean 注册。
 */
public final class RagModuleMarker {
    private RagModuleMarker() {}
}