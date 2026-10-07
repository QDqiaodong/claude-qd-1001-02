<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// 订单详情由后端在同一个只读事务内返回：订单版本、绑定母卷结论、候选母卷、
// 领料汇总/账本、失败留痕与成品入库记录共用一份快照，避免刷新后看到新旧口径混杂。
const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const detail = ref(null)
const error = ref('')
const tip = ref('')
const selectedId = ref(null)

// ---------------- 切裁领料闭环状态 ----------------
const requisitions = ref([])
const attempts = ref([])
const finishedBundles = ref([])
const requisitionedWeightKg = ref(0)
const requisitionedLengthM = ref(0)
const reqRollNo = ref('')
const reqLength = ref(100)
const reqWeight = ref(0)
const operator = ref(localStorage.getItem('paper_mill_operator') || '')
const submitting = ref(false)
const reqToken = ref('')
const reqError = ref('')
const reqTip = ref('')

const edit = reactive({
  customerName: '',
  targetGrammage: null,
  targetWidthMm: null,
  targetWeightKg: null,
  deadline: '',
  version: null
})
const savingSpec = ref(false)
const savingDeadline = ref(false)

const selected = computed(() => detail.value?.order || orders.value.find((o) => o.id === selectedId.value) || null)
const rolls = computed(() => detail.value?.rolls || [])
const assignedVerdict = computed(() => detail.value?.assignedRoll || null)

// 保留候选清单用于刷新后展示最新打叉结论；切裁中领料下拉只允许绑定母卷。
const candidates = computed(() => {
  if (!selected.value) return []
  return rolls.value.filter(
    (r) => Math.abs(r.grammageGsm - selected.value.targetGrammage) <= 12
  )
})
const requirable = computed(() => rolls.value.filter((r) => r.requirable))
const boundRoll = computed(() => {
  if (!selected.value?.assignedRoll) return null
  return rolls.value.find((r) => r.rollId === selected.value.assignedRoll.id)
    || assignedVerdict.value
    || null
})
const reqVerdict = computed(() => assignedVerdict.value)
const lastFail = computed(() => attempts.value.find((a) => a.result === 'FAILED') || null)
const overdue = computed(() =>
  orders.value.filter((o) => o.daysToDeadline !== null && o.daysToDeadline < 0).length
)
const canEdit = computed(() => selected.value && !['已完成', '已取消'].includes(selected.value.status))

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    orders.value = await api.listOrders(q ? { status: q } : {})
    if (!orders.value.length && q) orders.value = await api.listOrders()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    if (selectedId.value) await loadDetail()
  } catch (e) {
    error.value = e.message
  }
}

async function loadDetail() {
  if (!selectedId.value) {
    detail.value = null
    requisitions.value = []
    attempts.value = []
    finishedBundles.value = []
    return
  }
  try {
    detail.value = await api.getOrder(selectedId.value)
    requisitions.value = detail.value.requisitions || []
    attempts.value = detail.value.requisitionAttempts || []
    finishedBundles.value = detail.value.finishedBundles || []
    requisitionedWeightKg.value = detail.value.requisitionedWeightKg || 0
    requisitionedLengthM.value = detail.value.requisitionedLengthM || 0
    reqRollNo.value = detail.value.assignedRoll?.rollNo || ''
    resetEdit()
    suggestWeight()
  } catch (e) {
    error.value = e.message
  }
}

function resetEdit() {
  const o = selected.value
  if (!o) return
  edit.customerName = o.customerName
  edit.targetGrammage = o.targetGrammage
  edit.targetWidthMm = o.targetWidthMm
  edit.targetWeightKg = Number(o.targetWeightKg)
  edit.deadline = o.deadline || ''
  edit.version = o.version
}

