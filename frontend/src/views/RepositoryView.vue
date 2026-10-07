<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  BookOpen,
  ChevronDown,
  Copy,
  File,
  Folder,
  GitBranch,
} from "lucide-vue-next";
import { api } from "../api";
import { errorMessage } from "../api/client";
import type { BlobView, Branch, TreeEntry } from "../api/types";
import { useSessionStore } from "../stores/session";
import { useWorkspaceStore } from "../stores/workspace";
import CatPaw from "../components/CatPaw.vue";

const route = useRoute();
const session = useSessionStore();
const workspace = useWorkspaceStore();
const { t } = useI18n();
const branches = ref<Branch[]>([]);
const entries = ref<TreeEntry[]>([]);
const branch = ref("");
const path = ref("");
const blob = ref<BlobView | null>(null);
const loading = ref(false);
const error = ref("");
const createName = ref("CodeTrove Demo");
const createSlug = ref("codetrove-demo");
const createDescription = ref("CodeTrove interactive demo repository");

const filteredEntries = computed(() => {
  const query = String(route.query.q ?? "").toLowerCase();
  return query
    ? entries.value.filter((entry) => entry.name.toLowerCase().includes(query))
    : entries.value;
});

watch(
  () => session.currentRepository?.id,
  () => void loadRepository(),
  { immediate: true },
);

async function loadRepository() {
  if (!session.currentRepository) return;
  loading.value = true;
  error.value = "";
  blob.value = null;
  path.value = "";
  try {
    branches.value = (await api.branches(session.currentRepository.id)).data;
    branch.value =
      branches.value.find((item) => item.default)?.name ??
      branches.value[0]?.name ??
      "";
    if (branch.value) await loadTree("");
    else entries.value = [];
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}

async function loadTree(nextPath: string) {
  if (!session.currentRepository || !branch.value) return;
  loading.value = true;
  error.value = "";
  blob.value = null;
  try {
    const result = (
      await api.tree(session.currentRepository.id, branch.value, nextPath)
    ).data;
    path.value = result.path;
    entries.value = result.entries;
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}

async function openEntry(entry: TreeEntry) {
  if (entry.type === "TREE") return loadTree(entry.path);
  if (!session.currentRepository) return;
  loading.value = true;
  error.value = "";
  try {
    blob.value = (
      await api.blob(session.currentRepository.id, branch.value, entry.path)
    ).data;
  } catch (requestError) {
    error.value = errorMessage(requestError);
  } finally {
    loading.value = false;
  }
}

async function createRepository() {
  try {
    await session.createRepository({
      name: createName.value,
      slug: createSlug.value,
      description: createDescription.value,
      visibility: "PRIVATE",
      initializeWithReadme: true,
    });
    await loadRepository();
  } catch {
    error.value = session.error;
  }
}

function copyCloneUrl() {
  if (!session.currentRepository) return;
  const gitBaseUrl = (
    import.meta.env.VITE_CODETROVE_GIT_BASE_URL || "http://127.0.0.1:8080"
  ).replace(/\/$/, "");
  const url = `${gitBaseUrl}${session.currentRepository.gitHttpUrl}`;
  void navigator.clipboard?.writeText(url);
  workspace.notifyKey("repository.cloneCopied");
}
</script>

<template>
  <section
    v-if="!session.currentRepository"
    class="settings-card form-card empty-workspace"
  >
    <CatPaw :size="34" />
    <h1>{{ t("repository.createFirst") }}</h1>
    <p>{{ t("repository.createDescription") }}</p>
    <label
      >{{ t("repository.repositoryName") }}<input v-model="createName"
    /></label>
    <label
      >{{ t("repository.repositorySlug") }}<input v-model="createSlug"
    /></label>
    <label
      >{{ t("repository.repositoryDescription")
      }}<textarea v-model="createDescription"></textarea>
    </label>
    <div v-if="session.error" class="api-error">{{ session.error }}</div>
    <button
      class="button button-primary"
      :disabled="session.loading"
      @click="createRepository"
    >
      {{ t("repository.createRepository") }}
    </button>
  </section>

  <div v-else class="repo-grid">
    <section class="main-column">
      <div v-if="error" class="api-error">{{ error }}</div>
      <div class="toolbar">
        <div class="toolbar-group">
          <label class="select-button"
            ><GitBranch :size="15" /><select
              v-model="branch"
              @change="loadTree('')"
            >
              <option
                v-for="item in branches"
                :key="item.name"
                :value="item.name"
              >
                {{ item.name }}
              </option></select
            ><ChevronDown :size="14"
          /></label>
          <button v-if="path" class="button button-quiet" @click="loadTree('')">
            {{ t("repository.backToRoot") }}
          </button>
        </div>
        <button class="button button-primary" @click="copyCloneUrl">
          <Copy :size="15" /> {{ t("repository.copyCloneUrl") }}
        </button>
      </div>

      <div v-if="loading" class="empty-state">{{ t("common.loading") }}</div>
      <div v-else-if="!branch" class="empty-state cat-empty">
        <CatPaw :size="28" /><strong>{{
          t("repository.emptyRepository")
        }}</strong>
      </div>
      <div v-else class="file-table">
        <button
          v-for="entry in filteredEntries"
          :key="entry.objectId"
          class="file-row real-file-row"
          @click="openEntry(entry)"
        >
          <component
            :is="entry.type === 'TREE' ? Folder : File"
            :size="17"
            :class="entry.type === 'TREE' ? 'folder' : 'file'"
          />
          <span class="file-name">{{ entry.name }}</span
          ><span class="file-message">{{ entry.type }}</span
          ><span class="file-time">{{ entry.size ?? "—" }}</span>
        </button>
        <div v-if="filteredEntries.length === 0" class="empty-state cat-empty">
          <CatPaw :size="28" /><strong>{{ t("repository.emptyTitle") }}</strong>
        </div>
      </div>

      <article v-if="blob" class="readme-card blob-card">
        <header>
          <BookOpen :size="17" /><strong>{{ blob.path }}</strong>
        </header>
        <pre v-if="blob.contentIncluded"><code>{{ blob.content }}</code></pre>
        <div v-else class="empty-state">
          {{ t("repository.fileUnavailable") }} · {{ blob.notIncludedReason }}
        </div>
      </article>
    </section>

    <aside class="sidebar-column">
      <section class="side-section">
        <h2>{{ t("repository.about") }}</h2>
        <p>{{ session.currentRepository.description || "—" }}</p>
      </section>
      <section class="side-section">
        <h2>{{ t("repository.branches", { count: branches.length }) }}</h2>
        <p>{{ branch || "—" }}</p>
      </section>
      <section class="side-section">
        <h2>Git Smart HTTP</h2>
        <code>{{ session.currentRepository.gitHttpUrl }}</code>
      </section>
    </aside>
  </div>
</template>
