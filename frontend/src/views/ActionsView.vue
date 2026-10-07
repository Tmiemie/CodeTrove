<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Play,
  RefreshCw,
  Search,
  TestTube2,
  XCircle,
} from "lucide-vue-next";
import { testCases } from "../data/mock";
import { useWorkspaceStore } from "../stores/workspace";

const store = useWorkspaceStore();
const { t } = useI18n();
const query = ref("");
const status = ref("all");
const rerunning = ref(false);

const filtered = computed(() =>
  testCases.filter((test) => {
    const queryMatch = `${test.name} ${t(test.categoryKey)}`
      .toLowerCase()
      .includes(query.value.toLowerCase());
    const statusMatch = status.value === "all" || test.status === status.value;
    return queryMatch && statusMatch;
  }),
);

function rerun() {
  rerunning.value = true;
  store.notifyKey("actions.queued");
  window.setTimeout(() => {
    rerunning.value = false;
    store.notifyKey("actions.completed");
  }, 1400);
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading split-heading">
      <div>
        <h1>{{ t("actions.title") }}</h1>
        <p>{{ t("actions.description") }}</p>
      </div>
      <button
        class="button button-primary"
        type="button"
        :disabled="rerunning"
        @click="rerun"
      >
        <RefreshCw :size="15" :class="{ spin: rerunning }" />
        {{ rerunning ? t("actions.running") : t("actions.rerunAll") }}
      </button>
    </header>

    <div class="workflow-summary">
      <div class="workflow-status failed">
        <XCircle :size="22" />
        <div>
          <strong>{{ t("actions.integrationTests") }}</strong
          ><span>{{ t("actions.completedFailure", { count: 1 }) }}</span>
        </div>
      </div>
      <dl>
        <div>
          <dt>{{ t("actions.triggeredBy") }}</dt>
          <dd>{{ t("actions.pullRequest", { id: 24 }) }}</dd>
        </div>
        <div>
          <dt>{{ t("actions.commit") }}</dt>
          <dd><code>7ca91be</code></dd>
        </div>
        <div>
          <dt>{{ t("actions.duration") }}</dt>
          <dd>4m 12s</dd>
        </div>
      </dl>
    </div>

    <div class="test-layout">
      <aside class="test-jobs">
        <h2>{{ t("actions.jobs") }}</h2>
        <button class="active">
          <TestTube2 :size="16" /><span
            >assay-tests<small>{{
              t("actions.failureCount", { count: 1 })
            }}</small></span
          ><XCircle :size="16" /></button
        ><button>
          <CheckCircle2 :size="16" /><span
            >curator-review<small>{{ t("actions.passed") }}</small></span
          ><CheckCircle2 :size="16" />
        </button>
      </aside>
      <div class="test-content">
        <div class="test-toolbar">
          <div class="pr-search">
            <Search :size="15" /><input
              v-model="query"
              :placeholder="t('actions.searchCases')"
              :aria-label="t('actions.searchCases')"
            />
          </div>
          <label class="select-button compact"
            ><select v-model="status">
              <option value="all">{{ t("actions.allStatuses") }}</option>
              <option value="passed">{{ t("actions.passed") }}</option>
              <option value="failed">{{ t("actions.failed") }}</option></select
            ><ChevronDown :size="13"
          /></label>
        </div>

        <div class="test-list">
          <article v-for="test in filtered" :key="test.name" class="test-row">
            <component
              :is="test.status === 'passed' ? CheckCircle2 : XCircle"
              :size="18"
              :class="test.status === 'passed' ? 'success-text' : 'danger-text'"
            />
            <div>
              <strong>{{ test.name }}</strong>
              <p>{{ t(test.categoryKey) }}</p>
            </div>
            <span class="test-duration"
              ><Clock3 :size="14" /> {{ test.duration }}</span
            ><button
              class="icon-button light"
              type="button"
              :aria-label="t('actions.runTest')"
              @click="
                store.notifyKey('actions.testQueued', { name: test.name })
              "
            >
              <Play :size="15" />
            </button>
          </article>
          <div v-if="filtered.length === 0" class="empty-state">
            {{ t("actions.empty") }}
          </div>
        </div>

        <article class="failure-detail">
          <header>
            <AlertCircle :size="18" /><strong>assay.invalid-schema</strong
            ><span>{{ t("actions.schemaFailed") }}</span>
          </header>
          <div class="failure-grid">
            <div>
              <span>{{ t("actions.jsonPointer") }}</span
              ><code>/assertions/0/operator</code>
            </div>
            <div>
              <span>{{ t("actions.expected") }}</span
              ><code>one of [equals, exists, contains]</code>
            </div>
            <div>
              <span>{{ t("actions.actual") }}</span
              ><code>matches</code>
            </div>
          </div>
          <pre><code>ASSAY_SCHEMA_INVALID: unsupported assertion operator at /assertions/0/operator</code></pre>
        </article>
      </div>
    </div>
  </section>
</template>