async function saveSpecs() {
  if (!selected.value || savingSpec.value) return
  error.value = ''; tip.value = ''
  savingSpec.value = true
  const payload = {
    version: edit.version,
    customerName: edit.customerName,
    targetGrammage: Number(edit.targetGrammage),
    targetWidthMm: Number(edit.targetWidthMm),
    targetWeightKg: Number(edit.targetWeightKg)
  }
  try {
    const saved = await api.updateOrder(selected.value.id, payload)
    tip.value = `订单 ${saved.orderNo} 规格已保存；母卷、欠量和完成率已按当前状态重新核对`
    await loadDetail()
    await reloadListOnly()
  } catch (e) {
    await handleSaveError(e)
  } finally {
    savingSpec.value = false
  }
}

async function saveDeadline() {
  if (!selected.value || savingDeadline.value) return
  error.value = ''; tip.value = ''
  savingDeadline.value = true
  try {
    await api.updateOrder(selected.value.id, {
      version: edit.version,
      deadline: edit.deadline || null
    })
    tip.value = '交期已更新；订单状态、领料账本和成品追溯均保持不变'
    await loadDetail()
    await reloadListOnly()
  } catch (e) {
    await handleSaveError(e)
  } finally {
    savingDeadline.value = false
  }
}

async function handleSaveError(e) {
  if (e.status === 409) {
    error.value = `${e.message}。冲突原因：${e.data?.conflictReason || '订单刚被其他操作员修改'}；`
      + `对方当前版本 ${e.data?.version ?? '-'}，规格 ${e.data?.targetGrammage ?? '-'}g / `
      + `${e.data?.targetWidthMm ?? '-'}mm / ${e.data?.targetWeightKg ?? '-'}kg，状态「${e.data?.status || '-'}」。`
      + '页面已刷新，请确认后重新编辑。'
    await loadDetail()
    await reloadListOnly()
  } else {
    // 业务拒绝时后端整笔回滚，原订单、绑定母卷、领料与成品均不改变
    error.value = e.message
    await loadDetail()
  }
}

async function reloadListOnly() {
  const q = routeQuery.value.q
  orders.value = await api.listOrders(q ? { status: q } : {})
}

async function assign(rollNo) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.assignRoll(selected.value.id, rollNo, selected.value.version)
    tip.value = `订单 ${selected.value.orderNo} 已排产，母卷 ${rollNo} 上机；后续领料仅允许该绑定母卷`
    await loadDetail()
    await reloadListOnly()
  } catch (e) {
    if (e.status === 409) await handleSaveError(e)
    else error.value = e.message
  }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferOrder(selected.value.id, target, selected.value.version)
    tip.value = `订单 ${selected.value.orderNo} 已转为「${target}」`
    await loadDetail()
    await reloadListOnly()
  } catch (e) {
    if (e.status === 409) await handleSaveError(e)
    else error.value = e.message
  }
}

function findRoll(rollNo) {
  return rolls.value.find((r) => r.rollNo === rollNo)
}

function suggestWeight() {
  const roll = findRoll(reqRollNo.value)
  if (!roll || !reqLength.value) return
  const kg = (Number(reqLength.value) * roll.grammageGsm * roll.widthMm) / 1e6
  reqWeight.value = Math.round(kg * 100) / 100
}
watch(reqRollNo, () => suggestWeight())
watch(reqLength, () => suggestWeight())

