<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  CheckCircle2,
  FileCode2,
  GitMerge,
  GitPullRequest,
  MessageSquare,
  ShieldCheck,
  XCircle,
} from "lucide-vue-next";
import { api } from "../api";
import { errorMessage } from "../api/client";
import type {
  AssayReport,
  CheckResponse,
  Comment,
  DiffView,
  MergeRequest,
  ReviewReport,
} from "../api/types";
import { useSessionStore } from "../stores/session";
import { useWorkspaceStore } from "../stores/workspace";

const route = useRoute();
const session = useSessionStore();
const workspace = useWorkspaceStore();
const { t } = useI18n();
const iid = computed(() => Number(route.params.id));
const mergeRequest = ref<MergeRequest | null>(null);
const diff = ref<DiffView | null>(null);
const comments = ref<Comment[]>([]);
const checks = ref<CheckResponse | null>(null);
const review = ref<ReviewReport | null>(null);
const assay = ref<AssayReport | null>(null);
const commentDraft = ref("");
const tab = ref<"conversation" | "checks" | "files">("conversation");
const loading = ref(false);
const mutating = ref(false);
const error = ref("");

const blockingPassed = computed(() => {
  const runs = checks.value?.current?.runs ?? [];
  const blocking = runs.filter((run) => run.blocking);
  return (
    blocking.length > 0 && blocking.every((run) => run.status === "SUCCESS")
  );
});

watch([() => session.currentRepository?.id, iid], () => void load(), {
  immediate: true,
});

