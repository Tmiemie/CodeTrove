<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  Check,
  CheckCircle2,
  ChevronDown,
  FileCode2,
  GitCommitHorizontal,
  GitMerge,
  GitPullRequest,
  MessageSquare,
  ShieldCheck,
} from "lucide-vue-next";
import { diffLines } from "../data/mock";
import { useWorkspaceStore } from "../stores/workspace";
import CatPaw from "../components/CatPaw.vue";

const route = useRoute();
const store = useWorkspaceStore();
const { t } = useI18n();
const commentDraft = ref("");
const reviewOpen = ref(false);
const prNumber = computed(() => route.params.id ?? "24");
const tabs = computed(() => [
  {
    id: "conversation" as const,
    label: t("pullRequestDetail.conversation"),
    icon: MessageSquare,
    count: 3,
  },
  {
    id: "commits" as const,
    label: t("pullRequestDetail.commits"),
    icon: GitCommitHorizontal,
    count: 4,
  },
  {
    id: "checks" as const,
    label: t("pullRequestDetail.checks"),
    icon: CheckCircle2,
    count: 2,
  },
  {
    id: "files" as const,
    label: t("pullRequestDetail.filesChanged"),
    icon: FileCode2,
    count: 5,
  },
]);

function submitComment() {
  if (store.addComment(commentDraft.value)) commentDraft.value = "";
}
</script>

