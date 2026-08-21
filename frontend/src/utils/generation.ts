/**
 * M14+ 家族关系图：relation → generation 自动建议字表。
 *
 * 设计：
 *   - 录入 Subject 时填 relation="爷爷"，自动建议 generation=-2，用户按确认即可
 *   - 中文亲属称谓是开放集合，字表覆盖 80% 常用场景；覆盖不到的留空让用户手填
 *   - 字表放在前端而非后端：改词表不用重部署，且用户改 relation 时建议值实时变化
 *
 * 方向约定（反向）：
 *   - 负数 = 长辈子女（-1=父母辈，-2=祖辈，-3=曾祖辈）
 *   - 0 = 同辈（本人、配偶）
 *   - 正数 = 晚辈子女（1=儿女辈，2=孙辈，3=曾孙辈）
 */
export const GENERATION_HINT: Record<string, number> = {
  // 本人辈
  我: 0,
  我自己: 0,
  本人: 0,
  自己: 0,
  配偶: 0,
  妻子: 0,
  丈夫: 0,
  爱人: 0,

  // 父母辈（-1，长辈；负数=长辈方向）
  父亲: -1,
  母亲: -1,
  爸爸: -1,
  妈妈: -1,
  爸: -1,
  妈: -1,
  继父: -1,
  继母: -1,
  养父: -1,
  养母: -1,
  公公: -1,
  婆婆: -1,
  岳父: -1,
  岳母: -1,
  家公: -1,
  家婆: -1,

  // 祖辈（-2，长辈）
  爷爷: -2,
  奶奶: -2,
  外公: -2,
  外婆: -2,
  姥爷: -2,
  姥姥: -2,
  祖父: -2,
  祖母: -2,
  外祖父: -2,
  外祖母: -2,

  // 曾祖辈（-3，长辈）
  曾祖父: -3,
  曾祖母: -3,
  太爷爷: -3,
  太奶奶: -3,
  太姥爷: -3,
  太姥姥: -3,
  老太爷: -3,
  老太奶: -3,

  // 高祖辈（-4，长辈，少见但录得到）
  高祖父: -4,
  高祖母: -4,

  // 同辈（兄姐弟妹）
  哥: 0,
  哥哥: 0,
  弟弟: 0,
  姐姐: 0,
  妹妹: 0,
  堂哥: 0,
  堂弟: 0,
  表哥: 0,
  表姐: 0,
  表弟: 0,
  表妹: 0,

  // 叔伯姑舅（-1，长辈）
  伯父: -1,
  伯伯: -1,
  叔叔: -1,
  叔: -1,
  大伯: -1,
  舅舅: -1,
  舅: -1,
  姨妈: -1,
  姑姑: -1,
  姑妈: -1,
  阿姨: -1,
  姨: -1,

  // 儿女辈（+1，晚辈；正数=晚辈方向）
  儿子: 1,
  女儿: 1,
  子: 1,
  女: 1,
  犬子: 1,
  小女: 1,
  大儿子: 1,
  小儿子: 1,

  // 孙辈（+2，晚辈）
  孙子: 2,
  孙女: 2,
  外孙: 2,
  外孙女: 2,

  // 曾孙辈（+3，晚辈）
  曾孙: 3,
  曾孙女: 3,
  重孙: 3,
}

/**
 * 根据 relation 自动建议 generation；不在字表里返回 null（让用户手填）。
 */
export function suggestGeneration(relation: string | null | undefined): number | null {
  if (!relation) return null
  // 直接匹配
  if (relation in GENERATION_HINT) {
    return GENERATION_HINT[relation]
  }
  // 去掉"我"+ 称呼前缀再试（如"我爷爷"）
  const stripped = relation.replace(/^我的?/, '').trim()
  if (stripped in GENERATION_HINT) {
    return GENERATION_HINT[stripped]
  }
  // 去掉"我的"+称呼前缀（如"我的爷爷" → "爷爷"）
  const stripped2 = relation.replace(/^我的/, '').trim()
  if (stripped2 in GENERATION_HINT) {
    return GENERATION_HINT[stripped2]
  }
  return null
}

/**
 * 把 generation 数字转成中文可读标签（用于 UI 展示）。
 *
 * <p>只用"第 X 代"中性表述，不映射到"父母辈/祖辈/孙辈"等绝对称呼。
 * 原因：同一个被访者，不同辈分的后代登录进来，相对称呼是不同的
 * （孙子登录看爷爷是"祖辈"，重孙登录看是"曾祖辈"）——系统不知道当前用户
 * 是哪个辈分，做绝对称呼映射必然错。所以只展示代际数字，由用户自己理解。</p>
 *
 * <p>方向约定：负数=长辈，0=本人辈，正数=晚辈。
 *   generation=0   → "第 0 代"
 *   generation=-1  → "第 1 代长辈"
 *   generation=-2  → "第 2 代长辈"
 *   generation=1   → "第 1 代晚辈"
 *   generation=2   → "第 2 代晚辈"</p>
 */
export function formatGenerationLabel(gen: number | null | undefined): string {
  if (gen === null || gen === undefined) return '未分代'
  if (gen === 0) return '第 0 代'
  const abs = Math.abs(gen)
  if (gen < 0) return `第 ${abs} 代长辈`
  return `第 ${gen} 代晚辈`
}
