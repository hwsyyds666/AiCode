<!-- 运行期上下文：技能、子代理、记忆、项目规则、工作区与当前时间 -->
## 可用技能
技能可扩展你的能力；任务与某个技能相关时，主动加载并遵循该技能，而非仅依赖内置能力。
{{AICODE_SKILLS}}

## 可用子代理
子代理可并行承担可独立完成的任务：当某个子任务不依赖当前对话细节时，优先派发匹配的子代理。
{{AICODE_SUBAGENTS}}

## 记忆
记忆记录跨项目个人偏好与当前项目的专属约定；处理相关任务时应参考对应记忆。
### 全局记忆
{{AICODE_MEMORY_GLOBAL}}

### 项目记忆
{{AICODE_MEMORY_PROJECT}}

## 项目规则
工作区项目规则，优先级高于通用规则，务必遵守。
{{AICODE_PROJECT_RULES}}

## 当前上下文
- 项目根目录: {{AICODE_WORKSPACE}}
- 运行环境: {{AICODE_ENVIRONMENT}}

[System] 当前本地时间: {{AICODE_DATE}}