<template>
  <section class="content-page pr-detail-page">
    <header class="pr-title-block">
      <div>
        <h1>
          {{ t("pullRequestDetail.title") }} <span>#{{ prNumber }}</span>
        </h1>
        <p>
          <span class="status-pill open"
            ><GitPullRequest :size="15" />
            {{ t("pullRequestDetail.open") }}</span
          >
          {{
            t("pullRequestDetail.mergeIntent", {
              author: "Tmiemie",
              count: 4,
              target: "main",
              source: "feature/repository-workspace",
            })
          }}
        </p>
      </div>
      <button
        class="button button-muted"
        type="button"
        @click="store.notifyKey('pullRequestDetail.editOpened')"
      >
        {{ t("common.edit") }}
      </button>
    </header>

    <nav class="detail-tabs" :aria-label="t('pullRequestDetail.sections')">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="{ active: store.pullRequestTab === tab.id }"
        @click="store.pullRequestTab = tab.id"
      >
        <component :is="tab.icon" :size="16" /> {{ tab.label }}
        <span>{{ tab.count }}</span>
      </button>
    </nav>

    <div v-if="store.pullRequestTab === 'conversation'" class="pr-detail-grid">
      <div class="timeline-column">
        <div class="timeline-item">
          <div class="timeline-avatar">TZ</div>
          <article class="comment-card">
            <header>
              <strong>Tmiemie</strong
              ><span>{{
                t("pullRequestDetail.commented", {
                  time: t("repository.minutesAgo", { count: 12 }),
                })
              }}</span>
            </header>
            <div class="comment-body">
              <p>{{ t("pullRequestDetail.intro") }}</p>
              <ul>
                <li>{{ t("pullRequestDetail.introRepository") }}</li>
                <li>{{ t("pullRequestDetail.introPr") }}</li>
                <li>{{ t("pullRequestDetail.introTest") }}</li>
              </ul>
            </div>
          </article>
        </div>

        <div class="commit-event">
          <GitCommitHorizontal :size="17" /><span>{{
            t("pullRequestDetail.addedCommits", { author: "Tmiemie", count: 4 })
          }}</span
          ><code>7ca91be</code>
        </div>

        <div class="timeline-item">
          <div class="timeline-avatar reviewer">CR</div>
          <article class="comment-card">
            <header>
              <strong>CodeCurator</strong
              ><span>{{ t("pullRequestDetail.reviewed") }}</span>
            </header>
            <div class="comment-body">
              <p>{{ t("pullRequestDetail.defaultReview") }}</p>
            </div>
          </article>
        </div>

        <div
          v-for="(comment, index) in store.comments"
          :key="`${comment}-${index}`"
          class="timeline-item"
        >
          <div class="timeline-avatar user">TZ</div>
          <article class="comment-card">
            <header>
              <strong>Tmiemie</strong
              ><span>{{
                t("pullRequestDetail.commented", {
                  time: t("repository.minutesAgo", { count: 1 }),
                })
              }}</span>
            </header>
            <div class="comment-body">
              <p>{{ comment }}</p>
            </div>
          </article>
        </div>

        <article class="review-summary">
          <header>
            <ShieldCheck :size="18" /><strong>{{
              t("pullRequestDetail.reviewTitle")
            }}</strong
            ><span class="status-label warning">{{
              t("pullRequestDetail.warningCount", { count: 1 })
            }}</span>
          </header>
          <div class="review-grid">
            <div>
              <span>{{ t("pullRequestDetail.logic") }}</span
              ><strong>{{ t("common.passed") }}</strong>
            </div>
            <div>
              <span>{{ t("pullRequestDetail.security") }}</span
              ><strong>{{ t("common.passed") }}</strong>
            </div>
            <div>
              <span>{{ t("pullRequestDetail.maintainability") }}</span
              ><strong class="warning-text">{{
                t("pullRequestDetail.reviewSuggested")
              }}</strong>
            </div>
          </div>
        </article>

        <article class="comment-composer">
          <div class="composer-tabs">
            <button class="active">{{ t("pullRequestDetail.write") }}</button
            ><button
              type="button"
              @click="store.notifyKey('pullRequestDetail.previewEmpty')"
            >
              {{ t("pullRequestDetail.preview") }}
            </button>
          </div>
          <textarea
            v-model="commentDraft"
            :placeholder="t('pullRequestDetail.leaveComment')"
            :aria-label="t('pullRequestDetail.leaveComment')"
          ></textarea>
          <footer>
            <span>{{ t("pullRequestDetail.markdownSupported") }}</span
            ><button
              class="button button-primary"
              type="button"
              :disabled="!commentDraft.trim()"
              @click="submitComment"
            >
              {{ t("pullRequestDetail.comment") }}
            </button>
          </footer>
        </article>
      </div>

      <aside class="pr-sidebar">
        <section>
          <h2>{{ t("pullRequestDetail.reviewers") }}</h2>
          <p>
            <span class="mini-avatar">CR</span> CodeCurator
            <Check :size="15" class="success-text" />
          </p>
        </section>
        <section>
          <h2>{{ t("pullRequestDetail.assignees") }}</h2>
          <p><span class="mini-avatar user">TM</span> Tmiemie</p>
        </section>
        <section>
          <h2>{{ t("pullRequestDetail.labels") }}</h2>
          <div class="label-list"><span>frontend</span><span>mvp</span></div>
        </section>
        <section>
          <h2>{{ t("pullRequestDetail.development") }}</h2>
          <p class="muted">{{ t("pullRequestDetail.mergeMayClose") }}</p>
          <button
            class="text-button"
            type="button"
            @click="store.notifyKey('pullRequestDetail.noLinkedIssues')"
          >
            {{ t("pullRequestDetail.linkIssue") }}
          </button>
        </section>
      </aside>
    </div>

    <div v-else-if="store.pullRequestTab === 'files'" class="diff-card">
      <header class="diff-header">
        <div>
          <FileCode2 :size="17" /><strong>RepositoryService.java</strong>
        </div>
        <span>+6 −2</span>
      </header>
      <div
        class="diff-table"
        role="table"
        :aria-label="t('pullRequestDetail.codeDiff')"
      >
        <div
          v-for="(line, index) in diffLines"
          :key="index"
          class="diff-line"
          :class="line.kind"
        >
          <span class="line-number">{{ line.oldNumber ?? "" }}</span
          ><span class="line-number">{{ line.newNumber ?? "" }}</span
          ><code
            >{{ line.kind === "add" ? "+" : line.kind === "remove" ? "−" : " "
            }}{{ line.content }}</code
          ><button
            v-if="line.kind !== 'header'"
            class="add-line-comment"
            type="button"
            :aria-label="t('pullRequestDetail.addLineComment')"
            @click="
              store.notifyKey('pullRequestDetail.commentingLine', {
                line: line.newNumber ?? line.oldNumber,
              })
            "
          >
            +
          </button>
          <div v-if="line.commentKey" class="inline-comment">
            <span class="mini-avatar">CR</span>
            <p><strong>CodeCurator</strong>{{ t(line.commentKey) }}</p>
          </div>
        </div>
      </div>
    </div>

    <div v-else-if="store.pullRequestTab === 'checks'" class="checks-panel">
      <article>
        <CheckCircle2 :size="22" class="success-text" />
        <div>
          <strong>{{ t("pullRequestDetail.reviewTitle") }}</strong>
          <p>{{ t("pullRequestDetail.reviewCompleted") }}</p>
        </div>
        <button
          class="button button-muted"
          @click="store.notifyKey('pullRequestDetail.reviewDetailsOpened')"
        >
          {{ t("common.details") }}
        </button>
      </article>
      <article>
        <CheckCircle2 :size="22" class="success-text" />
        <div>
          <strong>{{ t("actions.integrationTests") }}</strong>
          <p>{{ t("pullRequestDetail.testsCompleted") }}</p>
        </div>
        <button
          class="button button-muted"
          @click="store.notifyKey('pullRequestDetail.testReportOpened')"
        >
          {{ t("common.details") }}
        </button>
      </article>
    </div>

    <div v-else class="commit-list">
      <article v-for="item in 4" :key="item">
        <GitCommitHorizontal :size="18" />
        <div>
          <strong>{{
            item === 1
              ? t("pullRequestDetail.commitMain")
              : t("pullRequestDetail.commitRefactor", { index: item })
          }}</strong>
          <p>{{ t("pullRequestDetail.committedAgo", { count: item * 3 }) }}</p>
        </div>
        <code>{{
          ["7ca91be", "28c1d2a", "c370d45", "a01d119"][item - 1]
        }}</code>
      </article>
    </div>

    <section class="merge-box" :class="store.mergeState">
      <template v-if="store.mergeState === 'ready'"
        ><CatPaw :size="24" />
        <div class="merge-copy">
          <strong>{{ t("pullRequestDetail.allChecksPassed") }}</strong>
          <p>{{ t("pullRequestDetail.noConflicts") }}</p>
        </div>
        <div class="merge-action">
          <button
            class="button button-merge"
            type="button"
            @click="store.mergePullRequest"
          >
            <GitMerge :size="16" /> {{ t("pullRequestDetail.merge") }}</button
          ><button
            class="button button-merge split"
            type="button"
            :aria-label="t('pullRequestDetail.mergeOptions')"
            @click="reviewOpen = !reviewOpen"
          >
            <ChevronDown :size="15" />
          </button>
          <div v-if="reviewOpen" class="merge-menu">
            <button
              type="button"
              @click="
                store.notifyKey('pullRequestDetail.squashSelected');
                reviewOpen = false;
              "
            >
              {{ t("pullRequestDetail.squash") }}</button
            ><button
              type="button"
              @click="
                store.notifyKey('pullRequestDetail.rebaseSelected');
                reviewOpen = false;
              "
            >
              {{ t("pullRequestDetail.rebase") }}
            </button>
          </div>
        </div></template
      >
      <template v-else
        ><CatPaw :size="24" />
        <div class="merge-copy">
          <strong>{{ t("pullRequestDetail.mergedSuccess") }}</strong>
          <p>{{ t("pullRequestDetail.branchCanDelete") }}</p>
        </div>
        <button
          class="button button-muted"
          type="button"
          @click="store.notifyKey('pullRequestDetail.branchDeleted')"
        >
          {{ t("pullRequestDetail.deleteBranch") }}
        </button></template
      >
    </section>
  </section>
</template>
