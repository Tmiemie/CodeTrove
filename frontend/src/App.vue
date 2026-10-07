<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import {
  Bell,
  Box,
  CircleDot,
  Code2,
  GitPullRequest,
  Globe2,
  LogOut,
  Menu,
  PlayCircle,
  Search,
  Settings,
  Sparkles,
  X,
} from "lucide-vue-next";
import { useI18n } from "vue-i18n";
import { useWorkspaceStore } from "./stores/workspace";
import { useSessionStore } from "./stores/session";
import { setAppLocale, type AppLocale } from "./i18n";
import CatTroveMark from "./components/CatTroveMark.vue";
import CatAvatar from "./components/CatAvatar.vue";

const route = useRoute();
const router = useRouter();
const workspace = useWorkspaceStore();
const session = useSessionStore();
const { t, locale } = useI18n();
const mobileNavOpen = ref(false);
const shellVisible = computed(() => route.path !== "/login");

const navGroups = computed(() => [
  {
    label: t("navigation.repository"),
    items: [
      { label: t("navigation.code"), path: "/code", icon: Code2 },
      {
        label: t("navigation.pullRequests"),
        path: "/pull-requests",
        icon: GitPullRequest,
      },
    ],
  },
  {
    label: t("navigation.quality"),
    items: [
      { label: t("common.actions"), path: "/actions", icon: PlayCircle },
      { label: t("common.settings"), path: "/settings", icon: Settings },
    ],
  },
]);

const activePath = computed(() =>
  route.path.startsWith("/pull-requests") ? "/pull-requests" : route.path,
);
const currentTitle = computed(
  () =>
    navGroups.value
      .flatMap((group) => group.items)
      .find((item) => item.path === activePath.value)?.label ?? "CodeTrove",
);

onMounted(async () => {
  await session.initialize();
  if (!session.token && route.path !== "/login") await router.replace("/login");
});

function runSearch() {
  const query = workspace.searchQuery.trim();
  void router.push({ path: "/code", query: query ? { q: query } : {} });
}

function toggleLocale() {
  const nextLocale: AppLocale = locale.value === "en-US" ? "zh-CN" : "en-US";
  setAppLocale(nextLocale);
  workspace.notify(t("common.switchedLanguage"));
}

function changeRepository(event: Event) {
  const id = (event.target as HTMLSelectElement).value;
  const repository = session.repositories.find((item) => item.id === id);
  if (repository) session.selectRepository(repository);
}

async function logout() {
  session.logout();
  await router.replace("/login");
}
</script>

