<template>
  <!--
    养护助手页：基于知识库的养护问答（饮食/护理/疫苗/行为），涉及疾病与用药的问题自动拒答并建议就医。
    主要交互：输入或点快捷问题发起提问，展示答案、知识库引用，以及本次回答来源标记
             （python-agent = Python LangChain Agent；java-local = 大模型不可用时的本地 RAG 降级）。
    调用接口：aiChat（POST /api/ai/chat，一次性返回 answer/citations/source）、listPets。
    说明：当前是一次性请求 + loading 占位，拿到整段答案再渲染，不是 SSE 流式输出，也没有轮询。
  -->
  <div class="ai-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="head">
          <div>
            <span class="card-title">养护助手</span>
            <el-tag size="small" type="info" class="ml8">饮食 / 护理 / 疫苗 / 行为</el-tag>
          </div>
          <span class="muted">疾病与用药类问题会自动拒答并建议就医</span>
        </div>
      </template>

      <div ref="chatBox" class="chat-box">
        <div v-for="(msg, i) in messages" :key="i" class="row" :class="msg.role">
          <div class="bubble-wrap">
            <div class="bubble">
              <span v-if="msg.refused" class="refused">【已拒答】</span>{{ msg.content }}
            </div>
            <div v-if="msg.citations && msg.citations.length" class="citations">
              <el-tag v-for="c in msg.citations" :key="c.title" size="small" effect="plain" class="cite">
                来源：{{ c.title }}
              </el-tag>
            </div>
            <!-- 回答来源标记：区分 Python Agent 生成与大模型不可用时的本地 RAG 降级 -->
            <div v-if="msg.role === 'assistant' && (msg.llmUsed || msg.agentSource)" class="muted">
              <el-tag v-if="msg.agentSource === 'python-agent'" type="success" size="small" effect="plain" class="src-tag">
                Python · LangChain Agent
              </el-tag>
              <el-tag v-else-if="msg.agentSource === 'java-local'" type="info" size="small" effect="plain" class="src-tag">
                本地 RAG（降级）
              </el-tag>
              <span v-if="msg.llmUsed">由大模型生成，已附带知识库引用</span>
            </div>
          </div>
        </div>
        <div v-if="loading" class="row assistant">
          <div class="bubble">正在检索知识库...</div>
        </div>
      </div>

      <div class="quick">
        <el-tag v-for="q in quickQuestions" :key="q" class="quick-tag" effect="plain" @click="ask(q)">{{ q }}</el-tag>
      </div>

      <div class="input-row">
        <el-select v-model="petId" placeholder="选择宠物（可选）" clearable style="width: 180px">
          <el-option v-for="p in pets" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-input v-model="question" placeholder="例如：三个月幼猫每天喂几次？" @keyup.enter="ask()" />
        <el-button type="primary" :loading="loading" @click="ask()">发送</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiChat, listPets } from '../api'

const messages = ref([])
const question = ref('')
const loading = ref(false)
const sessionId = ref('')
const petId = ref(null)
const pets = ref([])
const chatBox = ref(null)

const quickQuestions = [
  '三个月大的幼猫每天喂几次？',
  '狗狗洗澡多久一次比较合适？',
  '换粮怎么过渡不容易软便？',
  '猫应激了怎么办？'
]

// 发起问答：POST /api/ai/chat（普通请求，非流式），成功后保存 sessionId 维持多轮上下文，
// 并把拒答标记、知识库引用、回答来源一起写入消息列表；用 loading 阻止并发重复提问
async function ask(text) {
  const q = (text || question.value).trim()
  if (!q || loading.value) return
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  loading.value = true
  scroll()
  try {
    const res = await aiChat({ message: q, petId: petId.value, sessionId: sessionId.value || undefined })
    const data = res.data
    sessionId.value = data.sessionId
    messages.value.push({
      role: 'assistant',
      content: data.answer,
      citations: data.citations || [],
      refused: data.refused,
      llmUsed: data.llmUsed,
      agentSource: data.source
    })
  } catch (e) {
    ElMessage.error('助手暂时不可用')
  } finally {
    loading.value = false
    scroll()
  }
}

// 消息追加后滚到底部：nextTick 等 DOM 更新完再设 scrollTop，否则滚不到最新一条
function scroll() {
  nextTick(() => {
    if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
  })
}

// 初始化：拉宠物下拉数据，并推一条欢迎语（提前说明医疗类问题会拒答、建议就医）
onMounted(async () => {
  pets.value = (await listPets()).data || []
  messages.value.push({
    role: 'assistant',
    content: '你好，我是毛孩到家的养护助手。饮食、护理、疫苗、行为问题都可以问我；涉及疾病和用药，请直接就医。'
  })
})
</script>

<style scoped>
.ai-page { max-width: 900px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.card-title { font-weight: 600; }
.ml8 { margin-left: 8px; }
.chat-box { height: 460px; overflow-y: auto; background: #f8f9fb; border-radius: 10px; padding: 14px; }
.row { display: flex; margin-bottom: 12px; }
.row.user { justify-content: flex-end; }
.bubble-wrap { max-width: 78%; }
.bubble {
  padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.75;
  white-space: pre-wrap; word-break: break-word;
}
.user .bubble { background: #409eff; color: #fff; border-top-right-radius: 2px; }
.assistant .bubble { background: #fff; border: 1px solid #ebeef5; border-top-left-radius: 2px; }
.refused { color: #f56c6c; font-weight: 600; }
.citations { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; }
.quick { margin: 12px 0; display: flex; flex-wrap: wrap; gap: 8px; }
.src-tag { margin-right: 6px; }
.quick-tag { cursor: pointer; }
.input-row { display: flex; gap: 10px; }
</style>
