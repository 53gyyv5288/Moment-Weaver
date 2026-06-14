"""叙事生成路由（M4 落地）。"""
from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class NarrativeRequest(BaseModel):
    template_id: str  # person | family
    subject_name: str
    facts: list[dict] = []
    timeline: list[dict] = []


@router.post("/")
async def generate(_req: NarrativeRequest) -> dict:
    """占位实现：M4 阶段实现 2 模板（人物小传 / 家族小传）。"""
    return {
        "template_id": _req.template_id,
        "title": f"{_req.subject_name} - {_req.template_id}",
        "content": "[M0 占位] 叙事生成尚未实现。M4 阶段实现 2 模板。",
    }
