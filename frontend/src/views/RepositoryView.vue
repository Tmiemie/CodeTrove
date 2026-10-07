<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  BookOpen,
  ChevronDown,
  Clock3,
  Code2,
  Copy,
  File,
  FileText,
  Folder,
  GitBranch,
  GitCommitHorizontal,
  Scale,
  ShieldCheck,
  Tag,
} from "lucide-vue-next";
import { repositoryFiles, type FileNode } from "../data/mock";
import { useWorkspaceStore } from "../stores/workspace";
import CatPaw from "../components/CatPaw.vue";

const route = useRoute();
const store = useWorkspaceStore();
const { t } = useI18n();
const branch = ref("main");
const cloneOpen = ref(false);
const branches = ["main", "develop", "feature/repository-workspace"];

const filteredFiles = computed(() => {
  const query = String(route.query.q ?? "").toLowerCase();
  if (!query) return repositoryFiles;
  return repositoryFiles.filter((file) =>
    `${file.name} ${t(file.messageKey)}`.toLowerCase().includes(query),
  );
});

function ageText(age: FileNode["age"]) {
  if (age.unit === "yesterday") return t("repository.yesterday");
  const key =
    age.unit === "minute"
      ? "repository.minutesAgo"
      : age.unit === "hour"
        ? "repository.hoursAgo"
        : "repository.daysAgo";
  return t(key, { count: age.value ?? 0 });
}

function copyCloneUrl() {
  navigator.clipboard?.writeText("https://github.com/Tmiemie/CodeTrove.git");
  store.notifyKey("repository.cloneCopied");
  cloneOpen.value = false;
}
</script>

