"""人物小传模板（person-template-v1）。

6 个章节：开篇 / 童年时光 / 家庭亲情 / 人生成就 / 性格特点 / 结语
"""
from __future__ import annotations

import json

from app.services.prompts import (
    NARRATIVE_PERSON_SYSTEM,
    NARRATIVE_PERSON_USER_TEMPLATE,
    NARRATIVE_REWRITE_SYSTEM,
    NARRATIVE_REWRITE_USER_TEMPLATE,
)
from app.services.templates.base import SectionMeta, Template


_PERSON_V1_SECTIONS: list[SectionMeta] = [
    SectionMeta("intro", "开篇", 100, 200, "ai"),
    SectionMeta("earlyLife", "童年时光", 200, 400, "ai"),
    SectionMeta("family", "家庭亲情", 200, 400, "ai"),
    SectionMeta("achievements", "人生成就", 200, 400, "ai"),
    SectionMeta("personality", "性格特点", 200, 400, "ai"),
    SectionMeta("reflection", "结语", 100, 200, "mixed"),
]


class PersonV1Template(Template):
    template_id = "person-template-v1"
    sections: list[SectionMeta] = _PERSON_V1_SECTIONS

    def __init__(self) -> None:
        super().__init__(template_id=self.template_id, sections=_PERSON_V1_SECTIONS)

    def build_messages(self, facts: list[dict], subjects: list[dict]) -> list[dict]:
        sections_json = json.dumps(
            [s.__dict__ for s in _PERSON_V1_SECTIONS],
            ensure_ascii=False, indent=2,
        )
        primary = subjects[0] if subjects else {}
        primary_name = primary.get("name") or "此人"
        system = NARRATIVE_PERSON_SYSTEM.format(sections_json=sections_json)
        user = NARRATIVE_PERSON_USER_TEMPLATE.format(
            primary_subject_name=primary_name,
            subjects_text=self._format_subjects(subjects),
            facts_text=self._format_facts(facts),
        )
        return [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]

    def coerce_sections(self, ai_json: dict) -> list[dict]:
        """校验/兜底 AI 返回的 sections。

        返回值结构：[{"sectionId", "sectionTitle", "content", "factsUsed"}]
        按 _PERSON_V1_SECTIONS 的顺序补齐；缺章节用空 content 占位。
        """
        valid_ids = {s.sectionId for s in _PERSON_V1_SECTIONS}
        title_lookup = {s.sectionId: s.sectionTitle for s in _PERSON_V1_SECTIONS}

        raw_sections = ai_json.get("sections") or []
        by_id: dict[str, dict] = {}
        for s in raw_sections:
            if not isinstance(s, dict):
                continue
            sid = s.get("sectionId") or s.get("section_id")
            if not sid or sid not in valid_ids:
                continue
            if sid in by_id:
                continue  # 重复，保留第一个
            by_id[sid] = s

        out: list[dict] = []
        for meta in _PERSON_V1_SECTIONS:
            raw = by_id.get(meta.sectionId)
            if raw is None:
                out.append({
                    "sectionId": meta.sectionId,
                    "sectionTitle": title_lookup[meta.sectionId],
                    "content": "",
                    "factsUsed": [],
                })
                continue
            content = (raw.get("content") or "").strip()
            facts_used = raw.get("factsUsed") or raw.get("facts_used") or []
            if not isinstance(facts_used, list):
                facts_used = []
            facts_used = [str(f) for f in facts_used if f]
            out.append({
                "sectionId": meta.sectionId,
                "sectionTitle": title_lookup[meta.sectionId],
                "content": content,
                "factsUsed": facts_used,
            })
        return out

    def build_rewrite_messages(
        self,
        section_id: str,
        section_title: str,
        current_content: str,
        style: str,
        facts: list[dict],
        subjects: list[dict],
    ) -> list[dict]:
        meta = next((s for s in _PERSON_V1_SECTIONS if s.sectionId == section_id), None)
        if meta is None:
            raise ValueError(f"未知章节：{section_id}")
        style_label = _STYLE_LABELS.get(style, style)
        user = NARRATIVE_REWRITE_USER_TEMPLATE.format(
            style_label=style_label,
            section_title=section_title,
            current_content=current_content or "（当前无内容）",
            facts_text=self._format_facts(facts),
            subjects_text=self._format_subjects(subjects),
            target_min=meta.targetCharsMin,
            target_max=meta.targetCharsMax,
        )
        return [
            {"role": "system", "content": NARRATIVE_REWRITE_SYSTEM},
            {"role": "user", "content": user},
        ]


_STYLE_LABELS = {
    "warmer": "更温暖",
    "concise": "更简洁",
    "vivid": "更生动（多用画面感细节）",
    "formal": "更正式",
}
