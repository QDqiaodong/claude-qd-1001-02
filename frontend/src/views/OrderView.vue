<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：进度环 + 母卷匹配清单打勾 + 切裁领料闭环。
// 每张订单是一枚环形进度（完成率）卡片，选中后右侧所有内容都来自「订单详情快照」
// （/cutting-orders/{id}/detail）：规格与版本、绑定母卷及其匹配结论、欠量/完成率、
// 领料账本与失败留痕、成品入库历史，同一次读出来的同一份最新状态。
// 刷新或重开订单详情后，旧候选母卷不会再显示为可上机——已排产订单只认绑定的那一卷。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件，直接读 router 实例上的当前路由
const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const rolls = ref([])
const error = ref('')
const tip = ref('')
const selectedId = ref(null)
const verdicts = ref({})
const checking = ref(false)

// ---------------- 订单详情快照（同一份最新状态） ----------------
const detail = ref(null)          // {order, assignedRoll, assignedMatch, requisitions, ...}
const requisitions = ref([])      // 本订单成功领料账本（只增不改）
const attempts = ref([])          // 本订单领料留痕（含失败原因，刷新仍可见）
const bundles = ref([])           // 本订单成品入库历史（含已出库）
const requisitionedKg = ref(0)    // 成功账本汇总的已领料重量

// ---------------- 切裁领料闭环状态 ----------------
const reqRollNo = ref('')
const reqLength = ref(100)
const reqWeight = ref(0)
const operator = ref(localStorage.getItem('paper_mill_operator') || '')
const submitting = ref(false)     // 提交中锁定
const reqToken = ref('')          // 一次点击一个令牌；网络未确认时保留以便同令牌重试
const reqError = ref('')
const reqTip = ref('')

// ---------------- 修改订单弹窗（带乐观版本） ----------------
const editOpen = ref(false)
const editSaving = ref(false)
const editError = ref('')
const editForm = ref({
  expectedVersion: null,
  customerName: '',
  targetGrammage: null,
  targetWidthMm: null,
  targetWeightKg: null,
  deadline: '',
  deadlineTouched: true
})

const selected = computed(() => orders.value.find((o) => o.id === selectedId.value) || null)
const snapOrder = computed(() => detail.value?.order || selected.value || null)
const assignedRoll = computed(() => detail.value?.assignedRoll || null)
const assignedMatch = computed(() => detail.value?.assignedMatch || null)

// 候选母卷：按订单目标定量筛选；已绑定母卷即使定量被改出筛选范围也强制保留在清单里
const candidates = computed(() => {
  if (!snapOrder.value) return []
  const list = rolls.value.filter(
    (r) => Math.abs(r.grammageGsm - snapOrder.value.targetGrammage) <= 12
  )
  if (assignedRoll.value && !list.some((r) => r.id === assignedRoll.value.id)) {
    list.unshift(assignedRoll.value)
  }
  return list
})

// 可领母卷（后端校验通过：可用 + 幅宽够 + 定量匹配 + 必须是已绑定的那一卷）
const requirable = computed(() =>
  candidates.value.filter((r) => verdicts.value[r.rollNo] && verdicts.value[r.rollNo].ok)
)

// 选中待领母卷的后端校验结论（里面有真实余量与可领上限）
const reqVerdict = computed(() => verdicts.value[reqRollNo.value] || null)

// 最近一次失败留痕（持久化在后端，刷新/重开页面仍显示）
const lastFail = computed(() => attempts.value.find((a) => a.result === 'FAILED') || null)

const overdue = computed(() =>
  orders.value.filter((o) => o.daysToDeadline !== null && o.daysToDeadline < 0).length
)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    orders.value = await api.listOrders(q ? { status: q } : {})
    if (!orders.value.length && q) orders.value = await api.listOrders()
    rolls.value = await api.listRolls()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
  } catch (e) {
    error.value = e.message
  }
}