<template>
  <div class="repo-grid">
    <section class="main-column">
      <div v-if="route.query.q" class="search-result-note">
        {{ t("repository.searchMatches", { query: route.query.q }) }}
      </div>

      <div class="toolbar">
        <div class="toolbar-group">
          <label class="select-button">
            <GitBranch :size="15" />
            <select v-model="branch" :aria-label="t('repository.selectBranch')">
              <option v-for="item in branches" :key="item">{{ item }}</option>
            </select>
            <ChevronDown :size="14" />
          </label>
          <button
            class="button button-quiet"
            type="button"
            @click="
              store.notifyKey('repository.branchesAvailable', { count: 3 })
            "
          >
            <GitBranch :size="15" />
            {{ t("repository.branches", { count: 3 }) }}
          </button>
          <button
            class="button button-quiet"
            type="button"
            @click="store.notifyKey('repository.releasesTagged', { count: 12 })"
          >
            <Tag :size="15" /> {{ t("repository.tags", { count: 12 }) }}
          </button>
        </div>
        <div class="clone-wrap">
          <button
            class="button button-primary"
            type="button"
            @click="cloneOpen = !cloneOpen"
          >
            <Code2 :size="16" /> {{ t("common.code") }}
            <ChevronDown :size="14" />
          </button>
          <div v-if="cloneOpen" class="clone-popover">
            <strong>{{ t("repository.clone") }}</strong>
            <p>{{ t("repository.cloneHelp") }}</p>
            <div class="copy-field">
              <code>https://github.com/Tmiemie/CodeTrove.git</code
              ><button
                type="button"
                :aria-label="t('repository.copyCloneUrl')"
                @click="copyCloneUrl"
              >
                <Copy :size="15" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="commit-banner">
        <div class="commit-avatar">TZ</div>
        <div class="commit-main">
          <strong>Tmiemie</strong
          ><span>{{ t("repository.commitMessage") }}</span>
        </div>
        <code>7ca91be</code>
        <span class="muted"
          ><Clock3 :size="14" />
          {{ t("repository.minutesAgo", { count: 12 }) }}</span
        >
        <button
          class="button button-quiet"
          type="button"
          @click="store.notifyKey('repository.showingCommits', { count: 37 })"
        >
          <GitCommitHorizontal :size="15" />
          {{ t("repository.commits", { count: 37 }) }}
        </button>
      </div>

      <div class="file-table">
        <div
          v-for="file in filteredFiles"
          :key="file.name"
          class="file-row"
          @click="store.notifyKey('repository.openedFile', { name: file.name })"
        >
          <component
            :is="file.type === 'folder' ? Folder : File"
            :size="17"
            :class="file.type"
          />
          <button type="button" class="file-name">{{ file.name }}</button>
          <span class="file-message">{{ t(file.messageKey) }}</span>
          <span class="file-time">{{ ageText(file.age) }}</span>
        </div>
        <div v-if="filteredFiles.length === 0" class="empty-state cat-empty">
          <CatPaw :size="28" /><strong>{{ t("repository.emptyTitle") }}</strong
          ><span>{{ t("repository.emptyDescription") }}</span>
        </div>
      </div>

      <article class="readme-card">
        <header><BookOpen :size="17" /><strong>README.md</strong></header>
        <div class="readme-content">
          <h1>CodeTrove</h1>
          <p class="lead">{{ t("repository.readmeTagline") }}</p>
          <div class="readme-badges">
            <span>Java 17</span><span>Spring Boot 3</span><span>Vue 3</span
            ><span>{{ t("repository.qualityGate") }}</span>
          </div>
          <h2>{{ t("repository.readmeHeading") }}</h2>
          <p>{{ t("repository.readmeDescription") }}</p>
          <pre><code>push → pull request → CodeCurator → CodeAssay → merge gate</code></pre>
          <h2>{{ t("repository.localDevelopment") }}</h2>
          <ul>
            <li>{{ t("repository.backendItem") }}</li>
            <li>{{ t("repository.frontendItem") }}</li>
            <li>{{ t("repository.databaseItem") }}</li>
            <li>{{ t("repository.middlewareItem") }}</li>
          </ul>
        </div>
      </article>
    </section>

    <aside class="sidebar-column">
      <section class="side-section">
        <h2>{{ t("repository.about") }}</h2>
        <p>{{ t("repository.aboutDescription") }}</p>
        <dl class="repo-meta">
          <div>
            <dt><BookOpen :size="15" /> {{ t("repository.readme") }}</dt>
            <dd>{{ t("common.available") }}</dd>
          </div>
          <div>
            <dt><Scale :size="15" /> {{ t("repository.license") }}</dt>
            <dd>MIT</dd>
          </div>
          <div>
            <dt><ShieldCheck :size="15" /> {{ t("repository.security") }}</dt>
            <dd>{{ t("repository.policyDefined") }}</dd>
          </div>
        </dl>
      </section>
      <section class="side-section">
        <h2>{{ t("repository.releases") }}</h2>
        <div class="release-item">
          <Tag :size="17" />
          <div>
            <strong>M0 backend skeleton</strong
            ><span>{{
              t("repository.latest", {
                time: t("repository.daysAgo", { count: 2 }),
              })
            }}</span>
          </div>
        </div>
        <button
          class="text-button"
          type="button"
          @click="store.notifyKey('repository.allReleasesOpened')"
        >
          {{ t("repository.viewAllReleases") }}
        </button>
      </section>
      <section class="side-section">
        <h2>{{ t("repository.languages") }}</h2>
        <div class="language-bar">
          <span class="java"></span><span class="vue"></span
          ><span class="other"></span>
        </div>
        <div class="language-list">
          <span><i class="dot java-dot"></i>Java <b>61.2%</b></span
          ><span><i class="dot vue-dot"></i>Vue <b>31.8%</b></span
          ><span
            ><i class="dot other-dot"></i>{{ t("repository.other") }}
            <b>7.0%</b></span
          >
        </div>
      </section>
      <section class="side-section license-note">
        <FileText :size="17" />
        <div>
          <strong>{{ t("repository.licensePending") }}</strong>
          <p>{{ t("repository.licenseDescription") }}</p>
        </div>
      </section>
    </aside>
  </div>
</template>
