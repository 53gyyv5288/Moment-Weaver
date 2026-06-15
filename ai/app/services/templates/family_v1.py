"""家族小传模板（family-template-v1）。

5 个章节：家族简介 / 家族渊源 / 关键时刻 / 家族价值观 / 展望
"""
from __future__ import annotations

import json

from app.services.prompts import (
    NARRATIVE_FAMILY_SYSTEM,
    NARRATIVE_FAMILY_USER_TEMPLATE,
    NARRATIVE_REWRITE_SYSTEM,
    NARRATIVE_REWRITE_USER_TEMPLATE,
)
from app.services.templates.base import SectionMeta, Template


_FAMILY_V1_SECTIONS: list[SectionMeta] = [
    SectionMeta("intro", "家族简介", 200, 300, "ai"),
    SectionMeta("origins", "家族渊源", 300, 500, "ai"),
    SectionMeta("milestones", "关键时刻", 300, 500, "ai"),
    SectionMeta("values", "家族价值观", 300, 500, "ai"),
    SectionMeta("reflection", "展望", 200, 300, "mixed"),
]


class FamilyV1Template(Template):
    template_id = "family-template-v1"
    sections: list[SectionMeta] = _FAMILY_V1_SECTIONS

    def __init__(self) -> None:
        super().__init__(template_id=self.template_id, sections=_FAMILY_V1_SECTIONS)

    def build_messages(self, facts: list[dict], subjects: list[dict]) -> list[dict]:
        sections_json = json.dumps(
            [s.__dict__ for s in _FAMILY_V1_SECTIONS],
            ensure_ascii=False, indent=2,
        )
        system = NARRATIVE_FAMILY_SYSTEM.format(sections_json=sections_json)
        user = NARRATIVE_FAMILY_USER_TEMPLATE.format(
            subjects_text=self._format_subjects(subjects),
            facts_text=self._format_facts(facts),
        )
        return [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]

    def coerce_sections(self, ai_json: dict) -> list[dict]:
        valid_ids = {s.sectionId for s in _FAMILY_V1_SECTIONS}
        title_lookup = {s.sectionId: s.sectionTitle for s in _FAMILY_V1_SECTIONS}

        raw_sections = ai_json.get("sections") or []
        by_id: dict[str, dict] = {}
        for s in raw_sections:
            if not isinstance(s, dict):
                continue
            sid = s.get("sectionId") or s.get("section_id")
            if not sid or sid not in valid_ids or sid in by_id:
                continue
            by_id[sid] = s

        out: list[dict] = []
        for meta in _FAMILY_V1_SECTIONS:
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
        meta = next((s for s in _FAMILY_V1_SECTIONS if s.sectionId == section_id), None)
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
