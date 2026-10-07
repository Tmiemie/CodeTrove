<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  RefreshCw,
  ShieldCheck,
  TestTube2,
  XCircle,
} from "lucide-vue-next";
import { api } from "../api";
import { errorMessage } from "../api/client";
import type {
  AssayReport,
  CheckResponse,
  MergeRequest,
  ReviewReport,
} from "../api/types";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const { t } = useI18n();
const mergeRequests = ref<MergeRequest[]>([]);
const selectedIid = ref<number | null>(null);
const checks = ref<CheckResponse | null>(null);
const review = ref<ReviewReport | null>(null);
const assay = ref<AssayReport | null>(null);
const loading = ref(false);
const error = ref("");
const selectedMergeRequest = computed(
  () =>
    mergeRequests.value.find((item) => item.iid === selectedIid.value) ?? null,
);
const displayedSuite = computed(
  () => checks.value?.current ?? checks.value?.history[0] ?? null,
);
const currentRuns = computed(() => displayedSuite.value?.runs ?? []);
const summaryStatus = computed(
  () => displayedSuite.value?.status ?? "NOT_STARTED",
);

watch(
  () => session.currentRepository?.id,
  () => void loadMergeRequests(),
  { immediate: true },
);
watch(selectedIid, () => void loadReports());

