"""提示词编排:把仓库解析结果转成各产物类型的结构化 prompt。

每个函数返回 OpenAI 兼容的 messages 列表([{role, content}, ...])。
设计原则:system 提示给出角色与输出格式(JSON 契约),user 提示携带解析出的
仓库信息;让模型"照格式填",而非自由发挥。
"""
from __future__ import annotations

import json
from typing import Any

SYSTEM_BASE = (
    "你是 CodeOffer 的 AI 分析引擎,服务于「把 GitHub 开源项目转化为简历竞争力」的目标。"
    "你熟悉主流框架与架构模式,输出务必严谨、具体、面向工程师,严禁空话。"
)


def _repo_block(repo_info: dict[str, Any]) -> str:
    """把解析结果精简为 prompt 里的结构化输入块。"""
    compact = {
        "name": repo_info.get("name"),
        "languages": repo_info.get("languages"),
        "core_files": repo_info.get("core_files"),
        "stats": repo_info.get("stats"),
        "tree": repo_info.get("tree"),
    }
    return json.dumps(compact, ensure_ascii=False, indent=2)


def _prefs_suffix(prefs: dict[str, Any] | None) -> str:
    """生成偏好 → 附加 system 指令;无 prefs 时不改变原有 prompt。"""
    if not prefs:
        return ""
    parts = []
    if prefs.get("language", "zh") == "en":
        parts.append("All natural-language output in English (including Mermaid labels).")
    else:
        parts.append("所有自然语言输出使用简体中文。")
    verbosity = prefs.get("verbosity", "standard")
    if verbosity == "concise":
        parts.append("内容精简,直击要点,篇幅约为标准的一半。")
    elif verbosity == "detailed":
        parts.append("内容详尽,展开技术细节与示例,篇幅约为标准的 1.5 倍。")
    return " " + " ".join(parts)


# ---------------------------------------------------------------- 知识卡片
CARD_TYPES_ZH = {
    "architecture": "架构设计",
    "difficulty": "技术难点",
    "highlight": "项目亮点",
    "takeaway": "学习收获",
    "run_guide": "运行指南",
}

CARDS_SYSTEM = SYSTEM_BASE + (
    "你负责把仓库解析结果转化为结构化知识卡片。每张卡片需直击要害,"
    "用工程师语言讲清「是什么、为什么、怎么用」,并标注对面试的价值。"
    "只输出 JSON,不要任何额外说明。"
)


def build_knowledge_cards_messages(
    repo_info: dict[str, Any], types: list[str], prefs: dict[str, Any] | None = None
) -> list[dict[str, str]]:
    zh = [CARD_TYPES_ZH.get(t, t) for t in types]
    user = (
        "请基于以下仓库解析结果,生成以下类型的知识卡片:"
        f"{'、'.join(zh)}。\n\n"
        "输出 JSON 格式:\n"
        '{"cards": [{"type": "<architecture|difficulty|highlight|takeaway|run_guide>", '
        '"title": "<简短标题>", "content": "<Markdown 内容,300-800 字,含技术要点与面试价值>"}]}\n'
        "要求:每类一张卡片;content 具体到文件/类/机制,不要泛泛而谈。\n\n"
        f"仓库解析结果:\n{_repo_block(repo_info)}"
    )
    return [
        {"role": "system", "content": CARDS_SYSTEM + _prefs_suffix(prefs)},
        {"role": "user", "content": user},
    ]


