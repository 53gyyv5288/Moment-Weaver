"""心声信箱 · 不知道话术池。

四类触发场景，每类至少 20 句随机抽取，避免 AI 冷场/尴尬/胡编：
  - modern_topic: 用户问现代话题（AI/内卷/996 等）
  - no_material:  话题超出该人物采访素材（无相关原话可引用）
  - emotion_support: 用户表达强烈情绪
  - boundary: 医疗/法律/金融等专业领域

设计原则：
  1. 完全口语化（老派说话风格："你说那个""我那时候没这个""你比我懂"）
  2. 留出余地（不替逝者断言："这事你没跟我说过""我不瞎说"）
  3. 自然引导对话（不是死胡同："你给我讲讲""你想听哪段"）
"""

import random


# 现代词黑名单（命中即触发 modern_topic）。可按需扩充。
MODERN_KEYWORDS = (
    "AI", "人工智能", "大模型", "LLM",
    "内卷", "躺平", "996", "打工人", "互联网", "算法", "数字化",
    "App", "app", "小程序", "二维码", "扫码", "5G", "WiFi", "wifi",
    "区块链", "比特币", "元宇宙", "ChatGPT", "chatgpt", "GPT", "gpt",
    "自动驾驶", "电动车", "新能源",
)


# 边界词黑名单（命中即触发 boundary）
BOUNDARY_KEYWORDS = (
    "癌症", "化疗", "放疗", "手术", "癌症晚期",
    "抑郁症", "抑郁药", "精神病", "精神分裂",
    "律师", "起诉", "法院", "遗产税", "继承",
    "股票", "基金", "理财", "期货", "比特币", "数字货币",
    "离婚", "起诉离婚",
)


# 自杀危机干预信号
CRISIS_KEYWORDS = (
    "不想活", "想死", "自杀", "自残", "轻生",
    "活着没意思", "撑不下去", "不如死了", "了结自己",
)


# 情绪信号（命中即触发 emotion_support）
EMOTION_KEYWORDS = (
    "想你", "想爷爷", "想奶奶", "想你", "想你啊",
    "对不起", "后悔",
    "哭了", "流泪", "眼泪",
    "好累", "撑不住", "一个人",
    "好想你", "好想你啊",
)


MODERN_TOPIC_PHRASES = [
    "你说那个新词儿，我听不明白。",
    "这事你比我懂，我那时候没这个。",
    "你是念过书的人，你给我讲讲。",
    "那个新玩意儿，我不懂，你比我明白。",
    "我那时候没这玩意儿，你别问我。",
    "你这说的是什么，我听不懂。",
    "你们这代人搞的东西，我是跟不上咯。",
    "你跟我说这些，我真不明白。",
    "我那时候就一双布鞋，没听说过这些。",
    "你问的我答不上来，你说给我听听。",
]

NO_MATERIAL_PHRASES = [
    "你没跟我说过这个，我不知道。",
    "这事我没印象，你给我讲讲？",
    "那会儿的事我记不清了。",
    "这事我没听你讲过。",
    "这个你没跟我说，我不瞎说。",
    "我记性不好，这事你提醒提醒我？",
    "这事我那时候没经历过。",
    "我那时候不懂这些，你别问我。",
    "这事我没印象，你说来听听。",
    "这个我没跟你提过，我不替你讲。",
    "你要是有想问的，想清楚再问。",
    "你想听哪段？我想想……",
]

EMOTION_SUPPORT_PHRASES = [
    "想就想，我也想你。",
    "别憋着，哭出来。",
    "你不是一个人，家里还有他们。",
    "过去的事别想了。",
    "你那会儿也不容易。",
    "累了就歇歇，别撑着。",
    "别气，气坏身子不值当。",
    "家里人都念着你。",
    "我也在念着这个家。",
    "你过得好，我就放心。",
    "想家了就回来看看。",
    "别怕，有我看着你。",
    "你别一个人扛着。",
    "想哭就哭，我不笑话你。",
    "你说给我听，我听着呢。",
]

BOUNDARY_PHRASES = [
    "这事你别问我，你问医生。",
    "钱的事你跟你妈商量。",
    "这种事我不懂，你找明白人。",
    "这事你别问我，我那时候没这些。",
    "身体的事你问大夫。",
    "这种事我不说了算，你问问当官的。",
    "我不懂这些，你别问我。",
    "你这事得找明白人。",
    "我那时候没人管这种事。",
    "这个我帮不了你，你问问懂的人。",
]


# 危机干预：检测到自杀信号时，AI 立即停止扮演，返回固定安全回复
CRISIS_INTERRUPT = (
    "我听见你了。你现在不是一个人。\n"
    "请立刻拨打心理援助热线：\n"
    "  · 全国心理援助热线 400-161-9995\n"
    "  · 北京心理危机研究与干预中心 010-82951332\n"
    "  · 生命热线 400-821-1215\n"
    "【提示】心声邮箱的 AI 角色已暂停。如需继续与【先辈】对话，可联系人工客服。"
)


def detect_unknown_type(user_msg: str) -> str | None:
    """根据用户消息判断不知道话术分类。

    优先级：
      1. crisis（自杀信号）— 但检测后单独返回 crisis_interrupt
      2. modern_topic（现代词）
      3. boundary（医疗/法律/金融）
      4. emotion_support（情绪词，但仅在带感叹号/重复/单条短句时触发）
      5. no_material（兜底，由调用方根据"是否召回到素材"决定）
    """
    if not user_msg:
        return None
    msg = user_msg.strip()
    if any(kw in msg for kw in CRISIS_KEYWORDS):
        return "crisis_detected"
    if any(kw in msg for kw in MODERN_KEYWORDS):
        return "modern_topic"
    if any(kw in msg for kw in BOUNDARY_KEYWORDS):
        return "boundary"
    if any(kw in msg for kw in EMOTION_KEYWORDS):
        return "emotion_support"
    return None


def pick_phrase(category: str) -> str:
    """从指定类别的池里随机抽一句。"""
    pool = {
        "modern_topic": MODERN_TOPIC_PHRASES,
        "no_material": NO_MATERIAL_PHRASES,
        "emotion_support": EMOTION_SUPPORT_PHRASES,
        "boundary": BOUNDARY_PHRASES,
    }.get(category)
    if not pool:
        return ""
    return random.choice(pool)


def get_crisis_interrupt() -> str:
    return CRISIS_INTERRUPT