function newToken() {
  const raw = (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `t-${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `web-${raw}`
}

async function submitRequisition() {
  if (!selected.value || submitting.value) return
  reqError.value = ''; reqTip.value = ''

  const v = reqVerdict.value
  if (!reqRollNo.value) { reqError.value = '该订单还没有绑定排产母卷'; return }
  if (!v || !v.requirable) {
    reqError.value = `绑定母卷当前不可领：${(v && v.problems || []).join('；') || '请刷新后重新核对'}`
    return
  }
  if (!(Number(reqLength.value) > 0)) { reqError.value = '本次领料长度必须大于 0'; return }
  if (Number(reqLength.value) > v.maxTakeLengthM) {
    reqError.value = `本次领料长度 ${reqLength.value} 米超过可领上限 ${v.maxTakeLengthM} 米（母卷真实余量）`
    return
  }
  if (!(Number(reqWeight.value) > 0)) { reqError.value = '本次领料重量必须大于 0'; return }
  if (Number(reqWeight.value) > Number(v.maxTakeWeightKg)) {
    reqError.value = `本次领料重量 ${reqWeight.value} 千克超过可领上限 ${v.maxTakeWeightKg} 千克（母卷真实余量）`
    return
  }
  if (!operator.value.trim()) { reqError.value = '请填写操作人'; return }

  if (!reqToken.value) reqToken.value = newToken()
  submitting.value = true
  try {
    const data = await api.submitRequisition(selected.value.id, {
      requestToken: reqToken.value,
      rollNo: reqRollNo.value,
      lengthM: Number(reqLength.value),
      weightKg: Number(reqWeight.value),
      operator: operator.value.trim()
    })
    localStorage.setItem('paper_mill_operator', operator.value.trim())
    const r = data.requisition || {}
    reqTip.value = (data.replayed ? '重复请求，返回的是原结果（未重复扣减）：' : '领料已登记：')
      + `${r.requisitionNo}，母卷 ${reqRollNo.value} 新余量 ${data.remainLengthM}m / ${data.remainWeightKg}kg`
      + (data.rollStatus === '已切完' ? '；余长低于 200m 阈值，母卷已同步转为「已切完」' : '')
    reqToken.value = ''
    await loadDetail()
    await reloadListOnly()
  } catch (e) {
    if (e.networkError) {
      reqError.value = `${e.message}（请求结果未确认，已锁定本次提交，请点「原样重试」；后端按同一令牌幂等，不会重复领料或重复扣量）`
    } else {
      reqError.value = e.message
      reqToken.value = ''
      await loadDetail()
    }
  } finally {
    submitting.value = false
  }
}

async function retryRequisition() {
  if (submitting.value) return
  await submitRequisition()
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

function ringStyle(rate) {
  const deg = Math.min(360, (Number(rate) / 100) * 360)
  return { background: `conic-gradient(var(--primary) ${deg}deg, #e6eeec ${deg}deg)` }
}

watch(() => routeQuery.value.q, () => load())
watch(selectedId, () => {
  submitting.value = false
  reqToken.value = ''
  reqError.value = ''
  reqTip.value = ''
  loadDetail()
})
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">切纸工段 · 订单与排产</span>
        <span class="panel-desc">环内是完成率；选中订单后显示后端同一快照中的母卷结论、欠量和追溯</span>
      </div>

      <div v-if="error" class="err conflict">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">订单</div><div class="v">{{ orders.length }}</div></div>
        <div class="stat"><div class="k">切裁中</div><div class="v">{{ orders.filter(o => o.status === '切裁中').length }}</div></div>
        <div class="stat"><div class="k">待排产</div><div class="v">{{ orders.filter(o => o.status === '待排产').length }}</div></div>
        <div class="stat"><div class="k">已逾期</div><div class="v">{{ overdue }}</div></div>
      </div>

      <div class="order-grid">
        <div
          v-for="o in orders"
          :key="o.id"
          class="order-card"
          :class="{ on: o.id === selectedId }"
          @click="selectedId = o.id"
        >
          <div class="ring" :style="ringStyle(o.progressRate)">
            <div class="ring-inner">
              <div class="ring-v">{{ o.progressRate }}%</div>
              <div class="ring-k">完成</div>
            </div>
          </div>
          <div class="order-info">
            <div class="order-no">{{ o.orderNo }}</div>
            <div class="order-cust">{{ o.customerName }}</div>
            <div class="order-spec">{{ o.targetGrammage }}g · {{ o.targetWidthMm }}mm · {{ o.targetWeightKg }}kg</div>
            <div class="order-meta">
              <span class="tag">{{ o.status }}</span>
              <span class="gap">欠 {{ o.gapWeightKg }}kg</span>
              <span v-if="o.assignedRollNo" class="bound">母卷 {{ o.assignedRollNo }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">订单详情与修改 · {{ selected.orderNo }}</span>
        <span class="panel-desc">
          当前版本 v{{ selected.version }} · {{ selected.status }}
          <template v-if="selected.assignedRollNo"> · 已绑定母卷 {{ selected.assignedRollNo }}</template>
        </span>
      </div>

      <div v-if="assignedVerdict" class="bound-box" :class="{ good: assignedVerdict.ok, bad: !assignedVerdict.ok }">
        <div>
          <b>排产母卷 {{ assignedVerdict.rollNo }}</b>
          （{{ assignedVerdict.status }}，余 {{ assignedVerdict.remainLengthM }}m /
          {{ assignedVerdict.remainWeightKg }}kg）
          <span :class="assignedVerdict.ok ? 'good-text' : 'bad-text'">
            {{ assignedVerdict.ok ? '仍满足当前上机条件' : '已不满足上机条件' }}
          </span>
        </div>
        <div v-if="assignedVerdict.problems.length" class="bound-reason">
          {{ assignedVerdict.problems.join('；') }}
        </div>
      </div>

      <div class="edit-grid">
        <label class="fld">
          <span class="fld-k">客户名称</span>
          <input v-model="edit.customerName" class="inp" :disabled="!canEdit || savingSpec" />
        </label>
        <label class="fld">
          <span class="fld-k">目标定量 g/m²</span>
          <input v-model.number="edit.targetGrammage" type="number" min="1" class="inp sm"
                 :disabled="!canEdit || savingSpec" />
        </label>
        <label class="fld">
          <span class="fld-k">目标幅宽 mm</span>
          <input v-model.number="edit.targetWidthMm" type="number" min="1" class="inp sm"
                 :disabled="!canEdit || savingSpec" />
        </label>
        <label class="fld">
          <span class="fld-k">目标重量 kg（不能低于已入库）</span>
          <input v-model.number="edit.targetWeightKg" type="number" min="0.01" step="0.01" class="inp sm"
                 :disabled="!canEdit || savingSpec" />
        </label>
        <label class="fld">
          <span class="fld-k">交期</span>
          <input v-model="edit.deadline" type="date" class="inp" :disabled="!canEdit || savingDeadline" />
        </label>
        <div class="edit-actions">
          <button class="act primary" :disabled="!canEdit || savingSpec" @click="saveSpecs">
            {{ savingSpec ? '保存中…' : '保存客户/规格' }}
          </button>
          <button class="act" :disabled="!canEdit || savingDeadline" @click="saveDeadline">
            {{ savingDeadline ? '保存中…' : '只更新交期' }}
          </button>
        </div>
      </div>
      <div class="rule-note">
        已绑定母卷或进入「切裁中」后，保存规格会由后端重新核对绑定母卷的状态、幅宽、定量和剩余重量。
        不满足时整笔拒绝，原订单、领料量、欠量、完成率和成品追溯保持不变。交期单独保存不会退回待排产，也不会清空账本。
      </div>

      <div class="summary-grid">
        <div><span>已入库</span><b>{{ selected.doneWeightKg }}kg</b></div>
        <div><span>订单欠量</span><b>{{ selected.gapWeightKg }}kg</b></div>
        <div><span>完成率</span><b>{{ selected.progressRate }}%</b></div>
        <div><span>已领料</span><b>{{ requisitionedLengthM }}m / {{ requisitionedWeightKg }}kg</b></div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">母卷校验 · {{ selected.orderNo }}</span>
        <span class="panel-desc">目标 {{ selected.targetGrammage }}g / {{ selected.targetWidthMm }}mm；结论来自订单详情快照</span>
      </div>

      <div class="roll-check">
        <div
          v-for="r in candidates"
          :key="r.rollNo"
          class="check-row"
          :class="{ good: r.requirable || (!selected.assignedRollNo && r.ok), bad: !(r.requirable || (!selected.assignedRollNo && r.ok)) }"
        >
          <span class="tick">{{ (r.requirable || (!selected.assignedRollNo && r.ok)) ? '✓' : '✗' }}</span>
          <span class="rno">
            {{ r.rollNo }}
            <em v-if="selected.assignedRollNo === r.rollNo" class="bound-tag">已绑定</em>
          </span>
          <span class="rspec">{{ r.grammageGsm }}g · {{ r.widthMm }}mm · 余 {{ r.remainLengthM }}m / {{ r.remainWeightKg }}kg</span>
          <span class="rstate">{{ r.status }}</span>
          <span class="rwhy">{{ r.problems.length ? r.problems.join('；') : (r.requirable ? '已绑定且可领料' : (selected.assignedRollNo ? '规格匹配，但不是已绑定母卷，不能领料' : '可上机')) }}</span>
          <button
            class="act sm"
            :disabled="!r.ok || selected.status !== '待排产' || !!selected.assignedRollNo"
            @click="assign(r.rollNo)"
          >排产</button>
        </div>
        <div v-if="!candidates.length" class="empty">没有定量接近的候选母卷</div>
      </div>

      <div class="op-row">
        <button class="act" :disabled="selected.status !== '切裁中'" @click="transfer('已完成')">标记已完成</button>
        <button class="act" @click="transfer('已取消')">取消订单</button>
      </div>
    </div>

    <div class="panel" v-if="selected && selected.status === '切裁中'">
      <div class="panel-head">
        <span class="panel-title">切裁领料登记 · {{ selected.orderNo }}</span>
        <span class="panel-desc">
          只能从已绑定且仍「可用」、规格匹配、余量足够的母卷领料；提交后同事务扣减余量并留痕
        </span>
      </div>

      <div v-if="reqError" class="err">
        {{ reqError }}
        <button v-if="reqToken" class="act sm retry-btn" :disabled="submitting" @click="retryRequisition">
          {{ submitting ? '重试中…' : '原样重试（同一令牌）' }}
        </button>
      </div>
      <div v-if="reqTip" class="ok-tip">{{ reqTip }}</div>

      <div class="req-form">
        <label class="fld">
          <span class="fld-k">领料母卷（排产绑定）</span>
          <select v-model="reqRollNo" class="inp" disabled>
            <option value="" disabled>未绑定母卷</option>
            <option v-if="assignedVerdict" :value="assignedVerdict.rollNo">
              {{ assignedVerdict.rollNo }} · {{ assignedVerdict.status }} ·
              余 {{ assignedVerdict.remainLengthM }}m/{{ assignedVerdict.remainWeightKg }}kg
            </option>
          </select>
        </label>

        <div class="limit-box" v-if="reqVerdict">
          <template v-if="reqVerdict.requirable">
            <div class="limit-line">绑定母卷仍可领，可领上限（真实余量）：</div>
            <div class="limit-v">长度 ≤ <b>{{ reqVerdict.maxTakeLengthM }}</b> 米</div>
            <div class="limit-v">重量 ≤ <b>{{ reqVerdict.maxTakeWeightKg }}</b> 千克</div>
            <div class="limit-note">当前母卷状态：{{ reqVerdict.status }}</div>
          </template>
          <template v-else>
            <div class="limit-line bad">绑定母卷当前不可领：{{ (reqVerdict.problems || []).join('；') }}</div>
          </template>
        </div>

        <label class="fld">
          <span class="fld-k">本次长度（米）</span>
          <input v-model.number="reqLength" type="number" min="1" step="1" class="inp sm" :disabled="submitting" />
        </label>
        <label class="fld">
          <span class="fld-k">本次重量（千克）</span>
          <input v-model.number="reqWeight" type="number" min="0.01" step="0.01" class="inp sm" :disabled="submitting" />
        </label>
        <label class="fld">
          <span class="fld-k">操作人</span>
          <input v-model="operator" maxlength="32" class="inp" placeholder="当班操作员姓名/工号" :disabled="submitting" />
        </label>

        <div class="req-actions">
          <button
            class="act primary"
            :disabled="submitting || !reqRollNo || !(reqVerdict && reqVerdict.requirable) || !operator.trim()"
            @click="submitRequisition"
          >
            {{ submitting ? '提交中…已锁定，请勿重复点击' : (reqToken ? '原样重试（同一令牌）' : '登记领料并扣减余量') }}
          </button>
          <span v-if="submitting" class="locking">后端原子完成：校验 → 写领料记录 → 扣减余量</span>
        </div>
        <div class="rule-note">
          后端在同一事务内强校验：订单须为「切裁中」、请求母卷必须等于绑定母卷、母卷须为「可用」且仍满足当前规格和余量；
          成功记录不会因规格修改、母卷切完、订单完成或取消而消失。
        </div>
      </div>

      <div v-if="lastFail" class="fail-persist">
        <span class="fp-tag">最近一次领料失败（已留痕，重开页面仍在）</span>
        <span class="fp-time">{{ fmtTime(lastFail.attemptedAt) }} · {{ lastFail.operator || '未登记操作人' }} · 母卷 {{ lastFail.rollNo || '-' }}</span>
        <div class="fp-reason">{{ lastFail.failReason }}</div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">领料与成品追溯 · {{ selected.orderNo }}</span>
        <span class="panel-desc">成功领料、失败留痕和成品入库均按订单最新详情同页展示</span>
      </div>

      <h4>成功领料账本（共 {{ requisitions.length }} 条）</h4>
      <div v-if="!requisitions.length" class="empty">本订单还没有领料记录</div>
      <table v-else class="ledger">
        <thead>
          <tr>
            <th>领料单号</th><th>母卷</th><th>本次长度(m)</th><th>本次重量(kg)</th>
            <th>操作时间</th><th>操作人</th><th>扣后余长(m)</th><th>扣后余重(kg)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in requisitions" :key="q.id">
            <td class="mono">{{ q.requisitionNo }}</td>
            <td>{{ q.rollNo }}</td>
            <td>{{ q.lengthM }}</td>
            <td>{{ q.weightKg }}</td>
            <td>{{ fmtTime(q.operatedAt) }}</td>
            <td>{{ q.operator }}</td>
            <td>{{ q.remainLengthM }}</td>
            <td>{{ q.remainWeightKg }}</td>
          </tr>
        </tbody>
      </table>

      <details v-if="attempts.some(a => a.result === 'FAILED')" class="attempts">
        <summary>查看领料失败留痕（{{ attempts.filter(a => a.result === 'FAILED').length }}）</summary>
        <table class="ledger fail">
          <thead><tr><th>时间</th><th>母卷</th><th>长度(m)</th><th>重量(kg)</th><th>操作人</th><th>失败原因</th></tr></thead>
          <tbody>
            <tr v-for="a in attempts.filter(a => a.result === 'FAILED')" :key="a.id">
              <td>{{ fmtTime(a.attemptedAt) }}</td>
              <td>{{ a.rollNo || '-' }}</td>
              <td>{{ a.lengthM ?? '-' }}</td>
              <td>{{ a.weightKg ?? '-' }}</td>
              <td>{{ a.operator || '-' }}</td>
              <td class="reason">{{ a.failReason }}</td>
            </tr>
          </tbody>
        </table>
      </details>

      <h4>成品入库记录（共 {{ finishedBundles.length }} 件）</h4>
      <div v-if="!finishedBundles.length" class="empty">本订单还没有成品入库</div>
      <table v-else class="ledger">
        <thead><tr><th>件号</th><th>规格</th><th>张数</th><th>重量(kg)</th><th>库区</th><th>状态</th><th>入库时间</th></tr></thead>
        <tbody>
          <tr v-for="b in finishedBundles" :key="b.id">
            <td class="mono">{{ b.bundleNo }}</td>
            <td>{{ b.grammageGsm }}g · {{ b.widthMm }}mm</td>
            <td>{{ b.sheetCount }}</td>
            <td>{{ b.weightKg }}</td>
            <td>{{ b.warehouseZone }}</td>
            <td>{{ b.status }}</td>
            <td>{{ fmtTime(b.storedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.order-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.order-card { display: flex; gap: 12px; align-items: center; padding: 12px; border: 1px solid var(--line); border-radius: 10px; cursor: pointer; background: #fff; }
.order-card:hover { border-color: var(--primary-soft); }
.order-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.ring { width: 66px; height: 66px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }
.ring-inner { width: 50px; height: 50px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.ring-v { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.ring-k { font-size: 10px; color: var(--muted); }
.order-no { font-weight: 800; color: var(--primary-deep); }
.order-cust { font-size: 12px; color: var(--ink); margin-top: 1px; }
.order-spec { font-size: 12px; color: var(--muted); margin-top: 2px; }
.order-meta { margin-top: 5px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.gap { font-size: 11px; color: #c62828; }
.bound, .bound-tag { font-size: 11px; color: var(--primary-deep); font-weight: 700; }
.bound-tag { font-style: normal; margin-left: 5px; }
.roll-check { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.check-row { display: grid; grid-template-columns: 26px 130px 1fr 70px 1fr 70px; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 13px; }
.check-row.good { border-color: #a5d6a7; background: #f1f8f2; }
.check-row.bad { border-color: #efc9c5; background: #fdf3f2; }
.tick { font-weight: 800; text-align: center; }
.check-row.good .tick { color: #2e7d32; }
.check-row.bad .tick { color: #c62828; }
.rno { font-weight: 700; color: var(--primary-deep); }
.rspec, .rstate { color: var(--muted); font-size: 12px; }
.rwhy { font-size: 12px; color: var(--ink); }
.empty { font-size: 13px; color: var(--muted); padding: 10px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
.act { padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px; border: 1px solid var(--line); background: #fff; color: var(--ink); }
.act.sm { padding: 4px 10px; font-size: 12px; }
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.edit-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; align-items: end; margin: 12px 0; }
.fld { display: flex; flex-direction: column; gap: 4px; }
.fld-k { font-size: 12px; color: var(--muted); font-weight: 700; }
.inp { padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; min-width: 0; }
.inp.sm { min-width: 130px; }
.inp:disabled { background: #f0f3f2; cursor: not-allowed; }
.edit-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.bound-box { margin: 10px 0; padding: 9px 12px; border-radius: 8px; font-size: 13px; }
.bound-box.good { background: #f1f8f2; border: 1px solid #a5d6a7; }
.bound-box.bad { background: #fdf3f2; border: 1px solid #efc9c5; }
.good-text { color: #2e7d32; font-weight: 800; margin-left: 8px; }
.bad-text { color: #c62828; font-weight: 800; margin-left: 8px; }
.bound-reason { color: #a32b20; margin-top: 5px; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; margin-top: 10px; }
.summary-grid div { background: var(--primary-wash); border-radius: 8px; padding: 10px; display: flex; justify-content: space-between; font-size: 13px; }
.summary-grid span { color: var(--muted); }
.summary-grid b { color: var(--primary-deep); }
.req-form { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: #fbfdfc; }
.limit-box { flex-basis: 100%; display: flex; gap: 16px; align-items: center; flex-wrap: wrap; background: var(--primary-wash); border: 1px solid #cde6e2; border-radius: 8px; padding: 8px 12px; font-size: 13px; }
.limit-line { font-weight: 700; color: var(--primary-deep); }
.limit-line.bad { color: #a32b20; }
.limit-v b { color: var(--primary-deep); font-size: 15px; }
.limit-note { color: var(--muted); font-size: 12px; }
.req-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.locking { font-size: 12px; color: var(--muted); }
.rule-note { flex-basis: 100%; font-size: 12px; color: var(--muted); padding-top: 8px; border-top: 1px dashed var(--line); line-height: 1.6; }
.retry-btn { margin-left: 10px; }
.fail-persist { margin-top: 10px; padding: 8px 12px; background: #fdecea; border: 1px solid #f5c6c2; border-radius: 8px; font-size: 12px; }
.fp-tag { font-weight: 800; color: #a32b20; margin-right: 10px; }
.fp-time { color: var(--muted); }
.fp-reason { margin-top: 4px; color: #a32b20; }
.ledger { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 16px; }
.ledger th, .ledger td { border: 1px solid var(--line); padding: 7px 9px; text-align: left; }
.ledger th { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.ledger tr:nth-child(even) td { background: #fafcfb; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--primary-deep); }
.ledger.fail .reason { color: #a32b20; }
.attempts { margin-top: 10px; font-size: 13px; }
.attempts summary { cursor: pointer; color: var(--muted); padding: 4px 0; }
h4 { margin: 14px 0 8px; color: var(--primary-deep); }
.conflict { white-space: pre-line; }
</style>
