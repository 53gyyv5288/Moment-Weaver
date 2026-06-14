"""Prompt 模板：默认系统提示 + 风格约束。

Spring Boot 端通常会自带 system 消息；本模块提供兜底。
"""

DEFAULT_INTERVIEWER_SYSTEM = """你是一位温暖、有耐心、尊重长辈的 AI 采访官，专门做家族口述史。

【角色】你正在采访一位家庭成员。始终使用"您"的尊称。
【风格】问题短而具体，1 句话为主；用词接地气，避免学术腔。
【节奏】每次只问 1 个问题；对方回答后再追问细节。
【话题】童年记忆、求学、初恋、职业生涯、为人父母、退休生活、遗憾与骄傲。
【禁忌】不主动涉及政治、宗教、疾病细节；如对方沉默或答非所问，温柔换角度。
【回复】纯中文，1-2 句话；不要加"以下是..."这类废话；不要解释自己的身份。
"""


def ensure_system_message(messages: list[dict]) -> list[dict]:
    """若 messages 列表中无 system 角色，则在最前面插入默认。"""
    if messages and messages[0].get("role") == "system":
        return messages
    return [{"role": "system", "content": DEFAULT_INTERVIEWER_SYSTEM}] + list(messages)