async function load() {
  if (!session.currentRepository || !iid.value) return;
  loading.value = true;
  error.value = "";
  try {
    const id = session.currentRepository.id;
    const [
      mrResult,
      diffResult,
      commentResult,
      checkResult,
      reviewResult,
      assayResult,
    ] = await Promise.all([
      api.mergeRequest(id, iid.value),
      api.diff(id, iid.value),
      api.comments(id, iid.value),
      api.checks(id, iid.value),
      api.findings(id, iid.value),
      api.assayReport(id, iid.value),
    ]);
    mergeRequest.value = mrResult.data;
    diff.value = diffResult.data;
    comments.value = commentResult.data;
    checks.value = checkResult.data;
    review.value = reviewResult.data;
    assay.value = assayResult.data;
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}

async function submitComment() {
  if (!session.currentRepository || !commentDraft.value.trim()) return;
  mutating.value = true;
  error.value = "";
  try {
    const created = (
      await api.createComment(
        session.currentRepository.id,
        iid.value,
        commentDraft.value.trim(),
      )
    ).data;
    comments.value.push(created);
    commentDraft.value = "";
    workspace.notify(t("m45.detail.commentCreated"));
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    mutating.value = false;
  }
}

async function merge() {
  if (!session.currentRepository || !mergeRequest.value) return;
  mutating.value = true;
  error.value = "";
  try {
    await api.merge(
      session.currentRepository.id,
      iid.value,
      mergeRequest.value.headCommit,
    );
    workspace.notify(t("m45.detail.merged"));
    await load();
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    mutating.value = false;
  }
}
</script>

<template>
  <section class="content-page pr-detail-page">
    <div v-if="error" class="api-error">{{ error }}</div>
    <div v-if="loading" class="empty-state">{{ t("m45.detail.loading") }}</div>
    <template v-else-if="mergeRequest">
      <header class="pr-title-block">
        <div>
          <h1>
            {{ mergeRequest.title }} <span>#{{ mergeRequest.iid }}</span>
          </h1>
          <p>
            <span class="status-pill open"
              ><GitPullRequest :size="15" />{{ mergeRequest.status }}</span
            >{{ mergeRequest.author.displayName }} ·
            {{ mergeRequest.sourceBranch }} → {{ mergeRequest.targetBranch }}
          </p>
        </div>
      </header>

      <nav class="detail-tabs">
        <button
          :class="{ active: tab === 'conversation' }"
          @click="tab = 'conversation'"
        >
          <MessageSquare :size="16" />{{ t("pullRequestDetail.conversation") }}
          <span>{{ comments.length }}</span>
        </button>
        <button :class="{ active: tab === 'checks' }" @click="tab = 'checks'">
          <CheckCircle2 :size="16" />{{ t("pullRequestDetail.checks") }}
          <span>{{ checks?.current?.runs.length ?? 0 }}</span>
        </button>
        <button :class="{ active: tab === 'files' }" @click="tab = 'files'">
          <FileCode2 :size="16" />{{ t("pullRequestDetail.filesChanged") }}
          <span>{{ diff?.files.length ?? 0 }}</span>
        </button>
      </nav>

      <div v-if="tab === 'conversation'" class="pr-detail-grid">
        <div class="timeline-column">
          <div
            v-for="comment in comments"
            :key="comment.id"
            class="timeline-item"
          >
            <div class="timeline-avatar">
              {{ comment.author?.displayName?.slice(0, 2) || "CT" }}
            </div>
            <article class="comment-card">
              <header>
                <strong>{{ comment.author?.displayName || "CodeTrove" }}</strong
                ><span>{{ comment.type }}</span>
              </header>
              <div class="comment-body">
                <p>{{ comment.body }}</p>
                <small v-if="comment.position"
                  >{{ comment.position.filePath }}:{{
                    comment.position.line
                  }}</small
                >
              </div>
            </article>
          </div>
          <article class="review-summary">
            <header>
              <ShieldCheck :size="18" /><strong>CodeCurator</strong
              ><span class="status-label">{{
                review?.task?.status || "NOT_STARTED"
              }}</span>
            </header>
            <div v-if="review?.findings.length" class="finding-list">
              <div v-for="finding in review.findings" :key="finding.id">
                <strong>{{ finding.severity }} · {{ finding.ruleId }}</strong>
                <p>{{ finding.message }}</p>
                <code>{{ finding.filePath }}:{{ finding.lineNumber }}</code>
              </div>
            </div>
            <div v-else class="empty-state">
              {{ t("m45.detail.noFindings") }}
            </div>
          </article>
          <article class="comment-composer">
            <textarea
              v-model="commentDraft"
              :placeholder="t('m45.detail.leaveComment')"
            ></textarea>
            <footer>
              <span>{{ t("m45.shared.backendStored") }}</span
              ><button
                class="button button-primary"
                :disabled="mutating || !commentDraft.trim()"
                @click="submitComment"
              >
                {{ t("pullRequestDetail.comment") }}
              </button>
            </footer>
          </article>
        </div>
        <aside class="pr-sidebar">
          <section>
            <h2>{{ t("m45.detail.headCommit") }}</h2>
            <code>{{ mergeRequest.headCommit }}</code>
          </section>
          <section>
            <h2>Assay</h2>
            <p>
              {{ assay?.execution?.status || "NOT_STARTED" }} ·
              {{ assay?.execution?.conclusion || "—" }}
            </p>
          </section>
        </aside>
      </div>

      <div v-else-if="tab === 'checks'" class="checks-panel">
        <article v-for="run in checks?.current?.runs ?? []" :key="run.id">
          <component
            :is="run.status === 'SUCCESS' ? CheckCircle2 : XCircle"
            :size="22"
            :class="run.status === 'SUCCESS' ? 'success-text' : 'danger-text'"
          />
          <div>
            <strong>{{ run.name }}</strong>
            <p>
              {{ run.status }} · {{ run.conclusion || "—" }} · attempt
              {{ run.attempt }}
            </p>
          </div>
        </article>
        <div v-if="!checks?.current" class="empty-state">
          {{ t("m45.detail.noChecks") }}
        </div>
      </div>

      <div v-else class="real-diff-list">
        <article
          v-for="file in diff?.files ?? []"
          :key="`${file.oldPath}-${file.newPath}`"
          class="diff-card"
        >
          <header class="diff-header">
            <strong>{{ file.newPath || file.oldPath }}</strong
            ><span>+{{ file.additions }} −{{ file.deletions }}</span>
          </header>
          <pre v-if="file.patch"><code>{{ file.patch }}</code></pre>
          <div v-else class="empty-state">
            {{
              file.binary
                ? t("m45.detail.binaryFile")
                : t("m45.detail.patchUnavailable")
            }}
          </div>
        </article>
      </div>

      <section class="merge-box" :class="blockingPassed ? 'ready' : 'blocked'">
        <component :is="blockingPassed ? CheckCircle2 : XCircle" :size="24" />
        <div class="merge-copy">
          <strong>{{
            blockingPassed
              ? t("m45.detail.checksPassed")
              : t("m45.detail.mergeBlocked")
          }}</strong>
          <p>
            {{
              mergeRequest.status === "OPEN"
                ? t("m45.detail.revalidate")
                : t("m45.detail.mrStatus", { status: mergeRequest.status })
            }}
          </p>
        </div>
        <button
          v-if="mergeRequest.status === 'OPEN'"
          class="button button-merge"
          :disabled="mutating || !blockingPassed"
          @click="merge"
        >
          <GitMerge :size="16" />{{ t("pullRequestDetail.merge") }}
        </button>
      </section>
    </template>
  </section>
</template>
