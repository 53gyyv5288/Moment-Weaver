"""图像基础分析路由（M3 落地）。"""
from fastapi import APIRouter

router = APIRouter()


@router.post("/ocr")
async def ocr() -> dict:
    """占位实现：M3 阶段接入 OCR / 敏感信息检测。"""
    return {"text": "", "pii_hits": []}
