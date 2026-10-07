<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import {
  Bell,
  Box,
  ChevronDown,
  CircleDot,
  Code2,
  GitPullRequest,
  Globe2,
  Menu,
  PlayCircle,
  Plus,
  Search,
  Settings,
  Sparkles,
  X,
} from "lucide-vue-next";
import { useI18n } from "vue-i18n";
import { useWorkspaceStore } from "./stores/workspace";
import { setAppLocale, type AppLocale } from "./i18n";
import CatTroveMark from "./components/CatTroveMark.vue";
import CatAvatar from "./components/CatAvatar.vue";

const route = useRoute();
const router = useRouter();
const store = useWorkspaceStore();
const { t, locale } = useI18n();
const mobileNavOpen = ref(false);

const navGroups = computed(() => [
  {
    label: t("navigation.repository"),
    items: [
      { label: t("navigation.code"), path: "/code", icon: Code2 },
      {
        label: t("navigation.pullRequests"),
        path: "/pull-requests",
        icon: GitPullRequest,
        count: 2,
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

const activePath = computed(() => {
  if (route.path.startsWith("/pull-requests")) return "/pull-requests";
  return route.path;
});

const currentTitle = computed(() => {
  const item = navGroups.value
    .flatMap((group) => group.items)
    .find((entry) => entry.path === activePath.value);
  return item?.label ?? "CodeTrove";
});

function runSearch() {
  const query = store.searchQuery.trim();
  router.push({ path: "/code", query: query ? { q: query } : {} });
  store.notify(
    query
      ? t("repository.searchMatches", { query })
      : t("header.searchPlaceholder"),
  );
}

function toggleLocale() {
  const nextLocale: AppLocale = locale.value === "en-US" ? "zh-CN" : "en-US";
  setAppLocale(nextLocale);
  store.notify(t("common.switchedLanguage"));
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-open': mobileNavOpen }">
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

      <div class="sidebar-project">
        <span class="project-icon"><Box :size="17" /></span>
        <span
          ><small>{{ t("navigation.currentRepository") }}</small
          ><strong>Tmiemie / CodeTrove</strong></span
        >
        <span class="project-private">{{ t("common.private") }}</span>
      </div>

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
            <component :is="item.icon" :size="18" />
            <span>{{ item.label }}</span>
            <span v-if="item.count" class="sidebar-count">{{
              item.count
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

      <button
        class="sidebar-profile"
        type="button"
        @click="store.notify('Tmiemie')"
      >
        <span class="avatar"><CatAvatar :size="27" /></span>
        <span
          ><strong>Tmiemie</strong
          ><small>{{ t("navigation.repositoryOwner") }}</small></span
        >
        <ChevronDown :size="14" />
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
          <Search :size="17" />
          <input
            v-model="store.searchQuery"
            :aria-label="t('common.search')"
            :placeholder="t('header.searchPlaceholder')"
          />
          <kbd>/</kbd>
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
            class="icon-button"
            type="button"
            :aria-label="t('header.createNew')"
            @click="store.notifyKey('header.createOpened')"
          >
            <Plus :size="18" /><ChevronDown :size="13" />
          </button>
          <button
            class="icon-button notification-button"
            type="button"
            :aria-label="t('header.notifications')"
            @click="store.notifyKey('header.allCaughtUp')"
          >
            <Bell :size="18" /><span></span>
          </button>
        </div>
      </header>

      <section class="repo-header">
        <div class="repo-title-row">
          <div class="repo-identity">
            <span class="repo-icon"><Box :size="19" /></span
            ><span class="repo-owner">Tmiemie</span><span class="slash">/</span
            ><strong>CodeTrove</strong
            ><span class="badge">{{ t("common.private") }}</span>
          </div>
          <div class="repo-actions">
            <button
              class="button button-muted"
              type="button"
              @click="store.notifyKey('header.notificationsSet')"
            >
              <Bell :size="15" /> {{ t("header.notifications") }}
            </button>
            <button
              class="button button-accent"
              type="button"
              @click="store.notifyKey('header.starred')"
            >
              {{ t("header.star") }} <span class="count">8</span>
            </button>
          </div>
        </div>
      </section>

      <main class="page-container"><RouterView /></main>

      <footer class="site-footer">
        <div class="footer-brand"><CatTroveMark :size="19" /> CodeTrove</div>
        <span>{{ t("footer.slogan") }}</span>
        <nav :aria-label="t('footer.docs')">
          <a
            href="https://github.com/Tmiemie/CodeTrove#documentation"
            target="_blank"
            rel="noreferrer"
            >{{ t("footer.docs") }}</a
          ><a
            href="https://github.com/Tmiemie/CodeTrove/blob/main/docs/04-api-contract.md"
            target="_blank"
            rel="noreferrer"
            >{{ t("footer.api") }}</a
          ><button
            type="button"
            @click="store.notifyKey('footer.supportOpened')"
          >
            {{ t("footer.support") }}
          </button>
        </nav>
      </footer>
    </div>

    <Transition name="toast"
      ><div v-if="store.toast" class="toast" role="status">
        <CircleDot :size="16" /> {{ store.toast }}
      </div></Transition
    >
  </div>
</template>