// 详情快照：规格、绑定母卷结论、欠量、进度、领料/成品历史全部来自同一次读
async function loadDetail() {
  if (!selectedId.value) {
    detail.value = null
    requisitions.value = []
    attempts.value = []
    bundles.value = []
    requisitionedKg.value = 0
    verdicts.value = {}
    return
  }
  try {
    const d = await api.orderDetail(selectedId.value)
    detail.value = d
    requisitions.value = d.requisitions || []
    attempts.value = d.requisitionAttempts || []
    bundles.value = d.finishedBundles || []
    requisitionedKg.value = d.requisitionedWeightKg || 0
    // 用快照里的最新订单替换列表中的同一条，卡片进度环/欠量也是同一份口径
    const idx = orders.value.findIndex((o) => o.id === d.order.id)
    if (idx >= 0) orders.value[idx] = d.order
    await runCheck()
  } catch (e) {
    error.value = e.message
  }
}

// 逐个候选母卷问后端校验结论（含可领上限）；已排产订单只有绑定卷会 ok
async function runCheck() {
  if (!snapOrder.value) { verdicts.value = {}; return }
  checking.value = true
  const out = {}
  for (const r of candidates.value) {
    try {
      out[r.rollNo] = await api.checkRoll(snapOrder.value.id, r.rollNo)
    } catch (e) {
      out[r.rollNo] = { ok: false, problems: [e.message] }
    }
  }
  verdicts.value = out
  checking.value = false
  // 默认选中绑定母卷；没有绑定时默认第一卷可领母卷；原先选中的卷仍可领则保持
  const prefer = assignedRoll.value?.rollNo
    || (requirable.value.length ? requirable.value[0].rollNo : '')
  if (!requirable.value.some((r) => r.rollNo === reqRollNo.value)) {
    reqRollNo.value = prefer
  }
}

async function assign(rollNo) {
  if (!snapOrder.value) return
  error.value = ''; tip.value = ''
  try {
    await api.assignRoll(snapOrder.value.id, rollNo)
    tip.value = `订单 ${snapOrder.value.orderNo} 已排产，母卷 ${rollNo} 上机并绑定`
    await loadDetail()
  } catch (e) { error.value = e.message }
}

async function transfer(target) {
  if (!snapOrder.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferOrder(snapOrder.value.id, target)
    tip.value = `订单 ${snapOrder.value.orderNo} 已转为「${target}」`
    await load()
    await loadDetail()
  } catch (e) { error.value = e.message }
}

// ---------------- 修改订单（乐观版本 + 绑定母卷复核） ----------------
function openEdit() {
  if (!snapOrder.value) return
  const o = snapOrder.value
  editError.value = ''
  editForm.value = {
    expectedVersion: o.version,
    customerName: o.customerName,
    targetGrammage: o.targetGrammage,
    targetWidthMm: o.targetWidthMm,
    targetWeightKg: Number(o.targetWeightKg),
    deadline: o.deadline || '',
    deadlineTouched: true
  }
  editOpen.value = true
}

async function saveEdit() {
  if (!snapOrder.value || editSaving.value) return
  editError.value = ''
  const f = editForm.value
  const payload = {
    expectedVersion: f.expectedVersion,
    customerName: f.customerName,
    targetGrammage: f.targetGrammage === '' || f.targetGrammage == null ? null : Number(f.targetGrammage),
    targetWidthMm: f.targetWidthMm === '' || f.targetWidthMm == null ? null : Number(f.targetWidthMm),
    targetWeightKg: f.targetWeightKg === '' || f.targetWeightKg == null ? null : Number(f.targetWeightKg),
    deadline: f.deadline || null,
    deadlineTouched: true
  }
  editSaving.value = true
  try {
    const saved = await api.updateOrder(snapOrder.value.id, payload)
    tip.value = `订单 ${saved.orderNo} 已按版本 ${saved.version} 更新；欠量 ${saved.gapWeightKg}kg、完成率 ${saved.progressRate}%`
    editOpen.value = false
    await load()
    await loadDetail()
  } catch (e) {
    editError.value = e.message
    if (e.conflict) {
      // 并发失败：对方已成功，本端编辑依据的版本过期。刷新后台最新状态，
      // 再把编辑框版本切到服务端当前版本，操作员按最新规格重新核对后提交（或放弃关闭）
      await load()
      const latest = orders.value.find((o) => o.id === snapOrder.value.id)
      if (e.currentVersion != null) editForm.value.expectedVersion = e.currentVersion
      else if (latest) editForm.value.expectedVersion = latest.version
      await loadDetail()
    }
  } finally {
    editSaving.value = false
  }
}