async function loadMergeRequests() {
  if (!session.currentRepository) {
    mergeRequests.value = [];
    selectedIid.value = null;
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    mergeRequests.value = (
      await api.mergeRequests(session.currentRepository.id)
    ).data;
    selectedIid.value = mergeRequests.value[0]?.iid ?? null;
    if (!selectedIid.value) clearReports();
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}
function clearReports() {
  checks.value = null;
  review.value = null;
  assay.value = null;
}
async function loadReports() {
  if (!session.currentRepository || !selectedIid.value) return;
  loading.value = true;
  error.value = "";
  try {
    const id = session.currentRepository.id;
    const [checkResult, reviewResult, assayResult] = await Promise.all([
      api.checks(id, selectedIid.value),
      api.findings(id, selectedIid.value),
      api.assayReport(id, selectedIid.value),
    ]);
    checks.value = checkResult.data;
    review.value = reviewResult.data;
    assay.value = assayResult.data;
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading split-heading">
      <div>
        <h1>{{ t("actions.title") }}</h1>
        <p>{{ t("m45.actions.description") }}</p>
      </div>
      <button
        class="button button-primary"
        :disabled="loading || !selectedIid"
        @click="loadReports"
      >
        <RefreshCw :size="15" :class="{ spin: loading }" />{{
          t("m45.actions.refreshReports")
        }}
      </button>
    </header>
    <div v-if="!session.currentRepository" class="empty-state">
      {{ t("m45.shared.selectRepository") }}
    </div>
    <template v-else>
      <div class="quality-selector settings-card">
        <label
          >{{ t("m45.actions.mergeRequest")
          }}<select v-model="selectedIid">
            <option
              v-for="item in mergeRequests"
              :key="item.id"
              :value="item.iid"
            >
              #{{ item.iid }} · {{ item.title }}
            </option>
          </select></label
        ><RouterLink
          v-if="selectedMergeRequest"
          class="button button-muted"
          :to="`/pull-requests/${selectedMergeRequest.iid}`"
          >{{ t("m45.actions.openMergeRequest") }}</RouterLink
        >
      </div>
      <div v-if="error" class="api-error">{{ error }}</div>
      <div v-if="loading" class="empty-state">
        {{ t("m45.actions.loading") }}
      </div>
      <div v-else-if="!selectedIid" class="empty-state">
        {{ t("m45.actions.noMergeRequest") }}
      </div>
      <template v-else>
        <div class="workflow-summary">
          <div class="workflow-status" :class="summaryStatus.toLowerCase()">
            <component
              :is="
                summaryStatus === 'SUCCESS'
                  ? CheckCircle2
                  : summaryStatus === 'FAILED'
                    ? XCircle
                    : Clock3
              "
              :size="22"
            />
            <div>
              <strong>{{ t("m45.actions.checkSuite") }}</strong
              ><span>{{ summaryStatus }}</span>
            </div>
          </div>
          <dl>
            <div>
              <dt>{{ t("m45.actions.mergeRequest") }}</dt>
              <dd>#{{ selectedIid }}</dd>
            </div>
            <div>
              <dt>{{ t("m45.actions.head") }}</dt>
              <dd>
                <code>{{ displayedSuite?.headCommit || "—" }}</code>
              </dd>
            </div>
            <div>
              <dt>{{ t("m45.actions.history") }}</dt>
              <dd>
                {{
                  t("m45.actions.suites", {
                    count: checks?.history.length ?? 0,
                  })
                }}
              </dd>
            </div>
          </dl>
        </div>
        <div class="quality-grid">
          <section class="settings-card quality-card">
            <header>
              <ShieldCheck :size="19" />
              <div>
                <h2>{{ t("m45.actions.checkRuns") }}</h2>
                <p>{{ t("m45.actions.checkRunsHelp") }}</p>
              </div>
            </header>
            <article
              v-for="run in currentRuns"
              :key="run.id"
              class="quality-row"
            >
              <component
                :is="
                  run.status === 'SUCCESS'
                    ? CheckCircle2
                    : run.status === 'FAILED'
                      ? XCircle
                      : Clock3
                "
                :size="18"
                :class="
                  run.status === 'SUCCESS'
                    ? 'success-text'
                    : run.status === 'FAILED'
                      ? 'danger-text'
                      : ''
                "
              />
              <div>
                <strong>{{ run.name }}</strong>
                <p>
                  {{ run.checkType }} · {{ run.status }} ·
                  {{ run.conclusion || "—" }}
                </p>
              </div>
              <span>{{
                t(
                  run.blocking
                    ? "m45.actions.blocking"
                    : "m45.actions.advisory",
                )
              }}</span>
            </article>
            <div v-if="currentRuns.length === 0" class="empty-state">
              {{ t("m45.actions.noRuns") }}
            </div>
          </section>
          <section class="settings-card quality-card">
            <header>
              <AlertCircle :size="19" />
              <div>
                <h2>CodeCurator</h2>
                <p>
                  {{ review?.task?.status || "NOT_STARTED" }} ·
                  {{ review?.task?.conclusion || "—" }}
                </p>
              </div>
            </header>
            <article
              v-for="finding in review?.findings ?? []"
              :key="finding.id"
              class="quality-row finding-row"
            >
              <AlertCircle :size="18" />
              <div>
                <strong>{{ finding.severity }} · {{ finding.title }}</strong>
                <p>{{ finding.message }}</p>
                <code>{{ finding.filePath }}:{{ finding.lineNumber }}</code>
              </div>
              <span>{{ finding.disposition }}</span>
            </article>
            <div v-if="!review?.findings.length" class="empty-state">
              {{ t("m45.actions.noFindings") }}
            </div>
          </section>
          <section class="settings-card quality-card assay-card">
            <header>
              <TestTube2 :size="19" />
              <div>
                <h2>CodeAssay</h2>
                <p>
                  {{ assay?.execution?.status || "NOT_STARTED" }} ·
                  {{ assay?.execution?.conclusion || "—" }}
                </p>
              </div>
            </header>
            <article
              v-for="testCase in assay?.cases ?? []"
              :key="testCase.id"
              class="quality-row"
            >
              <component
                :is="testCase.status === 'PASSED' ? CheckCircle2 : XCircle"
                :size="18"
                :class="
                  testCase.status === 'PASSED' ? 'success-text' : 'danger-text'
                "
              />
              <div>
                <strong>{{ testCase.caseKey }}</strong>
                <p>
                  {{ testCase.sourcePath }} ·
                  {{ testCase.failureCode || t("m45.shared.noFailure") }}
                </p>
              </div>
              <span>{{ testCase.durationMs }} ms</span>
            </article>
            <div v-if="!assay?.cases.length" class="empty-state">
              {{ t("m45.actions.noCases") }}
            </div>
          </section>
        </div>
        <p class="boundary-note">{{ t("m45.actions.rerunBoundary") }}</p>
      </template>
    </template>
  </section>
</template>
