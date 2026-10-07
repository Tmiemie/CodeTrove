<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  CheckCircle2,
  ChevronDown,
  CircleDot,
  GitPullRequest,
  Search,
  SlidersHorizontal,
} from "lucide-vue-next";
import { pullRequests, type PullRequestItem } from "../data/mock";
import { useWorkspaceStore } from "../stores/workspace";
import CatPaw from "../components/CatPaw.vue";

const store = useWorkspaceStore();
const { t } = useI18n();
const query = ref("");
const state = ref<"open" | "closed">("open");
const filtersOpen = ref(false);

const filtered = computed(() =>
  pullRequests.filter((item) => {
    const stateMatch =
      state.value === "open" ? item.state === "open" : item.state === "merged";
    return (
      stateMatch &&
      t(item.titleKey).toLowerCase().includes(query.value.toLowerCase())
    );
  }),
);

function ageText(age: PullRequestItem["age"]) {
  if (age.unit === "yesterday") return t("repository.yesterday");
  const key =
    age.unit === "minute" ? "repository.minutesAgo" : "repository.daysAgo";
  return t(key, { count: age.value ?? 0 });
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading split-heading">
      <div>
        <h1>{{ t("pullRequests.title") }}</h1>
        <p>{{ t("pullRequests.description") }}</p>
      </div>
      <button
        class="button button-primary"
        type="button"
        @click="store.notifyKey('pullRequests.newOpened')"
      >
        {{ t("pullRequests.new") }}
      </button>
    </header>

    <div class="pr-controls">
      <div class="segmented">
        <button
          type="button"
          :class="{ active: state === 'open' }"
          @click="state = 'open'"
        >
          <GitPullRequest :size="16" />
          {{ t("pullRequests.openCount", { count: 2 }) }}
        </button>
        <button
          type="button"
          :class="{ active: state === 'closed' }"
          @click="state = 'closed'"
        >
          <CheckCircle2 :size="16" />
          {{ t("pullRequests.closedCount", { count: 1 }) }}
        </button>
      </div>
      <div class="pr-search">
        <Search :size="16" /><input
          v-model="query"
          :placeholder="t('pullRequests.filterPlaceholder')"
          :aria-label="t('pullRequests.filterPlaceholder')"
        />
      </div>
      <div class="filter-wrap">
        <button
          class="button button-muted"
          type="button"
          @click="filtersOpen = !filtersOpen"
        >
          <SlidersHorizontal :size="15" /> {{ t("common.filters") }}
          <ChevronDown :size="13" />
        </button>
        <div v-if="filtersOpen" class="filter-menu">
          <button
            type="button"
            @click="
              query = 'feat';
              filtersOpen = false;
            "
          >
            {{ t("pullRequests.featureWork") }}
          </button>
          <button
            type="button"
            @click="
              query = 'docs';
              filtersOpen = false;
            "
          >
            {{ t("pullRequests.documentation") }}
          </button>
          <button
            type="button"
            @click="
              query = '';
              filtersOpen = false;
            "
          >
            {{ t("pullRequests.clearFilter") }}
          </button>
        </div>
      </div>
    </div>

    <div class="pr-list">
      <RouterLink
        v-for="pr in filtered"
        :key="pr.id"
        :to="`/pull-requests/${pr.id}`"
        class="pr-list-item"
      >
        <GitPullRequest
          :size="20"
          :class="pr.state === 'open' ? 'state-open' : 'state-merged'"
        />
        <div class="pr-copy">
          <h2>{{ t(pr.titleKey) }}</h2>
          <p>
            {{
              t("pullRequests.openedBy", {
                id: pr.id,
                author: pr.author,
                branch: pr.branch,
                updated: ageText(pr.age),
              })
            }}
          </p>
        </div>
        <div class="pr-checks" :class="pr.state">
          <CircleDot :size="14" />
          {{
            pr.checks
              ? t("pullRequests.checksPassed", pr.checks)
              : t("pullRequests.merged")
          }}
        </div>
      </RouterLink>
      <div v-if="filtered.length === 0" class="empty-state cat-empty">
        <CatPaw :size="28" /><strong>{{ t("pullRequests.emptyTitle") }}</strong
        ><span>{{ t("pullRequests.emptyDescription") }}</span>
      </div>
    </div>
  </section>
</template>
