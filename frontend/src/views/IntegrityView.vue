<template>
  <div>
    <!-- 总览 -->
    <div class="grid grid-4">
      <div class="stat"><div class="k">未修复缺陷</div><div class="v">{{ stats.totalOpenDefects ?? '—' }}</div></div>
      <div class="stat"><div class="k">删除冻结对象</div><div class="v">{{ stats.totalDeletionFrozen ?? '—' }}</div></div>
      <div class="stat"><div class="k">不可替代证据</div><div class="v">{{ stats.evidenceObjects ?? '—' }}</div></div>
      <div class="stat"><div class="k">EC 纠删码对象</div><div class="v">{{ stats.ecObjects ?? '—' }}</div></div>
    </div>

    <!-- 读量预算策略 -->
    <div class="card">
      <h2>读量预算策略（每存储层 × 审计周期：声明的对象比例与字节预算硬顶）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>存储层</th><th>周期(天)</th><th>对象比例上限</th><th>字节预算(B)</th>
            <th>抽样路线</th><th>证据轮换(天)</th><th>盲区/未修复</th></tr></thead>
          <tbody>
          <tr v-for="p in policies" :key="p.tierCode">
            <td>{{ p.tierCode }} <span class="muted">{{ p.tierName }}</span></td>
            <td>{{ p.auditCycleDays }}</td>
            <td class="mono">{{ (p.maxObjectRatio * 100).toFixed(1) }}%</td>
            <td class="mono">{{ fmtBytes(p.maxByteBudget) }}</td>
            <td><span class="tag" :class="p.samplingStrategy === 'ADAPTIVE' ? 'tag-warn' : 'tag-info'">
              {{ strategyText(p.samplingStrategy) }}</span></td>
            <td>{{ p.evidenceRotationCycleDays }}</td>
            <td>
              <span v-for="t in tierStats(p.tierCode)" :key="p.tierCode" class="mono muted">
                盲区 {{ t.blindSpots }} · 未修复 {{ t.openDefects }}
              </span>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
      <div class="toolbar" style="margin-top:12px">
        <select v-model="policyForm.tierCode">
          <option v-for="p in policies" :key="p.tierCode" :value="p.tierCode">{{ p.tierCode }}</option>
        </select>
        <input v-model.number="policyForm.maxObjectRatio" type="number" step="0.05" min="0.01" max="1"
               placeholder="对象比例 0-1" style="width:130px" />
        <input v-model.number="policyForm.maxByteBudget" type="number" step="1000000000"
               placeholder="字节预算" style="width:170px" />
        <select v-model="policyForm.samplingStrategy">
          <option value="STRATIFIED_FIXED">固定比例分层</option>
          <option value="ADAPTIVE">自适应</option>
        </select>
        <input v-model.number="policyForm.auditCycleDays" type="number" placeholder="周期天" style="width:100px" />
        <input v-model.number="policyForm.evidenceRotationCycleDays" type="number" placeholder="证据轮换天"
               style="width:120px" />
        <button class="btn btn-sm" @click="savePolicy">更新预算策略</button>
      </div>
    </div>

    <!-- 审计执行 -->
    <div class="card">
      <h2>抽样审计执行（不读取全量对象；预算内估计该层缺陷率区间）</h2>
      <div class="toolbar">
        <label>存储层
          <select v-model="cycleForm.tierCode">
            <option v-for="p in policies" :key="p.tierCode" :value="p.tierCode">{{ p.tierCode }}</option>
          </select>
        </label>
        <label>路线
          <select v-model="cycleForm.strategy">
            <option value="STRATIFIED_FIXED">固定比例分层（覆盖可预期，反应慢）</option>
            <option value="ADAPTIVE">自适应（告警/既往缺陷/证据价值，报告未覆盖概率）</option>
          </select>
        </label>
        <button class="btn" @click="runCycle">运行审计周期</button>
        <button class="btn btn-ghost btn-sm" @click="scanGaps">扫描覆盖盲区</button>
      </div>

      <div v-if="cycle" class="hint">
        周期 #{{ cycle.cycleSeq }} · {{ cycle.tierCode }} · {{ strategyText(cycle.strategy) }} ·
        总体 {{ cycle.populationObjects }} 对象 / {{ fmtBytes(cycle.populationBytes) }} →
        抽样 <b>{{ cycle.sampledObjects }}</b> 对象 / {{ fmtBytes(cycle.sampledBytes) }}
        （比例上限 {{ (cycle.objectRatioLimit * 100).toFixed(0) }}%，字节预算 {{ fmtBytes(cycle.byteBudget) }}）
        <span class="tag" :class="cycle.withinBudget ? 'tag-ok' : 'tag-danger'" style="margin-left:6px">
          {{ cycle.withinBudget ? '未超预算' : '超预算阻断' }}</span>
        <div style="margin-top:6px">
          缺陷率 Wilson 95% 区间：
          <b class="mono">{{ pct(cycle.defectRateLower) }} ~ {{ pct(cycle.defectRateUpper) }}</b>
          （点估计 {{ pct(cycle.defectRatePoint) }}，独立样本 n={{ independentN }}）
          <template v-if="cycle.uncoveredProbability !== null">
            · 自适应未覆盖概率 <b class="tag tag-warn">{{ pct(cycle.uncoveredProbability) }}</b>
          </template>
        </div>
      </div>

      <div v-if="cycle" class="table-wrap" style="margin-top:10px">
        <table>
          <thead><tr><th>对象</th><th>副本/片段</th><th>媒介</th><th>年龄层</th><th>价值层</th>
            <th>抽样依据</th><th>独立样本</th><th>结果</th><th>扫描字节</th></tr></thead>
          <tbody>
          <tr v-for="i in cycle.items" :key="i.id">
            <td class="mono">{{ i.uid }}</td>
            <td>{{ i.replicaKind }}<span v-if="i.ecFragmentIndex !== null" class="muted">#{{ i.ecFragmentIndex }}</span></td>
            <td class="mono">{{ i.mediaId }}</td><td>{{ i.ageBand }}</td><td>{{ valueText(i.valueBand) }}</td>
            <td><span class="tag tag-muted">{{ basisText(i.sampleBasis) }}</span></td>
            <td><span class="tag" :class="i.independentSample ? 'tag-ok' : 'tag-warn'">
              {{ i.independentSample ? '独立' : '重复非独立' }}</span></td>
            <td><span class="tag" :class="resultTag(i.resultStatus)">{{ resultText(i.resultStatus) }}</span></td>
            <td class="mono">{{ fmtBytes(i.bytesScanned) }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 覆盖地图 -->
    <div class="card">
      <h2>校验覆盖地图（覆盖 / 到期 / 逾期 / 盲区）</h2>
      <div class="toolbar">
        <select v-model="coverageTier" @change="loadCoverage">
          <option value="">全部层</option>
          <option v-for="p in policies" :key="p.tierCode" :value="p.tierCode">{{ p.tierCode }}</option>
        </select>
        <button class="btn btn-sm btn-ghost" @click="loadCoverage">刷新</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>对象</th><th>层</th><th>价值分级</th><th>副本健康/总数</th>
            <th>上次审计</th><th>覆盖状态</th><th>删除冻结</th><th>未修复缺陷</th></tr></thead>
          <tbody>
          <tr v-for="o in coverage" :key="o.uid">
            <td class="mono">{{ o.uid }}</td><td>{{ o.tierCode }}</td>
            <td>{{ valueText(o.integrityClass) }}<span v-if="o.evidence" class="tag tag-warn" style="margin-left:4px">证据</span></td>
            <td class="mono">{{ o.replicas.healthy }}/{{ o.replicas.total }}</td>
            <td class="muted">{{ o.lastAuditedAt ? fmt(o.lastAuditedAt) : '从未' }}</td>
            <td><span class="tag" :class="coverageTag(o.coverageStatus)">{{ coverageText(o.coverageStatus) }}</span></td>
            <td><span class="tag" :class="o.deletionFrozen ? 'tag-danger' : 'tag-muted'">
              {{ o.deletionFrozen ? '冻结' : '正常' }}</span></td>
            <td>{{ o.openDefects }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- EC 片段状态 -->
    <div class="card">
      <h2>纠删码片段状态（EC k+m 容忍度）</h2>
      <div class="toolbar">
        <select v-model="ecUid" @change="loadEc">
          <option value="MASK-A01">MASK-A01（EC 4+2）</option>
        </select>
      </div>
      <div v-if="ecFragments.length" class="hint">
        方案 EC({{ ecFragments[0].k }}+{{ ecFragments[0].m }})：坏片段 {{ ecFragments[0].badFragments }}，
        剩余冗余容忍度 <b>{{ ecFragments[0].toleranceRemaining }}</b>，
        <span :class="ecFragments[0].withinTolerance ? 'pass' : 'fail'">
          {{ ecFragments[0].withinTolerance ? '仍可重建' : '超出容忍，数据丢失风险' }}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>片段#</th><th>媒介</th><th>层</th><th>状态</th><th>潜在腐蚀</th><th>上次校验</th></tr></thead>
          <tbody>
          <tr v-for="f in ecFragments" :key="f.id">
            <td class="mono">{{ f.fragmentIndex }}</td><td class="mono">{{ f.mediaId }}</td>
            <td>{{ f.placementTier }}</td>
            <td><span class="tag" :class="f.status === 'HEALTHY' ? 'tag-ok' : 'tag-danger'">{{ f.status }}</span></td>
            <td><span class="tag" :class="f.latentCorruption ? 'tag-danger' : 'tag-muted'">
              {{ f.latentCorruption ? '是' : '否' }}</span></td>
            <td class="muted">{{ f.lastVerifiedAt ? fmt(f.lastVerifiedAt) : '从未' }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 缺陷台账与修复优先级 -->
    <div class="card">
      <h2>完整性缺陷台账与修复优先级（数值越小优先级越高）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>对象</th><th>类型</th><th>严重度</th><th>优先级</th>
            <th>状态</th><th>删除冻结</th><th>检出时间</th><th>操作</th></tr></thead>
          <tbody>
          <tr v-for="d in defects" :key="d.id">
            <td>{{ d.id }}</td><td class="mono">{{ d.uid }}</td>
            <td><span class="tag" :class="d.defectType === 'BIT_ROT' ? 'tag-danger' : 'tag-warn'">
              {{ defectTypeText(d.defectType) }}</span></td>
            <td>{{ d.severity }}</td><td><b>{{ d.repairPriority }}</b></td>
            <td><span class="tag" :class="d.repairStatus === 'OPEN' ? 'tag-danger' : 'tag-ok'">{{ d.repairStatus }}</span></td>
            <td>{{ d.freezeDeletion ? '是' : '否' }}</td>
            <td class="muted">{{ fmt(d.detectedAt) }}</td>
            <td><button class="btn btn-sm" :disabled="d.repairStatus !== 'OPEN'" @click="repair(d.id)">
              修复并重校验</button></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 故障注入与介质告警 -->
    <div class="card">
      <h2>故障注入演练与介质告警（自适应抽样输入）</h2>
      <div class="toolbar">
        <select v-model="faultUid">
          <option v-for="o in faultObjects" :key="o.uid" :value="o.uid">{{ o.uid }}</option>
        </select>
        <button class="btn btn-sm btn-danger" @click="inject('bit-rot')">注入位腐蚀</button>
        <button class="btn btn-sm btn-warn" @click="inject('misplacement')">注入错误分层副本</button>
        <button class="btn btn-sm btn-warn" @click="inject('lost')">注入片段丢失（盲区）</button>
        <button class="btn btn-sm btn-ghost" @click="clearFaults">清除全部故障</button>
      </div>
      <div class="table-wrap" style="max-height:260px">
        <table>
          <thead><tr><th>#</th><th>媒介</th><th>层</th><th>类型</th><th>严重度</th><th>活动</th></tr></thead>
          <tbody>
          <tr v-for="a in alerts" :key="a.id">
            <td>{{ a.id }}</td><td class="mono">{{ a.mediaId }}</td><td>{{ a.tierCode }}</td>
            <td>{{ a.alertType }}</td>
            <td><span class="tag" :class="a.severity === 'HIGH' ? 'tag-danger' : 'tag-warn'">{{ a.severity }}</span></td>
            <td>{{ a.active ? '活动' : '已解除' }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 审计周期历史 -->
    <div class="card">
      <h2>审计周期历史（缺陷率区间与预算执行）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>层</th><th>路线</th><th>抽样/总体</th><th>抽样字节/预算</th>
            <th>缺陷率区间</th><th>未覆盖概率</th><th>预算内</th><th>时间</th></tr></thead>
          <tbody>
          <tr v-for="c in cycles" :key="c.id">
            <td>{{ c.cycleSeq }}</td><td>{{ c.tierCode }}</td><td>{{ strategyText(c.strategy) }}</td>
            <td class="mono">{{ c.sampledObjects }}/{{ c.populationObjects }}</td>
            <td class="mono">{{ fmtBytes(c.sampledBytes) }} / {{ fmtBytes(c.byteBudget) }}</td>
            <td class="mono">{{ pct(c.defectRateLower) }}~{{ pct(c.defectRateUpper) }}</td>
            <td>{{ c.uncoveredProbability === null ? '—' : pct(c.uncoveredProbability) }}</td>
            <td><span class="tag" :class="c.withinBudget ? 'tag-ok' : 'tag-danger'">
              {{ c.withinBudget ? '是' : '否' }}</span></td>
            <td class="muted">{{ fmt(c.startedAt) }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import api from '../api'

const stats = ref({})
const policies = ref([])
const coverage = ref([])
const coverageTier = ref('')
const ecFragments = ref([])
const ecUid = ref('MASK-A01')
const defects = ref([])
const alerts = ref([])
const cycles = ref([])
const cycle = ref(null)
const faultObjects = ref([])
const policyForm = reactive({
  tierCode: 'COLD', maxObjectRatio: 0.4, maxByteBudget: 300000000000,
  samplingStrategy: 'STRATIFIED_FIXED', auditCycleDays: 30, evidenceRotationCycleDays: 180
})
const cycleForm = reactive({ tierCode: 'COLD', strategy: 'STRATIFIED_FIXED' })
const faultUid = ref('FEAT-A01')

const independentN = computed(() =>
  cycle.value ? cycle.value.items.filter(i => i.independentSample).length : 0)
function tierStats(code) {
  return (stats.value.tiers || []).filter(t => t.tierCode === code)
}

function fmt(t) { return t ? String(t).slice(0, 16) : '' }
function pct(x) { return x === null || x === undefined ? '—' : (Number(x) * 100).toFixed(2) + '%' }
function fmtBytes(b) {
  if (b === null || b === undefined) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; let v = Number(b)
  while (v >= 1000 && i < u.length - 1) { v /= 1000; i++ }
  return v.toFixed(v >= 100 ? 0 : 2) + u[i]
}
function strategyText(s) { return { STRATIFIED_FIXED: '固定比例分层', ADAPTIVE: '自适应' }[s] || s }
function valueText(v) {
  return { IRREPLACEABLE_EVIDENCE: '不可替代证据', CRITICAL: '关键', STANDARD: '标准' }[v] || v
}
function basisText(b) {
  return {
    STRATUM_FIXED: '分层固定', EVIDENCE_ROTATION: '证据轮换', PRIOR_DEFECT: '既往缺陷',
    MEDIA_ALERT: '介质告警', HIGH_VALUE: '高价值'
  }[b] || b
}
function resultText(r) {
  return { PENDING: '待检', VERIFIED: '校验通过', DEFECT_BIT_ROT: '位腐蚀', DEFECT_MISPLACED: '错误分层' }[r] || r
}
function resultTag(r) {
  return r === 'VERIFIED' ? 'tag-ok' : r === 'PENDING' ? 'tag-muted' : 'tag-danger'
}
function defectTypeText(t) {
  return { BIT_ROT: '位腐蚀', MISPLACED_REPLICA: '错误分层副本', COVERAGE_GAP: '覆盖盲区' }[t] || t
}
function coverageText(c) {
  return { COVERED: '已覆盖', DUE: '到期待检', OVERDUE: '逾期未检', BLIND: '盲区' }[c] || c
}
function coverageTag(c) {
  return { COVERED: 'tag-ok', DUE: 'tag-warn', OVERDUE: 'tag-danger', BLIND: 'tag-danger' }[c] || 'tag-muted'
}

async function loadAll() {
  stats.value = (await api.get('/integrity/stats')).data
  policies.value = (await api.get('/integrity/policies')).data
  await loadCoverage()
  await loadEc()
  defects.value = (await api.get('/integrity/defects')).data
  alerts.value = (await api.get('/integrity/media-alerts')).data
  cycles.value = (await api.get('/integrity/cycles')).data
  faultObjects.value = (await api.get('/catalog/objects')).data
}
async function loadCoverage() {
  coverage.value = (await api.get('/integrity/coverage', { params: { tier: coverageTier.value || null } })).data
}
async function loadEc() {
  ecFragments.value = (await api.get('/integrity/ec-fragments', { params: { uid: ecUid.value } })).data
}
async function savePolicy() {
  await api.post('/integrity/policies', policyForm)
  await loadAll()
}
async function runCycle() {
  cycle.value = (await api.post('/integrity/cycles', cycleForm)).data
  await loadAll()
}
async function scanGaps() {
  await api.post('/integrity/coverage/scan-gaps')
  await loadAll()
}
async function repair(id) {
  await api.post(`/integrity/defects/${id}/repair`)
  await loadAll()
}
async function inject(kind) {
  await api.post(`/integrity/faults/${kind}`, { uid: faultUid.value })
  await loadAll()
}
async function clearFaults() {
  await api.post('/integrity/faults/clear')
  await loadAll()
}
onMounted(loadAll)
</script>
