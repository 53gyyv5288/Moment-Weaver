"""模板抽象基类（M4）。"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class SectionMeta:
    sectionId: str
    sectionTitle: str
    targetCharsMin: int
    targetCharsMax: int
    # ai | human_first | mixed | system —— 模板里标注的本节期望来源策略
    markPolicy: str = "ai"


@dataclass
class Template:
    template_id: str
    sections: list[SectionMeta] = field(default_factory=list)

    def build_messages(self, facts: list[dict], subjects: list[dict]) -> list[dict]:
        """生成完整 draft 的 LLM 输入 messages。"""
        raise NotImplementedError

    def coerce_sections(self, ai_json: dict) -> list[dict]:
        """校验/兜底 AI 返回的 sections 列表。返回 [{sectionId, sectionTitle, content, factsUsed}]"""
        raise NotImplementedError

    def build_rewrite_messages(
        self,
        section_id: str,
        section_title: str,
        current_content: str,
        style: str,
        facts: list[dict],
        subjects: list[dict],
    ) -> list[dict]:
        """生成单章节重写的 LLM 输入 messages。返回的 content 是纯文本，不是 JSON 包装。"""
        raise NotImplementedError

    # --------- helpers ---------

    @staticmethod
    def _format_facts(facts: list[dict]) -> str:
        """把 facts 列表渲染成可读文本。"""
        if not facts:
            return "（无事实）"
        lines: list[str] = []
        for f in facts:
            fid = f.get("factId") or f.get("fact_id") or "?"
            source = f.get("source") or "unknown"
            text = (f.get("text") or "").strip()
            if not text:
                continue
            lines.append(f"- [{fid}] ({source}) {text}")
        return "\n".join(lines) if lines else "（无事实）"

    @staticmethod
    def _format_subjects(subjects: list[dict]) -> str:
        if not subjects:
            return "（未提供）"
        lines: list[str] = []
        for s in subjects:
            sid = s.get("subjectId") or s.get("subject_id") or "?"
            name = s.get("name") or "未命名"
            relation = s.get("relation") or ""
            year = s.get("birthYear") or s.get("birth_year") or ""
            extra = []
            if relation:
                extra.append(f"关系：{relation}")
            if year:
                extra.append(f"出生年：{year}")
            extra_s = " · ".join(extra) if extra else "无附加信息"
            lines.append(f"- [{sid}] {name}（{extra_s}）")
        return "\n".join(lines)
