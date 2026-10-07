<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  CheckCircle2,
  CircleDot,
  GitPullRequest,
  Plus,
  Search,
  X,
} from "lucide-vue-next";
import { api } from "../api";
import { errorMessage } from "../api/client";
import type { Branch, MergeRequest } from "../api/types";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const router = useRouter();
const { t } = useI18n();
const items = ref<MergeRequest[]>([]);
const branches = ref<Branch[]>([]);
const query = ref("");
const state = ref<"OPEN" | "CLOSED">("OPEN");
const loading = ref(false);
const creating = ref(false);
const showCreate = ref(false);
const error = ref("");
const title = ref("");
const description = ref("");
const sourceBranch = ref("");
const targetBranch = ref("main");

const filtered = computed(() =>
  items.value.filter((item) =>
    item.title.toLowerCase().includes(query.value.toLowerCase()),
  ),
);
const openCount = computed(
  () => items.value.filter((item) => item.status === "OPEN").length,
);
const canCreate = computed(
  () =>
    title.value.trim().length > 0 &&
    sourceBranch.value &&
    targetBranch.value &&
    sourceBranch.value !== targetBranch.value,
);

watch([() => session.currentRepository?.id, state], () => void load(), {
  immediate: true,
});

async function load() {
  if (!session.currentRepository) {
    items.value = [];
    branches.value = [];
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const [mrResult, branchResult] = await Promise.all([
      api.mergeRequests(
        session.currentRepository.id,
        state.value === "CLOSED" ? undefined : "OPEN",
      ),
      api.branches(session.currentRepository.id),
    ]);
    const all = mrResult.data;
    items.value =
      state.value === "CLOSED"
        ? all.filter((item) => item.status !== "OPEN")
        : all;
    branches.value = branchResult.data;
    targetBranch.value = session.currentRepository.defaultBranch;
    sourceBranch.value =
      branches.value.find((item) => item.name !== targetBranch.value)?.name ??
      "";
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}

async function createMergeRequest() {
  if (!session.currentRepository || !canCreate.value) return;
  creating.value = true;
  error.value = "";
  try {
    const created = (
      await api.createMergeRequest(session.currentRepository.id, {
        title: title.value.trim(),
        description: description.value.trim(),
        sourceBranch: sourceBranch.value,
        targetBranch: targetBranch.value,
      })
    ).data;
    showCreate.value = false;
    await router.push(`/pull-requests/${created.iid}`);
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    creating.value = false;
  }
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading split-heading">
      <div>
        <h1>{{ t("pullRequests.title") }}</h1>
        <p>{{ t("m45.pullRequests.description") }}</p>
      </div>
      <button
        v-if="session.currentRepository"
        class="button button-primary"
        @click="showCreate = !showCreate"
      >
        <component :is="showCreate ? X : Plus" :size="15" />{{
          showCreate ? t("m45.pullRequests.cancel") : t("m45.pullRequests.new")
        }}
      </button>
    </header>
    <div v-if="!session.currentRepository" class="empty-state">
      {{ t("m45.shared.selectRepository") }}
    </div>
    <template v-else>
      <form
        v-if="showCreate"
        class="settings-card form-card create-pr-card"
        @submit.prevent="createMergeRequest"
      >
        <label
          >{{ t("m45.pullRequests.title")
          }}<input v-model="title" maxlength="255" required
        /></label>
        <label
          >{{ t("m45.pullRequests.descriptionField")
          }}<textarea v-model="description" maxlength="20000"></textarea>
        </label>
        <div class="form-row">
          <label
            >{{ t("m45.pullRequests.sourceBranch")
            }}<select v-model="sourceBranch">
              <option
                v-for="branch in branches"
                :key="branch.name"
                :value="branch.name"
              >
                {{ branch.name }}
              </option>
            </select></label
          >
          <label
            >{{ t("m45.pullRequests.targetBranch")
            }}<select v-model="targetBranch">
              <option
                v-for="branch in branches"
                :key="branch.name"
                :value="branch.name"
              >
                {{ branch.name }}
              </option>
            </select></label
          >
        </div>
        <p v-if="branches.length < 2" class="boundary-note">
          {{ t("m45.pullRequests.pushBranchHelp") }}
        </p>
        <div class="form-actions">
          <span>{{ t("m45.pullRequests.branchesResolved") }}</span
          ><button
            class="button button-primary"
            :disabled="creating || !canCreate"
          >
            {{ t("m45.pullRequests.create") }}
          </button>
        </div>
      </form>
      <div class="pr-controls">
        <div class="segmented">
          <button
            type="button"
            :class="{ active: state === 'OPEN' }"
            @click="state = 'OPEN'"
          >
            <GitPullRequest :size="16" />{{
              t("pullRequests.openCount", { count: openCount })
            }}
          </button>
          <button
            type="button"
            :class="{ active: state === 'CLOSED' }"
            @click="state = 'CLOSED'"
          >
            <CheckCircle2 :size="16" />{{ t("m45.pullRequests.closedMerged") }}
          </button>
        </div>
        <div class="pr-search">
          <Search :size="16" /><input
            v-model="query"
            :placeholder="t('pullRequests.filterPlaceholder')"
          />
        </div>
        <button class="button button-muted" @click="load">
          {{ t("common.refresh") }}
        </button>
      </div>
      <div v-if="error" class="api-error">{{ error }}</div>
      <div v-if="loading" class="empty-state">{{ t("common.loading") }}</div>
      <div v-else class="pr-list">
        <RouterLink
          v-for="pr in filtered"
          :key="pr.id"
          :to="`/pull-requests/${pr.iid}`"
          class="pr-list-item"
          ><GitPullRequest
            :size="20"
            :class="pr.status === 'OPEN' ? 'state-open' : 'state-merged'"
          />
          <div class="pr-copy">
            <h2>{{ pr.title }}</h2>
            <p>
              #{{ pr.iid }} · {{ pr.author.displayName }} ·
              {{ pr.sourceBranch }} → {{ pr.targetBranch }}
            </p>
          </div>
          <div class="pr-checks" :class="pr.status.toLowerCase()">
            <CircleDot :size="14" />{{ pr.status }}
          </div></RouterLink
        >
        <div v-if="filtered.length === 0" class="empty-state">
          {{ t("m45.pullRequests.noMatch") }}
        </div>
      </div>
    </template>
  </section>
</template>