// 按长度折算建议重量：kg = L(m) × 定量(g/m2) × 幅宽(mm) / 1e6
function suggestWeight() {
  const roll = rolls.value.find((r) => r.rollNo === reqRollNo.value)
  if (!roll || !reqLength.value) return
  const kg = (Number(reqLength.value) * roll.grammageGsm * roll.widthMm) / 1e6
  reqWeight.value = Math.round(kg * 100) / 100
}

// 折算值仅为便利填写，最终以后端按真实余量校验为准
watch(reqRollNo, () => suggestWeight())
watch(reqLength, () => suggestWeight())

// ---------------- 切裁领料提交（幂等 + 锁定） ----------------
function newToken() {
  const raw = (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `t-${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `web-${raw}`
}

async function submitRequisition() {
  if (!snapOrder.value || submitting.value) return
  reqError.value = ''; reqTip.value = ''

  // ---- 前端只做体验性预校验，真正的闸在后端事务里 ----
  const v = reqVerdict.value
  if (!reqRollNo.value) { reqError.value = '请选择要领的母卷'; return }
  if (!v || !v.ok) { reqError.value = `该母卷当前不可领：${(v && v.problems || []).join('；') || '请重新选择'}`; return }
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

  // 一次点击领一个令牌；网络未确认结果时沿用旧令牌，由后端幂等返回原结果
  if (!reqToken.value) reqToken.value = newToken()
  submitting.value = true
  try {
    const data = await api.submitRequisition(snapOrder.value.id, {
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
  } catch (e) {
    if (e.networkError) {
      // 请求是否到达/是否已扣减无法确认：保留同一令牌，让操作员原样重试，后端按同一令牌幂等
      reqError.value = `${e.message}（请求结果未确认，已锁定本次提交，请点「原样重试」；后端按同一令牌幂等，不会重复领料或重复扣量）`
    } else {
      // 明确的业务失败：原因已在后端留痕。换新点击、刷新真实余量与失败原因
      reqError.value = e.message
      reqToken.value = ''
      await loadDetail()
    }
  } finally {
    submitting.value = false
  }
}

// 网络未确认后的原样重试：沿用同一令牌
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
  // 切单即视为一次新的操作上下文：清掉提交中锁定/未确认令牌/提示，防止残留状态串单
  submitting.value = false
  reqToken.value = ''
  reqError.value = ''
  reqTip.value = ''
  reqRollNo.value = ''
  loadDetail()
})
onMounted(async () => {
  await load()
  await loadDetail()
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">切纸工段 · 订单与排产</span>
        <span class="panel-desc">环内是完成率；选中订单后右侧所有规格、母卷结论、欠量与历史均来自同一份详情快照</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
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

    <div class="panel" v-if="snapOrder">
      <div class="panel-head">
        <span class="panel-title">母卷校验 · {{ snapOrder.orderNo }}</span>
        <span class="panel-desc">
          目标 {{ snapOrder.targetGrammage }}g / {{ snapOrder.targetWidthMm }}mm / {{ snapOrder.targetWeightKg }}kg
          · 已入库 {{ snapOrder.doneWeightKg }}kg · 欠 {{ snapOrder.gapWeightKg }}kg
        </span>
        <span v-if="checking" class="checking">校验中…</span>
        <button class="act sm spacer"
                :disabled="snapOrder.status === '已完成' || snapOrder.status === '已取消'"
                @click="openEdit">修改订单</button>
      </div>

      <!-- 已排产：当前绑定母卷及最新匹配结论（来自详情快照） -->
      <div v-if="assignedRoll" class="bound-box"
           :class="{ good: assignedMatch && assignedMatch.ok, bad: assignedMatch && !(assignedMatch.ok) }">
        <div class="bound-head">
          <span class="bound-tag">当前排产母卷</span>
          <span class="rno">{{ assignedRoll.rollNo }}</span>
          <span class="rspec">{{ assignedRoll.grammageGsm }}g · {{ assignedRoll.widthMm }}mm
            · 余 {{ assignedRoll.lengthM }}m / {{ assignedRoll.weightKg }}kg</span>
          <span class="rstate">{{ assignedRoll.status }}</span>
          <span class="tick">{{ assignedMatch ? (assignedMatch.ok ? '✓ 仍满足上机条件' : '✗ 已不满足上机条件') : '…' }}</span>
        </div>
        <div v-if="assignedMatch && !(assignedMatch.ok)" class="bound-reason">
          {{ assignedMatch.problems.join('；') }}
        </div>
        <div class="bound-note">
          订单已绑定该母卷：改定量/幅宽/重量时后端会在事务内按此卷重新核对，不满足将整笔拒绝并保留原订单；
          其他候选母卷不能再用于上机或领料。
        </div>
      </div>

      <div class="roll-check">
        <div
          v-for="r in candidates"
          :key="r.rollNo"
          class="check-row"
          :class="{
            good: verdicts[r.rollNo] && verdicts[r.rollNo].ok,
            bad: verdicts[r.rollNo] && !verdicts[r.rollNo].ok,
            boundrow: assignedRoll && assignedRoll.rollNo === r.rollNo
          }"
        >
          <span class="tick">{{ verdicts[r.rollNo] ? (verdicts[r.rollNo].ok ? '✓' : '✗') : '…' }}</span>
          <span class="rno">{{ r.rollNo }}<span v-if="assignedRoll && assignedRoll.rollNo === r.rollNo" class="bound-mini">已绑定</span></span>
          <span class="rspec">{{ r.grammageGsm }}g · {{ r.widthMm }}mm · 余 {{ r.lengthM }}m / {{ r.weightKg }}kg</span>
          <span class="rstate">{{ r.status }}</span>
          <span class="rwhy">
            {{ verdicts[r.rollNo] && verdicts[r.rollNo].problems && verdicts[r.rollNo].problems.length
               ? verdicts[r.rollNo].problems.join('；') : '可上机 / 可领料' }}
          </span>
          <button
            class="act sm"
            :disabled="!verdicts[r.rollNo] || !verdicts[r.rollNo].ok || snapOrder.status !== '待排产'"
            @click="assign(r.rollNo)"
          >排产</button>
        </div>
        <div v-if="!candidates.length" class="empty">没有定量接近的候选母卷</div>
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="snapOrder.status !== '切裁中'" @click="transfer('已完成')">标记已完成</button>
        <button class="act" @click="transfer('已取消')">取消订单</button>
        <span class="op-note">待排产订单经「排产」绑定合格母卷后自动进入切裁中，不能手工跳级</span>
      </div>
    </div>

    <!-- ============ 切裁领料登记（闭环：订单 + 记录 + 余量 同一业务动作） ============ -->
    <div class="panel" v-if="snapOrder && snapOrder.status === '切裁中'">
      <div class="panel-head">
        <span class="panel-title">切裁领料登记 · {{ snapOrder.orderNo }}</span>
        <span class="panel-desc">
          只能从已绑定母卷{{ assignedRoll ? ' ' + assignedRoll.rollNo : '' }}领料；提交后在同一动作内扣减余长与余重，
          余长低于 200m 母卷自动转「已切完」。已领料合计 {{ requisitionedKg }}kg
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
          <span class="fld-k">领料母卷</span>
          <select v-model="reqRollNo" class="inp" :disabled="submitting">
            <option value="" disabled>请选择母卷</option>
            <option
              v-for="r in candidates" :key="r.rollNo" :value="r.rollNo"
              :disabled="!(verdicts[r.rollNo] && verdicts[r.rollNo].ok)"
            >
              {{ r.rollNo }} · {{ r.grammageGsm }}g/{{ r.widthMm }}mm · 余 {{ r.lengthM }}m
              {{ verdicts[r.rollNo] && verdicts[r.rollNo].ok ? '' : '（不可领）' }}
            </option>
          </select>
        </label>

        <div class="limit-box" v-if="reqVerdict">
          <template v-if="reqVerdict.ok">
            <div class="limit-line">可领上限（母卷真实余量，提交前看清）：</div>
            <div class="limit-v">长度 ≤ <b>{{ reqVerdict.maxTakeLengthM }}</b> 米</div>
            <div class="limit-v">重量 ≤ <b>{{ reqVerdict.maxTakeWeightKg }}</b> 千克</div>
            <div class="limit-note">当前母卷状态：{{ reqVerdict.status }}</div>
          </template>
          <template v-else>
            <div class="limit-line bad">该母卷当前不可领：{{ (reqVerdict.problems || []).join('；') }}</div>
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
            :disabled="submitting || !reqRollNo || !(reqVerdict && reqVerdict.ok) || !operator.trim()"
            @click="submitRequisition"
          >
            {{ submitting ? '提交中…已锁定，请勿重复点击' : (reqToken ? '原样重试（同一令牌）' : '登记领料并扣减余量') }}
          </button>
          <span v-if="submitting" class="locking">正在由后端原子完成：校验 → 写领料记录 → 扣减余量，期间重复点击只会返回同一结果</span>
        </div>
        <div class="rule-note">
          后端在同一事务内强校验：订单须为「切裁中」、母卷必须是排产绑定的那一卷且仍「可用」、规格仍匹配（幅宽≥目标、定量偏差≤3）、
          长度/重量为正且不超过余量；任一不满足只返回原因、不写记录不动量。扣后余长低于 200m 母卷自动转「已切完」。
        </div>
      </div>

      <div v-if="lastFail" class="fail-persist">
        <span class="fp-tag">最近一次领料失败（已留痕，重开页面仍在）</span>
        <span class="fp-time">{{ fmtTime(lastFail.attemptedAt) }} · {{ lastFail.operator || '未登记操作人' }} · 母卷 {{ lastFail.rollNo || '-' }}</span>
        <div class="fp-reason">{{ lastFail.failReason }}</div>
      </div>
    </div>

    <!-- ============ 成品入库追溯（快照的一部分，规格变更不改变历史件） ============ -->
    <div class="panel" v-if="snapOrder">
      <div class="panel-head">
        <span class="panel-title">成品入库追溯 · {{ snapOrder.orderNo }}</span>
        <span class="panel-desc">共 {{ bundles.length }} 件，已入库合计 {{ snapOrder.doneWeightKg }}kg；历史件规格定格，不随后续改单变化</span>
      </div>
      <div v-if="!bundles.length" class="empty">本订单还没有成品入库记录</div>
      <table v-else class="ledger">
        <thead>
          <tr>
            <th>件号</th><th>定量(g)</th><th>幅宽(mm)</th><th>张数</th><th>重量(kg)</th>
            <th>库区</th><th>状态</th><th>入库时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in bundles" :key="b.id">
            <td class="mono">{{ b.bundleNo }}</td>
            <td>{{ b.grammageGsm }}</td>
            <td>{{ b.widthMm }}</td>
            <td>{{ b.sheetCount }}</td>
            <td>{{ b.weightKg }}</td>
            <td>{{ b.warehouseZone }}</td>
            <td>{{ b.status }}</td>
            <td>{{ fmtTime(b.storedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ============ 领料追溯：订单详情侧（成功记录不随状态变化消失） ============ -->
    <div class="panel" v-if="snapOrder">
      <div class="panel-head">
        <span class="panel-title">领料追溯 · {{ snapOrder.orderNo }}</span>
        <span class="panel-desc">
          共 {{ requisitions.length }} 次成功领料；即使母卷已切完、订单已完成/取消，账本仍保留
        </span>
      </div>

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
          <thead>
            <tr><th>时间</th><th>母卷</th><th>长度(m)</th><th>重量(kg)</th><th>操作人</th><th>失败原因</th></tr>
          </thead>
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
    </div>

    <!-- ============ 修改订单弹窗（乐观版本 + 已绑定母卷复核提示） ============ -->
    <div v-if="editOpen" class="modal-mask" @click.self="editOpen = false">
      <div class="modal">
        <div class="modal-head">
          <span>修改订单 · {{ snapOrder.orderNo }}</span>
          <span class="modal-ver">当前版本 v{{ editForm.expectedVersion }}</span>
        </div>

        <div v-if="editError" class="err modal-conflict">
          {{ editError }}
          <template v-if="editForm.expectedVersion !== snapOrder.version">
            （表单已切到服务端最新版本 v{{ editForm.expectedVersion }}，请按上方最新规格重新核对后再提交）
          </template>
        </div>

        <div v-if="snapOrder.status === '切裁中' && assignedRoll" class="edit-warn">
          订单已排产绑定母卷 <b>{{ assignedRoll.rollNo }}</b>。修改目标定量/幅宽/重量后，
          后端会在同一事务内重新核对该母卷是否仍满足上机条件（可用 + 幅宽≥目标 + 定量偏差≤3）：
          不满足将整笔拒绝、保留原订单；已领料（合计 {{ requisitionedKg }}kg）与已入库
          （{{ snapOrder.doneWeightKg }}kg）不会被清退，欠量与完成率按新目标重量重算。
          目标重量不能低于已入库量；只改交期则仅更新交期，状态、领料与成品追溯均不变。
        </div>

        <div class="edit-grid">
          <label class="fld">
            <span class="fld-k">客户名称</span>
            <input v-model="editForm.customerName" maxlength="64" class="inp" :disabled="editSaving" />
          </label>
          <label class="fld">
            <span class="fld-k">目标定量（g/m²）</span>
            <input v-model.number="editForm.targetGrammage" type="number" min="1" step="1" class="inp sm" :disabled="editSaving" />
          </label>
          <label class="fld">
            <span class="fld-k">目标幅宽（mm）</span>
            <input v-model.number="editForm.targetWidthMm" type="number" min="1" step="1" class="inp sm" :disabled="editSaving" />
          </label>
          <label class="fld">
            <span class="fld-k">目标重量（kg，不能低于已入库 {{ snapOrder.doneWeightKg }}kg）</span>
            <input v-model.number="editForm.targetWeightKg" type="number"
                   :min="Number(snapOrder.doneWeightKg)" step="0.01" class="inp sm" :disabled="editSaving" />
          </label>
          <label class="fld">
            <span class="fld-k">交期</span>
            <input v-model="editForm.deadline" type="date" class="inp sm" :disabled="editSaving" />
          </label>
        </div>

        <div class="modal-actions">
          <button class="act" :disabled="editSaving" @click="editOpen = false">放弃</button>
          <button class="act primary" :disabled="editSaving" @click="saveEdit">
            {{ editSaving ? '保存中…' : '保存修改' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }

.order-card {
  display: flex; gap: 12px; align-items: center;
  padding: 12px; border: 1px solid var(--line); border-radius: 10px;
  cursor: pointer; background: #fff;
}
.order-card:hover { border-color: var(--primary-soft); }
.order-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }

.ring { width: 66px; height: 66px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }
.ring-inner {
  width: 50px; height: 50px; border-radius: 50%; background: #fff;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ring-v { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.ring-k { font-size: 10px; color: var(--muted); }

.order-no { font-weight: 800; color: var(--primary-deep); }
.order-cust { font-size: 12px; color: var(--ink); margin-top: 1px; }
.order-spec { font-size: 12px; color: var(--muted); margin-top: 2px; }
.order-meta { margin-top: 5px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.gap { font-size: 11px; color: #c62828; }
.bound { font-size: 11px; color: var(--primary-soft); }

.checking { margin-left: auto; font-size: 12px; color: var(--muted); }

/* ---------------- 当前绑定母卷结论 ---------------- */
.bound-box { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.bound-box.good { border-color: #a5d6a7; background: #f1f8f2; }
.bound-box.bad { border-color: #efc9c5; background: #fdf3f2; }
.bound-head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.bound-tag {
  font-size: 11px; font-weight: 800; padding: 2px 8px; border-radius: 9px;
  background: var(--primary); color: #fff;
}
.good .tick { color: #2e7d32; font-weight: 800; }
.bad .tick { color: #c62828; font-weight: 800; }
.bound-reason { margin-top: 6px; font-size: 12px; color: #a32b20; }
.bound-note { margin-top: 6px; font-size: 12px; color: var(--muted); }

.roll-check { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.check-row {
  display: grid; grid-template-columns: 26px 110px 1fr 70px 1fr 70px;
  align-items: center; gap: 10px;
  padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 13px;
}
.check-row.good { border-color: #a5d6a7; background: #f1f8f2; }
.check-row.bad { border-color: #efc9c5; background: #fdf3f2; }
.check-row.boundrow { box-shadow: inset 3px 0 0 var(--primary); }
.tick { font-weight: 800; text-align: center; }
.check-row.good .tick { color: #2e7d32; }
.check-row.bad .tick { color: #c62828; }
.rno { font-weight: 700; color: var(--primary-deep); }
.bound-mini {
  margin-left: 6px; font-size: 10px; font-weight: 800; color: #fff; background: var(--primary);
  border-radius: 8px; padding: 0 6px;
}
.rspec, .rstate { color: var(--muted); font-size: 12px; }
.rwhy { font-size: 12px; color: var(--ink); }

.empty { font-size: 13px; color: var(--muted); padding: 10px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.op-note { font-size: 12px; color: var(--muted); }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act.sm { padding: 4px 10px; font-size: 12px; }
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.spacer { margin-left: auto; }

/* ---------------- 切裁领料表单 ---------------- */
.req-form {
  display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap;
  padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: #fbfdfc;
}
.fld { display: flex; flex-direction: column; gap: 4px; }
.fld-k { font-size: 12px; color: var(--muted); font-weight: 700; }
.inp { padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; min-width: 200px; }
.inp.sm { min-width: 130px; }
.inp:disabled { background: #f0f3f2; cursor: not-allowed; }

.limit-box {
  flex-basis: 100%;
  display: flex; gap: 16px; align-items: center; flex-wrap: wrap;
  background: var(--primary-wash); border: 1px solid #cde6e2; border-radius: 8px;
  padding: 8px 12px; font-size: 13px;
}
.limit-line { font-weight: 700; color: var(--primary-deep); }
.limit-line.bad { color: #a32b20; }
.limit-v { color: var(--ink); }
.limit-v b { color: var(--primary-deep); font-size: 15px; }
.limit-note { color: var(--muted); font-size: 12px; }

.req-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.locking { font-size: 12px; color: var(--muted); }
.rule-note {
  flex-basis: 100%; font-size: 12px; color: var(--muted);
  padding-top: 8px; border-top: 1px dashed var(--line); line-height: 1.6;
}
.retry-btn { margin-left: 10px; }

.fail-persist {
  margin-top: 10px; padding: 8px 12px;
  background: #fdecea; border: 1px solid #f5c6c2; border-radius: 8px; font-size: 12px;
}
.fp-tag { font-weight: 800; color: #a32b20; margin-right: 10px; }
.fp-time { color: var(--muted); }
.fp-reason { margin-top: 4px; color: #a32b20; }

/* ---------------- 追溯账本表 ---------------- */
.ledger { width: 100%; border-collapse: collapse; font-size: 13px; }
.ledger th, .ledger td {
  border: 1px solid var(--line); padding: 7px 9px; text-align: left;
}
.ledger th { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.ledger tr:nth-child(even) td { background: #fafcfb; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--primary-deep); }
.ledger.fail .reason { color: #a32b20; }
.attempts { margin-top: 10px; font-size: 13px; }
.attempts summary { cursor: pointer; color: var(--muted); padding: 4px 0; }

/* ---------------- 修改订单弹窗 ---------------- */
.modal-mask {
  position: fixed; inset: 0; background: rgba(0, 38, 31, 0.42);
  display: flex; align-items: center; justify-content: center; z-index: 80;
}
.modal {
  width: 620px; max-width: calc(100vw - 32px); max-height: calc(100vh - 60px); overflow: auto;
  background: #fff; border-radius: 12px; padding: 18px 20px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.28);
}
.modal-head {
  display: flex; align-items: baseline; gap: 12px;
  font-size: 15px; font-weight: 800; color: var(--primary-deep); margin-bottom: 12px;
}
.modal-ver { margin-left: auto; font-size: 12px; color: var(--muted); font-weight: 600; }
.modal-conflict { white-space: normal; }
.edit-warn {
  background: #fff8e1; border: 1px solid #f0d98c; color: #6d4c00;
  border-radius: 8px; padding: 9px 12px; font-size: 12px; line-height: 1.7; margin-bottom: 12px;
}
.edit-grid { display: flex; flex-direction: column; gap: 10px; }
.edit-grid .inp { min-width: 0; width: 100%; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }
</style>