# ---------------------------------------------------------------- 核心流程图
def build_flow_diagram_messages(
    repo_info: dict[str, Any], prefs: dict[str, Any] | None = None
) -> list[dict[str, str]]:
    system = SYSTEM_BASE + (
        "你负责把仓库的核心调用链/请求链路梳理成 Mermaid 流程图。"
        "只输出 JSON,不要任何额外说明。"
    ) + _prefs_suffix(prefs)
    user = (
        "请基于以下仓库解析结果,梳理该项目的核心流程(如一次请求从入口到数据层的调用链,"
        "或核心模块间依赖关系),输出 Mermaid 代码。\n\n"
        "输出 JSON 格式:\n"
        '{"title": "<流程图标题>", "description": "<100字以内的流程说明>", "mermaid": "<mermaid 代码>"}\n\n'
        "要求:mermaid 使用 flowchart LR 或 TD;节点名用简短英文;配中文 label 说明关键步骤。\n\n"
        f"仓库解析结果:\n{_repo_block(repo_info)}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


# ---------------------------------------------------------------- STAR 简历
def build_resume_messages(
    repo_info: dict[str, Any], prefs: dict[str, Any] | None = None
) -> list[dict[str, str]]:
    system = SYSTEM_BASE + (
        "你负责把仓库解析结果转化为符合 STAR 法则的简历项目描述,面向大模型应用开发/后端开发岗位。"
        "只输出 JSON,不要任何额外说明。"
    ) + _prefs_suffix(prefs)
    user = (
        "请基于以下仓库解析结果,撰写一份简历项目描述。要求:\n"
        "1. Situation/Task/Action/Result 四段式,Action 具体到技术手段(框架、设计模式、性能指标);\n"
        "2. Result 尽量量化(如:吞吐提升、响应时间、覆盖模块数),无法量化的写质量维度;\n"
        "3. 突出难点与解决方案,体现独立思考。\n\n"
        "输出 JSON 格式:\n"
        '{"star": {"situation": "<项目背景>", "task": "<职责目标>", "action": "<技术行动,可多行>", '
        '"result": "<成果>", "summary": "<一句话亮点总结>"}}\n\n'
        f"仓库解析结果:\n{_repo_block(repo_info)}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


# ---------------------------------------------------------------- 面试题
def build_interview_messages(
    repo_info: dict[str, Any], prefs: dict[str, Any] | None = None
) -> list[dict[str, str]]:
    system = SYSTEM_BASE + (
        "你负责基于仓库解析结果出模拟面试题,考察候选人对该项目的真实理解深度。"
        "只输出 JSON,不要任何额外说明。"
    ) + _prefs_suffix(prefs)
    user = (
        "请基于以下仓库解析结果,生成 5-10 道针对该项目的模拟面试题,分三个难度层级:\n"
        "- base:基础(项目是什么、用了什么技术)\n"
        "- advance:进阶(架构取舍、难点如何解决)\n"
        "- deep:深挖(如果改进会怎么做、与其他方案对比)\n\n"
        "输出 JSON 格式:\n"
        '{"questions": [{"level": "<base|advance|deep>", "question": "<问题>", "answer": "<参考回答,含技术细节>"}]}\n\n'
        f"仓库解析结果:\n{_repo_block(repo_info)}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


# ---------------------------------------------------------------- 体检报告
def build_health_report_messages(
    repo_url: str, meta: dict[str, Any] | None, prefs: dict[str, Any] | None = None
) -> list[dict[str, str]]:
    system = SYSTEM_BASE + (
        "你负责帮用户判断一个开源项目是否值得投入时间学习,并给出学习路线建议。"
        "只输出 JSON,不要任何额外说明。"
    ) + _prefs_suffix(prefs)
    meta_block = json.dumps(meta or {}, ensure_ascii=False, indent=2)
    user = (
        f"请评估仓库 {repo_url} 的学习价值。\n\n"
        f"已知元数据:{meta_block}\n\n"
        "输出 JSON 格式:\n"
        '{"verdict": "<值得学|值得浅尝|不建议>", "score": <1-10 整数>, '
        '"reasons": ["<理由1>", ...], "learning_path": ["<步骤1>", ...], '
        '"risk": "<潜在坑,如文档差/版本旧/依赖重>"}\n\n'
        "要求:理由具体(结合 star/活跃度/文档/技术栈与求职方向匹配度);学习路线 3-5 步。"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]