<template>
  <RouterView v-if="!shellVisible" />
  <div v-else class="app-shell" :class="{ 'sidebar-open': mobileNavOpen }">
    <aside class="dashboard-sidebar" :aria-label="t('navigation.repository')">
      <div class="sidebar-brand-row">
        <RouterLink
          class="brand"
          to="/code"
          aria-label="CodeTrove"
          @click="mobileNavOpen = false"
        >
          <span class="brand-mark"><CatTroveMark :size="26" /></span>
          <span class="brand-copy"
            ><strong>CodeTrove</strong
            ><small>{{ t("navigation.brandSubtitle") }}</small></span
          >
        </RouterLink>
        <button
          class="sidebar-close"
          type="button"
          :aria-label="t('common.closeNavigation')"
          @click="mobileNavOpen = false"
        >
          <X :size="19" />
        </button>
      </div>

      <label class="sidebar-project">
        <span class="project-icon"><Box :size="17" /></span>
        <span
          ><small>{{ t("navigation.currentRepository") }}</small>
          <select
            v-if="session.repositories.length"
            :value="session.currentRepository?.id"
            @change="changeRepository"
          >
            <option
              v-for="repository in session.repositories"
              :key="repository.id"
              :value="repository.id"
            >
              {{ repository.owner }} / {{ repository.name }}
            </option>
          </select>
          <strong v-else>{{ t("repository.noRepository") }}</strong>
        </span>
        <span v-if="session.currentRepository" class="project-private">{{
          session.currentRepository.visibility
        }}</span>
      </label>

      <nav class="sidebar-nav">
        <section
          v-for="group in navGroups"
          :key="group.label"
          class="sidebar-group"
        >
          <h2>{{ group.label }}</h2>
          <RouterLink
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            :class="{ active: activePath === item.path }"
            @click="mobileNavOpen = false"
          >
            <component :is="item.icon" :size="18" /><span>{{
              item.label
            }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="sidebar-curator-note">
        <Sparkles :size="17" />
        <div>
          <strong>{{ t("navigation.curatorWatching") }}</strong
          ><span>{{ t("navigation.qualityProtects") }}</span>
        </div>
      </div>
      <button class="sidebar-profile" type="button" @click="logout">
        <span class="avatar"><CatAvatar :size="27" /></span>
        <span
          ><strong>{{
            session.user?.displayName || session.user?.username
          }}</strong
          ><small>{{ t("auth.signOut") }}</small></span
        >
        <LogOut :size="15" />
      </button>
    </aside>

    <div class="sidebar-backdrop" @click="mobileNavOpen = false"></div>
    <div class="dashboard-main">
      <header class="global-header">
        <button
          class="icon-button mobile-menu"
          type="button"
          :aria-label="t('navigation.repository')"
          @click="mobileNavOpen = !mobileNavOpen"
        >
          <Menu :size="20" />
        </button>
        <div class="page-context">
          <span>{{ t("navigation.workspace") }}</span
          ><strong>{{ currentTitle }}</strong>
        </div>
        <form class="global-search" role="search" @submit.prevent="runSearch">
          <Search :size="17" /><input
            v-model="workspace.searchQuery"
            :aria-label="t('common.search')"
            :placeholder="t('header.searchPlaceholder')"
          /><kbd>/</kbd>
        </form>
        <div class="header-actions">
          <button
            class="language-switch"
            type="button"
            :aria-label="t('common.switchLanguage')"
            @click="toggleLocale"
          >
            <Globe2 :size="17" /><span>{{ t("common.language") }}</span>
          </button>
          <button
            class="icon-button notification-button"
            type="button"
            :aria-label="t('header.notifications')"
            @click="workspace.notifyKey('header.allCaughtUp')"
          >
            <Bell :size="18" /><span></span>
          </button>
        </div>
      </header>

      <section v-if="session.currentRepository" class="repo-header">
        <div class="repo-title-row">
          <div class="repo-identity">
            <span class="repo-icon"><Box :size="19" /></span
            ><span class="repo-owner">{{
              session.currentRepository.owner
            }}</span
            ><span class="slash">/</span
            ><strong>{{ session.currentRepository.name }}</strong
            ><span class="badge">{{
              session.currentRepository.visibility
            }}</span>
          </div>
          <div class="repo-actions">
            <code>{{ session.currentRepository.currentUserRole }}</code>
          </div>
        </div>
      </section>

      <main class="page-container"><RouterView /></main>
      <footer class="site-footer">
        <div class="footer-brand"><CatTroveMark :size="19" /> CodeTrove</div>
        <span>{{ t("footer.slogan") }}</span>
        <nav :aria-label="t('footer.docs')">
          <a
            href="https://github.com/Tmiemie/CodeTrove"
            target="_blank"
            rel="noreferrer"
            >GitHub</a
          ><a
            href="https://github.com/Tmiemie/CodeTrove/blob/main/docs/04-api-contract.md"
            target="_blank"
            rel="noreferrer"
            >{{ t("footer.api") }}</a
          >
        </nav>
      </footer>
    </div>

    <Transition name="toast"
      ><div v-if="workspace.toast" class="toast" role="status">
        <CircleDot :size="16" /> {{ workspace.toast }}
      </div></Transition
    >
  </div>
</template>
