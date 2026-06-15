"""叙事成稿模板注册表（M4）。

模板是「章节结构 + 渲染 prompt + 结果兜底」的三件套。
每个模板是一个 dataclass-style class，实现统一接口：

  - template_id: str
  - sections: list[SectionMeta]
  - build_messages(facts, subjects) -> list[dict]  # 给 chat() 的 messages
  - coerce_sections(ai_json) -> list[dict]         # 校验/兜底 AI 返回
  - build_rewrite_messages(...) -> list[dict]      # 单章节重写
"""
from app.services.templates.base import SectionMeta, Template
from app.services.templates.person_v1 import PersonV1Template
from app.services.templates.family_v1 import FamilyV1Template

REGISTRY: dict[str, Template] = {
    PersonV1Template.template_id: PersonV1Template(),
    FamilyV1Template.template_id: FamilyV1Template(),
}


def get(template_id: str) -> Template:
    """取模板；找不到抛 KeyError（由 router 转 400）。"""
    if template_id not in REGISTRY:
        raise KeyError(f"未知模板：{template_id}")
    return REGISTRY[template_id]


__all__ = ["SectionMeta", "Template", "get", "REGISTRY"]
